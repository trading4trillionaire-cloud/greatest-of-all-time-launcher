package com.goat.app.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator

/**
 * Draws a skeleton/placeholder grid (a header bar + a grid of icon-shaped cells) with an
 * animated diagonal shimmer sweep, used to cover the app drawer's Recommended/Recent/All
 * sections while they're being rebuilt on drawer open. This intentionally blocks touches
 * (it sits on top of the RecyclerView) so the user can't accidentally tap an app icon a
 * split second before the list reorders underneath it.
 */
class ShimmerOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val density = resources.displayMetrics.density
    private val cellSize = 56f * density
    private val cellSpacing = 14f * density
    private val cellCornerRadius = 16f * density
    private val headerHeight = 16f * density
    private val headerWidth = 140f * density
    private val sectionGap = 20f * density

    private val baseColor = 0x14FFFFFF
    private val highlightColor = 0x33FFFFFF

    private val cellPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = baseColor }
    private val shimmerPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()

    private var shimmerAnimator: ValueAnimator? = null
    private var shimmerTranslate = 0f

    init {
        // Consume all touches so nothing underneath (the RecyclerView) can be tapped
        // while this overlay is showing.
        isClickable = true
        isFocusable = true
    }

    fun startShimmer() {
        visibility = VISIBLE
        if (shimmerAnimator?.isRunning == true) return

        shimmerAnimator = ValueAnimator.ofFloat(-1f, 2f).apply {
            duration = 1100L
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener {
                shimmerTranslate = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    fun stopShimmer() {
        shimmerAnimator?.cancel()
        shimmerAnimator = null
        visibility = GONE
    }

    override fun onDetachedFromWindow() {
        shimmerAnimator?.cancel()
        shimmerAnimator = null
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width == 0 || height == 0) return

        val sweepWidth = width * 0.6f
        val startX = width * shimmerTranslate - sweepWidth / 2f
        shimmerPaint.shader = LinearGradient(
            startX, 0f, startX + sweepWidth, 0f,
            intArrayOf(baseColor, highlightColor, baseColor),
            floatArrayOf(0f, 0.5f, 1f),
            Shader.TileMode.CLAMP
        )

        val columns = ((width + cellSpacing) / (cellSize + cellSpacing)).toInt().coerceAtLeast(1)
        var y = headerHeight * 1.6f

        // Recommended-section placeholder: header bar + one row of icon cells.
        y = drawHeaderBar(canvas, y)
        y = drawIconRow(canvas, y, columns)
        y += sectionGap

        // Recent-section placeholder: header bar + one row of icon cells.
        y = drawHeaderBar(canvas, y)
        drawIconRow(canvas, y, columns)
    }

    private fun drawHeaderBar(canvas: Canvas, top: Float): Float {
        rect.set(0f, top, headerWidth, top + headerHeight)
        canvas.drawRoundRect(rect, headerHeight / 2f, headerHeight / 2f, cellPaint)
        canvas.drawRoundRect(rect, headerHeight / 2f, headerHeight / 2f, shimmerPaint)
        return top + headerHeight + cellSpacing
    }

    private fun drawIconRow(canvas: Canvas, top: Float, columns: Int): Float {
        var x = 0f
        for (i in 0 until columns) {
            rect.set(x, top, x + cellSize, top + cellSize)
            canvas.drawRoundRect(rect, cellCornerRadius, cellCornerRadius, cellPaint)
            canvas.drawRoundRect(rect, cellCornerRadius, cellCornerRadius, shimmerPaint)
            x += cellSize + cellSpacing
        }
        return top + cellSize + cellSpacing
    }
}
