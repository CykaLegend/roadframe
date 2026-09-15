package be.roadframe.coach

import android.Manifest
import android.content.ContentValues
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraMetadata
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.SystemClock
import android.provider.MediaStore
import android.text.TextUtils
import android.view.Choreographer
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.Surface
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.AspectRatio
import androidx.camera.core.Camera
import androidx.camera.core.CameraInfo
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
import kotlin.math.atan
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Two clocks. The sensors (up to 200 Hz) and the display (every vsync) drive level, pitch,
 * heading and the predicted car box; the detector (about 10 Hz) only re-anchors that box. The
 * coach engine runs once per displayed frame on pure numbers and the overlay draws its answer.
 */
class MainActivity : AppCompatActivity() {
    private lateinit var root: FrameLayout
    private lateinit var previewView: PreviewView
    private lateinit var overlay: CoachOverlayView
    private lateinit var shotChip: TextView
    private lateinit var noseChip: TextView
    private lateinit var subjectChip: TextView
    private lateinit var shutterButton: TextView
    private lateinit var prefs: CoachPrefs

    private val cameraExecutor = Executors.newSingleThreadExecutor()
    private val coachEngine = CoachEngine()
    private val tracker = SubjectTracker()
    private lateinit var poseSensor: PoseSensor
    private var vehicleAnalyzer: VehicleAnalyzer? = null
    private var imageAnalysis: ImageAnalysis? = null
    private var camera: Camera? = null
    private var imageCapture: ImageCapture? = null

    private var category = ShotCategory.FRONT_THREE_QUARTER
    private var structure = CoachStructure.ROADFRAME
    private var subjectPreference = SubjectPreference.AUTO
    private var showStats = true

    private var lastStats = FrameStats()
    private var manualSelection: NormalizedBox? = null
    /** Heading of the camera when the user tapped NOSE while facing the nose head-on. */
    private var noseHeading: Float? = null
    private var currentZoom = 1f
    private var sensorLongFov = DEFAULT_LONG_FOV
    private var sensorShortFov = DEFAULT_SHORT_FOV
    private var realtimeFrameTimestamps = false
    private var delegateName = "CPU"
    /** What the detector last reported, raw, for the stats line: "car 63%" or "none". */
    private var lastDetectionSummary = "none"

    private var wasShoot = false
    private var lastPrimaryRule: Rule? = null
    private val perf = PerfMeter()
    private var running = false
    private var lastTickNanos = 0L

