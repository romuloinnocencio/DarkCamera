package org.darkcamera.app

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.max

/** Live microphone meter for standby (MediaRecorder owns the mic while recording). */
class AudioMonitor(private val onLevel: (Float, Float) -> Unit) {
    @Volatile private var running = false
    private var thread: Thread? = null

    @SuppressLint("MissingPermission")
    fun start() {
        if (running) return
        running = true
        thread = Thread {
            val rate = 48000
            var stereo = true
            var rec: AudioRecord? = null
            for (ch in intArrayOf(AudioFormat.CHANNEL_IN_STEREO, AudioFormat.CHANNEL_IN_MONO)) {
                try {
                    val min = AudioRecord.getMinBufferSize(rate, ch, AudioFormat.ENCODING_PCM_16BIT)
                    if (min <= 0) continue
                    val r = AudioRecord(MediaRecorder.AudioSource.CAMCORDER, rate, ch, AudioFormat.ENCODING_PCM_16BIT, max(min, 4800 * 4))
                    if (r.state == AudioRecord.STATE_INITIALIZED) {
                        rec = r
                        stereo = ch == AudioFormat.CHANNEL_IN_STEREO
                        break
                    }
                    r.release()
                } catch (_: Exception) {
                }
            }
            val r = rec ?: run { running = false; return@Thread }
            val buf = ShortArray(4800)
            try {
                r.startRecording()
                while (running) {
                    val n = r.read(buf, 0, buf.size)
                    if (n <= 0) continue
                    var pl = 0
                    var pr = 0
                    var i = 0
                    while (i < n) {
                        val a = abs(buf[i].toInt())
                        if (stereo && i + 1 < n) {
                            val b = abs(buf[i + 1].toInt())
                            if (a > pl) pl = a
                            if (b > pr) pr = b
                            i += 2
                        } else {
                            if (a > pl) pl = a
                            pr = pl
                            i++
                        }
                    }
                    onLevel(norm(pl), norm(pr))
                }
            } catch (_: Exception) {
            } finally {
                try { r.stop() } catch (_: Exception) {}
                r.release()
            }
        }.apply { name = "dc-audio"; start() }
    }

    fun stop() {
        running = false
        thread?.join(300)
        thread = null
    }

    companion object {
        /** 16-bit peak to a 0..1 meter position covering -60..0 dBFS. */
        fun norm(peak: Int): Float {
            if (peak <= 0) return 0f
            val db = 20 * log10(peak / 32768.0)
            return ((db + 60) / 60).toFloat().coerceIn(0f, 1f)
        }
    }
}
