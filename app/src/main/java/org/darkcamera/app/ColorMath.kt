package org.darkcamera.app

import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.params.ColorSpaceTransform
import android.hardware.camera2.params.RggbChannelVector
import android.hardware.camera2.params.TonemapCurve
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Manual white balance and LOG curve math.
 *
 * White balance uses the sensor's own DNG calibration data (colour matrices + forward matrices)
 * so a Kelvin value produces real-world neutral colours instead of a rough RGB tint.
 */
object ColorMath {

    val IDENTITY = ColorSpaceTransform(intArrayOf(1, 1, 0, 1, 0, 1, 0, 1, 1, 1, 0, 1, 0, 1, 0, 1, 1, 1))

    /** XYZ (D50) -> linear sRGB (D65), Bradford adapted. */
    private val XYZD50_TO_SRGB = doubleArrayOf(
        3.1338561, -1.6168667, -0.4906146,
        -0.9787684, 1.9161415, 0.0334540,
        0.0719453, -0.2289914, 1.4052427,
    )

    /** Planckian locus approximation (Kim et al. 2002). */
    fun kelvinToXy(kIn: Double): DoubleArray {
        val t = kIn.coerceIn(1667.0, 25000.0)
        val x = if (t <= 4000) {
            -0.2661239e9 / (t * t * t) - 0.2343589e6 / (t * t) + 0.8776956e3 / t + 0.179910
        } else {
            -3.0258469e9 / (t * t * t) + 2.1070379e6 / (t * t) + 0.2226347e3 / t + 0.240390
        }
        val y = when {
            t <= 2222 -> -1.1063814 * x * x * x - 1.34811020 * x * x + 2.18555832 * x - 0.20219683
            t <= 4000 -> -0.9549476 * x * x * x - 1.37418593 * x * x + 2.09137015 * x - 0.16748867
            else -> 3.0817580 * x * x * x - 5.87338670 * x * x + 3.75112997 * x - 0.37001483
        }
        return doubleArrayOf(x, y)
    }

    /** Correlated colour temperature of a camera2 reference illuminant. */
    fun cct(illuminant: Int?): Double = when (illuminant) {
        1, 4, 9 -> 5500.0
        2 -> 4200.0
        3, 17 -> 2856.0
        10 -> 6500.0
        11 -> 7500.0
        12 -> 6430.0
        13 -> 5000.0
        14 -> 4150.0
        15 -> 3450.0
        18 -> 4874.0
        19 -> 6774.0
        20 -> 5503.0
        21 -> 6504.0
        22 -> 7504.0
        23 -> 5003.0
        24 -> 3200.0
        else -> 6504.0
    }

    private fun toArr(t: ColorSpaceTransform?): DoubleArray? {
        t ?: return null
        return DoubleArray(9) { i -> t.getElement(i % 3, i / 3).toDouble() }
    }

    private fun mul(a: DoubleArray, b: DoubleArray) = DoubleArray(9) { i ->
        val r = i / 3
        val c = i % 3
        a[r * 3] * b[c] + a[r * 3 + 1] * b[3 + c] + a[r * 3 + 2] * b[6 + c]
    }

    private fun mulV(a: DoubleArray, v: DoubleArray) = DoubleArray(3) { r ->
        a[r * 3] * v[0] + a[r * 3 + 1] * v[1] + a[r * 3 + 2] * v[2]
    }

    private fun lerp(a: DoubleArray, b: DoubleArray?, w: Double) =
        if (b == null) a else DoubleArray(9) { a[it] * w + b[it] * (1 - w) }

    private fun toTransform(m: DoubleArray): ColorSpaceTransform {
        val e = IntArray(18)
        for (i in 0 until 9) {
            e[i * 2] = (m[i] * 10000).roundToInt()
            e[i * 2 + 1] = 10000
        }
        return ColorSpaceTransform(e)
    }

    class WbSolver(ch: CameraCharacteristics) {
        private val cm1: DoubleArray?
        private val cm2: DoubleArray?
        private val fm1: DoubleArray?
        private val fm2: DoubleArray?
        private val t1: Double
        private val t2: Double

