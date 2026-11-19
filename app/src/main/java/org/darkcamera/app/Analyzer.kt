package org.darkcamera.app

import android.graphics.Bitmap
import android.media.Image
import android.os.Handler
import android.os.HandlerThread
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs

/**
 * Reads the luma plane of a small YUV stream and produces:
 * a 64-bin histogram, the mean brightness, and an ARGB overlay with zebra stripes and focus peaking.
 */
class Analyzer(private val onResult: (Bitmap?, IntArray, Float) -> Unit) {
    @Volatile var zebra = true
    @Volatile var zebraLevel = 242
    @Volatile var peak = false
    @Volatile var peakColor = 0xFFF3A738.toInt()

    private val thread = HandlerThread("dc-analysis").apply { start() }
    private val h = Handler(thread.looper)
    private val busy = AtomicBoolean(false)
    private var luma = ByteArray(0)
    private var w = 0
    private var hh = 0
    private var pixels = IntArray(0)
    private val bitmaps = arrayOfNulls<Bitmap>(3)
    private var bi = 0
    private var frame = 0

    /** Called on the camera thread. Copies a half-resolution luma plane and closes the image right away. */
    fun submit(img: Image) {
        if (!busy.compareAndSet(false, true)) {
            img.close()
            return
        }
        try {
            val p = img.planes[0]
            val buf = p.buffer
            val rs = p.rowStride
            val ps = p.pixelStride
            val ow = img.width / 2
            val oh = img.height / 2
            if (luma.size != ow * oh) luma = ByteArray(ow * oh)
            w = ow
            hh = oh
            var k = 0
            for (y in 0 until oh) {
                val row = y * 2 * rs
                for (x in 0 until ow) luma[k++] = buf.get(row + x * 2 * ps)
            }
        } catch (e: Exception) {
            img.close()
            busy.set(false)
            return
        }
        img.close()
        h.post {
            try {
                compute()
            } finally {
                busy.set(false)
            }
        }
    }

    private fun compute() {
        val ow = w
        val oh = hh
        val n = ow * oh
        if (n == 0) return
        if (pixels.size != n) pixels = IntArray(n)
        val hist = IntArray(64)
        var sum = 0L
        val z = zebra
        val zl = zebraLevel
        val pk = peak
        val pc = peakColor
        frame++
        val ph = frame / 2
        for (y in 0 until oh) {
            val base = y * ow
            for (x in 0 until ow) {
                val i = base + x
                val l = luma[i].toInt() and 0xFF
                hist[l shr 2]++
                sum += l
                var c = 0
                if (pk && x < ow - 1 && y < oh - 1) {
                    val e = abs(l - (luma[i + 1].toInt() and 0xFF)) + abs(l - (luma[i + ow].toInt() and 0xFF))
                    if (e > 46) c = pc
                }
                if (c == 0 && z && l >= zl && (((x + y + ph) shr 2) and 1) == 0) c = 0xC8141414.toInt()
                pixels[i] = c
            }
        }
        val bmp: Bitmap? = if (z || pk) {
            var b = bitmaps[bi]
            if (b == null || b.width != ow || b.height != oh) {
                b = Bitmap.createBitmap(ow, oh, Bitmap.Config.ARGB_8888)
                bitmaps[bi] = b
            }
            b!!.setPixels(pixels, 0, ow, 0, 0, ow, oh)
            bi = (bi + 1) % bitmaps.size
            b
        } else null
        onResult(bmp, hist, sum.toFloat() / n)
    }

    fun release() {
        thread.quitSafely()
    }
}
