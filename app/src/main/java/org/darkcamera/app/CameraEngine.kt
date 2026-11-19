package org.darkcamera.app

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Rect
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureFailure
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.DngCreator
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.ColorSpaceTransform
import android.hardware.camera2.params.MeteringRectangle
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.hardware.camera2.params.TonemapCurve
import android.media.Image
import android.media.ImageReader
import android.media.MediaCodec
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.util.Range
import android.util.Size
import android.view.Surface
import android.view.SurfaceHolder
import java.io.File
import java.util.concurrent.Executor
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

data class LensOption(val id: String, val zoom: Float, val label: String)

data class Config(
    val cameraId: String,
    val zoom: Float,
    val photo: Boolean,
    val preview: Size,
    val video: Size?,
    val jpeg: Size?,
    val raw: Size?,
    val analysis: Size,
)

data class Readout(val iso: Int, val expNs: Long, val focusDiopters: Float, val afState: Int)

/** Camera2 controller. Every camera call runs on its own background thread; callbacks arrive on the main thread. */
class CameraEngine(private val ctx: Context, private val analyzer: Analyzer, private val cb: Callbacks) {

    interface Callbacks {
        fun onReady(note: String?)
        fun onError(msg: String)
        fun onReadout(r: Readout)
        fun onAfLocked(norm: Float)
        fun onPhotoSaved(name: String, thumb: Bitmap?)
        fun onPhotoError(msg: String)
    }

    val manager: CameraManager = ctx.getSystemService(CameraManager::class.java)
    private val ui = Handler(Looper.getMainLooper())
    private val thread = HandlerThread("dc-camera").apply { start() }
    private val h = Handler(thread.looper)
    private val exec = Executor { h.post(it) }

    private var device: CameraDevice? = null
    private var deviceId: String? = null
    private var session: CameraCaptureSession? = null
    private var cfg: Config? = null
    private var ch: CameraCharacteristics? = null
    private var preview: Surface? = null
    private var analysisReader: ImageReader? = null
    private var jpegReader: ImageReader? = null
    private var rawReader: ImageReader? = null
    private var analysisOn = false
    private var rawOn = false

    private var recSurface: Surface? = null
    private var recSurfaceSize: Size? = null
    private var recorder: MediaRecorder? = null
    private var recUri: Uri? = null
    private var recPfd: ParcelFileDescriptor? = null
    private var recName: String? = null
    @Volatile var recording = false
        private set

    private var s = Settings()
    private var wb: ColorMath.WbSolver? = null
    private var awbTransform: ColorSpaceTransform? = null
    private var logCurve: TonemapCurve? = null
    private var afPending = false
    private var afStart = 0L
    private var afRegions: Array<MeteringRectangle>? = null
    private var lastUi = 0L
    @Volatile private var lastIso = 0
    @Volatile private var lastExp = 0L
    private var jpegOrientation = 0
    private var wantJpeg = true
    private var wantRaw = false
    private val results = HashMap<Long, TotalCaptureResult>()
    private val raws = HashMap<Long, Image>()
    private val names = HashMap<Long, String>()
    private var shotSeq = 0

    private fun post(r: () -> Unit) {
        ui.post(r)
    }

    fun chars(id: String): CameraCharacteristics = manager.getCameraCharacteristics(id)

    private fun caps(c: CameraCharacteristics): IntArray =
        c.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: IntArray(0)

    fun hasCap(id: String, cap: Int) = caps(chars(id)).contains(cap)

    fun sensorOrientation(id: String) = chars(id).get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90

    fun minFocus(id: String) = chars(id).get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f

