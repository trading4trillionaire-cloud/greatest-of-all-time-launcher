package com.nihalthakral.nihalhome.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import androidx.core.content.ContextCompat
import com.nihalthakral.nihalhome.R
import kotlin.math.cos
import kotlin.math.sin

/**
 * Lightweight circular progress ring for the charging screen.
 *
 * - Draws a static track circle + a progress arc for the battery percentage.
 * - A small glow dot orbits slowly along the border for a subtle "alive" feel.
 * - The orbit animation is a single infinite ValueAnimator that is started only
 *   while the view is shown and explicitly paused/stopped otherwise, so it costs
 *   nothing while charging UI isn't visible (no background CPU/battery drain).
 */
class ChargingRingView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = ContextCompat.getColor(context, R.color.charging_ring_track)
    }

    private val progressPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        color = ContextCompat.getColor(context, R.color.charging_ring_progress)
    }

    private val glowDotPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    private val arcRect = RectF()

    private val glowColor = ContextCompat.getColor(context, R.color.charging_ring_glow)

    private var displayedSweepAngle = 0f
    private var targetPercent = 0

    private var orbitAngleDeg = -90f

    private var strokeWidthPx = 0f
    private var dotRadiusPx = 0f

    private var sweepAnimator: ValueAnimator? = null
    private var orbitAnimator: ValueAnimator? = null

    init {
        val density = resources.displayMetrics.density
        strokeWidthPx = 6f * density
        dotRadiusPx = 4.5f * density
        trackPaint.strokeWidth = strokeWidthPx
        progressPaint.strokeWidth = strokeWidthPx
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val inset = strokeWidthPx / 2f + dotRadiusPx
        arcRect.set(inset, inset, w - inset, h - inset)
    }

    /** Sets the battery percentage. Animates the arc smoothly from its current value. */
    fun setPercent(percent: Int, animate: Boolean = true) {
        val clamped = percent.coerceIn(0, 100)
        if (clamped == targetPercent) return
        targetPercent = clamped

        val targetSweep = 360f * (clamped / 100f)

        sweepAnimator?.cancel()
        if (!animate) {
            displayedSweepAngle = targetSweep
            invalidate()
            return
        }

        sweepAnimator = ValueAnimator.ofFloat(displayedSweepAngle, targetSweep).apply {
            duration = 500L
            addUpdateListener {
                displayedSweepAngle = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    /** Starts the lightweight border glow animation. No-op if already running. */
    fun startGlowAnimation() {
        if (orbitAnimator != null) {
            if (orbitAnimator?.isPaused == true) orbitAnimator?.resume()
            return
        }
        orbitAnimator = ValueAnimator.ofFloat(-90f, 270f).apply {
            duration = 3200L
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener {
                orbitAngleDeg = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    /** Pauses the glow animation (view stays visible, just stops spinning). */
    fun pauseGlowAnimation() {
        orbitAnimator?.pause()
    }

    /** Fully stops and releases the glow animation. */
    fun stopGlowAnimation() {
        orbitAnimator?.cancel()
        orbitAnimator = null
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (arcRect.width() <= 0f || arcRect.height() <= 0f) return

        // Track
        canvas.drawOval(arcRect, trackPaint)

        // Progress arc
        canvas.drawArc(arcRect, -90f, displayedSweepAngle, false, progressPaint)

        // Subtle glow dot orbiting the border
        val radius = arcRect.width() / 2f
        val cx = arcRect.centerX()
        val cy = arcRect.centerY()
        val angleRad = Math.toRadians(orbitAngleDeg.toDouble())
        val dotX = (cx + radius * cos(angleRad)).toFloat()
        val dotY = (cy + radius * sin(angleRad)).toFloat()

        glowDotPaint.shader = RadialGradient(
            dotX, dotY, dotRadiusPx * 2.2f,
            glowColor, glowColor and 0x00FFFFFF, Shader.TileMode.CLAMP
        )
        canvas.drawCircle(dotX, dotY, dotRadiusPx * 2.2f, glowDotPaint)

        glowDotPaint.shader = null
        glowDotPaint.color = glowColor
        canvas.drawCircle(dotX, dotY, dotRadiusPx, glowDotPaint)
    }

    override fun onDetachedFromWindow() {
        stopGlowAnimation()
        sweepAnimator?.cancel()
        sweepAnimator = null
        super.onDetachedFromWindow()
    }
}
