package be.roadframe.coach

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import androidx.core.graphics.ColorUtils
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

class CoachOverlayView(context: Context) : View(context) {
    var onManualSelection: ((NormalizedBox?) -> Unit)? = null
    var onFocusTap: ((Float, Float) -> Unit)? = null

    private var subject: DetectedSubject? = null
    private var advice: CoachAdvice? = null
    private var mode: ShotMode = ShotMode.BALANCED
    private var inferenceMillis: Long = 0
    private var detailMode = false
    private var selectionStartX = 0f
    private var selectionStartY = 0f
    private var manualRect: RectF? = null
    private var focusX = -1f
    private var focusY = -1f
    private var focusAlpha = 0f
    private var flashAlpha = 0f

    private val lime = Color.rgb(200, 255, 54)
    private val amber = Color.rgb(255, 176, 32)
    private val white = Color.rgb(247, 249, 250)
    private val boxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(2.2f)
    }
    private val targetPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1.5f)
        color = ColorUtils.setAlphaComponent(white, 155)
    }
    private val cardPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(226, 7, 9, 11)
        style = Paint.Style.FILL
    }
    private val titlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = white
        textSize = sp(20f)
        typeface = Typeface.create("sans", Typeface.BOLD)
    }
    private val bodyPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(205, 211, 215)
        textSize = sp(13.5f)
        typeface = Typeface.create("sans", Typeface.NORMAL)
    }
    private val metaPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(195, 201, 205)
        textSize = sp(11f)
        typeface = Typeface.create("sans", Typeface.BOLD)
        letterSpacing = 0.08f
    }
    private val arrowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = lime
        style = Paint.Style.STROKE
        strokeWidth = dp(4f)
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    fun update(
        subject: DetectedSubject?,
        advice: CoachAdvice,
        mode: ShotMode,
        inferenceMillis: Long
    ) {
        this.subject = subject
        this.advice = advice
        this.mode = mode
        this.inferenceMillis = inferenceMillis
        invalidate()
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
        val currentAdvice = advice

        currentAdvice?.targetBox?.let { drawGuide(canvas, it) }
        subject?.let { drawSubject(canvas, it, currentAdvice?.ready == true) }
        manualRect?.let {
            boxPaint.color = if (currentAdvice?.ready == true) lime else amber
            boxPaint.pathEffect = null
            canvas.drawRoundRect(it, dp(10f), dp(10f), boxPaint)
        }
        currentAdvice?.let { drawArrow(canvas, it) }
        drawLevelLine(canvas)
        currentAdvice?.let { drawAdviceCard(canvas, it) }
        drawFocus(canvas)

        if (flashAlpha > 0f) {
            canvas.drawColor(Color.argb((flashAlpha * 255).toInt(), 255, 255, 255))
        }
    }

    private fun drawGuide(canvas: Canvas, box: NormalizedBox) {
        val rect = box.toPixels()
        val corner = min(rect.width(), rect.height()) * 0.16f
        targetPaint.color = ColorUtils.setAlphaComponent(if (advice?.ready == true) lime else white, 165)
        val path = Path().apply {
            moveTo(rect.left, rect.top + corner); lineTo(rect.left, rect.top); lineTo(rect.left + corner, rect.top)
            moveTo(rect.right - corner, rect.top); lineTo(rect.right, rect.top); lineTo(rect.right, rect.top + corner)
            moveTo(rect.right, rect.bottom - corner); lineTo(rect.right, rect.bottom); lineTo(rect.right - corner, rect.bottom)
            moveTo(rect.left + corner, rect.bottom); lineTo(rect.left, rect.bottom); lineTo(rect.left, rect.bottom - corner)
        }
        canvas.drawPath(path, targetPaint)
    }

    private fun drawSubject(canvas: Canvas, detected: DetectedSubject, ready: Boolean) {
        val rect = detected.box.toPixels()
        boxPaint.color = if (ready) lime else amber
        boxPaint.pathEffect = null
        canvas.drawRoundRect(rect, dp(11f), dp(11f), boxPaint)

        val label = "${detected.kind.displayName.uppercase()}  ${(detected.confidence * 100).toInt()}%"
        val labelWidth = metaPaint.measureText(label) + dp(16f)
        val labelRect = RectF(rect.left, rect.top - dp(27f), rect.left + labelWidth, rect.top - dp(5f))
        cardPaint.color = Color.argb(215, 7, 9, 11)
        canvas.drawRoundRect(labelRect, dp(7f), dp(7f), cardPaint)
        metaPaint.color = if (ready) lime else white
        canvas.drawText(label, labelRect.left + dp(8f), labelRect.bottom - dp(6f), metaPaint)
    }

    private fun drawLevelLine(canvas: Canvas) {
        val y = height * 0.50f
        targetPaint.color = ColorUtils.setAlphaComponent(white, 45)
        targetPaint.strokeWidth = dp(1f)
        canvas.drawLine(width * 0.43f, y, width * 0.57f, y, targetPaint)
        canvas.drawCircle(width * 0.5f, y, dp(2.5f), targetPaint)
    }

    private fun drawArrow(canvas: Canvas, current: CoachAdvice) {
        if (current.arrowX == 0f && current.arrowY == 0f) return
        val centerX = subject?.box?.centerX?.times(width) ?: width / 2f
        val centerY = subject?.box?.centerY?.times(height) ?: height / 2f
        val length = dp(64f)
        val magnitude = kotlin.math.sqrt(current.arrowX * current.arrowX + current.arrowY * current.arrowY)
            .coerceAtLeast(0.001f)
        val dx = current.arrowX / magnitude * length
        val dy = current.arrowY / magnitude * length
        val endX = centerX + dx
        val endY = centerY + dy
        canvas.drawLine(centerX, centerY, endX, endY, arrowPaint)

        val angle = atan2(dy, dx)
        val head = dp(15f)
        val leftAngle = angle + Math.PI.toFloat() * 0.82f
        val rightAngle = angle - Math.PI.toFloat() * 0.82f
        val path = Path().apply {
            moveTo(endX + cos(leftAngle) * head, endY + sin(leftAngle) * head)
            lineTo(endX, endY)
            lineTo(endX + cos(rightAngle) * head, endY + sin(rightAngle) * head)
        }
        canvas.drawPath(path, arrowPaint)
    }

    private fun drawAdviceCard(canvas: Canvas, current: CoachAdvice) {
        val horizontalMargin = dp(18f)
        val landscape = width > height
        val bottomInset = if (landscape) dp(18f) else dp(158f)
        val cardHeight = if (landscape) dp(112f) else dp(136f)
        val cardRight = if (landscape) {
            (width - dp(380f)).coerceAtLeast(dp(360f))
        } else {
            width - horizontalMargin
        }
        val rect = RectF(horizontalMargin, height - bottomInset - cardHeight, cardRight, height - bottomInset)
        cardPaint.color = Color.argb(226, 7, 9, 11)
        canvas.drawRoundRect(rect, dp(20f), dp(20f), cardPaint)

        val accentPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = if (current.ready) lime else amber
            strokeWidth = dp(3f)
            strokeCap = Paint.Cap.ROUND
        }
        canvas.drawLine(rect.left + dp(16f), rect.top + dp(18f), rect.left + dp(16f), rect.bottom - dp(18f), accentPaint)

        metaPaint.color = if (current.ready) lime else amber
        canvas.drawText(
            "${mode.chipLabel}  •  ${current.score}/100  •  ${inferenceMillis}MS",
            rect.left + dp(30f),
            rect.top + dp(24f),
            metaPaint
        )
        canvas.drawText(current.title, rect.left + dp(30f), rect.top + dp(56f), titlePaint)

        val textWidth = (rect.width() - dp(50f)).toInt().coerceAtLeast(1)
        val layout = StaticLayout.Builder.obtain(
            current.explanation,
            0,
            current.explanation.length,
            bodyPaint,
            textWidth
        )
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setIncludePad(false)
            .setMaxLines(2)
            .build()
        canvas.save()
        canvas.translate(rect.left + dp(30f), rect.top + dp(70f))
        layout.draw(canvas)
        canvas.restore()
    }

    private fun drawFocus(canvas: Canvas) {
        if (focusAlpha <= 0f || focusX < 0f) return
        targetPaint.color = ColorUtils.setAlphaComponent(lime, (focusAlpha * 230).toInt())
        targetPaint.strokeWidth = dp(1.5f)
        canvas.drawCircle(focusX, focusY, dp(24f) + dp(6f) * (1f - focusAlpha), targetPaint)
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
