package be.roadframe.coach

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Log
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.framework.image.MPImage
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.ImageProcessingOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.objectdetector.ObjectDetector
import com.google.mediapipe.tasks.vision.objectdetector.ObjectDetectorResult
import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.max

class VehicleAnalyzer(
    context: Context,
    private val onFrame: (AnalysisFrame) -> Unit,
    private val onError: (String) -> Unit
) : ImageAnalysis.Analyzer, Closeable {

    private val busy = AtomicBoolean(false)
    private val lock = Any()
    private var pending: PendingFrame? = null
    private var lastSubmittedAt = 0L
    private var lastTimestamp = 0L

    private val detector: ObjectDetector

    init {
        val baseOptions = BaseOptions.builder()
            .setModelAssetPath(MODEL_FILE)
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
        detector = ObjectDetector.createFromOptions(context, options)
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
            val bitmap = Bitmap.createBitmap(
                imageProxy.width,
                imageProxy.height,
                Bitmap.Config.ARGB_8888
            )
            imageProxy.planes[0].buffer.rewind()
            bitmap.copyPixelsFromBuffer(imageProxy.planes[0].buffer)
            val width = imageProxy.width
            val height = imageProxy.height
            imageProxy.close()

            val lumaGrid = measureLumaGrid(bitmap, rotation)
            val mpImage = BitmapImageBuilder(bitmap).build()
            val timestamp = max(now, lastTimestamp + 1)
            lastTimestamp = timestamp
            synchronized(lock) {
                pending = PendingFrame(timestamp, width, height, rotation, lumaGrid, bitmap, mpImage)
            }
            val processing = ImageProcessingOptions.builder()
                .setRotationDegrees(rotation)
                .build()
            detector.detectAsync(mpImage, processing, timestamp)
        } catch (error: Throwable) {
            imageProxy.close()
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
                    inferenceMillis = (SystemClock.uptimeMillis() - result.timestampMs()).coerceAtLeast(0),
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

    private data class PendingFrame(
        val timestamp: Long,
        val width: Int,
        val height: Int,
        val rotation: Int,
        val lumaGrid: LumaGrid,
        val bitmap: Bitmap,
        val mpImage: MPImage
    )

    private data class LumaGrid(
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
        private const val MODEL_FILE = "efficientdet_lite0.tflite"
        private const val FRAME_INTERVAL_MS = 110L
        private const val GRID_WIDTH = 32
        private const val GRID_HEIGHT = 24
    }
}
