package be.roadframe.coach

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.text.TextPaint
import android.text.TextUtils
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import androidx.core.graphics.ColorUtils
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * Draws the coach: the car box, the dashed target, one set of arrows for the primary instruction,
 * a level line that rotates with the phone, a ring around the shutter that fills as the errors
 * shrink, and the headline with its one-line reason. Deliberately sparse: one instruction, not a
 * HUD. Nothing here allocates per frame except text measurement.
 */
class CoachOverlayView(context: Context) : View(context) {
    var onManualSelection: ((NormalizedBox?) -> Unit)? = null
    var onFocusTap: ((Float, Float) -> Unit)? = null

    private var subject: DetectedSubject? = null
    private var guidance: Guidance? = null
    private var statsLine: String? = null
    private var detailMode = false
    private var selectionStartX = 0f
    private var selectionStartY = 0f
    private var manualRect: RectF? = null
    private var focusX = -1f
    private var focusY = -1f
    private var focusAlpha = 0f
    private var flashAlpha = 0f
    private var shutterCenterX = -1f
    private var shutterCenterY = -1f
    private var shutterRadius = 0f

    private val lime = Color.rgb(200, 255, 54)
    private val amber = Color.rgb(255, 176, 32)
    private val white = Color.rgb(247, 249, 250)
    private val boxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(2.5f)
        strokeCap = Paint.Cap.ROUND
    }
    private val targetPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(2.5f)
        color = ColorUtils.setAlphaComponent(amber, 210)
        pathEffect = DashPathEffect(floatArrayOf(dp(9f), dp(7f)), 0f)
    }
    private val thinPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1.5f)
        color = white
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = amber
        setShadowLayer(dp(4f), 0f, dp(1f), Color.argb(150, 0, 0, 0))
    }
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(5f)
        strokeCap = Paint.Cap.ROUND
    }
    private val levelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(3f)
        strokeCap = Paint.Cap.ROUND
        setShadowLayer(dp(4f), 0f, 0f, Color.argb(170, 0, 0, 0))
    }
    private val orbitPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(4f)
        strokeCap = Paint.Cap.ROUND
        color = amber
        setShadowLayer(dp(4f), 0f, 0f, Color.argb(170, 0, 0, 0))
    }
    private val cardPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(215, 7, 9, 11)
        style = Paint.Style.FILL
    }
    private val headlinePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = white
        textSize = sp(34f)
        typeface = Typeface.create("sans", Typeface.BOLD)
        textAlign = Paint.Align.CENTER
        letterSpacing = 0.02f
        setShadowLayer(dp(5f), 0f, dp(1f), Color.argb(200, 0, 0, 0))
    }
    private val whyPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(225, 247, 249, 250)
        textSize = sp(14f)
        typeface = Typeface.create("sans", Typeface.NORMAL)
        textAlign = Paint.Align.CENTER
        setShadowLayer(dp(4f), 0f, dp(1f), Color.argb(220, 0, 0, 0))
    }
    private val metaPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(200, 247, 249, 250)
        textSize = sp(11f)
        typeface = Typeface.create("sans", Typeface.BOLD)
        letterSpacing = 0.06f
        textAlign = Paint.Align.CENTER
        setShadowLayer(dp(3f), 0f, dp(1f), Color.argb(220, 0, 0, 0))
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = white
        textSize = sp(11f)
        typeface = Typeface.create("sans", Typeface.BOLD)
        letterSpacing = 0.06f
    }
    private val path = Path()
    private val arc = RectF()

    fun update(subject: DetectedSubject?, guidance: Guidance, statsLine: String?) {
        this.subject = subject
        this.guidance = guidance
        this.statsLine = statsLine
        invalidate()
    }

    fun setShutter(centerX: Float, centerY: Float, radius: Float) {
        shutterCenterX = centerX
        shutterCenterY = centerY
        shutterRadius = radius
    }

    fun setDetailMode(enabled: Boolean) {
        detailMode = enabled
        if (!enabled) {
            manualRect = null
            onManualSelection?.invoke(null)
        }
        invalidate()
    }

    fun clearManualSelection() {
        manualRect = null
        onManualSelection?.invoke(null)
        invalidate()
    }

    fun showCaptureFlash() {
        ValueAnimator.ofFloat(0.65f, 0f).apply {
            duration = 230
            addUpdateListener {
                flashAlpha = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    private fun showFocus(pointX: Float, pointY: Float) {
        focusX = pointX
        focusY = pointY
        ValueAnimator.ofFloat(1f, 0f).apply {
            duration = 900
            addUpdateListener {
                focusAlpha = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val current = guidance
        val landscape = width > height
        val textArea = textArea(landscape)
        val shoot = current?.shoot == true

        val currentSubject = subject
        if (currentSubject != null) {
            current?.arrows?.target?.let { drawTarget(canvas, it) }
            drawSubject(canvas, currentSubject, shoot)
        }
        manualRect?.let {
            boxPaint.color = if (shoot) lime else amber
            canvas.drawRoundRect(it, dp(10f), dp(10f), boxPaint)
        }
        if (current != null) {
            drawArrows(canvas, current.arrows, currentSubject, textArea, landscape)
            drawLevel(canvas, current.arrows.rollDeg, landscape)
            drawShutterRing(canvas, current)
            drawText(canvas, current, textArea)
        }
        drawFocus(canvas)
        if (flashAlpha > 0f) {
            canvas.drawColor(Color.argb((flashAlpha * 255).toInt(), 255, 255, 255))
        }
    }

    /** Where the headline lives: left, right and the baseline of the headline. */
    private fun textArea(landscape: Boolean): RectF = if (landscape) {
        RectF(dp(18f), height - dp(96f), (width - dp(370f)).coerceAtLeast(dp(320f)), height - dp(58f))
    } else {
        RectF(dp(18f), height - dp(236f), width - dp(18f), height - dp(200f))
    }

    private fun drawTarget(canvas: Canvas, box: NormalizedBox) {
        val rect = box.toPixels()
        drawCorners(canvas, rect, targetPaint, min(rect.width(), rect.height()) * 0.18f)
    }

    private fun drawSubject(canvas: Canvas, detected: DetectedSubject, shoot: Boolean) {
        val rect = detected.box.toPixels()
        boxPaint.color = if (shoot) lime else white
        drawCorners(canvas, rect, boxPaint, min(rect.width(), rect.height()) * 0.14f)
        if (detected.label == "detail") return

        val label = "${detected.kind.displayName.uppercase()}  ${(detected.confidence * 100).toInt()}%"
        val labelWidth = labelPaint.measureText(label) + dp(14f)
        val top = (rect.top - dp(24f)).coerceAtLeast(dp(4f))
        val labelRect = RectF(rect.left.coerceAtLeast(0f), top, rect.left.coerceAtLeast(0f) + labelWidth, top + dp(19f))
        canvas.drawRoundRect(labelRect, dp(6f), dp(6f), cardPaint)
        labelPaint.color = if (shoot) lime else white
        canvas.drawText(label, labelRect.left + dp(7f), labelRect.bottom - dp(5f), labelPaint)
    }

    private fun drawCorners(canvas: Canvas, rect: RectF, paint: Paint, cornerLength: Float) {
        val corner = cornerLength.coerceIn(dp(8f), dp(40f))
        path.reset()
        path.moveTo(rect.left, rect.top + corner); path.lineTo(rect.left, rect.top); path.lineTo(rect.left + corner, rect.top)
        path.moveTo(rect.right - corner, rect.top); path.lineTo(rect.right, rect.top); path.lineTo(rect.right, rect.top + corner)
        path.moveTo(rect.right, rect.bottom - corner); path.lineTo(rect.right, rect.bottom); path.lineTo(rect.right - corner, rect.bottom)
        path.moveTo(rect.left + corner, rect.bottom); path.lineTo(rect.left, rect.bottom); path.lineTo(rect.left, rect.bottom - corner)
        canvas.drawPath(path, paint)
    }

    private fun drawArrows(canvas: Canvas, arrows: Arrows, detected: DetectedSubject?, textArea: RectF, landscape: Boolean) {
        val topSafe = dp(if (landscape) 130f else 150f)
        val bottomSafe = textArea.top - dp(40f)
        if (arrows.panX != 0) {
            val x = if (arrows.panX < 0) dp(28f) else width - dp(28f)
            drawChevron(canvas, x, height / 2f, if (arrows.panX < 0) Dir.LEFT else Dir.RIGHT, dp(22f))
        }
        if (arrows.panY != 0) {
            val y = if (arrows.panY < 0) topSafe else bottomSafe
            drawChevron(canvas, width / 2f, y, if (arrows.panY < 0) Dir.UP else Dir.DOWN, dp(22f))
        }
        if (arrows.size != 0 && detected != null) {
            val rect = detected.box.toPixels()
            val margin = dp(24f)
            val inward = arrows.size > 0
            drawChevron(canvas, rect.left - margin, rect.centerY(), if (inward) Dir.RIGHT else Dir.LEFT, dp(13f))
            drawChevron(canvas, rect.right + margin, rect.centerY(), if (inward) Dir.LEFT else Dir.RIGHT, dp(13f))
        }
        arrows.orbitDeg?.let { drawOrbit(canvas, textArea.centerX(), textArea.top - dp(46f), dp(38f), it > 0f) }
        if (arrows.lower) {
            drawChevron(canvas, width - dp(44f), height / 2f + dp(70f), Dir.DOWN, dp(18f))
            drawChevron(canvas, width - dp(44f), height / 2f + dp(96f), Dir.DOWN, dp(18f))
        }
    }

    private enum class Dir { LEFT, RIGHT, UP, DOWN }

    private fun drawChevron(canvas: Canvas, cx: Float, cy: Float, dir: Dir, size: Float) {
        path.reset()
        when (dir) {
            Dir.LEFT -> { path.moveTo(cx + size * 0.6f, cy - size); path.lineTo(cx - size * 0.6f, cy); path.lineTo(cx + size * 0.6f, cy + size) }
            Dir.RIGHT -> { path.moveTo(cx - size * 0.6f, cy - size); path.lineTo(cx + size * 0.6f, cy); path.lineTo(cx - size * 0.6f, cy + size) }
            Dir.UP -> { path.moveTo(cx - size, cy + size * 0.6f); path.lineTo(cx, cy - size * 0.6f); path.lineTo(cx + size, cy + size * 0.6f) }
            Dir.DOWN -> { path.moveTo(cx - size, cy - size * 0.6f); path.lineTo(cx, cy + size * 0.6f); path.lineTo(cx + size, cy - size * 0.6f) }
        }
        path.close()
        canvas.drawPath(path, fillPaint)
    }

    /** A curved arrow over the headline: walk around the car this way. */
    private fun drawOrbit(canvas: Canvas, cx: Float, cy: Float, radius: Float, left: Boolean) {
        arc.set(cx - radius, cy - radius, cx + radius, cy + radius)
        canvas.drawArc(arc, 200f, 140f, false, orbitPaint)
        val endAngle = Math.toRadians(if (left) 200.0 else 340.0)
        val endX = cx + radius * cos(endAngle).toFloat()
        val endY = cy + radius * sin(endAngle).toFloat()
        val head = dp(12f)
        path.reset()
        if (left) {
            path.moveTo(endX - head * 0.2f, endY - head); path.lineTo(endX - head * 0.3f, endY + head * 0.6f); path.lineTo(endX + head, endY + head * 0.1f)
        } else {
            path.moveTo(endX + head * 0.2f, endY - head); path.lineTo(endX + head * 0.3f, endY + head * 0.6f); path.lineTo(endX - head, endY + head * 0.1f)
        }
        path.close()
        canvas.drawPath(path, fillPaint)
    }

    private fun drawLevel(canvas: Canvas, rollDeg: Float?, landscape: Boolean) {
        if (rollDeg == null) return
        val cx = width / 2f
        val cy = height * (if (landscape) 0.5f else 0.46f)
        val length = min(dp(180f), width * 0.42f)
        val gap = dp(22f)
        thinPaint.color = Color.argb(140, 255, 255, 255)
        canvas.drawLine(cx - gap, cy, cx + gap, cy, thinPaint)
        levelPaint.color = if (abs(rollDeg) <= CoachEngine.LEVEL_ENTER) lime else amber
        canvas.save()
        canvas.rotate(-rollDeg, cx, cy)
        canvas.drawLine(cx - length / 2f, cy, cx - gap, cy, levelPaint)
        canvas.drawLine(cx + gap, cy, cx + length / 2f, cy, levelPaint)
        canvas.restore()
    }

    private fun drawShutterRing(canvas: Canvas, current: Guidance) {
        if (shutterCenterX < 0f) return
        val r = shutterRadius + dp(7f)
        arc.set(shutterCenterX - r, shutterCenterY - r, shutterCenterX + r, shutterCenterY + r)
        ringPaint.color = Color.argb(60, 255, 255, 255)
        canvas.drawArc(arc, 0f, 360f, false, ringPaint)
        ringPaint.color = if (current.shoot) lime else amber
        canvas.drawArc(arc, -90f, 360f * current.score / 100f, false, ringPaint)
    }

    private fun drawText(canvas: Canvas, current: Guidance, textArea: RectF) {
        headlinePaint.color = if (current.shoot) lime else white
        val headline = TextUtils.ellipsize(current.headline, headlinePaint, textArea.width(), TextUtils.TruncateAt.END)
        canvas.drawText(headline, 0, headline.length, textArea.centerX(), textArea.bottom, headlinePaint)
        val why = TextUtils.ellipsize(current.why, whyPaint, textArea.width(), TextUtils.TruncateAt.END)
        canvas.drawText(why, 0, why.length, textArea.centerX(), textArea.bottom + dp(21f), whyPaint)
        var line = textArea.bottom + dp(38f)
        if (current.secondary.isNotEmpty()) {
            metaPaint.color = ColorUtils.setAlphaComponent(amber, 230)
            val next = "THEN  " + current.secondary.joinToString("  ·  ") { it.text }
            val text = TextUtils.ellipsize(next, metaPaint, textArea.width(), TextUtils.TruncateAt.END)
            canvas.drawText(text, 0, text.length, textArea.centerX(), line, metaPaint)
            line += dp(16f)
        }
        statsLine?.let {
            metaPaint.color = Color.argb(170, 247, 249, 250)
            val text = TextUtils.ellipsize(it, metaPaint, textArea.width(), TextUtils.TruncateAt.END)
            canvas.drawText(text, 0, text.length, textArea.centerX(), line, metaPaint)
        }
    }

    private fun drawFocus(canvas: Canvas) {
        if (focusAlpha <= 0f || focusX < 0f) return
        thinPaint.color = ColorUtils.setAlphaComponent(lime, (focusAlpha * 230).toInt())
        canvas.drawCircle(focusX, focusY, dp(24f) + dp(6f) * (1f - focusAlpha), thinPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (detailMode) {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    selectionStartX = event.x
                    selectionStartY = event.y
                    manualRect = RectF(event.x, event.y, event.x, event.y)
                    parent?.requestDisallowInterceptTouchEvent(true)
                }
                MotionEvent.ACTION_MOVE -> {
                    manualRect = RectF(
                        minOf(selectionStartX, event.x),
                        minOf(selectionStartY, event.y),
                        maxOf(selectionStartX, event.x),
                        maxOf(selectionStartY, event.y)
                    )
                    invalidate()
                }
                MotionEvent.ACTION_UP -> {
                    val rect = manualRect
                    if (rect != null && rect.width() > dp(44f) && rect.height() > dp(44f)) {
                        val normalized = NormalizedBox(
                            rect.left / width,
                            rect.top / height,
                            rect.right / width,
                            rect.bottom / height
                        ).clamped()
                        onManualSelection?.invoke(normalized)
                        showFocus(rect.centerX(), rect.centerY())
                        onFocusTap?.invoke(rect.centerX(), rect.centerY())
                    } else {
                        manualRect = null
                        onManualSelection?.invoke(null)
                    }
                    parent?.requestDisallowInterceptTouchEvent(false)
                    performClick()
                    invalidate()
                }
                MotionEvent.ACTION_CANCEL -> parent?.requestDisallowInterceptTouchEvent(false)
            }
            return true
        }

        if (event.actionMasked == MotionEvent.ACTION_UP) {
            showFocus(event.x, event.y)
            onFocusTap?.invoke(event.x, event.y)
            performClick()
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    private fun NormalizedBox.toPixels(): RectF = RectF(
        left * width,
        top * height,
        right * width,
        bottom * height
    )

    private fun dp(value: Float): Float = value * resources.displayMetrics.density
    private fun sp(value: Float): Float = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_SP,
        value,
        resources.displayMetrics
    )
}
