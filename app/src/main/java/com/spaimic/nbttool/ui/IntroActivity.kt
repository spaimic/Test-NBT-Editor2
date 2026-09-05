package com.spaimic.nbttool.ui

import android.app.Activity
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Movie
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import com.spaimic.nbttool.R
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/**
 * One-time Build 200 entrance animation. The animation is intentionally self-contained
 * so it does not depend on the map/world loading pipeline underneath it.
 */
class IntroActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setStatusBarColor(Color.WHITE)
        window.setNavigationBarColor(Color.WHITE)
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        setContentView(Build200IntroView(this) { finish() })
    }
}

private class Build200IntroView(
    activity: Activity,
    private val onFinished: () -> Unit
) : View(activity) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG)
    private val movie: Movie = activity.resources.openRawResource(R.raw.build200_impact).use { Movie.decodeStream(it) }
    private val startedAt = SystemClock.uptimeMillis()
    private var finished = false
    private val density = resources.displayMetrics.density

    private val convergeMs = 1000L
    // The supplied GIF is exactly 3 × 400 ms = 1.20 seconds. Keep this fixed so it
    // cannot accidentally continue into the following logo animation.
    private val gifMs = 1200L
    private val logoMs = 1800L
    private val fadeMs = 500L
    private val totalMs = convergeMs + gifMs + logoMs + fadeMs

    init {
        setBackgroundColor(Color.WHITE)
        isFocusable = true
        postInvalidateOnAnimation()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val elapsed = SystemClock.uptimeMillis() - startedAt
        canvas.drawColor(Color.WHITE)

        when {
            elapsed < convergeMs -> drawConvergingS(canvas, elapsed.toFloat() / convergeMs)
            elapsed < convergeMs + gifMs -> drawImpactGif(canvas, elapsed - convergeMs)
            elapsed < convergeMs + gifMs + logoMs -> drawLogoSequence(canvas, elapsed - convergeMs - gifMs)
            elapsed < totalMs -> {
                drawLogoSequence(canvas, logoMs.toFloat())
                val fade = ((elapsed - convergeMs - gifMs - logoMs).toFloat() / fadeMs).coerceIn(0f, 1f)
                alpha = 1f - fade
            }
            else -> {
                if (!finished) {
                    finished = true
                    alpha = 0f
                    IntroActivityState.markSeen(context)
                    onFinished()
                }
                return
            }
        }

        if (elapsed < totalMs) postInvalidateOnAnimation()
    }

    private fun drawConvergingS(canvas: Canvas, progress: Float) {
        val p = easeInOut(progress)
        val cx = width / 2f
        val cy = height / 2f
        val insetX = max(64f * density, width * 0.08f)
        val insetY = max(80f * density, height * 0.10f)
        val starts = arrayOf(
            floatArrayOf(insetX, insetY),
            floatArrayOf(width - insetX, insetY),
            floatArrayOf(insetX, height - insetY),
            floatArrayOf(width - insetX, height - insetY)
        )
        val size = 76f * density
        paint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        paint.textSize = size
        paint.color = Color.BLACK
        paint.textAlign = Paint.Align.CENTER
        val rotation = progress * 900f
        for (start in starts) {
            val x = start[0] + (cx - start[0]) * p
            val y = start[1] + (cy - start[1]) * p
            canvas.save()
            canvas.rotate(rotation, x, y)
            canvas.drawText("S", x, y - (paint.ascent() + paint.descent()) / 2f, paint)
            canvas.restore()
        }
    }

    private fun drawImpactGif(canvas: Canvas, gifElapsed: Long) {
        movie.setTime(gifElapsed.coerceAtMost(gifMs - 1).toInt())
        val scale = minOf(width / movie.width.toFloat(), height / movie.height.toFloat(), 3.2f)
        val drawW = movie.width * scale
        val drawH = movie.height * scale
        val left = (width - drawW) / 2f
        val top = (height - drawH) / 2f
        canvas.save()
        canvas.translate(left, top)
        canvas.scale(scale, scale)
        movie.draw(canvas, 0f, 0f, paint)
        canvas.restore()
    }

    private fun drawLogoSequence(canvas: Canvas, t: Float) {
        val cx = width / 2f
        val centerY = height * 0.38f

        // The S flies upward, hits the top edge, then drops and bounces into place.
        val y: Float
        when {
            t < 420f -> {
                val p = easeOutCubic(t / 420f)
                y = centerY + (70f * density - centerY) * p
            }
            t < 900f -> {
                val p = ((t - 420f) / 480f).coerceIn(0f, 1f)
                y = 70f * density + (centerY - 70f * density) * easeInCubic(p)
            }
            else -> {
                val p = ((t - 900f) / 900f).coerceIn(0f, 1f)
                val bounce = sin(p * Math.PI * 4.0).toFloat() * (1f - p) * 32f * density
                y = centerY + bounce
            }
        }

        paint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        paint.textSize = 76f * density
        paint.color = Color.BLACK
        paint.textAlign = Paint.Align.CENTER
        canvas.save()
        canvas.drawText("S", cx, y - (paint.ascent() + paint.descent()) / 2f, paint)
        canvas.restore()

        // Start typing while the S is still bouncing.
        val title = "Spaimic's World Editor"
        val typingStart = 650f
        val typingDuration = 850f
        val typed = if (t <= typingStart) 0 else {
            ((t - typingStart) / typingDuration * title.length).toInt().coerceIn(0, title.length)
        }
        if (typed > 0) {
            paint.textSize = 27f * density
            paint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            paint.color = Color.BLACK
            canvas.drawText(title.substring(0, typed), cx, height * 0.58f, paint)
        }
    }

    private fun easeInOut(x: Float): Float = if (x < 0.5f) 4f * x * x * x else 1f - ((-2f * x + 2f).let { it * it * it } / 2f)
    private fun easeOutCubic(x: Float): Float { val a = 1f - x; return 1f - a * a * a }
    private fun easeInCubic(x: Float): Float = x * x * x
}

/** Small preference wrapper kept separate from the animation view for easy testing. */
object IntroActivityState {
    private const val PREFS = "build200_intro"
    private const val SEEN = "seen"

    fun hasSeen(context: android.content.Context): Boolean =
        context.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE).getBoolean(SEEN, false)

    fun markSeen(context: android.content.Context) {
        context.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE).edit().putBoolean(SEEN, true).apply()
    }
}
