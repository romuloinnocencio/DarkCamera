package org.darkcamera.app

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

object C {
    const val BG = 0xFF0B0D10.toInt()
    const val PANEL = 0xD90C0E12.toInt()
    const val PANEL_SOLID = 0xFF15181D.toInt()
    const val CHIP = 0xFF1D2128.toInt()
    const val LINE = 0xFF2A2F38.toInt()
    const val FG = 0xFFEEF1F5.toInt()
    const val DIM = 0xFF8B93A3.toInt()
    const val ACCENT = 0xFFF3A738.toInt()
    const val REC = 0xFFFF3B30.toInt()
    const val METER = 0xFF43D17A.toInt()
    const val WARN = 0xFFF5D142.toInt()
    const val INK = 0xFF111111.toInt()

    val COND: Typeface = Typeface.create("sans-serif-condensed", Typeface.NORMAL)
    val COND_BOLD: Typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)
    val MONO: Typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
}

/** Keeps its size at a fixed aspect ratio, fitted inside the space its parent offers. */
class AspectFrame(ctx: Context) : FrameLayout(ctx) {
    var aspect = 16f / 9
        set(v) {
            if (field != v) {
                field = v
                requestLayout()
            }
        }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = MeasureSpec.getSize(heightMeasureSpec)
        var cw = w
        var chh = (w / aspect).toInt()
        if (chh > h) {
            chh = h
            cw = (h * aspect).toInt()
        }
        super.onMeasure(MeasureSpec.makeMeasureSpec(cw, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(chh, MeasureSpec.EXACTLY))
    }
}

/** Zebra / peaking bitmap, thirds grid, framing guides and the tap-to-focus reticle. */
class OverlayView(ctx: Context) : View(ctx) {
    var analysis: Bitmap? = null
        set(v) {
            field = v
            invalidate()
        }
    var grid = true
        set(v) { field = v; invalidate() }
    var guide = 0
        set(v) { field = v; invalidate() }
    var rot180 = false
    private var retX = -1f
    private var retY = -1f
    private var retT = 0L
    private val d = resources.displayMetrics.density
    private val pLine = Paint().apply { color = 0x48FFFFFF; strokeWidth = 1.2f * d }
    private val pBmp = Paint(Paint.FILTER_BITMAP_FLAG)
    private val pMatte = Paint().apply { color = 0x8C000000.toInt() }
    private val pGuide = Paint().apply { color = 0x73FFFFFF; style = Paint.Style.STROKE; strokeWidth = 1.2f * d }
    private val pRet = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = C.ACCENT; style = Paint.Style.STROKE; strokeWidth = 1.6f * d }
    private val dst = RectF()

    fun reticle(x: Float, y: Float) {
        retX = x
        retY = y
        retT = SystemClock.uptimeMillis()
        invalidate()
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        analysis?.let { b ->
            c.save()
            if (rot180) c.rotate(180f, w / 2, h / 2)
            dst.set(0f, 0f, w, h)
            c.drawBitmap(b, null, dst, pBmp)
            c.restore()
        }
        if (grid) {
            c.drawLine(w / 3, 0f, w / 3, h, pLine)
            c.drawLine(2 * w / 3, 0f, 2 * w / 3, h, pLine)
            c.drawLine(0f, h / 3, w, h / 3, pLine)
            c.drawLine(0f, 2 * h / 3, w, 2 * h / 3, pLine)
        }
        if (guide != 0) {
            val r = when (guide) { 1 -> 2.39f; 2 -> 1.85f; else -> 9f / 16 }
            if (r > w / h) {
                val gh = w / r
                val t = (h - gh) / 2
                c.drawRect(0f, 0f, w, t, pMatte)
                c.drawRect(0f, h - t, w, h, pMatte)
                c.drawRect(0f, t, w, h - t, pGuide)
            } else {
                val gw = h * r
                val l = (w - gw) / 2
                c.drawRect(0f, 0f, l, h, pMatte)
                c.drawRect(w - l, 0f, w, h, pMatte)
                c.drawRect(l, 0f, w - l, h, pGuide)
            }
        }
        val age = SystemClock.uptimeMillis() - retT
        if (retX >= 0 && age < 1400) {
            val s = 34 * d * (1f + 0.25f * (1f - min(1f, age / 220f)))
            pRet.alpha = if (age < 1000) 255 else (255 * (1400 - age) / 400f).toInt()
            c.drawRect(retX - s, retY - s, retX + s, retY + s, pRet)
            postInvalidateOnAnimation()
        }
    }
}

