package cn.weibo.carousel

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import androidx.appcompat.widget.AppCompatImageButton

/** Draws download progress as a filled pie slice inside the selection circle. */
internal class CarouselSelectionProgressView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : AppCompatImageButton(context, attrs) {
    private val progressPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val circleBounds = RectF()

    var downloadProgress: Int? = null
        set(value) {
            field = value
            invalidate()
        }

    var progressColor: Int = 0
        set(value) {
            field = value
            invalidate()
        }

    var progressBaseColor: Int = 0
        set(value) {
            field = value
            invalidate()
        }

    override fun onDraw(canvas: Canvas) {
        val value = downloadProgress
        if (value == null) {
            super.onDraw(canvas)
            return
        }
        circleBounds.set(EDGE_INSET_PX, EDGE_INSET_PX, width - EDGE_INSET_PX, height - EDGE_INSET_PX)
        if (circleBounds.width() <= 0f || circleBounds.height() <= 0f) {
            super.onDraw(canvas)
            return
        }

        val percent = value.coerceIn(0, 100)
        if (value < 0 || percent == 0) {
            progressPaint.color = progressBaseColor
            canvas.drawOval(circleBounds, progressPaint)
            super.onDraw(canvas)
            return
        }

        progressPaint.color = progressColor
        canvas.drawOval(circleBounds, progressPaint)
        if (percent < 100) {
            val progressSweep = 360f * percent / 100f
            progressPaint.color = progressBaseColor
            canvas.drawArc(
                circleBounds,
                START_ANGLE + progressSweep,
                360f - progressSweep,
                true,
                progressPaint,
            )
        }
        super.onDraw(canvas)
    }

    private companion object {
        const val START_ANGLE = -90f
        const val EDGE_INSET_PX = 0.5f
    }
}
