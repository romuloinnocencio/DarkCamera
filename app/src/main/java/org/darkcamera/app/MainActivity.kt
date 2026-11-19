package org.darkcamera.app

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.drawable.GradientDrawable
import android.hardware.camera2.CameraMetadata
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Size
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlin.math.abs
import kotlin.math.log2
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.roundToLong

class MainActivity : Activity(), CameraEngine.Callbacks, SurfaceHolder.Callback {

    private lateinit var s: Settings
    private lateinit var engine: CameraEngine
    private lateinit var analyzer: Analyzer
    private val ui = Handler(Looper.getMainLooper())

    private var lenses: List<LensOption> = emptyList()
    private var camGranted = false
    private var started = false
    private var surfaceReady = false
    private var surfaceSize = Size(0, 0)
    private var pending: Config? = null
    private var current: Config? = null
    private var recording = false
    private var recStart = 0L
    private var lastIso = 0
    private var lastExp = 0L
    private var lastFocusD = 0f
    private var meanLuma = 118f
    private var busyPhoto = false
    private var shots = 0
    private var audio: AudioMonitor? = null
    private var lvlL = 0f
    private var lvlR = 0f

    // ----- views
    private lateinit var root: FrameLayout
    private lateinit var aspect: AspectFrame
    private lateinit var surface: SurfaceView
    private lateinit var overlay: OverlayView
    private lateinit var recFrame: View
    private lateinit var flash: View
    private lateinit var iRes: TextView
    private lateinit var iFmt: TextView
    private lateinit var cA: Cell
    private lateinit var cB: Cell
    private lateinit var cIso: Cell
    private lateinit var cWb: Cell
    private lateinit var cTint: Cell
    private lateinit var battery: BatteryView
    private lateinit var batteryTxt: TextView
    private lateinit var lensBox: LinearLayout
    private lateinit var focusTxt: TextView
    private lateinit var slider: VerticalSlider
    private lateinit var timerChip: TextView
    private lateinit var afChip: TextView
    private lateinit var modeVideo: TextView
    private lateinit var modePhoto: TextView
    private lateinit var recBtn: RecButton
    private lateinit var tZebra: TextView
    private lateinit var tPeak: TextView
    private lateinit var tGrid: TextView
    private lateinit var tLog: TextView
    private lateinit var tBrkt: TextView
    private lateinit var hist: HistogramView
    private lateinit var evTxt: TextView
    private lateinit var meter: MeterView
    private lateinit var audioCell: LinearLayout
    private lateinit var photoCell: LinearLayout
    private lateinit var thumb: ImageView
    private lateinit var shotName: TextView
    private lateinit var shotCount: TextView
    private lateinit var tcLab: TextView
    private lateinit var tc: TextView
    private lateinit var remain: TextView
    private lateinit var freeTxt: TextView
    private lateinit var picker: LinearLayout
    private lateinit var pickerTitle: TextView
    private lateinit var chips: LinearLayout
    private lateinit var pickerScroll: HorizontalScrollView
    private var pickerKey: String? = null
    private lateinit var settingsPanel: LinearLayout
    private lateinit var tabs: LinearLayout
    private lateinit var sbody: LinearLayout
    private var tab = 0
    private lateinit var toastTv: TextView
    private lateinit var countTv: TextView
    private lateinit var messageTv: TextView

    private class Cell(val box: LinearLayout, val label: TextView, val value: TextView, val sub: TextView)