class HistogramView(ctx: Context) : View(ctx) {
    private var data = IntArray(64)
    private val path = Path()
    private val pBg = Paint().apply { color = 0x0DFFFFFF }
    private val pGrid = Paint().apply { color = 0x1FFFFFFF; strokeWidth = 1f }
    private val pFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xC7EEF1F5.toInt() }
    private val pHi = Paint().apply { color = C.REC }
    private val pLo = Paint().apply { color = 0xFF4AA3FF.toInt() }

    fun set(h: IntArray) {
        data = h
        invalidate()
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        c.drawRect(0f, 0f, w, h, pBg)
        for (q in 1..3) c.drawLine(w * q / 4, 0f, w * q / 4, h, pGrid)
        var mx = 1
        var total = 0
        for (i in data.indices) {
            total += data[i]
            if (i in 1..62 && data[i] > mx) mx = data[i]
        }
        path.reset()
        path.moveTo(0f, h)
        for (i in 0 until 64) {
            val v = min(1f, sqrt(data[i].toFloat() / mx))
            path.lineTo(i / 63f * w, h - v * (h - 3))
        }
        path.lineTo(w, h)
        path.close()
        c.drawPath(path, pFill)
        if (total > 0 && data[63] > total * 0.004f) c.drawRect(w - 4, 0f, w, h, pHi)
        if (total > 0 && data[0] > total * 0.02f) c.drawRect(0f, 0f, 4f, h, pLo)
    }
}

/** Two-channel audio level meter (values 0..1). */
class MeterView(ctx: Context) : View(ctx) {
    var left = 0f
    var right = 0f
    private val d = resources.displayMetrics.density
    private val pText = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = C.DIM; textSize = 10 * d; typeface = C.MONO }
    private val pOff = Paint().apply { color = 0xFF23272E.toInt() }
    private val pG = Paint().apply { color = C.METER }
    private val pY = Paint().apply { color = C.WARN }
    private val pR = Paint().apply { color = C.REC }

    fun set(l: Float, r: Float) {
        left = l
        right = r
        invalidate()
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val bh = 6 * d
        val x0 = 14 * d
        val bw = w - x0
        val ys = floatArrayOf(h / 2 - bh - 3 * d, h / 2 + 3 * d)
        val vals = floatArrayOf(left, right)
        val labels = arrayOf("L", "R")
        for (k in 0..1) {
            val y = ys[k]
            c.drawText(labels[k], 0f, y + bh, pText)
            c.drawRect(x0, y, x0 + bw, y + bh, pOff)
            val v = vals[k].coerceIn(0f, 1f)
            val g = min(v, 0.68f)
            c.drawRect(x0, y, x0 + bw * g, y + bh, pG)
            if (v > 0.68f) c.drawRect(x0 + bw * 0.68f, y, x0 + bw * min(v, 0.86f), y + bh, pY)
            if (v > 0.86f) c.drawRect(x0 + bw * 0.86f, y, x0 + bw * v, y + bh, pR)
        }
    }
}

