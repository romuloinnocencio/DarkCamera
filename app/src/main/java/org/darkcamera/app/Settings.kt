package org.darkcamera.app

import android.content.Context

/** All user-facing camera settings. Copied (immutable snapshot) before being handed to the camera thread. */
data class Settings(
    var mode: Int = MODE_VIDEO,
    // video
    var fps: Int = 30,
    var res4k: Boolean = true,
    var hevc: Boolean = true,
    var bitrate: Int = 100,
    var audio: Boolean = true,
    var log: Boolean = false,
    // exposure
    var aeAuto: Boolean = true,
    var iso: Int = 400,
    var expNs: Long = 16_666_667L,
    // white balance
    var wbAuto: Boolean = true,
    var wbK: Int = 5600,
    var tint: Int = 0,
    // focus
    var afContinuous: Boolean = true,
    var focusNorm: Float = 0.3f, // 0 = infinity, 1 = closest focus
    // lens
    var lens: Int = -1,
    var ois: Boolean = true,
    // monitoring
    var zebra: Boolean = true,
    var zebraLvl: Int = 95,
    var peak: Boolean = false,
    var peakColor: Int = 0,
    var grid: Boolean = true,
    var guide: Int = 0, // 0 none, 1 2.39, 2 1.85, 3 9:16
    // photo
    var fmt: Int = FMT_RAW_JPEG,
    var aspect: Int = 0, // 0 4:3, 1 16:9, 2 1:1
    var timer: Int = 0,
    var bracket: Boolean = false,
    var brStep: Int = 1,
) {
    val photo get() = mode == MODE_PHOTO

    companion object {
        const val MODE_VIDEO = 0
        const val MODE_PHOTO = 1
        const val FMT_RAW_JPEG = 0
        const val FMT_JPEG = 1
        const val FMT_RAW = 2

        private const val P = "dc"

        fun load(ctx: Context): Settings {
            val p = ctx.getSharedPreferences(P, Context.MODE_PRIVATE)
            val d = Settings()
            return Settings(
                mode = p.getInt("mode", d.mode),
                fps = p.getInt("fps", d.fps),
                res4k = p.getBoolean("res4k", d.res4k),
                hevc = p.getBoolean("hevc", d.hevc),
                bitrate = p.getInt("bitrate", d.bitrate),
                audio = p.getBoolean("audio", d.audio),
                log = p.getBoolean("log", d.log),
                aeAuto = p.getBoolean("aeAuto", d.aeAuto),
                iso = p.getInt("iso", d.iso),
                expNs = p.getLong("expNs", d.expNs),
                wbAuto = p.getBoolean("wbAuto", d.wbAuto),
                wbK = p.getInt("wbK", d.wbK),
                tint = p.getInt("tint", d.tint),
                afContinuous = p.getBoolean("afC", d.afContinuous),
                focusNorm = p.getFloat("focus", d.focusNorm),
                lens = p.getInt("lens", d.lens),
                ois = p.getBoolean("ois", d.ois),
                zebra = p.getBoolean("zebra", d.zebra),
                zebraLvl = p.getInt("zebraLvl", d.zebraLvl),
                peak = p.getBoolean("peak", d.peak),
                peakColor = p.getInt("peakColor", d.peakColor),
                grid = p.getBoolean("grid", d.grid),
                guide = p.getInt("guide", d.guide),
                fmt = p.getInt("fmt", d.fmt),
                aspect = p.getInt("aspect", d.aspect),
                timer = p.getInt("timer", d.timer),
                bracket = p.getBoolean("bracket", d.bracket),
                brStep = p.getInt("brStep", d.brStep),
            )
        }
    }

    fun save(ctx: Context) {
        ctx.getSharedPreferences(P, Context.MODE_PRIVATE).edit()
            .putInt("mode", mode).putInt("fps", fps).putBoolean("res4k", res4k).putBoolean("hevc", hevc)
            .putInt("bitrate", bitrate).putBoolean("audio", audio).putBoolean("log", log)
            .putBoolean("aeAuto", aeAuto).putInt("iso", iso).putLong("expNs", expNs)
            .putBoolean("wbAuto", wbAuto).putInt("wbK", wbK).putInt("tint", tint)
            .putBoolean("afC", afContinuous).putFloat("focus", focusNorm)
            .putInt("lens", lens).putBoolean("ois", ois)
            .putBoolean("zebra", zebra).putInt("zebraLvl", zebraLvl).putBoolean("peak", peak)
            .putInt("peakColor", peakColor).putBoolean("grid", grid).putInt("guide", guide)
            .putInt("fmt", fmt).putInt("aspect", aspect).putInt("timer", timer)
            .putBoolean("bracket", bracket).putInt("brStep", brStep)
            .apply()
    }
}
