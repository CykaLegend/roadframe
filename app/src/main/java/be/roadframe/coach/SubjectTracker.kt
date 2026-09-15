package be.roadframe.coach

/**
 * Keeps the car box alive between detections. The detector (about 10 Hz) re-anchors the box
 * together with the pose the phone had when that frame was captured; every screen frame the box
 * is then predicted from how far the phone has turned since. The detector's own latency is
 * cancelled the same way: the anchor is dated at capture time, not at result time.
 *
 * Pure Kotlin so the blend and the time-out are unit-tested.
 */
class SubjectTracker {
    private var anchorBox: NormalizedBox? = null
    private var anchorPose: Pose? = null
    private var kind = VehicleKind.CAR
    private var label = ""
    private var confidence = 0f
    private var imageAspect = 1f
    private var lastDetectionMs = 0L

    val hasSubject: Boolean get() = anchorBox != null

    fun clear() {
        anchorBox = null
        anchorPose = null
        lastDetectionMs = 0L
    }

    /**
     * @param box the detection in screen-normalized coordinates.
     * @param capturePose the pose at the moment the analysed frame was captured, or null when no
     *   sensors are available.
     * @param fovX field of view along the screen's width, degrees.
     * @param fovY field of view along the screen's height, degrees.
     */
    fun onDetection(
        box: NormalizedBox,
        detectedKind: VehicleKind,
        detectedLabel: String,
        detectedConfidence: Float,
        detectedImageAspect: Float,
        capturePose: Pose?,
        fovX: Float,
        fovY: Float,
        nowMs: Long
    ) {
        val previousBox = anchorBox
        val previousPose = anchorPose
        val blended = if (previousBox != null && kind == detectedKind) {
            // Move the old anchor to where it should be at this capture, then blend out jitter.
            val carried = if (previousPose != null && capturePose != null) {
                PoseMath.predictBox(previousBox, previousPose, capturePose, fovX, fovY)
            } else previousBox
            carried.lerp(box, BLEND)
        } else box
        anchorBox = blended
        anchorPose = capturePose
        kind = detectedKind
        label = detectedLabel
        confidence = detectedConfidence
        imageAspect = detectedImageAspect
        lastDetectionMs = nowMs
    }

    /** Subject predicted for [currentPose], or null once the detector has been silent too long. */
    fun current(currentPose: Pose?, fovX: Float, fovY: Float, nowMs: Long): DetectedSubject? {
        val box = anchorBox ?: return null
        if (nowMs - lastDetectionMs > LOST_AFTER_MS) {
            clear()
            return null
        }
        val pose = anchorPose
        val predicted = if (pose != null && currentPose != null) {
            PoseMath.predictBox(box, pose, currentPose, fovX, fovY)
        } else box
        return DetectedSubject(predicted, kind, label, confidence, imageAspect)
    }

    /** Milliseconds since the last detector anchor; how stale the box would be without prediction. */
    fun anchorAgeMs(nowMs: Long): Long = if (anchorBox == null) 0L else nowMs - lastDetectionMs

    companion object {
        const val BLEND = 0.6f
        const val LOST_AFTER_MS = 900L
    }
}