/** Vertical focus slider: top = infinity (0), bottom = closest focus (1). */
class VerticalSlider(ctx: Context) : View(ctx) {
    var value = 0.3f
        set(v) {
            field = v.coerceIn(0f, 1f)
            invalidate()
        }
    var active = false
        set(v) { field = v; invalidate() }
    var onChange: ((Float) -> Unit)? = null
    private val d = resources.displayMetrics.density
    private val pTrack = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = C.LINE; strokeWidth = 4 * d; strokeCap = Paint.Cap.ROUND }
    private val pFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = 4 * d; strokeCap = Paint.Cap.ROUND }
    private val pThumb = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pTick = Paint().apply { color = 0x55FFFFFF; strokeWidth = 1 * d }

    private fun pad() = 12 * d

    override fun onDraw(c: Canvas) {
        val cx = width / 2f
        val top = pad()
        val bot = height - pad()
        val y = top + (bot - top) * value
        c.drawLine(cx, top, cx, bot, pTrack)
        val col = if (active) C.ACCENT else C.DIM
        pFill.color = col
        pThumb.color = col
        c.drawLine(cx, top, cx, y, pFill)
        for (i in 0..8) {
            val ty = top + (bot - top) * i / 8f
            c.drawLine(cx + 6 * d, ty, cx + 10 * d, ty, pTick)
        }
        c.drawCircle(cx, y, 8 * d, pThumb)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                val v = (e.y - pad()) / (height - 2 * pad())
                value = v
                onChange?.invoke(value)
                return true
            }
        }
        return true
    }
}

/** Record / shutter button: red for video, white for photo, rounded square while recording. */
class RecButton(ctx: Context) : View(ctx) {
    var photo = false
        set(v) { field = v; invalidate() }
    var recording = false
        set(v) { field = v; invalidate() }
    private val d = resources.displayMetrics.density
    private val pRing = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xD9FFFFFF.toInt(); style = Paint.Style.STROKE; strokeWidth = 3.5f * d }
    private val pIn = Paint(Paint.ANTI_ALIAS_FLAG)
    private val r = RectF()

    override fun onDraw(c: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val rad = min(width, height) / 2f - 2.5f * d
        c.drawCircle(cx, cy, rad, pRing)
        pIn.color = if (photo) 0xFFF4F5F7.toInt() else C.REC
        val inner = if (isPressed) rad * 0.66f else rad * 0.74f
        if (recording) {
            val s = rad * 0.42f
            r.set(cx - s, cy - s, cx + s, cy + s)
            c.drawRoundRect(r, 4 * d, 4 * d, pIn)
        } else c.drawCircle(cx, cy, inner, pIn)
    }

    override fun drawableStateChanged() {
        super.drawableStateChanged()
        invalidate()
    }
}

class GearView(ctx: Context) : View(ctx) {
    private val d = resources.displayMetrics.density
    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = C.FG; style = Paint.Style.STROKE; strokeWidth = 1.8f * d; strokeCap = Paint.Cap.ROUND }
    private val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = C.PANEL }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = C.LINE; style = Paint.Style.STROKE; strokeWidth = 1 * d }

    override fun onDraw(c: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val rad = min(width, height) / 2f - d
        c.drawCircle(cx, cy, rad, bg)
        c.drawCircle(cx, cy, rad, ring)
        val g = rad * 0.32f
        c.drawCircle(cx, cy, g, p)
        for (i in 0 until 8) {
            val a = Math.PI * i / 4
            val r1 = rad * 0.46f
            val r2 = rad * 0.62f
            c.drawLine(cx + (cos(a) * r1).toFloat(), cy + (sin(a) * r1).toFloat(), cx + (cos(a) * r2).toFloat(), cy + (sin(a) * r2).toFloat(), p)
        }
    }
}

class BatteryView(ctx: Context) : View(ctx) {
    var level = 1f
        set(v) { field = v; invalidate() }
    private val d = resources.displayMetrics.density
    private val pS = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = C.FG; style = Paint.Style.STROKE; strokeWidth = 1.5f * d }
    private val pF = Paint(Paint.ANTI_ALIAS_FLAG)
    private val r = RectF()

    override fun onDraw(c: Canvas) {
        val w = width - 4 * d
        val h = height.toFloat()
        r.set(d, d, w, h - d)
        c.drawRoundRect(r, 2 * d, 2 * d, pS)
        pF.color = if (level < 0.15f) C.REC else C.FG
        r.set(3 * d, 3 * d, 3 * d + (w - 5 * d) * level.coerceIn(0f, 1f), h - 3 * d)
        c.drawRect(r, pF)
        pF.color = C.FG
        r.set(w + d, h * 0.3f, w + 3.5f * d, h * 0.7f)
        c.drawRect(r, pF)
    }
}