    fun isoRange(id: String): Range<Int>? = chars(id).get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)

    fun expRange(id: String): Range<Long>? = chars(id).get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)

    // ---------------------------------------------------------------- lenses

    fun lensOptions(): List<LensOption> {
        val ids = try { manager.cameraIdList.toList() } catch (e: Exception) { emptyList() }
        val back = ids.filter {
            val c = chars(it)
            c.get(CameraCharacteristics.LENS_FACING) == CameraMetadata.LENS_FACING_BACK &&
                caps(c).contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_BACKWARD_COMPATIBLE)
        }
        if (back.isEmpty()) return ids.take(1).map { LensOption(it, 1f, "1x") }
        val mainId = back.first()
        val mc = chars(mainId)
        fun fov(c: CameraCharacteristics): Float {
            val f = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull() ?: return 0f
            val sw = c.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)?.width ?: return 0f
            return if (f > 0) sw / f else 0f
        }
        val mainFov = fov(mc)
        val zr: Range<Float>? = if (Build.VERSION.SDK_INT >= 30) mc.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE) else null
        val others = back.drop(1).map { it to fov(chars(it)) }.filter { it.second > 0f && mainFov > 0f }
        val out = ArrayList<LensOption>()
        val wide = others.filter { it.second > mainFov * 1.2f }.maxByOrNull { it.second }
        if (zr != null && zr.lower < 0.95f) out += LensOption(mainId, zr.lower, label(zr.lower))
        else if (wide != null) out += LensOption(wide.first, 1f, label(mainFov / wide.second))
        out += LensOption(mainId, 1f, "1x")
        val tele = others.filter { it.second < mainFov / 1.5f }.minByOrNull { it.second }
        when {
            tele != null -> out += LensOption(tele.first, 1f, label(mainFov / tele.second))
            zr != null && zr.upper >= 3f -> out += LensOption(mainId, 3f, "3x")
            (mc.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM) ?: 1f) >= 2f -> out += LensOption(mainId, 2f, "2x")
        }
        return out
    }

    private fun label(z: Float): String = when {
        z < 0.95f -> "." + (z * 10).roundToInt() + "x"
        z < 1.5f -> String.format("%.1fx", z)
        else -> "${z.roundToInt()}x"
    }

    // ---------------------------------------------------------------- sizes

    private fun area(s: Size) = s.width.toLong() * s.height

    private fun pick(sizes: Array<Size>?, ratio: Float, maxW: Int, maxH: Int): Size? =
        sizes?.filter { abs(it.width.toFloat() / it.height - ratio) < 0.02f && it.width <= maxW && it.height <= maxH }
            ?.maxByOrNull { area(it) }

    fun computeConfig(lens: LensOption, set: Settings): Config {
        val c = chars(lens.id)
        val map = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: throw IllegalStateException("no stream map")
        val yuv = map.getOutputSizes(ImageFormat.YUV_420_888) ?: emptyArray()
        val prev = map.getOutputSizes(SurfaceHolder::class.java) ?: emptyArray()
        fun smallYuv(ratio: Float) = pick(yuv, ratio, 800, 800)
            ?: yuv.filter { it.width >= 320 }.minByOrNull { area(it) } ?: Size(640, 480)
        if (!set.photo) {
            val vids = map.getOutputSizes(MediaRecorder::class.java) ?: emptyArray()
            val want = if (set.res4k) Size(3840, 2160) else Size(1920, 1080)
            val video = vids.firstOrNull { it == want } ?: pick(vids, 16f / 9, 1920, 1080)
                ?: vids.maxByOrNull { area(it) } ?: Size(1920, 1080)
            val ratio = video.width.toFloat() / video.height
            val pv = pick(prev, ratio, 1920, 1080) ?: pick(prev, ratio, 4000, 3000) ?: prev.firstOrNull() ?: Size(1280, 720)
            return Config(lens.id, lens.zoom, false, pv, video, null, null, smallYuv(ratio))
        }
        val ratio = when (set.aspect) { 1 -> 16f / 9; 2 -> 1f; else -> 4f / 3 }
        val jpegs = map.getOutputSizes(ImageFormat.JPEG) ?: emptyArray()
        val jpeg = pick(jpegs, ratio, 100000, 100000) ?: jpegs.maxByOrNull { area(it) } ?: Size(4000, 3000)
        val rawCap = caps(c).contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_RAW)
        val raw = if (set.fmt != Settings.FMT_JPEG && rawCap)
            map.getOutputSizes(ImageFormat.RAW_SENSOR)?.maxByOrNull { area(it) } else null
        val jr = jpeg.width.toFloat() / jpeg.height
        val pv = pick(prev, jr, 1920, 1440) ?: pick(prev, jr, 4000, 4000) ?: prev.firstOrNull() ?: Size(1440, 1080)
        return Config(lens.id, lens.zoom, true, pv, null, jpeg, raw, smallYuv(jr))
    }

    // ---------------------------------------------------------------- lifecycle

    fun open(c: Config, surface: Surface, set: Settings) = h.post {
        s = set
        preview = surface
        if (recording) return@post
        if (device != null && deviceId == c.cameraId) createSession(c)
        else {
            closeDevice()
            openDevice(c)
        }
    }

    fun update(set: Settings) = h.post {
        s = set
        startRepeating()
    }

    fun setZoom(z: Float, set: Settings) = h.post {
        s = set
        cfg = cfg?.copy(zoom = z)
        startRepeating()
    }

    fun close() = h.post {
        if (recording) stopInternal()
        closeDevice()
    }

    fun release() {
        close()
        h.post {
            recSurface?.release()
            recSurface = null
            thread.quitSafely()
        }
    }

    private fun closeDevice() {
        try { session?.close() } catch (_: Exception) {}
        session = null
        try { device?.close() } catch (_: Exception) {}
        device = null
        deviceId = null
        closeReaders()
    }

    private fun closeReaders() {
        analysisReader?.close(); analysisReader = null
        jpegReader?.close(); jpegReader = null
        rawReader?.close(); rawReader = null
        raws.values.forEach { it.close() }
        raws.clear()
        results.clear()
    }

    @SuppressLint("MissingPermission")
    private fun openDevice(c: Config) {
        try {
            manager.openCamera(c.cameraId, exec, object : CameraDevice.StateCallback() {
                override fun onOpened(d: CameraDevice) {
                    device = d
                    deviceId = c.cameraId
                    createSession(c)
                }

                override fun onDisconnected(d: CameraDevice) {
                    d.close()
                    if (device === d) { device = null; deviceId = null; session = null }
                }

                override fun onError(d: CameraDevice, error: Int) {
                    d.close()
                    if (device === d) { device = null; deviceId = null; session = null }
                    post { cb.onError("Erro ao abrir a câmera (código $error)") }
                }
            })
        } catch (e: Exception) {
            post { cb.onError("Não foi possível abrir a câmera: ${e.message}") }
        }
    }

    private fun createSession(c: Config) {
        val d = device ?: return
        try { session?.close() } catch (_: Exception) {}
        session = null
        closeReaders()
        cfg = c
        val chars = chars(c.cameraId)
        ch = chars
        wb = ColorMath.WbSolver(chars)
        logCurve = ColorMath.logCurve(chars.get(CameraCharacteristics.TONEMAP_MAX_CURVE_POINTS) ?: 0)
        awbTransform = null
        val pv = preview ?: return

        val an = ImageReader.newInstance(c.analysis.width, c.analysis.height, ImageFormat.YUV_420_888, 3)
        an.setOnImageAvailableListener({ r ->
            val img = try { r.acquireLatestImage() } catch (e: Exception) { null }
            if (img != null) analyzer.submit(img)
        }, h)
        analysisReader = an

        val attempts = ArrayList<List<Surface>>()
        if (!c.photo) {
            val rs = ensureRecSurface(c)
            if (rs == null) {
                post { cb.onError("Não foi possível preparar o gravador de vídeo") }
                return
            }
            attempts += listOf(pv, rs, an.surface)
            attempts += listOf(pv, rs)
        } else {
            val jr = c.jpeg?.let { ImageReader.newInstance(it.width, it.height, ImageFormat.JPEG, 4) }
            jr?.setOnImageAvailableListener({ onJpeg(it) }, h)
            jpegReader = jr
            val rr = c.raw?.let { ImageReader.newInstance(it.width, it.height, ImageFormat.RAW_SENSOR, 4) }
            rr?.setOnImageAvailableListener({ onRaw(it) }, h)
            rawReader = rr
            val base = listOfNotNull(pv, jr?.surface)
            if (rr != null) {
                attempts += base + rr.surface + an.surface
                attempts += base + rr.surface
            }
            attempts += base + an.surface
            attempts += base
        }
        tryCreate(d, attempts, 0)
    }

    private fun tryCreate(d: CameraDevice, attempts: List<List<Surface>>, i: Int) {
        val outs = attempts[i]
        val callback = object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(sess: CameraCaptureSession) {
                if (device !== d) {
                    sess.close()
                    return
                }
                session = sess
                val an = analysisReader?.surface
                analysisOn = an != null && outs.contains(an)
                val rs = rawReader?.surface
                rawOn = rs != null && outs.contains(rs)
                startRepeating()
                val note = when {
                    cfg?.raw != null && !rawOn -> "RAW indisponível nesta combinação: as fotos serão salvas em JPEG"
                    !analysisOn -> "Histograma e zebras indisponíveis nesta configuração"
                    else -> null
                }
                post { cb.onReady(note) }
            }

            override fun onConfigureFailed(sess: CameraCaptureSession) {
                if (i + 1 < attempts.size) tryCreate(d, attempts, i + 1)
                else post { cb.onError("Esta configuração de câmera não é suportada pelo aparelho") }
            }
        }
        try {
            d.createCaptureSession(
                SessionConfiguration(SessionConfiguration.SESSION_REGULAR, outs.map { OutputConfiguration(it) }, exec, callback),
            )
        } catch (e: Exception) {
            if (i + 1 < attempts.size) tryCreate(d, attempts, i + 1)
            else post { cb.onError("Falha ao configurar a câmera: ${e.message}") }
        }
    }

    private fun newRecorder(): MediaRecorder =
        if (Build.VERSION.SDK_INT >= 31) MediaRecorder(ctx) else @Suppress("DEPRECATION") MediaRecorder()

    /** A persistent encoder surface lets recording start without rebuilding the camera session. */
    private fun ensureRecSurface(c: Config): Surface? {
        val size = c.video ?: return null
        recSurface?.let { if (recSurfaceSize == size) return it }
        recSurface?.release()
        recSurface = null
        val surf = MediaCodec.createPersistentInputSurface()
        val tmp = File(ctx.cacheDir, "init.mp4")
        val r = newRecorder()
        try {
            r.setVideoSource(MediaRecorder.VideoSource.SURFACE)
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            r.setOutputFile(tmp.absolutePath)
            r.setVideoEncodingBitRate(10_000_000)
            r.setVideoFrameRate(30)
            r.setVideoSize(size.width, size.height)
            r.setVideoEncoder(MediaRecorder.VideoEncoder.H264)
            r.setInputSurface(surf)
            r.prepare()
        } catch (e: Exception) {
            r.release()
            surf.release()
            tmp.delete()
            return null
        }
        r.release()
        tmp.delete()
        recSurface = surf
        recSurfaceSize = size
        return surf
    }

    // ---------------------------------------------------------------- requests

    private fun fpsRange(c: CameraCharacteristics, fps: Int): Range<Int>? {
        val ranges = c.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES) ?: return null
        return ranges.firstOrNull { it.lower == fps && it.upper == fps }
            ?: ranges.filter { it.upper == fps }.maxByOrNull { it.lower }
    }

    private fun startRepeating() {
        val d = device ?: return
        val sess = session ?: return
        val c = cfg ?: return
        val pv = preview ?: return
        try {
            val b = d.createCaptureRequest(if (c.photo) CameraDevice.TEMPLATE_PREVIEW else CameraDevice.TEMPLATE_RECORD)
            b.addTarget(pv)
            if (analysisOn) analysisReader?.surface?.let { b.addTarget(it) }
            if (recording) recSurface?.let { b.addTarget(it) }
            fill(b, false, 0.0, null)
            sess.setRepeatingRequest(b.build(), repeatCb, h)
        } catch (e: Exception) {
            post { cb.onError("Erro na câmera: ${e.message}") }
        }
    }

    private fun fill(b: CaptureRequest.Builder, still: Boolean, ev: Double, force: Pair<Int, Long>?) {
        val c = cfg ?: return
        val chars = ch ?: return
        val cp = caps(chars)
        val manualSensor = cp.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR)
        val manualPost = cp.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_POST_PROCESSING)
        val frameNs = 1_000_000_000L / s.fps

        b.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)

        // exposure
        val man = force ?: if (!s.aeAuto) Pair(s.iso, s.expNs) else null
        if (man == null || !manualSensor) {
            b.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON)
            if (!c.photo) fpsRange(chars, s.fps)?.let { b.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, it) }
            if (ev != 0.0) {
                val step = chars.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP)?.toDouble() ?: (1.0 / 3)
                val range = chars.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE)
                var steps = (ev / step).roundToInt()
                if (range != null) steps = steps.coerceIn(range.lower, range.upper)
                b.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, steps)
            }
        } else {
            b.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_OFF)
            val isoR = chars.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
            val expR = chars.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
            var exp = (man.second * 2.0.pow(ev)).toLong()
            if (!c.photo) exp = min(exp, frameNs)
            if (expR != null) exp = exp.coerceIn(expR.lower, expR.upper)
            val iso = if (isoR != null) man.first.coerceIn(isoR.lower, isoR.upper) else man.first
            b.set(CaptureRequest.SENSOR_SENSITIVITY, iso)
            b.set(CaptureRequest.SENSOR_EXPOSURE_TIME, exp)
            b.set(CaptureRequest.SENSOR_FRAME_DURATION, if (!c.photo) frameNs else if (still) exp else max(exp, 33_333_333L))
        }

        // white balance
        if (s.wbAuto || !manualPost) {
            b.set(CaptureRequest.CONTROL_AWB_MODE, CameraMetadata.CONTROL_AWB_MODE_AUTO)
        } else {
            b.set(CaptureRequest.CONTROL_AWB_MODE, CameraMetadata.CONTROL_AWB_MODE_OFF)
            b.set(CaptureRequest.COLOR_CORRECTION_MODE, CameraMetadata.COLOR_CORRECTION_MODE_TRANSFORM_MATRIX)
            val r = wb?.solve(s.wbK, s.tint)
            if (r != null) {
                b.set(CaptureRequest.COLOR_CORRECTION_GAINS, r.first)
                b.set(CaptureRequest.COLOR_CORRECTION_TRANSFORM, r.second ?: awbTransform ?: ColorMath.IDENTITY)
            } else {
                b.set(CaptureRequest.COLOR_CORRECTION_GAINS, ColorMath.naiveGains(s.wbK, s.tint))
                b.set(CaptureRequest.COLOR_CORRECTION_TRANSFORM, awbTransform ?: ColorMath.IDENTITY)
            }
        }

        // focus
        val minF = chars.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f
        val afModes = chars.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES) ?: IntArray(0)
        if (afPending) {
            b.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_AUTO)
            afRegions?.let { b.set(CaptureRequest.CONTROL_AF_REGIONS, it) }
        } else if (s.afContinuous || minF <= 0f) {
            val m = when {
                !c.photo && afModes.contains(CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_VIDEO) -> CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_VIDEO
                afModes.contains(CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE) -> CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE
                afModes.contains(CameraMetadata.CONTROL_AF_MODE_AUTO) -> CameraMetadata.CONTROL_AF_MODE_AUTO
                else -> CameraMetadata.CONTROL_AF_MODE_OFF
            }
            b.set(CaptureRequest.CONTROL_AF_MODE, m)
        } else {
            b.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_OFF)
            b.set(CaptureRequest.LENS_FOCUS_DISTANCE, s.focusNorm.coerceIn(0f, 1f) * minF)
        }

        // LOG curve
        val tm = chars.get(CameraCharacteristics.TONEMAP_AVAILABLE_TONE_MAP_MODES) ?: IntArray(0)
        val curve = logCurve
        if (s.log && !c.photo && curve != null && tm.contains(CameraMetadata.TONEMAP_MODE_CONTRAST_CURVE)) {
            b.set(CaptureRequest.TONEMAP_MODE, CameraMetadata.TONEMAP_MODE_CONTRAST_CURVE)
            b.set(CaptureRequest.TONEMAP_CURVE, curve)
        }

        // optical stabilisation
        val ois = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION) ?: IntArray(0)
        if (ois.contains(CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_ON)) {
            b.set(
                CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE,
                if (s.ois) CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_ON else CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_OFF,
            )
        }

        // zoom
        val zr: Range<Float>? = if (Build.VERSION.SDK_INT >= 30) chars.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE) else null
        if (Build.VERSION.SDK_INT >= 30 && zr != null) {
            b.set(CaptureRequest.CONTROL_ZOOM_RATIO, c.zoom.coerceIn(zr.lower, zr.upper))
        } else if (c.zoom > 1f) {
            val act = chars.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
            val maxZ = chars.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM) ?: 1f
            if (act != null) {
                val z = min(c.zoom, maxZ)
                val w = (act.width() / z).toInt()
                val hh = (act.height() / z).toInt()
                b.set(CaptureRequest.SCALER_CROP_REGION, Rect(act.centerX() - w / 2, act.centerY() - hh / 2, act.centerX() + w / 2, act.centerY() + hh / 2))
            }
        }

        if (still) {
            b.set(CaptureRequest.JPEG_QUALITY, 95.toByte())
            b.set(CaptureRequest.JPEG_ORIENTATION, jpegOrientation)
        }
    }

    private val repeatCb = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
            if (s.wbAuto) result.get(CaptureResult.COLOR_CORRECTION_TRANSFORM)?.let { awbTransform = it }
            val iso = result.get(CaptureResult.SENSOR_SENSITIVITY) ?: 0
            val exp = result.get(CaptureResult.SENSOR_EXPOSURE_TIME) ?: 0L
            if (iso > 0) lastIso = iso
            if (exp > 0) lastExp = exp
            val af = result.get(CaptureResult.CONTROL_AF_STATE) ?: 0
            val dist = result.get(CaptureResult.LENS_FOCUS_DISTANCE) ?: 0f
            if (afPending) {
                val done = af == CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED || af == CaptureResult.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED
                if (done || SystemClock.elapsedRealtime() - afStart > 2500) {
                    afPending = false
                    afRegions = null
                    val minF = ch?.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f
                    val norm = if (minF > 0f) (dist / minF).coerceIn(0f, 1f) else 0f
                    post { cb.onAfLocked(norm) }
                }
            }
            val now = SystemClock.elapsedRealtime()
            if (now - lastUi > 120) {
                lastUi = now
                val r = Readout(iso, exp, dist, af)
                post { cb.onReadout(r) }
            }
        }
    }

    // ---------------------------------------------------------------- focus

    /** Tap-to-focus. nx/ny are 0..1 coordinates inside the preview (already corrected for rotation). */
    fun focusAt(nx: Float, ny: Float, set: Settings) = h.post {
        s = set
        val chars = ch ?: return@post
        val c = cfg ?: return@post
        val d = device ?: return@post
        val sess = session ?: return@post
        val act = chars.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE) ?: return@post
        val maxRegions = chars.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AF) ?: 0
        val ar = c.preview.width.toFloat() / c.preview.height
        var vw = act.width().toFloat()
        var vh = vw / ar
        if (vh > act.height()) {
            vh = act.height().toFloat()
            vw = vh * ar
        }
        val z = max(c.zoom, 1f)
        val cx = act.width() / 2f + (nx - 0.5f) * vw / z
        val cy = act.height() / 2f + (ny - 0.5f) * vh / z
        val half = max((vw / z * 0.06f).toInt(), 16)
        val l = (cx.toInt() - half).coerceIn(0, act.width() - 2 * half)
        val t = (cy.toInt() - half).coerceIn(0, act.height() - 2 * half)
        afRegions = if (maxRegions > 0) arrayOf(MeteringRectangle(l, t, 2 * half, 2 * half, MeteringRectangle.METERING_WEIGHT_MAX - 1)) else null
        afPending = true
        afStart = SystemClock.elapsedRealtime()
        startRepeating()
        try {
            val b = d.createCaptureRequest(if (c.photo) CameraDevice.TEMPLATE_PREVIEW else CameraDevice.TEMPLATE_RECORD)
            preview?.let { b.addTarget(it) }
            if (recording) recSurface?.let { b.addTarget(it) }
            fill(b, false, 0.0, null)
            b.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_START)
            sess.capture(b.build(), null, h)
        } catch (e: Exception) {
            afPending = false
        }
    }

    // ---------------------------------------------------------------- video

    fun startRecording(set: Settings, hint: Int, done: (Boolean) -> Unit) = h.post {
        s = set
        val c = cfg
        val rs = recSurface
        val video = c?.video
        if (c == null || c.photo || rs == null || video == null || session == null) {
            post { done(false) }
            return@post
        }
        try {
            val name = "DC_" + Store.stamp() + ".mp4"
            val (uri, pfd) = Store.newVideo(ctx, name)
            recUri = uri
            recPfd = pfd
            recName = name
            val r = newRecorder()
            recorder = r
            if (set.audio) r.setAudioSource(MediaRecorder.AudioSource.CAMCORDER)
            r.setVideoSource(MediaRecorder.VideoSource.SURFACE)
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            r.setOutputFile(pfd.fileDescriptor)
            r.setVideoEncodingBitRate(set.bitrate * 1_000_000)
            r.setVideoFrameRate(set.fps)
            r.setVideoSize(video.width, video.height)
            r.setVideoEncoder(if (set.hevc) MediaRecorder.VideoEncoder.HEVC else MediaRecorder.VideoEncoder.H264)
            if (set.audio) {
                r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                r.setAudioSamplingRate(48000)
                r.setAudioEncodingBitRate(256_000)
                r.setAudioChannels(2)
            }
            r.setOrientationHint(hint)
            r.setInputSurface(rs)
            r.prepare()
            recording = true
            startRepeating()
            r.start()
            post { done(true) }
        } catch (e: Exception) {
            recording = false
            cleanupRec(false)
            startRepeating()
            post { done(false) }
        }
    }

    fun stopRecording(done: (String?) -> Unit) = h.post {
        val name = recName
        val ok = stopInternal()
        post { done(if (ok) name else null) }
    }

    private fun stopInternal(): Boolean {
        val r = recorder ?: return false
        var ok = true
        try { r.stop() } catch (e: Exception) { ok = false }
        recording = false
        cleanupRec(ok)
        startRepeating()
        return ok
    }

    private fun cleanupRec(ok: Boolean) {
        try { recorder?.reset() } catch (_: Exception) {}
        try { recorder?.release() } catch (_: Exception) {}
        recorder = null
        try { recPfd?.close() } catch (_: Exception) {}
        recPfd = null
        recUri?.let { Store.finish(ctx, it, ok) }
        recUri = null
        recName = null
    }

    fun amplitude(): Int = try { recorder?.maxAmplitude ?: 0 } catch (e: Exception) { 0 }

    // ---------------------------------------------------------------- photo

    fun takePhoto(set: Settings, evs: List<Double>, orientation: Int) = h.post {
        s = set
        val d = device
        val sess = session
        val c = cfg
        if (d == null || sess == null || c == null || !c.photo) {
            post { cb.onPhotoError("A câmera ainda não está pronta") }
            return@post
        }
        jpegOrientation = orientation
        wantRaw = set.fmt != Settings.FMT_JPEG && rawOn
        wantJpeg = set.fmt != Settings.FMT_RAW || !wantRaw
        val force = when {
            !set.aeAuto -> Pair(set.iso, set.expNs)
            evs.size > 1 && lastExp > 0 -> Pair(lastIso, lastExp)
            else -> null
        }
        try {
            val reqs = evs.map { ev ->
                val b = d.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
                if (wantJpeg) jpegReader?.surface?.let { b.addTarget(it) }
                if (wantRaw) rawReader?.surface?.let { b.addTarget(it) }
                fill(b, true, ev, force)
                b.build()
            }
            sess.captureBurst(reqs, stillCb, h)
        } catch (e: Exception) {
            post { cb.onPhotoError("Falha ao fotografar: ${e.message}") }
        }
    }

    private val stillCb = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
            val ts = result.get(CaptureResult.SENSOR_TIMESTAMP) ?: return
            if (wantRaw) {
                results[ts] = result
                pairRaw(ts)
            }
        }

        override fun onCaptureFailed(session: CameraCaptureSession, request: CaptureRequest, failure: CaptureFailure) {
            post { cb.onPhotoError("A câmera falhou ao capturar") }
        }
    }

    private fun nameFor(ts: Long): String {
        if (names.size > 64) names.clear()
        return names.getOrPut(ts) { "DC_" + Store.stamp() + "_" + (++shotSeq) }
    }

    private fun onJpeg(r: ImageReader) {
        val img = try { r.acquireNextImage() } catch (e: Exception) { null } ?: return
        val ts = img.timestamp
        val buf = img.planes[0].buffer
        val bytes = ByteArray(buf.remaining())
        buf.get(bytes)
        img.close()
        val name = nameFor(ts)
        val ok = Store.saveBytes(ctx, "$name.jpg", "image/jpeg", bytes)
        val thumb = try {
            val b = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = 16 })
            if (b != null && jpegOrientation != 0) {
                Bitmap.createBitmap(b, 0, 0, b.width, b.height, Matrix().apply { postRotate(jpegOrientation.toFloat()) }, true)
            } else b
        } catch (e: Exception) {
            null
        }
        val label = if (wantRaw) "$name.DNG + JPG" else "$name.JPG"
        post { if (ok) cb.onPhotoSaved(label, thumb) else cb.onPhotoError("Falha ao salvar a foto") }
    }

    private fun onRaw(r: ImageReader) {
        val img = try { r.acquireNextImage() } catch (e: Exception) { null } ?: return
        raws[img.timestamp] = img
        pairRaw(img.timestamp)
    }

    private fun pairRaw(ts: Long) {
        val res = results[ts] ?: return
        val img = raws[ts] ?: return
        results.remove(ts)
        raws.remove(ts)
        val chars = ch
        if (chars == null) {
            img.close()
            return
        }
        val name = nameFor(ts)
        val exif = when (jpegOrientation) { 90 -> 6; 180 -> 3; 270 -> 8; else -> 1 }
        var ok: Boolean
        try {
            ok = DngCreator(chars, res).use { dng ->
                dng.setOrientation(exif)
                Store.saveStream(ctx, "$name.dng", "image/x-adobe-dng") { out -> dng.writeImage(out, img) }
            }
        } catch (e: Exception) {
            ok = false
        } finally {
            img.close()
        }
        if (!wantJpeg) post { if (ok) cb.onPhotoSaved("$name.DNG", null) else cb.onPhotoError("Falha ao salvar o RAW") }
        else if (!ok) post { cb.onPhotoError("Falha ao salvar o RAW") }
    }

    // ---------------------------------------------------------------- info

    fun info(id: String): List<Pair<String, String>> {
        val c = chars(id)
        val lvl = when (c.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL)) {
            CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY -> "LEGACY"
            CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED -> "LIMITED"
            CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_FULL -> "FULL"
            CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_3 -> "LEVEL_3"
            CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_EXTERNAL -> "EXTERNAL"
            else -> "?"
        }
        val cp = caps(c)
        fun yn(b: Boolean) = if (b) "Sim" else "Não"
        val iso = c.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
        val exp = c.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
        val tm = c.get(CameraCharacteristics.TONEMAP_AVAILABLE_TONE_MAP_MODES) ?: IntArray(0)
        return listOf(
            "Aparelho" to "${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE}",
            "Câmera ativa" to "ID $id · nível $lvl",
            "Controle manual (ISO/obturador)" to yn(cp.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR)),
            "Balanço de branco manual" to yn(cp.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_POST_PROCESSING)),
            "Foto RAW (DNG)" to yn(cp.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_RAW)),
            "Curva LOG" to yn(tm.contains(CameraMetadata.TONEMAP_MODE_CONTRAST_CURVE)),
            "ISO" to (iso?.let { "${it.lower} a ${it.upper}" } ?: "—"),
            "Obturador" to (exp?.let { "1/${(1e9 / it.lower).roundToInt()} a ${String.format("%.2f", it.upper / 1e9)} s" } ?: "—"),
        )
    }
}