    private val lensChips = linkedMapOf<Float, TextView>()

    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!running) return
            if (frameTimeNanos - lastTickNanos >= MIN_TICK_NANOS) {
                lastTickNanos = frameTimeNanos
                tick()
            }
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

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

        prefs = CoachPrefs(this)
        category = prefs.category
        structure = prefs.structure
        subjectPreference = prefs.subject
        showStats = prefs.showStats

        buildInterface()
        poseSensor = PoseSensor(this, ::displayRotationDegrees) { }
        overlay.setDetailMode(!category.geometry.tracksVehicle)

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            previewView.post { startCamera() }
        } else {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    override fun onResume() {
        super.onResume()
        poseSensor.start()
        running = true
        Choreographer.getInstance().postFrameCallback(frameCallback)
    }

    override fun onPause() {
        running = false
        Choreographer.getInstance().removeFrameCallback(frameCallback)
        poseSensor.stop()
        super.onPause()
    }

    override fun onDestroy() {
        vehicleAnalyzer?.close()
        cameraExecutor.shutdown()
        super.onDestroy()
    }

    private fun displayRotationDegrees(): Int = when (previewView.display?.rotation ?: Surface.ROTATION_0) {
        Surface.ROTATION_90 -> 90
        Surface.ROTATION_180 -> 180
        Surface.ROTATION_270 -> 270
        else -> 0
    }

    // ---------------------------------------------------------------- interface

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
                coachEngine.reset()
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

        val settingsButton = makeChip("?", compact = true).apply {
            contentDescription = "Coach settings"
            setOnClickListener { showSettings() }
        }
        root.addView(settingsButton, FrameLayout.LayoutParams(dp(44), dp(38)).apply {
            gravity = Gravity.TOP or Gravity.END
            rightMargin = dp(18)
            topMargin = dp(if (landscape) 18 else 37)
        })

        val rows = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val shotRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        shotChip = makeChip(shotLabel()).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setOnClickListener { showShotPicker() }
        }
        val briefChip = makeChip("BRIEF").apply { setOnClickListener { showBrief() } }
        shotRow.addView(shotChip, LinearLayout.LayoutParams(0, dp(38), 1f))
        shotRow.addView(briefChip, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(38)).apply { leftMargin = dp(8) })
        rows.addView(shotRow, LinearLayout.LayoutParams(if (landscape) dp(360) else LinearLayout.LayoutParams.MATCH_PARENT, dp(42)))

        val toolRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        noseChip = makeChip(noseLabel()).apply {
            contentDescription = "Calibrate the viewing angle: tap while facing the nose of the car"
            setOnClickListener { calibrateNose() }
            setOnLongClickListener { clearNose(); true }
        }
        subjectChip = makeChip(getString(R.string.subject_chip, subjectPreference.chipLabel)).apply {
            setOnClickListener {
                subjectPreference = subjectPreference.next()
                prefs.subject = subjectPreference
                text = getString(R.string.subject_chip, subjectPreference.chipLabel)
                tracker.clear()
                coachEngine.reset()
            }
        }
        toolRow.addView(noseChip, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(38)))
        toolRow.addView(subjectChip, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(38)).apply { leftMargin = dp(8) })
        rows.addView(toolRow, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(42)).apply { topMargin = dp(4) })

        root.addView(rows, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.TOP or Gravity.START
            leftMargin = dp(18)
            rightMargin = dp(18)
            topMargin = dp(if (landscape) 72 else 94)
        })

        val lensRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        listOf(0.6f to ".6", 1f to "1×", 2f to "2×", 3f to "3×", 5f to "5×").forEach { (ratio, label) ->
            val chip = makeChip(label, compact = true).apply {
                contentDescription = "$ratio times zoom"
                setOnClickListener { setZoom(ratio) }
            }
            lensChips[ratio] = chip
            lensRow.addView(chip, LinearLayout.LayoutParams(dp(46), dp(38)).apply {
                leftMargin = dp(3)
                rightMargin = dp(3)
            })
        }
        root.addView(lensRow, FrameLayout.LayoutParams(dp(270), dp(42)).apply {
            gravity = if (landscape) Gravity.BOTTOM or Gravity.END else Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            bottomMargin = dp(if (landscape) 24 else 106)
            if (landscape) rightMargin = dp(102)
        })

        shutterButton = TextView(this).apply {
            contentDescription = "Take photo"
            gravity = Gravity.CENTER
            background = shutterBackground()
            setOnClickListener { capturePhoto() }
            addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
                this@MainActivity.overlay.setShutter(v.x + v.width / 2f, v.y + v.height / 2f, v.width / 2f)
            }
        }
        root.addView(shutterButton, FrameLayout.LayoutParams(dp(68), dp(68)).apply {
            gravity = if (landscape) Gravity.BOTTOM or Gravity.END else Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            bottomMargin = dp(if (landscape) 12 else 32)
            if (landscape) rightMargin = dp(18)
        })

        setContentView(root)
        updateLensSelection(1f)
    }

    private fun shotLabel(): String = "${category.number} · ${category.chipLabel}"
    private fun noseLabel(): String = if (noseHeading == null) "NOSE 0°" else "NOSE ✓"

    // ---------------------------------------------------------------- camera

    @Suppress("DEPRECATION")
    private fun startCamera() {
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            try {
                val provider = providerFuture.get()
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
                imageAnalysis = analysis

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
                camera?.cameraInfo?.let { readCameraGeometry(it) }
                observeZoomRange()
                startAnalyzer()
            } catch (error: Throwable) {
                showDetectorError(error.message ?: "Camera could not start")
            }
        }, ContextCompat.getMainExecutor(this))
    }

    /** Field of view and clock of the sensor, so box prediction and frame timing are real numbers. */
    @OptIn(ExperimentalCamera2Interop::class)
    private fun readCameraGeometry(info: CameraInfo) {
        try {
            val camera2 = Camera2CameraInfo.from(info)
            val size = camera2.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
            val focal = camera2.getCameraCharacteristic(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull()
            if (size != null && focal != null && focal > 0f) {
                val long = max(size.width, size.height)
                val short = min(size.width, size.height)
                sensorLongFov = Math.toDegrees(2.0 * atan(long / (2.0 * focal))).toFloat()
                sensorShortFov = Math.toDegrees(2.0 * atan(short / (2.0 * focal))).toFloat()
            }
            val source = camera2.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE)
            realtimeFrameTimestamps = source == CameraMetadata.SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME
        } catch (_: Throwable) {
            sensorLongFov = DEFAULT_LONG_FOV
            sensorShortFov = DEFAULT_SHORT_FOV
            realtimeFrameTimestamps = false
        }
    }

    private fun startAnalyzer() {
        val analysis = imageAnalysis ?: return
        val preferGpu = prefs.useGpu
        cameraExecutor.execute {
            val old = vehicleAnalyzer
            old?.close()
            val analyzer = try {
                VehicleAnalyzer(
                    applicationContext,
                    preferGpu = preferGpu,
                    realtimeFrameTimestamps = realtimeFrameTimestamps,
                    onFrame = { frame -> runOnUiThread { handleAnalysis(frame) } },
                    onError = { message -> runOnUiThread { showDetectorError(message) } }
                )
            } catch (error: Throwable) {
                runOnUiThread { showDetectorError(error.message ?: "Detector could not start") }
                return@execute
            }
            vehicleAnalyzer = analyzer
            runOnUiThread {
                delegateName = analyzer.delegateName
                if (preferGpu && analyzer.delegateName != "GPU") {
                    Toast.makeText(this, "GPU delegate unavailable, using CPU", Toast.LENGTH_SHORT).show()
                }
            }
            analysis.setAnalyzer(cameraExecutor, analyzer)
        }
    }

    /** Detector clock: re-anchor the tracked box with the pose the phone had at capture time. */
    private fun handleAnalysis(frame: AnalysisFrame) {
        val now = SystemClock.uptimeMillis()
        lastStats = frame.stats
        perf.onDetection(now, frame.inferenceMillis, frame.capturedAtNanos)
        lastDetectionSummary = if (frame.detections.isEmpty()) "none" else
            frame.detections.joinToString(" ") { "${it.label} ${(it.confidence * 100).toInt()}%" }

        val best = frame.detections
            .filter { subjectPreference.accepts(it.kind) }
            .maxByOrNull { it.confidence } ?: return
        if (overlay.width <= 0 || overlay.height <= 0) return

        val mappedBox = CoordinateMapper.toViewNormalized(
            best.box,
            frame.imageWidth,
            frame.imageHeight,
            frame.rotationDegrees,
            overlay.width,
            overlay.height
        )
        val imageAspect = if (best.box.height() > 1f) best.box.width() / best.box.height() else 1f
        val (fovX, fovY) = viewFov()
        tracker.onDetection(
            mappedBox, best.kind, best.label, best.confidence, imageAspect,
            poseSensor.poseAt(frame.capturedAtNanos), fovX, fovY, now
        )
    }

    private fun viewFov(): Pair<Float, Float> = PoseMath.viewFov(
        sensorLongFov, sensorShortFov, overlay.width, overlay.height,
        portrait = overlay.height >= overlay.width, zoomRatio = currentZoom
    )

    /** Display clock: predict, coach, draw. Runs once per vsync, capped at about 60 Hz. */
    private fun tick() {
        if (!::overlay.isInitialized || overlay.width == 0) return
        val nowMs = SystemClock.uptimeMillis()
        perf.onTick(nowMs)
        val pose = poseSensor.latest
        val (fovX, fovY) = viewFov()
        val portrait = overlay.height >= overlay.width

        val subject = if (category.geometry.tracksVehicle) {
            tracker.current(pose, fovX, fovY, nowMs)
        } else {
            manualSelection?.let {
                val kind = if (subjectPreference == SubjectPreference.MOTORCYCLE) VehicleKind.MOTORCYCLE else VehicleKind.CAR
                DetectedSubject(it, kind, "detail", 1f)
            }
        }
        val viewingAngle = noseHeading?.let { nose -> pose?.let { PoseMath.wrap(it.headingDeg - nose) } }

        val guidance = coachEngine.evaluate(
            CoachEngine.Input(
                category = category,
                structure = structure,
                subject = subject,
                pose = pose,
                viewingAngle = viewingAngle,
                stats = lastStats,
                zoomRatio = currentZoom,
                portrait = portrait,
                nowMs = nowMs
            )
        )

        val statsLine = if (showStats) {
            val analyzer = vehicleAnalyzer
            val frames = if (analyzer == null) "no analyzer" else "${analyzer.framesSubmitted}/${analyzer.resultsReceived}"
            perf.line(delegateName, tracker.anchorAgeMs(nowMs), viewingAngle, pose) +
                "\nFRAMES $frames  ·  SEES $lastDetectionSummary"
        } else null
        overlay.update(subject, guidance, statsLine)

        if (guidance.shoot && !wasShoot) {
            overlay.performHapticFeedback(
                if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.LONG_PRESS
            )
        } else if (guidance.primary?.rule != lastPrimaryRule && guidance.primary != null) {
            overlay.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        }
        wasShoot = guidance.shoot
        lastPrimaryRule = guidance.primary?.rule
    }

    // ---------------------------------------------------------------- calibration

    private fun calibrateNose() {
        val pose = poseSensor.latest
        if (!poseSensor.headingAvailable || pose == null) {
            Toast.makeText(this, "No rotation sensor: the angle cannot be measured on this phone", Toast.LENGTH_LONG).show()
            return
        }
        noseHeading = pose.headingDeg
        noseChip.text = noseLabel()
        coachEngine.reset()
        Toast.makeText(this, "0° set at the nose. Walk around; the coach counts the degrees.", Toast.LENGTH_SHORT).show()
    }

    private fun clearNose() {
        noseHeading = null
        noseChip.text = noseLabel()
        coachEngine.reset()
        Toast.makeText(this, "Angle calibration cleared", Toast.LENGTH_SHORT).show()
    }

    // ---------------------------------------------------------------- dialogs

    private fun showShotPicker() {
        val labels = ShotCategory.entries.map { "${it.number}.  ${it.title}\n      ${it.tag}" }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Which shot?")
            .setItems(labels) { _, index -> selectCategory(ShotCategory.entries[index]) }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun selectCategory(next: ShotCategory) {
        category = next
        prefs.category = next
        shotChip.text = shotLabel()
        manualSelection = null
        overlay.setDetailMode(!next.geometry.tracksVehicle)
        coachEngine.reset()
        Toast.makeText(this, "${next.title}: ${next.tag}", Toast.LENGTH_SHORT).show()
    }

    private fun showBrief() {
        val b = category.brief
        val text = buildString {
            append("WHY\n").append(b.why).append("\n\n")
            append("PARK\n").append(b.park).append("\n\n")
            append("STAND\n").append(b.stand).append("\n\n")
            append("Distance: ").append(b.distance).append('\n')
            append("Camera height: ").append(b.height).append('\n')
            append("Lens: ").append(b.lens).append("\n\n")
            append("CHECK BEFORE YOU SHOOT\n")
            b.checklist.forEach { append("•  ").append(it).append('\n') }
            append('\n').append(ARROW_GUIDE)
        }
        AlertDialog.Builder(this)
            .setTitle("${category.number}. ${category.title}")
            .setMessage(text)
            .setPositiveButton("Got it", null)
            .show()
    }

    private fun showSettings() {
        val padding = dp(20)
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, dp(8), padding, dp(8))
        }
        column.addView(TextView(this).apply {
            text = "COACH STRUCTURE\nWhich problem the coach fixes first when several are present."
            textSize = 13f
        })
        val group = RadioGroup(this)
        CoachStructure.entries.forEach { option ->
            group.addView(RadioButton(this).apply {
                id = View.generateViewId()
                text = "${option.label}\n${option.summary}"
                textSize = 14f
                isChecked = option == structure
                tag = option
                setPadding(dp(4), dp(10), 0, dp(10))
            })
        }
        group.setOnCheckedChangeListener { g, checkedId ->
            val option = g.findViewById<RadioButton>(checkedId)?.tag as? CoachStructure ?: return@setOnCheckedChangeListener
            structure = option
            prefs.structure = option
            coachEngine.reset()
        }
        column.addView(group)

        column.addView(SwitchCompat(this).apply {
            text = "GPU detector (float16 model). Compare the ms in the stats line."
            isChecked = prefs.useGpu
            setPadding(0, dp(12), 0, dp(12))
            setOnCheckedChangeListener { _, checked ->
                prefs.useGpu = checked
                startAnalyzer()
            }
        })
        column.addView(SwitchCompat(this).apply {
            text = "Show stats: detections per second and ms, lag from capture to coach, age of the last detector box, screen rate, roll, pitch, angle"
            isChecked = showStats
            setPadding(0, dp(12), 0, dp(12))
            setOnCheckedChangeListener { _, checked ->
                showStats = checked
                prefs.showStats = checked
            }
        })
        column.addView(TextView(this).apply {
            text = ABOUT
            textSize = 13f
            setPadding(0, dp(16), 0, 0)
        })
        AlertDialog.Builder(this)
            .setTitle("RoadFrame coach")
            .setView(ScrollView(this).apply { addView(column) })
            .setPositiveButton("Done", null)
            .show()
    }

    // ---------------------------------------------------------------- camera controls

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
        val zoom = requested.coerceIn(state.minZoomRatio, state.maxZoomRatio)
        activeCamera.cameraControl.setZoomRatio(zoom)
        updateLensSelection(zoom)
    }

    private fun observeZoomRange() {
        camera?.cameraInfo?.zoomState?.observe(this) { state ->
            currentZoom = state.zoomRatio
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

    // ---------------------------------------------------------------- widgets

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

    /** Counters for the stats line: measured, not guessed. */
    private class PerfMeter {
        private var detections = 0
        private var detectionRate = 0
        private var detectionWindowStart = 0L
        private var ticks = 0
        private var tickRate = 0
        private var tickWindowStart = 0L
        private var inferenceMs = 0L
        private var pipelineMs = 0L

        fun onDetection(nowMs: Long, inference: Long, capturedAtNanos: Long) {
            inferenceMs = inference
            pipelineMs = ((SystemClock.elapsedRealtimeNanos() - capturedAtNanos) / 1_000_000L).coerceIn(0L, 5_000L)
            detections++
            if (nowMs - detectionWindowStart >= 1000L) {
                detectionRate = detections
                detections = 0
                detectionWindowStart = nowMs
            }
        }

        fun onTick(nowMs: Long) {
            ticks++
            if (nowMs - tickWindowStart >= 1000L) {
                tickRate = ticks
                ticks = 0
                tickWindowStart = nowMs
            }
        }

        fun line(delegate: String, anchorAgeMs: Long, viewingAngle: Float?, pose: Pose?): String = buildString {
            append("DET ").append(detectionRate).append("/s ").append(inferenceMs).append("ms ").append(delegate)
            append("  ·  LAG ").append(pipelineMs).append("ms")
            append("  ·  BOX ").append(anchorAgeMs).append("ms")
            append("  ·  UI ").append(tickRate).append("/s")
            pose?.let { append("  ·  ROLL ").append(it.rollDeg.roundToInt()).append("° PITCH ").append(it.pitchDeg.roundToInt()).append('°') }
            viewingAngle?.let { append("  ·  ANGLE ").append(it.roundToInt()).append('°') }
        }
    }

    companion object {
        /** About 60 Hz even on a 120 Hz panel: the coach does not need more, the battery does. */
        private const val MIN_TICK_NANOS = 15_000_000L
        /** 24 mm-equivalent main camera, 4:3 sensor: used until the real characteristics are read. */
        private const val DEFAULT_LONG_FOV = 72f
        private const val DEFAULT_SHORT_FOV = 56f

        private const val ARROW_GUIDE = "HOW TO READ THE ARROWS\n" +
            "•  Chevron at the left or right edge: turn the phone that way (AIM).\n" +
            "•  Chevron at the top or bottom: tilt that way, keep the phone at the same height.\n" +
            "•  Arrows beside the car pointing in: get closer or zoom in. Pointing out: back up.\n" +
            "•  Curved arrow with degrees: walk around the car that way, camera on the car. Tap NOSE once while facing the nose head-on so the degrees are real.\n" +
            "•  Double chevron on the right pointing down: the phone points down, crouch lower and aim level.\n" +
            "•  Line in the middle: the horizon. Turn the phone until it is green and flat.\n" +
            "•  Ring around the shutter: fills as the errors shrink, green plus a tick means shoot."

        private const val ABOUT = "Everything runs on the phone: the bundled detector finds the car about ten " +
            "times a second, the gyro carries the box between detections, and the coach turns the numbers into one " +
            "instruction at a time. No image leaves the phone. This build measures where the car is and how the " +
            "phone is held; it does not yet see wheel direction or tell the front from the rear by itself, so tap " +
            "NOSE once per car for the viewing angle."
    }
}
