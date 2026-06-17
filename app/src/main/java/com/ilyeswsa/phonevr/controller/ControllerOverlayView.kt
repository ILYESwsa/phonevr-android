package com.ilyeswsa.phonevr.controller

import android.content.Context
import android.graphics.*
import android.view.MotionEvent
import android.view.View

/**
 * Transparent overlay drawn on top of the GL surface.
 * Shows the left/right controller zones + button hints.
 * Fades out after 3 seconds of no touch, reappears on touch.
 */
class ControllerOverlayView(context: Context) : View(context) {

    private var overlayAlpha = 0f
    private var fadeJob: kotlinx.coroutines.Job? = null
    private val scope = kotlinx.coroutines.MainScope()

    // Current touch feedback circles
    private val touchPoints = HashMap<Int, PointF>()

    // Paints
    private val zonePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f
        color = Color.argb(80, 255, 255, 255)
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(180, 255, 255, 255)
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    private val touchCirclePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.argb(80, 255, 255, 255)
    }
    private val dividerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f
        color = Color.argb(60, 255, 255, 255)
        pathEffect = DashPathEffect(floatArrayOf(12f, 8f), 0f)
    }

    // Button hint positions (relative to each half)
    data class ButtonHint(val label: String, val xFrac: Float, val yFrac: Float)

    private val leftHints = listOf(
        ButtonHint("TRIGGER\ntap", 0.5f, 0.5f),
        ButtonHint("GRIP\ndrag", 0.5f, 0.72f),
        ButtonHint("X\n2-finger tap", 0.25f, 0.3f),
        ButtonHint("Y\n2-finger hold", 0.75f, 0.3f),
        ButtonHint("MOVE\nswipe", 0.5f, 0.85f),
    )
    private val rightHints = listOf(
        ButtonHint("TRIGGER\ntap", 0.5f, 0.5f),
        ButtonHint("GRIP\ndrag", 0.5f, 0.72f),
        ButtonHint("A\n2-finger tap", 0.75f, 0.3f),
        ButtonHint("B\n2-finger hold", 0.25f, 0.3f),
        ButtonHint("MOVE\nswipe", 0.5f, 0.85f),
    )

    override fun onDraw(canvas: Canvas) {
        if (overlayAlpha <= 0f) return

        val w = width.toFloat()
        val h = height.toFloat()
        val mid = w / 2f
        val a = (overlayAlpha * 255).toInt()

        // Left zone background
        zonePaint.color = Color.argb((a * 0.15f).toInt(), 91, 155, 213)
        canvas.drawRect(0f, 0f, mid, h, zonePaint)

        // Right zone background
        zonePaint.color = Color.argb((a * 0.15f).toInt(), 213, 91, 91)
        canvas.drawRect(mid, 0f, w, h, zonePaint)

        // Center divider
        canvas.drawLine(mid, 0f, mid, h, dividerPaint)

        // Left border
        borderPaint.alpha = a
        canvas.drawRect(4f, 4f, mid - 4f, h - 4f, borderPaint)
        // Right border
        canvas.drawRect(mid + 4f, 4f, w - 4f, h - 4f, borderPaint)

        // Zone labels
        textPaint.textSize = 28f
        textPaint.alpha = (a * 0.9f).toInt()
        canvas.drawText("LEFT", mid * 0.5f, 48f, textPaint)
        canvas.drawText("RIGHT", mid + mid * 0.5f, 48f, textPaint)

        // Button hints
        textPaint.textSize = 18f
        textPaint.alpha = (a * 0.7f).toInt()

        leftHints.forEach { hint ->
            val x = hint.xFrac * mid
            val y = hint.yFrac * h
            drawMultilineText(canvas, hint.label, x, y)
        }
        rightHints.forEach { hint ->
            val x = mid + hint.xFrac * mid
            val y = hint.yFrac * h
            drawMultilineText(canvas, hint.label, x, y)
        }

        // Touch feedback circles
        touchCirclePaint.alpha = (a * 0.6f).toInt()
        touchPoints.values.forEach { pt ->
            canvas.drawCircle(pt.x, pt.y, 40f, touchCirclePaint)
            canvas.drawCircle(pt.x, pt.y, 40f, borderPaint)
        }
    }

    private fun drawMultilineText(canvas: Canvas, text: String, x: Float, y: Float) {
        val lines = text.split("\n")
        val lineHeight = textPaint.textSize * 1.4f
        val totalH = lineHeight * lines.size
        lines.forEachIndexed { i, line ->
            canvas.drawText(line, x, y - totalH / 2f + i * lineHeight + lineHeight, textPaint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val idx = event.actionIndex
                touchPoints[event.getPointerId(idx)] = PointF(event.getX(idx), event.getY(idx))
                showOverlay()
            }
            MotionEvent.ACTION_MOVE -> {
                for (i in 0 until event.pointerCount) {
                    val pid = event.getPointerId(i)
                    touchPoints[pid]?.set(event.getX(i), event.getY(i))
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                touchPoints.remove(event.getPointerId(event.actionIndex))
                if (touchPoints.isEmpty()) scheduleFade()
            }
        }
        invalidate()
        return false // pass through to controller sender
    }

    private fun showOverlay() {
        fadeJob?.cancel()
        overlayAlpha = 1f
        invalidate()
    }

    private fun scheduleFade() {
        fadeJob?.cancel()
        fadeJob = scope.launch {
            kotlinx.coroutines.delay(3000)
            // Fade out over 500ms
            val steps = 20
            repeat(steps) {
                overlayAlpha = 1f - (it + 1f) / steps
                withContext(kotlinx.coroutines.Dispatchers.Main) { invalidate() }
                kotlinx.coroutines.delay(25)
            }
            overlayAlpha = 0f
        }
    }

    fun showTemporarily() = showOverlay().also { scheduleFade() }
}