        init {
            val c1 = toArr(ch.get(CameraCharacteristics.SENSOR_COLOR_TRANSFORM1))
            val c2 = toArr(ch.get(CameraCharacteristics.SENSOR_COLOR_TRANSFORM2))
            val k1 = toArr(ch.get(CameraCharacteristics.SENSOR_CALIBRATION_TRANSFORM1))
            val k2 = toArr(ch.get(CameraCharacteristics.SENSOR_CALIBRATION_TRANSFORM2))
            cm1 = if (c1 != null && k1 != null) mul(k1, c1) else c1
            cm2 = if (c2 != null && k2 != null) mul(k2, c2) else c2
            fm1 = toArr(ch.get(CameraCharacteristics.SENSOR_FORWARD_MATRIX1))
            fm2 = toArr(ch.get(CameraCharacteristics.SENSOR_FORWARD_MATRIX2))
            t1 = cct(ch.get(CameraCharacteristics.SENSOR_REFERENCE_ILLUMINANT1))
            t2 = cct(ch.get(CameraCharacteristics.SENSOR_REFERENCE_ILLUMINANT2)?.toInt())
        }

        /** Returns channel gains + colour transform for a Kelvin/tint pair, or null when no calibration exists. */
        fun solve(kelvin: Int, tint: Int): Pair<RggbChannelVector, ColorSpaceTransform?>? {
            val m1 = cm1 ?: return null
            val xy = kelvinToXy(kelvin.toDouble())
            val x = xy[0]
            val y = xy[1] - tint * 0.0004 // positive tint = magenta
            val xyz = doubleArrayOf(x / y, 1.0, (1 - x - y) / y)
            val w = if (cm2 == null || abs(t1 - t2) < 1) 1.0
            else ((1.0 / kelvin - 1.0 / t2) / (1.0 / t1 - 1.0 / t2)).coerceIn(0.0, 1.0)
            val n = mulV(lerp(m1, cm2, w), xyz)
            if (n[0] <= 0 || n[1] <= 0 || n[2] <= 0) return null
            val gR = (n[1] / n[0]).coerceIn(0.25, 8.0)
            val gB = (n[1] / n[2]).coerceIn(0.25, 8.0)
            val gains = RggbChannelVector(gR.toFloat(), 1f, 1f, gB.toFloat())
            val f1 = fm1
            val ccm = if (f1 != null) toTransform(mul(XYZD50_TO_SRGB, lerp(f1, fm2, w))) else null
            return Pair(gains, ccm)
        }
    }

    /** Fallback for sensors without calibration data. */
    fun naiveGains(kelvin: Int, tint: Int): RggbChannelVector {
        val t = kelvin / 100.0
        val r = if (t <= 66) 255.0 else (329.698727446 * (t - 60).pow(-0.1332047592)).coerceIn(1.0, 255.0)
        val g = (if (t <= 66) 99.4708025861 * ln(t) - 161.1195681661
        else 288.1221695283 * (t - 60).pow(-0.0755148492)).coerceIn(1.0, 255.0)
        val b = when {
            t >= 66 -> 255.0
            t <= 19 -> 1.0
            else -> (138.5177312231 * ln(t - 10) - 305.0447927307).coerceIn(1.0, 255.0)
        }
        val gG = (1.0 - tint * 0.006).toFloat()
        return RggbChannelVector((g / r).toFloat() * 1.8f, gG, gG, (g / b).toFloat() * 1.6f)
    }

    /** "DarkLog" curve: lifts shadows and keeps highlights for grading. Input is linear. */
    fun logCurve(maxPoints: Int): TonemapCurve? {
        if (maxPoints < 2) return null
        val n = min(maxPoints, 64)
        val pts = FloatArray(n * 2)
        for (i in 0 until n) {
            val t = i.toDouble() / (n - 1)
            val xin = t * t
            val out = 0.03 + 0.97 * ln(1 + 10 * xin) / ln(11.0)
            pts[i * 2] = xin.toFloat()
            pts[i * 2 + 1] = out.toFloat().coerceIn(0f, 1f)
        }
        return TonemapCurve(pts, pts.copyOf(), pts.copyOf())
    }
}
