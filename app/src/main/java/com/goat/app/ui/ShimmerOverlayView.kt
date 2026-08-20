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
import kotlin.math.ceil

/**
 * Draws a skeleton/placeholder grid, shaped and sized to exactly match the real
 * Recommended/Recent app rows (same column count, same icon size, same label-bar
 * position), with an animated shimmer sweep. Used to cover ONLY the Recommended +
 * Recent sections of the drawer while they're being rebuilt on drawer open -- "All
 * Apps" below is left untouched and scrollable/tappable as normal.
 *
 * This view sizes itself (via [configure]) rather than stretching to match_parent,
 * so it never covers more (or less) than the two sections it's meant to hide, and
 * never shows a mismatched column/row count.
 */
class ShimmerOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val density = resources.displayMetrics.density

    // Mirrors item_app.xml spacing: paddingTop 12dp, icon, marginTop 4dp, label, paddingBottom 8dp.
    private val itemPaddingTop = 12f * density
    private val itemPaddingBottom = 8f * density
    private val labelMarginTop = 4f * density
    private val labelBarHeight = 10f * density
    private val labelBarWidthFraction = 0.6f
    private val cellCornerRadius = 14f * density

    // Mirrors item_drawer_header.xml spacing: marginTop 14dp, text ~13sp, marginBottom 6dp.
    private val headerMarginTop = 14f * density
    private val headerBarHeight = 13f * density
    private val headerBarWidth = 120f * density
    private val headerMarginBottom = 6f * density
    private val headerBarCornerRadius = headerBarHeight / 2f

    private val sectionGap = 4f * density

    private val baseColor = 0x14FFFFFF
    private val highlightColor = 0x33FFFFFF

    private val cellPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = baseColor }
    private val shimmerPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()

    private var shimmerAnimator: ValueAnimator? = null
    private var shimmerTranslate = 0f

    private var columns = 4
    private var iconSizePx = (48f * density).toInt()
    private var recommendedCount = 0
    private var recentCount = 0

    init {
        // Consume all touches so nothing underneath (the RecyclerView) can be tapped
        // while this overlay is showing.
        isClickable = true
        isFocusable = true
    }

    private fun rowHeight(): Float = itemPaddingTop + iconSizePx + labelMarginTop + labelBarHeight + itemPaddingBottom

    private fun sectionHeight(appCount: Int): Float {
        if (appCount <= 0) return 0f
        val rows = ceil(appCount / columns.toFloat()).toInt()
        return headerMarginTop + headerBarHeight + headerMarginBottom + rows * rowHeight()
    }

    /**
     * Sizes and reshapes the placeholder to match the real grid: [columns] must equal
     * the RecyclerView's actual span count, [iconSizePx] the real icon size, and
     * [recommendedCount]/[recentCount] the number of app rows currently shown in each
     * section (read from the list as it stood right before the refresh), so the
     * placeholder's row count matches what's about to be redrawn underneath it.
     * Returns the total height (px) this view needs -- caller should apply it to the
     * view's layout params before showing.
     */
    fun configure(
        columns: Int,
        iconSizePx: Int,
        recommendedCount: Int,
        recentCount: Int
    ): Int {
        this.columns = columns.coerceAtLeast(1)
        this.iconSizePx = iconSizePx
        this.recommendedCount = recommendedCount
        this.recentCount = recentCount

        val recommendedHeight = sectionHeight(recommendedCount)
        val recentHeight = sectionHeight(recentCount)
        val gap = if (recommendedHeight > 0f && recentHeight > 0f) sectionGap else 0f
        return (recommendedHeight + gap + recentHeight).toInt()
    }

    /** True if there's nothing to placeholder (no recommended/recent apps yet). */
    fun isEmpty(): Boolean = recommendedCount <= 0 && recentCount <= 0

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

        var y = 0f
        if (recommendedCount > 0) {
            y = drawSection(canvas, y, recommendedCount)
        }
        if (recentCount > 0) {
            if (recommendedCount > 0) y += sectionGap
            drawSection(canvas, y, recentCount)
        }
    }

    private fun drawSection(canvas: Canvas, top: Float, appCount: Int): Float {
        var y = top + headerMarginTop
        rect.set(0f, y, headerBarWidth, y + headerBarHeight)
        canvas.drawRoundRect(rect, headerBarCornerRadius, headerBarCornerRadius, cellPaint)
        canvas.drawRoundRect(rect, headerBarCornerRadius, headerBarCornerRadius, shimmerPaint)
        y += headerBarHeight + headerMarginBottom

        val columnWidth = width.toFloat() / columns
        val rows = ceil(appCount / columns.toFloat()).toInt()
        var remaining = appCount

        for (row in 0 until rows) {
            val rowTop = y + itemPaddingTop
            val colsInRow = minOf(columns, remaining)
            for (col in 0 until colsInRow) {
                val cellCenterX = columnWidth * col + columnWidth / 2f

                val iconLeft = cellCenterX - iconSizePx / 2f
                rect.set(iconLeft, rowTop, iconLeft + iconSizePx, rowTop + iconSizePx)
                canvas.drawRoundRect(rect, cellCornerRadius, cellCornerRadius, cellPaint)
                canvas.drawRoundRect(rect, cellCornerRadius, cellCornerRadius, shimmerPaint)

                val labelWidth = iconSizePx * labelBarWidthFraction
                val labelTop = rowTop + iconSizePx + labelMarginTop
                val labelLeft = cellCenterX - labelWidth / 2f
                rect.set(labelLeft, labelTop, labelLeft + labelWidth, labelTop + labelBarHeight)
                canvas.drawRoundRect(rect, labelBarHeight / 2f, labelBarHeight / 2f, cellPaint)
                canvas.drawRoundRect(rect, labelBarHeight / 2f, labelBarHeight / 2f, shimmerPaint)
            }
            remaining -= colsInRow
            y = rowTop + iconSizePx + labelMarginTop + labelBarHeight + itemPaddingBottom
        }
        return y
    }
}
