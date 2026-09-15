package be.roadframe.coach

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.SystemClock
import android.util.Log
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.framework.image.MPImage
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.ImageProcessingOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.objectdetector.ObjectDetector
import com.google.mediapipe.tasks.vision.objectdetector.ObjectDetectorResult
import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.max

/**
 * The slow clock: the generic object detector. It never blocks the camera thread for longer
 * than a frame copy; MediaPipe runs inference on its own thread and calls back. Frames that
 * arrive while a detection is running are dropped (KEEP_ONLY_LATEST upstream, a busy flag here),
 * so the detector always looks at the newest picture.
 *
 * @param preferGpu try the GPU delegate with the float16 model; falls back to CPU + int8.
 * @param realtimeFrameTimestamps true when the camera stamps frames on the elapsedRealtime
 *   clock, so `imageInfo.timestamp` can be matched with sensor timestamps directly.
 */
class VehicleAnalyzer(
    context: Context,
    preferGpu: Boolean,
    private val realtimeFrameTimestamps: Boolean,
    private val onFrame: (AnalysisFrame) -> Unit,
    private val onError: (String) -> Unit
) : ImageAnalysis.Analyzer, Closeable {

    private val busy = AtomicBoolean(false)
    private val lock = Any()
    private var pending: PendingFrame? = null
    private var lastSubmittedAt = 0L
    private var lastTimestamp = 0L

    private val detector: ObjectDetector

    /** "GPU" or "CPU": what actually runs, after any fallback. */
    val delegateName: String

    init {
        var created: ObjectDetector? = null
        var name = "CPU"
        if (preferGpu) {
            try {
                created = build(context, Delegate.GPU, MODEL_FP16)
                name = "GPU"
            } catch (error: Throwable) {
                Log.w(TAG, "GPU delegate unavailable, falling back to CPU", error)
            }
        }
        detector = created ?: build(context, Delegate.CPU, MODEL_INT8)
        delegateName = name
    }

    private fun build(context: Context, delegate: Delegate, model: String): ObjectDetector {
        val baseOptions = BaseOptions.builder()
            .setModelAssetPath(model)
            .setDelegate(delegate)
            .build()
        val options = ObjectDetector.ObjectDetectorOptions.builder()
            .setBaseOptions(baseOptions)
            .setRunningMode(RunningMode.LIVE_STREAM)
            .setMaxResults(5)
            .setScoreThreshold(0.28f)
            .setCategoryAllowlist(listOf("car", "motorcycle", "bus", "truck"))
            .setResultListener(::handleResult)
            .setErrorListener(::handleError)
            .build()
        return ObjectDetector.createFromOptions(context, options)
    }

    override fun analyze(imageProxy: ImageProxy) {
        val now = SystemClock.uptimeMillis()
        if (now - lastSubmittedAt < FRAME_INTERVAL_MS || !busy.compareAndSet(false, true)) {
            imageProxy.close()
            return
        }
        lastSubmittedAt = now

        try {
            val rotation = imageProxy.imageInfo.rotationDegrees
            val capturedAt = if (realtimeFrameTimestamps) {
                imageProxy.imageInfo.timestamp
            } else {
                SystemClock.elapsedRealtimeNanos() - ASSUMED_PIPELINE_DELAY_NANOS
            }
            // CameraX 1.5 honours the row stride of the RGBA buffer; a plain buffer copy did not.
            val raw = imageProxy.toBitmap()
            imageProxy.close()
            // Rotate upright here, like Google's MediaPipe sample, so boxes come back in screen
            // orientation and no code downstream has to guess which frame MediaPipe reports in.
            val bitmap = if (rotation == 0) raw else {
                val matrix = Matrix().apply { postRotate(rotation.toFloat()) }
                Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, matrix, false).also { raw.recycle() }
            }
            val width = bitmap.width
            val height = bitmap.height

            val lumaGrid = measureLumaGrid(bitmap, 0)
            val mpImage = BitmapImageBuilder(bitmap).build()
            val timestamp = max(now, lastTimestamp + 1)
            lastTimestamp = timestamp
            synchronized(lock) {
                pending = PendingFrame(timestamp, capturedAt, width, height, 0, lumaGrid, bitmap, mpImage)
            }
            val processing = ImageProcessingOptions.builder()
                .setRotationDegrees(0)
                .build()
            detector.detectAsync(mpImage, processing, timestamp)
        } catch (error: Throwable) {
            runCatching { imageProxy.close() }
            releasePending()
            busy.set(false)
            onError(error.message ?: "Camera frame analysis failed")
        }
    }

    private fun handleResult(result: ObjectDetectorResult, input: MPImage) {
        val frame = synchronized(lock) {
            pending.also { pending = null }
        }
        try {
            if (frame == null) return
            val detections = result.detections().mapNotNull { detection ->
                val category = detection.categories().maxByOrNull { it.score() } ?: return@mapNotNull null
                val kind = VehicleKind.fromModelLabel(category.categoryName()) ?: return@mapNotNull null
                RawDetection(
                    android.graphics.RectF(detection.boundingBox()),
                    kind,
                    category.categoryName(),
                    category.score()
                )
            }
            val strongestBox = detections.maxByOrNull { it.confidence }?.box?.let { detectorBox ->
                val rotatedWidth = if (frame.rotation == 90 || frame.rotation == 270) frame.height else frame.width
                val rotatedHeight = if (frame.rotation == 90 || frame.rotation == 270) frame.width else frame.height
                CoordinateMapper.toViewNormalized(
                    detectorBox,
                    frame.width,
                    frame.height,
                    frame.rotation,
                    rotatedWidth,
                    rotatedHeight
                )
            }
            onFrame(
                AnalysisFrame(
                    detections = detections,
                    imageWidth = frame.width,
                    imageHeight = frame.height,
                    rotationDegrees = frame.rotation,
                    capturedAtNanos = frame.capturedAtNanos,
                    inferenceMillis = (SystemClock.uptimeMillis() - frame.timestamp).coerceAtLeast(0),
                    stats = frame.lumaGrid.statsFor(strongestBox)
                )
            )
        } finally {
            runCatching { input.close() }
            frame?.bitmap?.recycle()
            busy.set(false)
        }
    }

    private fun handleError(error: RuntimeException) {
        Log.e(TAG, "Vehicle detector error", error)
        releasePending()
        busy.set(false)
        onError(error.message ?: "On-device detector failed")
    }

    private fun releasePending() {
        val frame = synchronized(lock) {
            pending.also { pending = null }
        }
        runCatching { frame?.mpImage?.close() }
        frame?.bitmap?.recycle()
    }

    override fun close() {
        releasePending()
        detector.close()
    }

    private fun measureLumaGrid(bitmap: Bitmap, rotationDegrees: Int): LumaGrid {
        val displayWidth = if (rotationDegrees == 90 || rotationDegrees == 270) bitmap.height else bitmap.width
        val displayHeight = if (rotationDegrees == 90 || rotationDegrees == 270) bitmap.width else bitmap.height
        val values = FloatArray(GRID_WIDTH * GRID_HEIGHT)
        for (gridY in 0 until GRID_HEIGHT) {
            val y = (((gridY + 0.5f) / GRID_HEIGHT) * displayHeight).toInt().coerceIn(0, displayHeight - 1)
            for (gridX in 0 until GRID_WIDTH) {
                val x = (((gridX + 0.5f) / GRID_WIDTH) * displayWidth).toInt().coerceIn(0, displayWidth - 1)
                val (sourceX, sourceY) = when (rotationDegrees) {
                    90 -> y to (bitmap.height - 1 - x)
                    180 -> (bitmap.width - 1 - x) to (bitmap.height - 1 - y)
                    270 -> (bitmap.width - 1 - y) to x
                    else -> x to y
                }
                val color = bitmap.getPixel(sourceX, sourceY)
                val r = (color shr 16) and 0xff
                val g = (color shr 8) and 0xff
                val b = color and 0xff
                val luma = (0.2126f * r + 0.7152f * g + 0.0722f * b) / 255f
                values[gridY * GRID_WIDTH + gridX] = luma
            }
        }
        return LumaGrid(GRID_WIDTH, GRID_HEIGHT, values)
    }

    private class PendingFrame(
        val timestamp: Long,
        val capturedAtNanos: Long,
        val width: Int,
        val height: Int,
        val rotation: Int,
        val lumaGrid: LumaGrid,
        val bitmap: Bitmap,
        val mpImage: MPImage
    )

    private class LumaGrid(
        val width: Int,
        val height: Int,
        val values: FloatArray
    ) {
        fun statsFor(subject: NormalizedBox?): FrameStats {
            val box = subject?.clamped()
            val expanded = box?.let {
                NormalizedBox(
                    (it.left - 0.035f).coerceAtLeast(0f),
                    (it.top - 0.035f).coerceAtLeast(0f),
                    (it.right + 0.035f).coerceAtMost(1f),
                    (it.bottom + 0.035f).coerceAtMost(1f)
                )
            }
            var subjectTotal = 0f
            var subjectSamples = 0
            var highlights = 0
            var shadows = 0
            var leftEdges = 0
            var rightEdges = 0
            var leftComparisons = 0
            var rightComparisons = 0

            fun isInside(test: NormalizedBox?, x: Float, y: Float): Boolean {
                return test != null && x in test.left..test.right && y in test.top..test.bottom
            }

            for (gridY in 0 until height) {
                val normalizedY = (gridY + 0.5f) / height
                for (gridX in 0 until width) {
                    val normalizedX = (gridX + 0.5f) / width
                    val luma = values[gridY * width + gridX]
                    if (box == null || isInside(box, normalizedX, normalizedY)) {
                        subjectTotal += luma
                        subjectSamples++
                        if (luma > 0.94f) highlights++
                        if (luma < 0.08f) shadows++
                    }

                    if (gridX > 0 && !isInside(expanded, normalizedX, normalizedY)) {
                        val previousX = (gridX - 0.5f) / width
                        if (!isInside(expanded, previousX, normalizedY)) {
                            val previous = values[gridY * width + gridX - 1]
                            val isEdge = abs(luma - previous) > 0.12f
                            if (normalizedX < 0.5f) {
                                leftComparisons++
                                if (isEdge) leftEdges++
                            } else {
                                rightComparisons++
                                if (isEdge) rightEdges++
                            }
                        }
                    }
                }
            }

            return FrameStats(
                meanLuma = if (subjectSamples == 0) 0.5f else subjectTotal / subjectSamples,
                highlightFraction = if (subjectSamples == 0) 0f else highlights.toFloat() / subjectSamples,
                shadowFraction = if (subjectSamples == 0) 0f else shadows.toFloat() / subjectSamples,
                leftEdgeDensity = if (leftComparisons == 0) 0f else leftEdges.toFloat() / leftComparisons,
                rightEdgeDensity = if (rightComparisons == 0) 0f else rightEdges.toFloat() / rightComparisons
            )
        }
    }

    companion object {
        private const val TAG = "VehicleAnalyzer"
        const val MODEL_INT8 = "efficientdet_lite0.tflite"
        const val MODEL_FP16 = "efficientdet_lite0_fp16.tflite"
        /** Lower bound between detector submissions; a slow delegate throttles itself via the busy flag. */
        private const val FRAME_INTERVAL_MS = 80L
        /** Sensor-to-analyzer delay assumed when the camera clock cannot be matched to the sensors. */
        private const val ASSUMED_PIPELINE_DELAY_NANOS = 30_000_000L
        private const val GRID_WIDTH = 32
        private const val GRID_HEIGHT = 24
    }
}
