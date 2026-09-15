package be.roadframe.coach

import android.Manifest
import android.content.ContentValues
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.AspectRatio
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {
    private lateinit var root: FrameLayout
    private lateinit var previewView: PreviewView
    private lateinit var overlay: CoachOverlayView
    private lateinit var subjectChip: TextView
    private lateinit var modeChip: TextView
    private lateinit var shutterButton: TextView

    private val cameraExecutor = Executors.newSingleThreadExecutor()
    private val coachEngine = CoachEngine()
    private lateinit var levelSensor: LevelSensor
    private var vehicleAnalyzer: VehicleAnalyzer? = null
    private var camera: Camera? = null
    private var imageCapture: ImageCapture? = null

    private var subjectPreference = SubjectPreference.AUTO
    private var shotMode = ShotMode.BALANCED
    private var rollDegrees = 0f
    private var lastStats = FrameStats()
    private var lastInferenceMillis = 0L
    private var detectedSubject: DetectedSubject? = null
    private var manualSelection: NormalizedBox? = null
    private var lastDetectionAt = 0L

    private var stableAdvice: CoachAdvice? = null
    private var candidateAction: AdviceAction? = null
    private var candidateFrames = 0
    private var wasReady = false

    private val lensChips = linkedMapOf<Float, TextView>()
    private var requestedZoom = 1f

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            previewView.post { startCamera() }
        } else {
            showCameraPermissionMessage()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        buildInterface()
        levelSensor = LevelSensor(this) { roll ->
            runOnUiThread {
                rollDegrees = roll
                refreshAdvice()
            }
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            previewView.post { startCamera() }
        } else {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    override fun onResume() {
        super.onResume()
        if (::levelSensor.isInitialized) levelSensor.start()
    }

    override fun onPause() {
        if (::levelSensor.isInitialized) levelSensor.stop()
        super.onPause()
    }

    override fun onDestroy() {
        vehicleAnalyzer?.close()
        cameraExecutor.shutdown()
        super.onDestroy()
    }

    private fun buildInterface() {
        val landscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        previewView = PreviewView(this).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
            implementationMode = PreviewView.ImplementationMode.PERFORMANCE
        }
        overlay = CoachOverlayView(this).apply {
            onManualSelection = { box ->
                manualSelection = box
                resetAdviceStability()
                refreshAdvice()
            }
            onFocusTap = { x, y -> focusAt(x, y) }
        }

        root.addView(previewView, FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        root.addView(overlay, FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)

        val brandColumn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(this@MainActivity).apply {
                text = getString(R.string.brand_name)
                setTextColor(Color.WHITE)
                textSize = 20f
                typeface = Typeface.create("sans", Typeface.BOLD)
                letterSpacing = 0.12f
            })
            addView(TextView(this@MainActivity).apply {
                text = getString(R.string.offline_badge)
                setTextColor(Color.rgb(200, 255, 54))
                textSize = 9f
                typeface = Typeface.create("sans", Typeface.BOLD)
                letterSpacing = 0.1f
            })
        }
        root.addView(brandColumn, FrameLayout.LayoutParams(dp(190), dp(54)).apply {
            gravity = Gravity.TOP or Gravity.START
            leftMargin = dp(18)
            topMargin = dp(if (landscape) 18 else 34)
        })

        val infoButton = makeChip("?", compact = true).apply {
            contentDescription = "About RoadFrame"
            setOnClickListener { showAbout() }
        }
        root.addView(infoButton, FrameLayout.LayoutParams(dp(44), dp(38)).apply {
            gravity = Gravity.TOP or Gravity.END
            rightMargin = dp(18)
            topMargin = dp(if (landscape) 18 else 37)
        })

        val controlRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        subjectChip = makeChip(getString(R.string.subject_chip, subjectPreference.chipLabel)).apply {
            setOnClickListener {
                subjectPreference = subjectPreference.next()
                text = getString(R.string.subject_chip, subjectPreference.chipLabel)
                detectedSubject = null
                resetAdviceStability()
                refreshAdvice()
            }
        }
        modeChip = makeChip(getString(R.string.mode_chip, shotMode.chipLabel)).apply {
            setOnClickListener {
                shotMode = shotMode.next()
                text = getString(R.string.mode_chip, shotMode.chipLabel)
                manualSelection = null
                this@MainActivity.overlay.setDetailMode(shotMode == ShotMode.DETAIL)
                resetAdviceStability()
                Toast.makeText(this@MainActivity, shotMode.description, Toast.LENGTH_SHORT).show()
                refreshAdvice()
            }
        }
        controlRow.addView(subjectChip)
        controlRow.addView(modeChip, LinearLayout.LayoutParams.WRAP_CONTENT, dp(38)).also {
            (modeChip.layoutParams as LinearLayout.LayoutParams).leftMargin = dp(8)
        }
        root.addView(controlRow, FrameLayout.LayoutParams.MATCH_PARENT, dp(42).apply { }).also {
            (controlRow.layoutParams as FrameLayout.LayoutParams).apply {
                gravity = Gravity.TOP or Gravity.START
                leftMargin = dp(18)
                rightMargin = dp(18)
                topMargin = dp(if (landscape) 72 else 94)
            }
        }

        val lensRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        listOf(0.6f to ".6", 1f to "1×", 3f to "3×", 5f to "5×").forEach { (ratio, label) ->
            val chip = makeChip(label, compact = true).apply {
                contentDescription = "$ratio times zoom"
                setOnClickListener { setZoom(ratio) }
            }
            lensChips[ratio] = chip
            lensRow.addView(chip, LinearLayout.LayoutParams(dp(48), dp(38)).apply {
                leftMargin = dp(4)
                rightMargin = dp(4)
            })
        }
        root.addView(lensRow, FrameLayout.LayoutParams(dp(240), dp(42)).apply {
            gravity = if (landscape) Gravity.BOTTOM or Gravity.END else Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            bottomMargin = dp(if (landscape) 24 else 106)
            if (landscape) rightMargin = dp(102)
        })

        shutterButton = TextView(this).apply {
            contentDescription = "Take photo"
            gravity = Gravity.CENTER
            background = shutterBackground()
            setOnClickListener { capturePhoto() }
        }
        root.addView(shutterButton, FrameLayout.LayoutParams(dp(68), dp(68)).apply {
            gravity = if (landscape) Gravity.BOTTOM or Gravity.END else Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            bottomMargin = dp(if (landscape) 12 else 32)
            if (landscape) rightMargin = dp(18)
        })

        setContentView(root)
        updateLensSelection(1f)
    }

    @Suppress("DEPRECATION")
    private fun startCamera() {
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            try {
                val provider = providerFuture.get()
                vehicleAnalyzer?.close()
                vehicleAnalyzer = VehicleAnalyzer(
                    applicationContext,
                    onFrame = { frame -> runOnUiThread { handleAnalysis(frame) } },
                    onError = { message -> runOnUiThread { showDetectorError(message) } }
                )

                val rotation = previewView.display.rotation
                val preview = Preview.Builder()
                    .setTargetAspectRatio(AspectRatio.RATIO_4_3)
                    .setTargetRotation(rotation)
                    .build()
                    .also { it.setSurfaceProvider(previewView.surfaceProvider) }

                val analysis = ImageAnalysis.Builder()
                    .setTargetAspectRatio(AspectRatio.RATIO_4_3)
                    .setTargetRotation(rotation)
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                    .build()
                    .also { it.setAnalyzer(cameraExecutor, vehicleAnalyzer!!) }

                imageCapture = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                    .setTargetRotation(rotation)
                    .build()

                provider.unbindAll()
                camera = provider.bindToLifecycle(
                    this,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    analysis,
                    imageCapture
                )
                observeZoomRange()
            } catch (error: Throwable) {
                showDetectorError(error.message ?: "Camera could not start")
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun handleAnalysis(frame: AnalysisFrame) {
        lastStats = frame.stats
        lastInferenceMillis = frame.inferenceMillis

        val best = frame.detections
            .filter { subjectPreference.accepts(it.kind) }
            .maxByOrNull { it.confidence }

        if (best != null && overlay.width > 0 && overlay.height > 0) {
            val mappedBox = CoordinateMapper.toViewNormalized(
                best.box,
                frame.imageWidth,
                frame.imageHeight,
                frame.rotationDegrees,
                overlay.width,
                overlay.height
            )
            val next = DetectedSubject(mappedBox, best.kind, best.label, best.confidence)
            detectedSubject = detectedSubject
                ?.takeIf { it.kind == next.kind }
                ?.let { previous ->
                    previous.copy(
                        box = previous.box.lerp(next.box, 0.38f),
                        confidence = next.confidence,
                        label = next.label
                    )
                }
                ?: next
            lastDetectionAt = android.os.SystemClock.uptimeMillis()
        } else if (android.os.SystemClock.uptimeMillis() - lastDetectionAt > 550L) {
            detectedSubject = null
        }
        refreshAdvice()
    }

    private fun refreshAdvice() {
        if (!::overlay.isInitialized) return
        val subject = if (shotMode == ShotMode.DETAIL) {
            manualSelection?.let {
                DetectedSubject(
                    it,
                    if (subjectPreference == SubjectPreference.MOTORCYCLE) VehicleKind.MOTORCYCLE else VehicleKind.CAR,
                    "detail",
                    1f
                )
            }
        } else {
            detectedSubject
        }
        val raw = coachEngine.evaluate(
            subject,
            shotMode,
            rollDegrees,
            lastStats,
            detailSelectionPending = shotMode == ShotMode.DETAIL && manualSelection == null
        )
        val shown = stabilize(raw)
        overlay.update(subject, shown, shotMode, lastInferenceMillis)

        if (shown.ready && !wasReady) {
            overlay.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        }
        wasReady = shown.ready
    }

    private fun stabilize(raw: CoachAdvice): CoachAdvice {
        val existing = stableAdvice
        if (existing == null || raw.action == AdviceAction.FIND_SUBJECT || raw.action == AdviceAction.SELECT_DETAIL) {
            stableAdvice = raw
            candidateAction = null
            candidateFrames = 0
            return raw
        }
        if (raw.action == existing.action) {
            stableAdvice = raw
            candidateAction = null
            candidateFrames = 0
            return raw
        }

        if (candidateAction == raw.action) {
            candidateFrames++
        } else {
            candidateAction = raw.action
            candidateFrames = 1
        }
        if (candidateFrames >= 3) {
            stableAdvice = raw
            candidateAction = null
            candidateFrames = 0
            return raw
        }
        return existing.copy(score = raw.score, targetBox = raw.targetBox)
    }

    private fun resetAdviceStability() {
        stableAdvice = null
        candidateAction = null
        candidateFrames = 0
        wasReady = false
    }

    private fun focusAt(x: Float, y: Float) {
        val activeCamera = camera ?: return
        if (previewView.width == 0 || previewView.height == 0) return
        val point = previewView.meteringPointFactory.createPoint(x, y)
        val action = FocusMeteringAction.Builder(point)
            .setAutoCancelDuration(3, TimeUnit.SECONDS)
            .build()
        activeCamera.cameraControl.startFocusAndMetering(action)
    }

    private fun setZoom(requested: Float) {
        val activeCamera = camera ?: return
        val state = activeCamera.cameraInfo.zoomState.value ?: return
        requestedZoom = requested.coerceIn(state.minZoomRatio, state.maxZoomRatio)
        activeCamera.cameraControl.setZoomRatio(requestedZoom)
        updateLensSelection(requestedZoom)
    }

    private fun observeZoomRange() {
        camera?.cameraInfo?.zoomState?.observe(this) { state ->
            lensChips.forEach { (ratio, chip) ->
                val available = ratio in state.minZoomRatio..state.maxZoomRatio
                chip.isEnabled = available
                chip.alpha = if (available) 1f else 0.35f
            }
            updateLensSelection(state.zoomRatio)
        }
    }

    private fun updateLensSelection(actualRatio: Float) {
        val selected = lensChips.keys.minByOrNull { kotlin.math.abs(it - actualRatio) } ?: 1f
        lensChips.forEach { (ratio, chip) ->
            chip.background = pillBackground(
                fill = if (ratio == selected) Color.WHITE else Color.argb(150, 7, 9, 11),
                stroke = if (ratio == selected) Color.WHITE else Color.argb(90, 255, 255, 255)
            )
            chip.setTextColor(if (ratio == selected) Color.BLACK else Color.WHITE)
        }
    }

    private fun capturePhoto() {
        val capture = imageCapture ?: return
        shutterButton.isEnabled = false
        val name = "RoadFrame_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}.jpg"
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/RoadFrame")
        }
        val options = ImageCapture.OutputFileOptions.Builder(
            contentResolver,
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
            values
        ).build()

        capture.takePicture(
            options,
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    shutterButton.isEnabled = true
                    overlay.showCaptureFlash()
                    Toast.makeText(this@MainActivity, "Saved to Pictures/RoadFrame", Toast.LENGTH_SHORT).show()
                }

                override fun onError(exception: ImageCaptureException) {
                    shutterButton.isEnabled = true
                    Toast.makeText(this@MainActivity, "Photo failed: ${exception.message}", Toast.LENGTH_LONG).show()
                }
            }
        )
    }

    private fun makeChip(label: String, compact: Boolean = false): TextView = TextView(this).apply {
        text = label
        gravity = Gravity.CENTER
        setTextColor(Color.WHITE)
        textSize = if (compact) 13f else 11f
        typeface = Typeface.create("sans", Typeface.BOLD)
        letterSpacing = if (compact) 0.02f else 0.06f
        setPadding(dp(if (compact) 10 else 14), 0, dp(if (compact) 10 else 14), 0)
        background = pillBackground(Color.argb(165, 7, 9, 11), Color.argb(90, 255, 255, 255))
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(38))
    }

    private fun pillBackground(fill: Int, stroke: Int): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = dp(19).toFloat()
        setColor(fill)
        setStroke(dp(1), stroke)
    }

    private fun shutterBackground(): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(Color.WHITE)
        setStroke(dp(5), Color.argb(210, 7, 9, 11))
    }

    private fun showAbout() {
        AlertDialog.Builder(this)
            .setTitle("RoadFrame beta")
            .setMessage(
                "The bundled neural model detects cars and motorcycles completely offline. " +
                    "RoadFrame then measures level, spacing, subject size, exposure and background edges, " +
                    "and gives one explainable correction at a time.\n\n" +
                    "Balanced is the neutral training mode. Sale prioritizes clear coverage. Cinematic " +
                    "leaves deliberate negative space. In Detail mode, drag around a wheel, badge or repaired panel.\n\n" +
                    "No image leaves this phone. This beta does not yet recognise wheel direction or distinguish " +
                    "front, side and rear three-quarter angles."
            )
            .setPositiveButton("Got it", null)
            .show()
    }

    private fun showCameraPermissionMessage() {
        AlertDialog.Builder(this)
            .setTitle("Camera permission needed")
            .setMessage("RoadFrame processes the live preview on your phone and cannot coach a frame without camera access.")
            .setPositiveButton("Try again") { _, _ -> permissionLauncher.launch(Manifest.permission.CAMERA) }
            .setNegativeButton("Close") { _, _ -> finish() }
            .setCancelable(false)
            .show()
    }

    private fun showDetectorError(message: String) {
        Toast.makeText(this, "RoadFrame: $message", Toast.LENGTH_LONG).show()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
