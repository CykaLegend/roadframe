package be.roadframe.coach

import android.graphics.Matrix
import android.graphics.RectF
import kotlin.math.max

object CoordinateMapper {
    /** Maps detector coordinates into a PreviewView using FILL_CENTER semantics. */
    fun toViewNormalized(
        detectorBox: RectF,
        imageWidth: Int,
        imageHeight: Int,
        rotationDegrees: Int,
        viewWidth: Int,
        viewHeight: Int
    ): NormalizedBox {
        if (imageWidth <= 0 || imageHeight <= 0 || viewWidth <= 0 || viewHeight <= 0) {
            return NormalizedBox(0f, 0f, 0f, 0f)
        }

        val rect = RectF(detectorBox)
        val matrix = Matrix().apply {
            postTranslate(-imageWidth / 2f, -imageHeight / 2f)
            postRotate(rotationDegrees.toFloat())
            if (rotationDegrees == 90 || rotationDegrees == 270) {
                postTranslate(imageHeight / 2f, imageWidth / 2f)
            } else {
                postTranslate(imageWidth / 2f, imageHeight / 2f)
            }
        }
        matrix.mapRect(rect)

        val rotatedWidth = if (rotationDegrees == 90 || rotationDegrees == 270) imageHeight else imageWidth
        val rotatedHeight = if (rotationDegrees == 90 || rotationDegrees == 270) imageWidth else imageHeight
        val scale = max(viewWidth.toFloat() / rotatedWidth, viewHeight.toFloat() / rotatedHeight)
        val offsetX = (viewWidth - rotatedWidth * scale) / 2f
        val offsetY = (viewHeight - rotatedHeight * scale) / 2f

        return NormalizedBox(
            (rect.left * scale + offsetX) / viewWidth,
            (rect.top * scale + offsetY) / viewHeight,
            (rect.right * scale + offsetX) / viewWidth,
            (rect.bottom * scale + offsetY) / viewHeight
        ).clamped()
    }
}