    // ======================================================================= lifecycle

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        s = Settings.load(this)
        analyzer = Analyzer { bmp, h, mean ->
            ui.post {
                overlay.analysis = bmp
                hist.set(h)
                meanLuma = mean
                updateEv()
            }
        }
        engine = CameraEngine(this, analyzer, this)
        buildUi()
        setContentView(root)
        hideBars()
        val need = arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
            .filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (need.isEmpty()) onPermissions() else requestPermissions(need.toTypedArray(), 7)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        onPermissions()
    }

    private fun onPermissions() {
        camGranted = checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        if (!camGranted) {
            messageTv.text = "O DarkCamera precisa da permissão de câmera.\nAtive em Configurações › Apps › DarkCamera › Permissões."
            messageTv.visibility = View.VISIBLE
            return
        }
        if (!started) {
            started = true
            lenses = try { engine.lensOptions() } catch (e: Exception) { emptyList() }
            if (lenses.isEmpty()) {
                messageTv.text = "Nenhuma câmera traseira encontrada."
                messageTv.visibility = View.VISIBLE
                return
            }
            if (s.lens !in lenses.indices) s.lens = lenses.indexOfFirst { it.zoom == 1f && it.label == "1x" }.coerceAtLeast(0)
            buildLensButtons()
        }
        refreshUi()
        requestConfig()
        startAudioMonitor()
    }

    override fun onResume() {
        super.onResume()
        hideBars()
        if (started) {
            requestConfig()
            startAudioMonitor()
        }
        ui.post(ticker)
    }

    override fun onPause() {
        super.onPause()
        if (recording) {
            recording = false
            engine.stopRecording { }
            updateRecUi()
        }
        engine.close()
        current = null
        stopAudioMonitor()
        ui.removeCallbacks(ticker)
        s.save(this)
    }

    override fun onDestroy() {
        super.onDestroy()
        engine.release()
        analyzer.release()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideBars()
    }

    private fun hideBars() {
        if (Build.VERSION.SDK_INT >= 30) {
            window.insetsController?.let {
                it.hide(WindowInsets.Type.systemBars())
                it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_STABLE)
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN || keyCode == KeyEvent.KEYCODE_CAMERA) {
            if (event.repeatCount == 0) onShutter()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    // ======================================================================= surface / config

    override fun surfaceCreated(holder: SurfaceHolder) {
        surfaceReady = true
        requestConfig()
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        surfaceSize = Size(width, height)
        val p = pending ?: return
        if (p.preview.width == width && p.preview.height == height) openConfig(p)
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        surfaceReady = false
        surfaceSize = Size(0, 0)
        engine.close()
        current = null
    }

    private fun lens(): LensOption = lenses[s.lens.coerceIn(0, lenses.size - 1)]

    /** Rebuild the camera session (mode, lens, resolution, aspect or format changed). */
    private fun requestConfig() {
        if (!camGranted || !surfaceReady || lenses.isEmpty() || recording) return
        val c = try { engine.computeConfig(lens(), s) } catch (e: Exception) {
            toast("Configuração indisponível: ${e.message}")
            return
        }
        aspect.aspect = c.preview.width.toFloat() / c.preview.height
        pending = c
        if (surfaceSize == c.preview) openConfig(c) else surface.holder.setFixedSize(c.preview.width, c.preview.height)
    }

    private fun openConfig(c: Config) {
        pending = null
        current = c
        overlay.rot180 = analysisRotation() == 180
        engine.open(c, surface.holder.surface, s.copy())
    }

    private fun displayDegrees(): Int {
        val rot = if (Build.VERSION.SDK_INT >= 30) display?.rotation ?: Surface.ROTATION_90
        else @Suppress("DEPRECATION") windowManager.defaultDisplay.rotation
        return when (rot) { Surface.ROTATION_90 -> 90; Surface.ROTATION_180 -> 180; Surface.ROTATION_270 -> 270; else -> 0 }
    }

    private fun analysisRotation(): Int {
        val sensor = try { engine.sensorOrientation(lens().id) } catch (e: Exception) { 90 }
        return (sensor - displayDegrees() + 360) % 360
    }

    private fun push() {
        analyzer.zebra = s.zebra
        analyzer.zebraLevel = (s.zebraLvl * 255 / 100).coerceAtMost(254)
        analyzer.peak = s.peak
        analyzer.peakColor = when (s.peakColor) { 1 -> 0xFFFF3737.toInt(); 2 -> 0xFFFFFFFF.toInt(); else -> C.ACCENT }
        overlay.grid = s.grid
        overlay.guide = if (s.photo) 0 else s.guide
        if (current != null) engine.update(s.copy())
        refreshUi()
    }

    // ======================================================================= camera callbacks

    override fun onReady(note: String?) {
        messageTv.visibility = View.GONE
        if (note != null) toast(note)
    }

    override fun onError(msg: String) {
        toast(msg)
    }

    override fun onReadout(r: Readout) {
        if (r.iso > 0) lastIso = r.iso
        if (r.expNs > 0) lastExp = r.expNs
        lastFocusD = r.focusDiopters
        if (s.aeAuto) refreshExposureCells()
        if (s.afContinuous) refreshFocus()
    }

    override fun onAfLocked(norm: Float) {
        s.afContinuous = false
        s.focusNorm = norm
        push()
    }

    override fun onPhotoSaved(name: String, thumb: Bitmap?) {
        shots++
        if (thumb != null) this.thumb.setImageBitmap(thumb)
        shotName.text = name
        shotCount.text = if (shots == 1) "1 foto nesta sessão" else "$shots fotos nesta sessão"
        refreshStorage()
    }

    override fun onPhotoError(msg: String) {
        toast(msg)
    }

    // ======================================================================= actions

    private fun onShutter() {
        if (!started || current == null) return
        closePicker()
        closeSettings()
        if (s.photo) {
            capture()
            return
        }
        if (!recording) {
            stopAudioMonitor()
            recBtn.isEnabled = false
            engine.startRecording(s.copy(), recordHint()) { ok ->
                recBtn.isEnabled = true
                if (ok) {
                    recording = true
                    recStart = SystemClock.elapsedRealtime()
                } else {
                    toast("Não foi possível iniciar a gravação")
                    startAudioMonitor()
                }
                updateRecUi()
            }
        } else {
            recBtn.isEnabled = false
            engine.stopRecording { name ->
                recBtn.isEnabled = true
                recording = false
                updateRecUi()
                startAudioMonitor()
                toast(if (name != null) "Vídeo salvo · $name" else "Falha ao salvar o vídeo")
                refreshStorage()
            }
        }
    }

    private fun recordHint(): Int {
        val sensor = try { engine.sensorOrientation(lens().id) } catch (e: Exception) { 90 }
        return (sensor - displayDegrees() + 360) % 360
    }

    private fun capture() {
        if (busyPhoto) return
        busyPhoto = true
        val evs = if (s.bracket) listOf(-s.brStep.toDouble(), 0.0, s.brStep.toDouble()) else listOf(0.0)
        fun fire() {
            countTv.visibility = View.GONE
            flash.alpha = 0.85f
            flash.animate().alpha(0f).setDuration(320).start()
            engine.takePhoto(s.copy(), evs, recordHint())
            val longMs = if (!s.aeAuto) (s.expNs / 1_000_000L) * evs.size else 0L
            ui.postDelayed({ busyPhoto = false }, 500 + longMs)
            if (s.bracket) toast("Bracket: 3 fotos (−${s.brStep} · 0 · +${s.brStep} EV)")
            else if (!s.aeAuto && s.expNs >= 66_000_000L) toast("Exposição longa: mantenha o celular parado")
        }
        if (s.timer <= 0) {
            fire()
            return
        }
        var left = s.timer
        countTv.visibility = View.VISIBLE
        val step = object : Runnable {
            override fun run() {
                if (left <= 0) {
                    fire()
                    return
                }
                countTv.text = left.toString()
                left--
                ui.postDelayed(this, 1000)
            }
        }
        ui.post(step)
    }

    private fun setMode(photo: Boolean) {
        if (recording) {
            toast("Pare a gravação antes de trocar de modo")
            return
        }
        val m = if (photo) Settings.MODE_PHOTO else Settings.MODE_VIDEO
        if (s.mode == m) return
        s.mode = m
        closePicker()
        closeSettings()
        toast(if (photo) "Modo FOTO" else "Modo VÍDEO")
        push()
        requestConfig()
    }

    private fun selectLens(i: Int) {
        if (i == s.lens) return
        val old = lens()
        val new = lenses[i]
        if (recording && new.id != old.id) {
            toast("Pare a gravação para trocar de lente")
            return
        }
        s.lens = i
        refreshUi()
        if (new.id == old.id && current != null) engine.setZoom(new.zoom, s.copy()) else requestConfig()
    }

    private fun onTapPreview(x: Float, y: Float) {
        if (picker.visibility == View.VISIBLE) { closePicker(); return }
        if (settingsPanel.visibility == View.VISIBLE) { closeSettings(); return }
        overlay.reticle(x, y)
        var nx = x / overlay.width
        var ny = y / overlay.height
        if (overlay.rot180) {
            nx = 1 - nx
            ny = 1 - ny
        }
        engine.focusAt(nx, ny, s.copy())
    }

    // ======================================================================= pickers

    private val ISO_LIST = intArrayOf(50, 64, 80, 100, 125, 160, 200, 250, 320, 400, 500, 640, 800, 1000, 1250, 1600, 2000, 2500, 3200, 4000, 5000, 6400)
    private val ANGLES = doubleArrayOf(45.0, 90.0, 144.0, 172.8, 180.0, 270.0, 360.0)
    private val SPEEDS = intArrayOf(8000, 4000, 2000, 1000, 500, 250, 125, 60, 30, 15, 8, 4, 2)
    private val KELVINS = intArrayOf(2500, 2800, 3200, 3600, 4000, 4500, 5000, 5600, 6000, 6500, 7000, 7500, 8000, 10000)

    private data class Opt(val value: String, val sub: String, val active: Boolean, val act: () -> Unit)

    private fun curExp(): Long = if (s.aeAuto) lastExp else s.expNs
    private fun curIso(): Int = if (s.aeAuto) lastIso else s.iso

    private fun toManual() {
        if (!s.aeAuto) return
        s.aeAuto = false
        if (lastIso > 0) s.iso = lastIso
        if (lastExp > 0) s.expNs = lastExp
        if (!s.photo) s.expNs = s.expNs.coerceAtMost(1_000_000_000L / s.fps)
    }

    private fun options(key: String): List<Opt> {
        val id = lens().id
        return when (key) {
            "fps" -> listOf(24, 25, 30, 60).map { f ->
                Opt("$f", when (f) { 24 -> "cinema"; 25 -> "PAL"; 30 -> "padrão"; else -> "só 1080p" }, s.fps == f) {
                    s.fps = f
                    var reconfig = false
                    if (f == 60 && s.res4k) {
                        s.res4k = false
                        reconfig = true
                        toast("60 fps grava em 1080p")
                    }
                    if (!s.aeAuto) s.expNs = s.expNs.coerceAtMost(1_000_000_000L / f)
                    push()
                    if (reconfig) requestConfig()
                }
            }
            "shutter" -> listOf(Opt("AUTO", "automático", s.aeAuto) { s.aeAuto = true; push() }) +
                ANGLES.map { a ->
                    val ns = (a / 360.0 / s.fps * 1e9).roundToLong()
                    Opt(fmtAngle(a), "1/" + (1e9 / ns).roundToInt(), !s.aeAuto && abs(s.expNs - ns) < ns * 0.02) {
                        toManual(); s.expNs = ns; push()
                    }
                }
            "pshut" -> {
                val maxNs = engine.expRange(id)?.upper ?: 500_000_000L
                listOf(Opt("AUTO", "automático", s.aeAuto) { s.aeAuto = true; push() }) +
                    SPEEDS.map { d -> d to 1_000_000_000L / d }.filter { it.second <= maxNs }.map { (d, ns) ->
                        Opt("1/$d", if (d <= 15) "use tripé" else "", !s.aeAuto && abs(s.expNs - ns) < ns * 0.02) {
                            toManual(); s.expNs = ns; push()
                        }
                    }
            }
            "iso" -> {
                val r = engine.isoRange(id)
                listOf(Opt("AUTO", "automático", s.aeAuto) { s.aeAuto = true; push() }) +
                    ISO_LIST.filter { r == null || it in r.lower..r.upper }.map { v ->
                        Opt("$v", if (r != null && v == r.lower) "mín." else if (r != null && v == r.upper) "máx." else "", !s.aeAuto && s.iso == v) {
                            toManual(); s.iso = v; push()
                        }
                    }
            }
            "wb" -> listOf(Opt("AUTO", "automático", s.wbAuto) { s.wbAuto = true; push() }) +
                KELVINS.map { k ->
                    Opt("${k}K", wbName(k), !s.wbAuto && s.wbK == k) { s.wbAuto = false; s.wbK = k; push() }
                }
            "tint" -> (-20..20 step 5).map { t ->
                Opt(if (t > 0) "+$t" else "$t", if (t < 0) "verde" else if (t > 0) "magenta" else "neutro", s.tint == t) {
                    s.tint = t; push()
                }
            }
            "fmt" -> {
                val raw = engine.hasCap(id, CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_RAW)
                val list = mutableListOf<Opt>()
                if (raw) list += Opt("RAW+JPG", "DNG + JPG", s.fmt == Settings.FMT_RAW_JPEG) { s.fmt = Settings.FMT_RAW_JPEG; push(); requestConfig() }
                list += Opt("JPEG", "arquivo leve", s.fmt == Settings.FMT_JPEG) { s.fmt = Settings.FMT_JPEG; push(); requestConfig() }
                if (raw) list += Opt("RAW", "DNG", s.fmt == Settings.FMT_RAW) { s.fmt = Settings.FMT_RAW; push(); requestConfig() }
                list
            }
            else -> emptyList()
        }
    }

    private fun pickerTitleFor(key: String) = when (key) {
        "fps" -> "Quadros por segundo"
        "shutter" -> "Obturador · ângulo"
        "pshut" -> "Velocidade do obturador"
        "iso" -> "ISO"
        "wb" -> "Balanço de branco"
        "tint" -> "Tint · verde ↔ magenta"
        "fmt" -> "Formato do arquivo"
        else -> ""
    }

    private fun openPicker(key: String) {
        if (!started || lenses.isEmpty()) return
        if (pickerKey == key) {
            closePicker()
            return
        }
        if (key == "tint" && s.wbAuto) {
            toast("Escolha um WB manual para ajustar o Tint")
            return
        }
        if (recording && (key == "fps" || key == "fmt")) {
            toast("Pare a gravação para mudar isto")
            return
        }
        closeSettings()
        pickerKey = key
        pickerTitle.text = pickerTitleFor(key)
        renderChips()
        picker.visibility = View.VISIBLE
        refreshCells()
    }

    private fun renderChips() {
        val key = pickerKey ?: return
        chips.removeAllViews()
        var activeView: View? = null
        for (o in options(key)) {
            val v = chip(o.value, o.sub, o.active) {
                o.act()
                renderChips()
            }
            chips.addView(v)
            if (o.active) activeView = v
        }
        activeView?.let { av -> pickerScroll.post { pickerScroll.smoothScrollTo((av.left - pickerScroll.width / 2 + av.width / 2).coerceAtLeast(0), 0) } }
    }

    private fun closePicker() {
        pickerKey = null
        picker.visibility = View.GONE
        refreshCells()
    }

    // ======================================================================= settings panel

    private data class Row(val label: String, val opts: List<String>, val sel: Int, val pick: (Int) -> Unit)

    private val TABS = listOf("Vídeo", "Foto", "Monitor", "Sobre")

    private fun rows(t: Int): List<Row> = when (t) {
        0 -> listOf(
            Row("Resolução", listOf("4K UHD", "1080p HD"), if (s.res4k) 0 else 1) {
                if (blockIfRec()) return@Row
                s.res4k = it == 0
                if (s.res4k && s.fps == 60) { s.fps = 30; toast("4K grava até 30 fps") }
                push(); requestConfig()
            },
            Row("Codec", listOf("H.265 (HEVC)", "H.264"), if (s.hevc) 0 else 1) { if (!blockIfRec()) { s.hevc = it == 0; push() } },
            Row("Bitrate", listOf("50 Mb/s", "100 Mb/s", "150 Mb/s"), listOf(50, 100, 150).indexOf(s.bitrate).coerceAtLeast(0)) {
                if (!blockIfRec()) { s.bitrate = listOf(50, 100, 150)[it]; push() }
            },
            Row("Perfil de cor", listOf("Padrão", "LOG"), if (s.log) 1 else 0) { s.log = it == 1; push() },
            Row("Gravar áudio", listOf("Ligado", "Desligado"), if (s.audio) 0 else 1) { if (!blockIfRec()) { s.audio = it == 0; push() } },
            Row("Estabilização óptica", listOf("Ligada", "Desligada"), if (s.ois) 0 else 1) { s.ois = it == 0; push() },
        )
        1 -> listOf(
            Row("Proporção", listOf("4:3", "16:9", "1:1"), s.aspect) { s.aspect = it; push(); requestConfig() },
            Row("Timer", listOf("Desligado", "2 s", "10 s"), listOf(0, 2, 10).indexOf(s.timer).coerceAtLeast(0)) { s.timer = listOf(0, 2, 10)[it]; push() },
            Row("Bracketing (3 fotos)", listOf("Desligado", "Ligado"), if (s.bracket) 1 else 0) { s.bracket = it == 1; push() },
            Row("Intervalo do bracket", listOf("±1 EV", "±2 EV"), s.brStep - 1) { s.brStep = it + 1; push() },
        )
        2 -> listOf(
            Row("Nível da zebra", listOf("90%", "95%", "100%"), listOf(90, 95, 100).indexOf(s.zebraLvl).coerceAtLeast(0)) { s.zebraLvl = listOf(90, 95, 100)[it]; push() },
            Row("Cor do peaking", listOf("Âmbar", "Vermelho", "Branco"), s.peakColor) { s.peakColor = it; push() },
            Row("Guia de enquadramento", listOf("Nenhum", "2.39:1", "1.85:1", "9:16"), s.guide) { s.guide = it; push() },
            Row("Grade de terços", listOf("Ligada", "Desligada"), if (s.grid) 0 else 1) { s.grid = it == 0; push() },
        )
        else -> emptyList()
    }

    private fun blockIfRec(): Boolean {
        if (recording) toast("Pare a gravação para mudar isto")
        return recording
    }

    private fun openSettings() {
        closePicker()
        if (settingsPanel.visibility == View.VISIBLE) {
            closeSettings()
            return
        }
        if (s.photo && tab == 0) tab = 1
        if (!s.photo && tab == 1) tab = 0
        renderSettings()
        settingsPanel.visibility = View.VISIBLE
    }

    private fun closeSettings() {
        settingsPanel.visibility = View.GONE
    }

    private fun renderSettings() {
        tabs.removeAllViews()
        TABS.forEachIndexed { i, name ->
            val t = text(name, 15f, if (i == tab) C.FG else C.DIM, true)
            t.setPadding(dp(14), dp(12), dp(14), dp(12))
            if (i == tab) t.background = GradientDrawable().apply { setColor(0x0AFFFFFF) }
            t.setOnClickListener { tab = i; renderSettings() }
            val wrap = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(View(this@MainActivity).apply { setBackgroundColor(if (i == tab) C.ACCENT else 0) }, LinearLayout.LayoutParams(dp(3), ViewGroup.LayoutParams.MATCH_PARENT))
                addView(t, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            }
            tabs.addView(wrap)
        }
        sbody.removeAllViews()
        val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        head.addView(text(TABS[tab], 18f, C.FG, true), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        head.addView(text("✕", 18f, C.DIM, true).apply { setPadding(dp(10), dp(4), dp(4), dp(4)); setOnClickListener { closeSettings() } })
        sbody.addView(head)
        if (tab == 3) {
            sbody.addView(text("DarkCamera OpenSource · versão 0.1.0", 14f, C.ACCENT, true), lp(top = 10))
            sbody.addView(text("Criado por Rômulo Innocêncio", 15f, C.FG, true), lp(top = 8))
            sbody.addView(text("github.com/romuloinnocencio · Licença MIT", 13f, C.DIM, false), lp(top = 4))
            val info = try { engine.info(lens().id) } catch (e: Exception) { emptyList() }
            for ((k, v) in info) {
                val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
                r.addView(text(k, 14f, C.DIM, false), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                r.addView(text(v, 14f, C.FG, true))
                sbody.addView(r, lp(top = 8))
            }
            sbody.addView(text("Dica: os botões de volume também gravam e fotografam.", 13f, C.DIM, false), lp(top = 14))
            return
        }
        if ((tab == 0 && s.photo) || (tab == 1 && !s.photo)) {
            sbody.addView(text(if (tab == 0) "Estas opções valem no modo VÍDEO." else "Estas opções valem no modo FOTO.", 13f, C.DIM, false), lp(top = 6))
        }
        for (row in rows(tab)) {
            val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            r.addView(text(row.label, 15f, C.FG, false), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            val seg = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                background = round(C.CHIP, C.LINE, 7f)
                clipToOutline = true
            }
            row.opts.forEachIndexed { i, label ->
                val b = text(label, 14f, if (i == row.sel) C.INK else C.FG, i == row.sel)
                b.setPadding(dp(11), dp(7), dp(11), dp(7))
                if (i == row.sel) b.background = round(C.ACCENT, 0, 7f)
                b.setOnClickListener { row.pick(i); renderSettings() }
                seg.addView(b)
            }
            r.addView(seg)
            sbody.addView(r, lp(top = 12))
        }
    }

    // ======================================================================= UI refresh

    private fun fmtAngle(a: Double) = if (a % 1.0 == 0.0) "${a.toInt()}°" else String.format("%.1f°", a)

    private fun wbName(k: Int) = when (k) {
        3200 -> "tungstênio"; 4000 -> "fluoresc."; 5600 -> "luz do dia"; 6500 -> "nublado"; 7500 -> "sombra"; else -> ""
    }

    private fun refreshUi() {
        val photo = s.photo
        modeVideo.background = if (!photo) round(C.FG, 0, 6f) else null
        modeVideo.setTextColor(if (!photo) C.INK else C.DIM)
        modePhoto.background = if (photo) round(C.FG, 0, 6f) else null
        modePhoto.setTextColor(if (photo) C.INK else C.DIM)
        recBtn.photo = photo
        tLog.visibility = if (photo) View.GONE else View.VISIBLE
        tBrkt.visibility = if (photo) View.VISIBLE else View.GONE
        timerChip.visibility = if (photo) View.VISIBLE else View.GONE
        audioCell.visibility = if (photo) View.GONE else View.VISIBLE
        photoCell.visibility = if (photo) View.VISIBLE else View.GONE
        toggle(tZebra, s.zebra); toggle(tPeak, s.peak); toggle(tGrid, s.grid); toggle(tLog, s.log); toggle(tBrkt, s.bracket)
        timerChip.text = if (s.timer > 0) "TIMER ${s.timer}s" else "TIMER OFF"
        toggle(timerChip, s.timer > 0)
        toggle(afChip, s.afContinuous)
        afChip.text = if (s.afContinuous) "AF-C" else "AF"
        for (i in 0 until lensBox.childCount) {
            val v = lensBox.getChildAt(i) as TextView
            val on = i == s.lens
            v.background = round(C.PANEL, if (on) C.ACCENT else C.LINE, 100f)
            v.setTextColor(if (on) C.ACCENT else C.FG)
        }
        if (photo) {
            iRes.text = "FOTO · ${(current?.jpeg?.let { it.width.toLong() * it.height / 1_000_000 } ?: 12)} MP · ${listOf("4:3", "16:9", "1:1")[s.aspect]}"
            iFmt.text = listOf("RAW (DNG) + JPEG", "JPEG", "RAW (DNG)")[s.fmt] + " · Timer " + (if (s.timer > 0) "${s.timer} s" else "off") +
                " · Bracket " + (if (s.bracket) "±${s.brStep} EV" else "off")
        } else {
            iRes.text = (if (s.res4k) "4K UHD" else "1080p HD") + " · ${s.fps}p"
            iFmt.text = (if (s.hevc) "H.265" else "H.264") + " · ${s.bitrate} Mb/s · " + (if (s.log) "LOG" else "Padrão")
        }
        refreshCells()
        refreshFocus()
        refreshStorage()
        updateRecUi()
    }

    private fun refreshCells() {
        val photo = s.photo
        cA.label.text = if (photo) "FORMATO" else "FPS"
        cA.value.text = if (photo) listOf("RAW+JPG", "JPEG", "RAW")[s.fmt] else "${s.fps}"
        cA.sub.text = if (photo) listOf("4:3", "16:9", "1:1")[s.aspect] else if (s.res4k) "4K UHD" else "1080p HD"
        refreshExposureCells()
        cWb.value.text = if (s.wbAuto) "AWB" else "${s.wbK}K"
        cWb.sub.text = if (s.wbAuto) "automático" else wbName(s.wbK).ifEmpty { "manual" }
        cTint.value.text = if (s.wbAuto) "—" else if (s.tint > 0) "+${s.tint}" else "${s.tint}"
        cTint.sub.text = if (s.wbAuto) "use WB manual" else if (s.tint < 0) "verde" else if (s.tint > 0) "magenta" else "neutro"
        val keyA = if (photo) "fmt" else "fps"
        val keyB = if (photo) "pshut" else "shutter"
        highlight(cA, pickerKey == keyA)
        highlight(cB, pickerKey == keyB)
        highlight(cIso, pickerKey == "iso")
        highlight(cWb, pickerKey == "wb")
        highlight(cTint, pickerKey == "tint")
    }

    private fun refreshExposureCells() {
        val exp = curExp()
        val iso = curIso()
        if (s.photo) {
            cB.value.text = if (exp > 0) shutterFraction(exp) else "—"
            cB.sub.text = if (s.aeAuto) "auto" else if (exp >= 66_000_000L) "use tripé" else "velocidade"
        } else {
            val angle = exp * s.fps * 360.0 / 1e9
            cB.value.text = if (exp > 0) fmtAngle((angle * 10).roundToInt() / 10.0) else "—"
            cB.sub.text = (if (s.aeAuto) "auto · " else "") + if (exp > 0) shutterFraction(exp) else ""
        }
        cIso.value.text = if (iso > 0) "$iso" else "—"
        cIso.sub.text = if (s.aeAuto) "auto" else if (iso >= 1600) "ruído alto" else if (iso <= 100) "limpo" else "ganho"
        val auto = s.aeAuto
        if (pickerKey != "iso") cIso.value.setTextColor(if (auto) C.DIM else C.FG)
        val keyB = if (s.photo) "pshut" else "shutter"
        if (pickerKey != keyB) cB.value.setTextColor(if (auto) C.DIM else C.FG)
    }

    private fun shutterFraction(ns: Long): String =
        if (ns >= 400_000_000L) String.format("%.1f s", ns / 1e9) else "1/" + (1e9 / ns).roundToInt()

    private fun highlight(c: Cell, on: Boolean) {
        c.box.setBackgroundColor(if (on) 0x1AF3A738 else 0)
        c.label.setTextColor(if (on) C.ACCENT else C.DIM)
        if (on) c.value.setTextColor(C.ACCENT) else if (c !== cIso && c !== cB) c.value.setTextColor(C.FG)
        if (c === cTint && s.wbAuto) c.value.setTextColor(C.DIM)
    }

    private fun refreshFocus() {
        val minF = try { engine.minFocus(lens().id) } catch (e: Exception) { 0f }
        if (s.afContinuous) {
            focusTxt.text = distText(lastFocusD)
            if (minF > 0) slider.value = (lastFocusD / minF).coerceIn(0f, 1f)
        } else {
            focusTxt.text = distText(s.focusNorm * minF)
            slider.value = s.focusNorm
        }
        slider.active = !s.afContinuous
        focusTxt.setTextColor(if (s.afContinuous) C.FG else C.ACCENT)
    }

    private fun distText(diopters: Float): String {
        if (diopters <= 0.02f) return "∞"
        val m = 1f / diopters
        return if (m >= 10) "${m.roundToInt()} m" else String.format("%.1f m", m).replace('.', ',')
    }

    private fun updateEv() {
        val lin = (meanLuma / 255.0).pow(2.2)
        val ev = if (lin > 0) log2(lin / 0.18) else -9.0
        evTxt.text = (if (ev >= 0.05) "+" else if (ev <= -0.05) "−" else "±") + String.format("%.1f", abs(ev))
        evTxt.setTextColor(if (abs(ev) > 1.5) C.WARN else C.FG)
    }

    private fun updateRecUi() {
        recBtn.recording = recording
        recFrame.visibility = if (recording) View.VISIBLE else View.GONE
        if (s.photo) {
            tcLab.text = "FOTOS RESTANTES"
            tc.setTextColor(C.FG)
        } else {
            tcLab.text = if (recording) "● REC" else "STANDBY"
            tcLab.setTextColor(if (recording) C.REC else C.DIM)
            tc.setTextColor(if (recording) C.REC else C.FG)
            if (!recording) tc.text = "00:00:00:00"
        }
        refreshStorage()
    }

    private fun photoMb(): Double = when (s.fmt) { Settings.FMT_JPEG -> 4.5; Settings.FMT_RAW -> 23.5; else -> 28.0 }

    private fun refreshStorage() {
        val free = Store.freeBytes()
        val gb = free / 1e9
        freeTxt.text = String.format("%.1f GB livres", gb).replace('.', ',')
        if (s.photo) {
            val n = (free / 1e6 / photoMb()).toLong()
            tc.text = String.format("%,d", n).replace(',', '.')
            remain.text = "≈ " + String.format("%.1f", photoMb()).replace('.', ',') + " MB/foto"
        } else {
            val minutes = (free * 8 / 1e6 / s.bitrate / 60).toLong()
            remain.text = (if (minutes >= 60) "${minutes / 60}h " else "") + String.format("%02dmin", minutes % 60)
        }
    }

    private val ticker = object : Runnable {
        var n = 0
        override fun run() {
            n++
            if (recording) {
                val el = SystemClock.elapsedRealtime() - recStart
                val fr = (el * s.fps / 1000) % s.fps
                val sec = el / 1000
                tc.text = String.format("%02d:%02d:%02d:%02d", sec / 3600, (sec / 60) % 60, sec % 60, fr)
                val a = AudioMonitor.norm(engine.amplitude())
                if (a > 0) { lvlL = a; lvlR = a } else { lvlL *= 0.85f; lvlR *= 0.85f }
                if (s.audio) meter.set(lvlL, lvlR) else meter.set(0f, 0f)
                tcLab.alpha = if ((sec % 2L) == 0L) 1f else 0.35f
            } else {
                tcLab.alpha = 1f
            }
            if (n % 50 == 0) {
                refreshStorage()
                updateBattery()
            }
            ui.postDelayed(this, 80)
        }
    }

    private fun updateBattery() {
        val bm = getSystemService(BatteryManager::class.java)
        val pct = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
        if (pct >= 0) {
            battery.level = pct / 100f
            batteryTxt.text = "$pct%"
        }
    }

    private fun startAudioMonitor() {
        if (recording || audio != null) return
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return
        audio = AudioMonitor { l, r -> ui.post { if (!recording) meter.set(l, r) } }.also { it.start() }
    }

    private fun stopAudioMonitor() {
        audio?.stop()
        audio = null
    }

    private fun toggle(t: TextView, on: Boolean) {
        t.background = round(if (on) C.ACCENT else C.PANEL, if (on) C.ACCENT else C.LINE, 6f)
        t.setTextColor(if (on) C.INK else C.FG)
    }

    private var toastSeq = 0
    private fun toast(msg: String) {
        toastTv.text = msg
        toastTv.visibility = View.VISIBLE
        toastTv.alpha = 0f
        toastTv.animate().alpha(1f).setDuration(160).start()
        val my = ++toastSeq
        ui.postDelayed({ if (my == toastSeq) toastTv.animate().alpha(0f).setDuration(250).withEndAction { toastTv.visibility = View.GONE }.start() }, 2400)
    }

    // ======================================================================= UI construction

    private fun dp(v: Int) = (v * resources.displayMetrics.density).roundToInt()

    private fun round(fill: Int, stroke: Int, radiusDp: Float) = GradientDrawable().apply {
        setColor(fill)
        cornerRadius = radiusDp * resources.displayMetrics.density
        if (stroke != 0) setStroke(dp(1), stroke)
    }

    private fun text(t: String, sp: Float, color: Int, bold: Boolean, mono: Boolean = false) = TextView(this).apply {
        text = t
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        setTextColor(color)
        typeface = if (mono) C.MONO else if (bold) C.COND_BOLD else C.COND
        includeFontPadding = false
        isSingleLine = true
    }

    private fun lp(w: Int = ViewGroup.LayoutParams.WRAP_CONTENT, h: Int = ViewGroup.LayoutParams.WRAP_CONTENT, top: Int = 0) =
        LinearLayout.LayoutParams(w, h).apply { topMargin = dp(top) }

    private fun chip(value: String, sub: String, active: Boolean, onClick: () -> Unit): View {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            minimumWidth = dp(64)
            setPadding(dp(12), dp(7), dp(12), dp(7))
            background = round(if (active) C.ACCENT else C.CHIP, if (active) C.ACCENT else C.LINE, 7f)
            setOnClickListener { onClick() }
        }
        box.addView(text(value, 18f, if (active) C.INK else C.FG, true))
        box.addView(text(sub.ifEmpty { " " }, 10f, if (active) 0xFF3A2A0C.toInt() else C.DIM, false), lp(top = 2))
        return box.also {
            it.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { rightMargin = dp(7) }
        }
    }

    private fun railChip(label: String, onClick: () -> Unit) = text(label, 12f, C.FG, true).apply {
        gravity = Gravity.CENTER
        letterSpacing = 0.08f
        setPadding(dp(6), dp(6), dp(6), dp(6))
        background = round(C.PANEL, C.LINE, 6f)
        setOnClickListener { onClick() }
    }

    private fun cell(key: () -> String, label: String): Cell {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setOnClickListener { openPicker(key()) }
        }
        val l = text(label, 10f, C.DIM, false).apply { letterSpacing = 0.16f }
        val v = text("—", 19f, C.FG, true)
        val sb = text("", 10f, C.DIM, false)
        box.addView(l)
        box.addView(v, lp(top = 1))
        box.addView(sb, lp(top = 1))
        return Cell(box, l, v, sb)
    }

    private fun sep() = View(this).apply { setBackgroundColor(C.LINE) }

    private fun buildUi() {
        val TOP = dp(52)
        val BOT = dp(62)
        val RAIL = dp(82)
        root = FrameLayout(this).apply { setBackgroundColor(0xFF000000.toInt()) }

        // ---- preview
        aspect = AspectFrame(this)
        surface = SurfaceView(this)
        surface.holder.addCallback(this)
        overlay = OverlayView(this)
        overlay.setOnTouchListener { _, e ->
            if (e.actionMasked == MotionEvent.ACTION_UP) onTapPreview(e.x, e.y)
            true
        }
        aspect.addView(surface, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        aspect.addView(overlay, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        root.addView(aspect, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER))

        recFrame = View(this).apply { background = GradientDrawable().apply { setColor(0); setStroke(dp(3), C.REC) }; visibility = View.GONE }
        root.addView(recFrame, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        flash = View(this).apply { setBackgroundColor(0xFFFFFFFF.toInt()); alpha = 0f; isClickable = false }
        root.addView(flash, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        // ---- top bar
        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setBackgroundColor(C.PANEL) }
        val info = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(14), 0, dp(8), 0) }
        iRes = text("4K UHD · 30p", 15f, C.FG, true)
        iFmt = text("", 11f, C.DIM, false)
        info.addView(iRes)
        info.addView(iFmt, lp(top = 4))
        top.addView(info, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1.5f))
        cA = cell({ if (s.photo) "fmt" else "fps" }, "FPS")
        cB = cell({ if (s.photo) "pshut" else "shutter" }, "SHUTTER")
        cIso = cell({ "iso" }, "ISO")
        cWb = cell({ "wb" }, "WB")
        cTint = cell({ "tint" }, "TINT")
        for (c in listOf(cA, cB, cIso, cWb, cTint)) {
            top.addView(sep(), LinearLayout.LayoutParams(dp(1), ViewGroup.LayoutParams.MATCH_PARENT))
            top.addView(c.box, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        }
        top.addView(sep(), LinearLayout.LayoutParams(dp(1), ViewGroup.LayoutParams.MATCH_PARENT))
        val batt = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER }
        battery = BatteryView(this)
        batteryTxt = text("—", 11f, C.DIM, false)
        batt.addView(battery, LinearLayout.LayoutParams(dp(30), dp(14)))
        batt.addView(batteryTxt, lp(top = 4))
        top.addView(batt, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 0.8f))
        root.addView(top, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, TOP, Gravity.TOP))
        root.addView(sep(), FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1), Gravity.TOP).apply { topMargin = TOP })

        // ---- left rail
        val left = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, dp(6), 0, dp(6))
            background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(0x8C000000.toInt(), 0))
        }
        lensBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
        left.addView(lensBox)
        focusTxt = text("∞", 12f, C.FG, true, mono = true)
        left.addView(focusTxt, lp(top = 6))
        slider = VerticalSlider(this)
        slider.onChange = { v ->
            s.afContinuous = false
            s.focusNorm = v
            push()
        }
        left.addView(slider, LinearLayout.LayoutParams(dp(40), 0, 1f).apply { topMargin = dp(2) })
        timerChip = railChip("TIMER OFF") {
            s.timer = when (s.timer) { 0 -> 2; 2 -> 10; else -> 0 }
            push()
        }
        left.addView(timerChip, LinearLayout.LayoutParams(dp(62), ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(4) })
        afChip = railChip("AF") {
            s.afContinuous = !s.afContinuous
            if (!s.afContinuous) {
                val minF = engine.minFocus(lens().id)
                if (minF > 0) s.focusNorm = (lastFocusD / minF).coerceIn(0f, 1f)
            }
            toast(if (s.afContinuous) "Foco automático contínuo" else "Foco manual")
            push()
        }
        left.addView(afChip, LinearLayout.LayoutParams(dp(62), ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(4) })
        root.addView(left, FrameLayout.LayoutParams(RAIL, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.START).apply { topMargin = TOP + dp(1); bottomMargin = BOT })

        // ---- right rail
        val right = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, dp(6), 0, dp(6))
            background = GradientDrawable(GradientDrawable.Orientation.RIGHT_LEFT, intArrayOf(0x8C000000.toInt(), 0))
        }
        val modeBox = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; background = round(C.PANEL, C.LINE, 6f); setPadding(dp(2), dp(2), dp(2), dp(2)) }
        modeVideo = text("VÍDEO", 11f, C.INK, true).apply { setPadding(dp(6), dp(5), dp(6), dp(5)); setOnClickListener { setMode(false) } }
        modePhoto = text("FOTO", 11f, C.DIM, true).apply { setPadding(dp(6), dp(5), dp(6), dp(5)); setOnClickListener { setMode(true) } }
        modeBox.addView(modeVideo)
        modeBox.addView(modePhoto)
        right.addView(modeBox)
        recBtn = RecButton(this).apply { isClickable = true; setOnClickListener { onShutter() } }
        right.addView(recBtn, LinearLayout.LayoutParams(dp(56), dp(56)).apply { topMargin = dp(8) })
        val toggles = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        fun tl(label: String, f: () -> Unit) = railChip(label, f).also { toggles.addView(it, LinearLayout.LayoutParams(dp(62), ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(4) }) }
        tZebra = tl("ZEBRA") { s.zebra = !s.zebra; push() }
        tPeak = tl("PEAK") { s.peak = !s.peak; push() }
        tGrid = tl("GRADE") { s.grid = !s.grid; push() }
        tLog = tl("LOG") { s.log = !s.log; toast(if (s.log) "Perfil LOG ligado" else "Perfil padrão"); push() }
        tBrkt = tl("BRKT") { s.bracket = !s.bracket; toast(if (s.bracket) "Bracketing ligado" else "Bracketing desligado"); push() }
        right.addView(toggles, lp(top = 2))
        right.addView(View(this), LinearLayout.LayoutParams(1, 0, 1f))
        val gear = GearView(this).apply { isClickable = true; setOnClickListener { openSettings() } }
        right.addView(gear, LinearLayout.LayoutParams(dp(36), dp(36)))
        root.addView(right, FrameLayout.LayoutParams(RAIL, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.END).apply { topMargin = TOP + dp(1); bottomMargin = BOT })

        // ---- bottom bar
        val bot = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setBackgroundColor(C.PANEL) }
        fun bcell(weight: Float = 0f, width: Int = ViewGroup.LayoutParams.WRAP_CONTENT): LinearLayout {
            val c = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(12), 0, dp(12), 0) }
            if (bot.childCount > 0) bot.addView(sep(), LinearLayout.LayoutParams(dp(1), ViewGroup.LayoutParams.MATCH_PARENT))
            bot.addView(c, if (weight > 0) LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, weight) else LinearLayout.LayoutParams(width, ViewGroup.LayoutParams.MATCH_PARENT))
            return c
        }
        val hc = bcell()
        hist = HistogramView(this)
        hc.addView(hist, LinearLayout.LayoutParams(dp(124), dp(40)))
        val ec = bcell()
        ec.addView(text("EXPOSIÇÃO", 10f, C.DIM, false).apply { letterSpacing = 0.16f })
        evTxt = text("±0.0", 17f, C.FG, true, mono = true)
        ec.addView(evTxt, lp(top = 4))
        audioCell = bcell(width = dp(150))
        meter = MeterView(this)
        audioCell.addView(meter, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(30)))
        audioCell.addView(text("ÁUDIO · 48 kHz", 10f, C.DIM, false, mono = false).apply { letterSpacing = 0.12f }, lp(top = 3))
        photoCell = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(12), 0, dp(12), 0) }
        bot.addView(photoCell, LinearLayout.LayoutParams(dp(190), ViewGroup.LayoutParams.MATCH_PARENT))
        thumb = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP; background = round(C.CHIP, C.LINE, 4f); clipToOutline = true }
        photoCell.addView(thumb, LinearLayout.LayoutParams(dp(60), dp(45)))
        val pcText = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(10), 0, 0, 0) }
        pcText.addView(text("ÚLTIMA FOTO", 10f, C.DIM, false).apply { letterSpacing = 0.16f })
        shotName = text("—", 13f, C.FG, true)
        shotCount = text("nenhuma ainda", 11f, C.DIM, false)
        pcText.addView(shotName, lp(top = 3))
        pcText.addView(shotCount, lp(top = 2))
        photoCell.addView(pcText)
        val tcc = bcell(weight = 1f)
        tcc.gravity = Gravity.CENTER
        tcLab = text("STANDBY", 10f, C.DIM, false).apply { letterSpacing = 0.16f }
        tc = text("00:00:00:00", 24f, C.FG, true, mono = true)
        tcc.addView(tcLab)
        tcc.addView(tc, lp(top = 3))
        val sc = bcell()
        sc.addView(text("ARMAZENAMENTO", 10f, C.DIM, false).apply { letterSpacing = 0.16f })
        remain = text("—", 15f, C.FG, true)
        freeTxt = text("", 11f, C.DIM, false)
        sc.addView(remain, lp(top = 3))
        sc.addView(freeTxt, lp(top = 2))
        root.addView(bot, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, BOT, Gravity.BOTTOM))
        root.addView(sep(), FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1), Gravity.BOTTOM).apply { bottomMargin = BOT })

        // ---- picker
        picker = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = round(C.PANEL_SOLID, C.LINE, 10f)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            visibility = View.GONE
            isClickable = true
        }
        val ph = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        pickerTitle = text("", 14f, C.FG, true)
        ph.addView(pickerTitle, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        ph.addView(text("✕", 16f, C.DIM, true).apply { setPadding(dp(10), dp(2), dp(2), dp(2)); setOnClickListener { closePicker() } })
        picker.addView(ph)
        pickerScroll = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false }
        chips = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        pickerScroll.addView(chips)
        picker.addView(pickerScroll, lp(top = 8))
        root.addView(picker, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM).apply {
            leftMargin = RAIL + dp(6); rightMargin = RAIL + dp(6); bottomMargin = BOT + dp(8)
        })

        // ---- settings panel
        settingsPanel = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = round(C.PANEL_SOLID, C.LINE, 10f)
            visibility = View.GONE
            isClickable = true
            clipToOutline = true
        }
        tabs = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(6), 0, dp(6)) }
        settingsPanel.addView(tabs, LinearLayout.LayoutParams(dp(130), ViewGroup.LayoutParams.MATCH_PARENT))
        settingsPanel.addView(sep(), LinearLayout.LayoutParams(dp(1), ViewGroup.LayoutParams.MATCH_PARENT))
        val scroll = ScrollView(this)
        sbody = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(12), dp(16), dp(16)) }
        scroll.addView(sbody)
        settingsPanel.addView(scroll, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        root.addView(settingsPanel, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT).apply {
            leftMargin = RAIL + dp(6); rightMargin = RAIL + dp(6); topMargin = TOP + dp(8); bottomMargin = BOT + dp(8)
        })

        // ---- overlays: countdown, toast, message
        countTv = text("", 110f, 0xEBFFFFFF.toInt(), true, mono = true).apply { gravity = Gravity.CENTER; visibility = View.GONE; setShadowLayer(18f, 0f, 0f, 0x99000000.toInt()) }
        root.addView(countTv, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        toastTv = text("", 14f, C.FG, false).apply {
            background = round(C.PANEL_SOLID, C.ACCENT, 8f)
            setPadding(dp(14), dp(8), dp(14), dp(8))
            visibility = View.GONE
        }
        root.addView(toastTv, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = TOP + dp(12) })
        messageTv = text("Iniciando a câmera…", 16f, C.FG, false).apply {
            isSingleLine = false
            gravity = Gravity.CENTER
            setPadding(dp(20), dp(14), dp(20), dp(14))
            background = round(C.PANEL_SOLID, C.LINE, 10f)
        }
        root.addView(messageTv, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))

        updateBattery()
    }

    private fun buildLensButtons() {
        lensBox.removeAllViews()
        lenses.forEachIndexed { i, l ->
            val b = text(l.label, 12f, C.FG, true).apply {
                gravity = Gravity.CENTER
                setOnClickListener { selectLens(i) }
            }
            lensBox.addView(b, LinearLayout.LayoutParams(dp(36), dp(36)).apply { topMargin = if (i == 0) 0 else dp(4) })
        }
    }
}
