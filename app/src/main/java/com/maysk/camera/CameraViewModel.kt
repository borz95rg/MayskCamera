package com.maysk.camera

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.hardware.camera2.CameraMetadata
import android.media.MediaActionSound
import android.net.Uri
import android.os.SystemClock
import androidx.camera.core.ImageCapture
import androidx.camera.extensions.ExtensionMode
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.viewModelScope
import com.maysk.camera.ai.AiEnhancer
import com.maysk.camera.ai.ImageOps
import com.maysk.camera.ai.LiveAnalyzer
import com.maysk.camera.ai.LiveScene
import com.maysk.camera.ai.PhotoPipeline
import com.maysk.camera.ai.PhotoSpec
import com.maysk.camera.ai.SceneType
import com.maysk.camera.camera.BindConfig
import com.maysk.camera.camera.CameraController
import com.maysk.camera.camera.CameraMode
import com.maysk.camera.camera.Effect
import com.maysk.camera.camera.Filters
import com.maysk.camera.camera.ManualSettings
import com.maysk.camera.camera.VoiceShutter
import com.maysk.camera.camera.gl.EffectParams
import com.maysk.camera.data.AppSettings
import com.maysk.camera.data.AudioMode
import com.maysk.camera.data.SettingsRepository
import com.maysk.camera.data.VideoRes
import com.maysk.camera.media.MediaStoreRepo
import com.maysk.camera.media.TimelapseEncoder
import com.maysk.camera.media.VideoTools
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.abs

enum class FlashMode(val captureMode: Int) { OFF(ImageCapture.FLASH_MODE_OFF), ON(ImageCapture.FLASH_MODE_ON), AUTO(ImageCapture.FLASH_MODE_AUTO) }

data class CameraUi(
    val mode: CameraMode = CameraMode.PHOTO,
    val front: Boolean = false,
    val filterId: String = "original",
    val filterIntensity: Float = 1f,
    val effect: Effect = Effect.NONE,
    val flash: FlashMode = FlashMode.OFF,
    val timer: Int = 0,
    val hdr: Boolean = false,
    val aiPro: Boolean = true,
    val beautyOn: Boolean = false,
    val ultraWide: Boolean = false,
    val busy: String? = null,
    val progress: Float? = null,
    val message: String? = null,
    val lastUri: Uri? = null,
    val lastIsVideo: Boolean = false,
    val countdown: Int = 0,
    val panoramaFrames: Int = 0,
    val panoramaActive: Boolean = false,
    val timelapseActive: Boolean = false,
    val timelapseFrames: Int = 0,
    val timelapseInterval: Float = 1f,
    val aiHint: String? = null,
    val aiActions: List<String> = emptyList(),
    val manual: ManualSettings = ManualSettings(),
    val voiceListening: Boolean = false,
    /** Белый экран как вспышка для селфи. */
    val screenFlash: Boolean = false,
    val burstCount: Int = 0,
    val updateAvailable: com.maysk.camera.data.UpdateInfo? = null,
    /** Прогресс загрузки обновления 0..1, null — не качается. */
    val updateProgress: Float? = null,
)

class CameraViewModel(app: Application) : AndroidViewModel(app) {
    private val ctx: Application get() = getApplication()
    val settingsRepo = SettingsRepository(app)
    val settings: StateFlow<AppSettings> = settingsRepo.flow.stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())

    val controller = CameraController(app)
    val cam = controller.state

    private val _ui = MutableStateFlow(CameraUi())
    val ui: StateFlow<CameraUi> = _ui
    val live = MutableStateFlow(LiveScene())

    /** Внешние нажатия затвора (громкость, голос). */
    val shutterEvents = MutableSharedFlow<Unit>(extraBufferCapacity = 2)

    private var owner: LifecycleOwner? = null
    private var previewView: PreviewView? = null
    private val sound = MediaActionSound()
    private var voice: VoiceShutter? = null
    private var captureJob: Job? = null
    private var lastFaceFocus = 0L
    private var lastAiEv = 0L
    private var aiEvOffset = 0
    // сглаженные AI-параметры (без «прыжков» света)
    private var faceDarkState = false
    private var smFaceLight = 0f
    private var smGain = 1f
    private var smDenoise = 0f
    private var smLift = 0f
    private var lastFocusX = -1f
    private var lastFocusY = -1f

    init {
        // тихая проверка обновлений при запуске
        viewModelScope.launch {
            delay(2500)
            val r = com.maysk.camera.data.Updater.check()
            if (r is com.maysk.camera.data.UpdateCheck.Available) _ui.update { it.copy(updateAvailable = r.info) }
        }
        sound.load(MediaActionSound.SHUTTER_CLICK)
        controller.analyzer = LiveAnalyzer(
            enabled = { settings.value.smartAi || _ui.value.aiPro || needsFaces() },
            onResult = { scene -> live.value = scene; viewModelScope.launch { onLiveScene(scene) } }
        )
    }

    private fun needsFaces() = _ui.value.mode in setOf(CameraMode.PORTRAIT, CameraMode.INTERVIEW, CameraMode.CINEMATIC, CameraMode.VLOG, CameraMode.AI_AUTO)

    // region привязка камеры
    fun attach(owner: LifecycleOwner, pv: PreviewView) {
        this.owner = owner
        previewView = pv
        viewModelScope.launch {
            runCatching { controller.init() }.onFailure { toast("Камера недоступна: ${it.message}") }
            rebind()
        }
    }

    fun detach() {
        stopVoice()
        controller.unbind()
        owner = null
        previewView = null
    }

    fun rebind() {
        val o = owner ?: return
        val pv = previewView ?: return
        val s = settings.value
        val u = _ui.value
        val hdrExt = if (u.hdr && (u.mode == CameraMode.PHOTO || u.mode == CameraMode.AI_AUTO) &&
            cam.value.backExtensions.contains(ExtensionMode.HDR) && !u.front) ExtensionMode.HDR else null
        val res = when (u.mode) {
            CameraMode.VIDEO_4K -> VideoRes.UHD
            CameraMode.VIDEO_8K -> VideoRes.UHD8K
            CameraMode.VIDEO -> VideoRes.FHD
            else -> s.videoRes
        }
        controller.bind(
            o, pv, BindConfig(
                mode = u.mode,
                front = u.front,
                ultraWideLens = u.ultraWide,
                videoRes = res,
                fps = if (u.mode == CameraMode.VIDEO_4K) 60 else s.fps,
                stabilization = s.stabilization,
                bitrateMbps = s.bitrate.mbpsPer1080,
                flashMode = u.flash.captureMode,
                liveEffects = true,
                useExtension = u.mode != CameraMode.PRO,
                lowResCapture = u.mode == CameraMode.PANORAMA || u.mode == CameraMode.TIMELAPSE,
                extensionOverride = hdrExt,
            )
        )
        controller.applyRequestOptions(u.manual)
        if (u.mode == CameraMode.VIDEO_8K) toast("8K недоступно через CameraX на этом устройстве — запись в максимальном качестве (4K)")
        updateEffect()
    }
    // endregion

    // region состояние UI
    fun setMode(m: CameraMode) {
        if (cam.value.recording || _ui.value.timelapseActive || _ui.value.panoramaActive) return
        val front = when (m) {
            CameraMode.VLOG -> true
            else -> _ui.value.front
        }
        _ui.update {
            it.copy(
                mode = m, front = front, filterId = m.filterId ?: "original", effect = Effect.NONE,
                beautyOn = m == CameraMode.PORTRAIT || m == CameraMode.VLOG || it.beautyOn,
                manual = if (m == CameraMode.PRO) it.manual else ManualSettings(),
                ultraWide = if (m.recordsVideo || m == CameraMode.PORTRAIT) false else it.ultraWide,
            )
        }
        m.tip?.let { toast(it) }
        rebind()
        if (m.evBias != 0) controller.setExposureIndex(m.evBias * stepsPerEv())
    }

    private fun stepsPerEv(): Int = cam.value.evStep.let { if (it > 0f) (1f / it).toInt().coerceAtLeast(1) else 0 }

    fun flipCamera() {
        _ui.update { it.copy(front = !it.front, ultraWide = false) }
        rebind()
    }

    fun cycleFlash() {
        val next = FlashMode.entries[(_ui.value.flash.ordinal + 1) % FlashMode.entries.size]
        _ui.update { it.copy(flash = next) }
        controller.setFlash(next.captureMode)
        if (_ui.value.mode.recordsVideo) controller.setTorch(next == FlashMode.ON)
    }

    fun cycleTimer() = _ui.update { it.copy(timer = when (it.timer) { 0 -> 3; 3 -> 10; else -> 0 }) }
    fun toggleHdr() { _ui.update { it.copy(hdr = !it.hdr) }; rebind() }
    fun toggleAiPro() { _ui.update { it.copy(aiPro = !it.aiPro, aiHint = null) }; aiEvOffset = 0; updateEffect() }
    fun setBeauty(on: Boolean) { _ui.update { it.copy(beautyOn = on) }; updateEffect() }
    fun setFilter(id: String) { _ui.update { it.copy(filterId = id) }; updateEffect() }
    fun setFilterIntensity(v: Float) { _ui.update { it.copy(filterIntensity = v) }; updateEffect() }
    fun setEffect(e: Effect) { _ui.update { it.copy(effect = e) }; updateEffect() }
    fun setTimelapseInterval(v: Float) = _ui.update { it.copy(timelapseInterval = v) }
    fun consumeMessage() = _ui.update { it.copy(message = null) }
    fun toast(msg: String) = _ui.update { it.copy(message = msg) }

    fun setManual(m: ManualSettings) {
        _ui.update { it.copy(manual = m) }
        controller.applyRequestOptions(m)
    }

    fun updateSettings(transform: (AppSettings) -> AppSettings, rebindAfter: Boolean = false) {
        viewModelScope.launch {
            settingsRepo.update(transform)
            delay(50)
            if (rebindAfter) rebind() else updateEffect()
        }
    }

    /** Выбор зума: 0.5x — логический сверхширик или отдельная камера, 1x/2x/3x — плавно. */
    fun selectZoom(z: Float) {
        val c = cam.value
        viewModelScope.launch {
            if (z < 0.95f) {
                if (c.minZoom < 0.95f) {
                    if (_ui.value.ultraWide) { _ui.update { it.copy(ultraWide = false) }; rebind(); delay(300) }
                    controller.animateZoom(maxOf(z, c.minZoom))
                } else if (c.hasUltraWide && !_ui.value.ultraWide && !_ui.value.front) {
                    _ui.update { it.copy(ultraWide = true) }
                    rebind()
                } else toast("Сверхширокая камера недоступна")
            } else {
                if (_ui.value.ultraWide) { _ui.update { it.copy(ultraWide = false) }; rebind(); delay(300) }
                controller.animateZoom(z)
            }
        }
    }

    /** Итоговые параметры GPU-обработки превью/видео. */
    fun updateEffect() {
        val s = settings.value
        val u = _ui.value
        val f = Filters.byId(u.filterId)
        var matrix = Filters.blended(f, u.filterIntensity)
        val video = u.mode.recordsVideo
        val aiVideo = video && s.aiVideoEnabled
        if (aiVideo && s.aiVideoColor) matrix = Filters.concat(matrix, Filters.adjustments(0f, 0.04f, 0.08f, 0f))
        val l = live.value
        val dark = l.mean < 0.22f
        val aiOn = u.aiPro || u.mode == CameraMode.AI_AUTO
        // гистерезис: «тёмное лицо» включается при <0.33 и выключается только при >0.42
        if (l.faces.isEmpty()) faceDarkState = false
        else if (!faceDarkState && l.faceLuma < 0.33f) faceDarkState = true
        else if (faceDarkState && l.faceLuma > 0.42f) faceDarkState = false
        val beauty = when {
            u.beautyOn -> s.beautySkin * 0.7f
            aiVideo && s.aiVideoSkin -> 0.25f
            else -> 0f
        }
        val tFaceLight = when {
            u.beautyOn && s.beautyFace > 0f -> s.beautyFace * 0.4f
            (aiOn || (aiVideo && s.aiVideoLight)) && faceDarkState -> 0.35f
            else -> 0f
        }
        val tGain = if ((aiOn || (aiVideo && s.aiVideoLight)) && dark && u.mode != CameraMode.NIGHT) 1.08f else 1f
        val tDenoise = when {
            u.mode == CameraMode.NIGHT -> 0.6f
            aiVideo && s.aiVideoNoise && dark -> 0.45f
            aiVideo && s.aiVideoNoise -> 0.1f
            aiOn && dark -> 0.3f
            else -> 0f
        }
        val tLift = if (u.mode == CameraMode.NIGHT) 0.5f else if (aiOn && dark) 0.15f else 0f
        // плавное приближение к цели (~0.5 с), чтобы яркость не прыгала
        val k = 0.12f
        smFaceLight += (tFaceLight - smFaceLight) * k
        smGain += (tGain - smGain) * k
        smDenoise += (tDenoise - smDenoise) * k
        smLift += (tLift - smLift) * k
        // общий мягкий контраст как на фото
        matrix = Filters.concat(Filters.adjustments(0f, -0.06f, 0f, 0f), matrix)
        controller.processor.params = EffectParams(
            colorMatrix = matrix,
            vignette = f.vignette * u.filterIntensity + if (u.mode == CameraMode.CINEMATIC) 0.25f else 0f,
            fade = f.fade * u.filterIntensity,
            beauty = beauty,
            denoise = smDenoise,
            faceLight = smFaceLight,
            sharpen = if (u.mode == CameraMode.LANDSCAPE) 0.12f else 0.04f,
            gain = smGain,
            lift = smLift,
            log = if (u.mode == CameraMode.LOG || (video && s.colorProfile == com.maysk.camera.data.ColorProfile.LOG)) 1f else 0f,
            effect = u.effect.id,
        )
    }

    private fun c0p5() = cam.value.zoom < 0.95f

    /** AI PRO: реагирует на сцену в реальном времени. */
    private fun onLiveScene(l: LiveScene) {
        val u = _ui.value
        val aiOn = u.aiPro || u.mode == CameraMode.AI_AUTO
        val now = SystemClock.elapsedRealtime()
        if (aiOn && !u.mode.recordsVideo && u.mode != CameraMode.PRO) {
            val actions = mutableListOf<String>()
            if (l.faces.isNotEmpty() && l.faceLuma < 0.38f) actions += "+15% свет лица"
            if (l.highlights > 0.08f) actions += "HDR баланс света"
            if (l.mean < 0.2f) actions += "Шумоподавление"
            if (l.shake > 0.3f) actions += "Держите ровно"
            val hint = when {
                l.faces.isNotEmpty() -> if (l.faces.size > 1) "Лица: ${l.faces.size}" else "Лицо обнаружено"
                l.scene != SceneType.GENERAL -> l.scene.title
                else -> null
            }
            _ui.update { it.copy(aiHint = hint, aiActions = actions) }
            // мягкая автокоррекция экспозиции: только основная камера, редко и с запасом,
            // на фронталке экспозицию по лицу ведёт сама камера — так свет не «прыгает»
            if (!u.front && now - lastAiEv > 3000 && stepsPerEv() > 0) {
                val target = when {
                    l.faces.isNotEmpty() && l.faceLuma < 0.3f -> stepsPerEv() / 2
                    l.faces.isNotEmpty() && l.faceLuma > 0.4f -> 0
                    l.highlights > 0.15f && l.faces.isEmpty() -> -(stepsPerEv() / 3)
                    l.highlights < 0.08f && l.faces.isEmpty() -> 0
                    else -> aiEvOffset
                }
                if (target != aiEvOffset) {
                    lastAiEv = now
                    aiEvOffset = target
                    controller.setExposureIndex(u.mode.evBias * stepsPerEv() + target)
                }
            }
        }
        // фокус-трекинг лица: только автофокус и только если лицо заметно сместилось
        val tracking = u.mode in setOf(CameraMode.INTERVIEW, CameraMode.CINEMATIC, CameraMode.VLOG, CameraMode.PORTRAIT) || aiOn
        if (tracking && l.faces.isNotEmpty() && now - lastFaceFocus > 3000 && u.manual.focusDiopters == null && controller.minFocusDiopters() > 0f) {
            val b = l.faces.maxByOrNull { it.box.width() }!!.box
            val moved = lastFocusX < 0f || kotlin.math.abs(b.centerX() - lastFocusX) > 0.08f || kotlin.math.abs(b.centerY() - lastFocusY) > 0.08f
            if (moved) {
                lastFaceFocus = now
                lastFocusX = b.centerX(); lastFocusY = b.centerY()
                controller.focusOnUpright(b.centerX(), b.centerY(), l.rotation)
            }
        }
        updateEffect()
    }
    // endregion

    // region затвор
    fun shutter() {
        val u = _ui.value
        if (u.busy != null && !u.timelapseActive && !u.panoramaActive) return
        when {
            u.mode == CameraMode.TIMELAPSE -> if (u.timelapseActive) stopTimelapse() else startTimelapse()
            u.mode == CameraMode.PANORAMA -> if (u.panoramaActive) stopPanorama() else startPanorama()
            u.mode.recordsVideo -> if (cam.value.recording) stopRecording() else startRecording()
            else -> {
                captureJob = viewModelScope.launch {
                    if (u.timer > 0) {
                        for (t in u.timer downTo 1) { _ui.update { it.copy(countdown = t) }; delay(1000) }
                        _ui.update { it.copy(countdown = 0) }
                    }
                    takePhoto()
                }
            }
        }
    }

    private fun playShutter() {
        if (settings.value.shutterSound) runCatching { sound.play(MediaActionSound.SHUTTER_CLICK) }
    }

    private suspend fun takePhoto() {
        val u = _ui.value
        val s = settings.value
        val c = cam.value
        val mirror = s.mirrorFront
        try {
            _ui.update { it.copy(busy = "Съёмка…") }
            val ext = c.activeExtension
            val raw: Bitmap = when {
                u.mode == CameraMode.NIGHT && ext == null -> {
                    _ui.update { it.copy(busy = "Ночной режим: серия кадров", progress = 0f) }
                    playShutter()
                    val frames = controller.captureBurst(6, mirror) { n -> _ui.update { it.copy(progress = n / 6f) } }
                    _ui.update { it.copy(busy = "AI обработка ночи…", progress = null) }
                    withContext(Dispatchers.Default) { ImageOps.nightProcess(ImageOps.stackFrames(frames)) }
                }
                (u.hdr || u.mode == CameraMode.LANDSCAPE) && ext == null && !u.front -> {
                    _ui.update { it.copy(busy = "HDR: брекетинг экспозиции") }
                    playShutter()
                    val frames = controller.captureBracket(mirror)
                    _ui.update { it.copy(busy = "Склейка HDR…") }
                    withContext(Dispatchers.Default) {
                        if (frames.size >= 3) ImageOps.localToneMap(ImageOps.exposureFusion(frames), 0.2f, 0.2f, 0.35f) else frames.first()
                    }
                }
                (u.ultraWide || c.zoom < 0.95f) && ext == null -> {
                    _ui.update { it.copy(busy = "Ultra Wide AI 0.5x") }
                    playShutter()
                    val frames = controller.captureBurst(3, mirror)
                    withContext(Dispatchers.Default) {
                        // самый резкий кадр + совпавшие кадры → меньше шума, детали сохраняются
                        val b = ImageOps.smartStack(frames)
                        ImageOps.sharpen(ImageOps.denoise(b, 0.25f), 0.15f)
                    }
                }
                u.front && ext == null -> {
                    // Селфи: вспышка экраном + серия, выбор самого резкого и сложение
                    val useScreenFlash = u.flash == FlashMode.ON || (u.flash == FlashMode.AUTO && live.value.mean < 0.25f)
                    if (useScreenFlash) { _ui.update { it.copy(screenFlash = true) }; delay(450) }
                    playShutter()
                    val frames = try { controller.captureBurst(3, mirror) } finally { _ui.update { it.copy(screenFlash = false) } }
                    _ui.update { it.copy(busy = "AI селфи: детализация…") }
                    withContext(Dispatchers.Default) {
                        val b = ImageOps.smartStack(frames)
                        ImageOps.sharpen(ImageOps.denoise(b, 0.2f), 0.12f)
                    }
                }
                u.mode == CameraMode.PET || u.mode == CameraMode.BABY -> {
                    playShutter()
                    val frames = controller.captureBurst(3, mirror)
                    _ui.update { it.copy(busy = "AI выбирает самый резкий кадр") }
                    withContext(Dispatchers.Default) { frames.maxByOrNull { ImageOps.sharpnessScore(it) }!! }
                }
                else -> {
                    playShutter()
                    controller.captureBitmap(mirror)
                }
            }
            _ui.update { it.copy(busy = "AI обработка…") }
            val location = if (s.geotag && ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED)
                MediaStoreRepo.lastLocation(ctx) else null
            val quality = if (s.photoQualityHigh) 96 else 88
            if (s.saveOriginals) MediaStoreRepo.saveJpeg(ctx, raw, 100, location, MediaStoreRepo.newName("IMG") + "_ORIG")

            val beauty = u.beautyOn || u.mode == CameraMode.PORTRAIT
            val spec = PhotoSpec(
                filterId = u.filterId,
                filterIntensity = u.filterIntensity,
                effect = u.effect,
                beautySkin = if (beauty) s.beautySkin else 0f,
                beautyEyes = if (beauty) s.beautyEyes else 0f,
                beautyFace = if (beauty) s.beautyFace else 0f,
                aiAuto = u.mode == CameraMode.AI_AUTO || (u.aiPro && s.smartAi && u.mode != CameraMode.PRO),
                portraitBlur = u.mode == CameraMode.PORTRAIT && ext != ExtensionMode.BOKEH,
                sharpen = if (u.mode == CameraMode.MACRO || u.mode == CameraMode.CAR) 0.2f else 0f,
                clarity = when {
                    u.front -> 0.1f
                    u.mode == CameraMode.LANDSCAPE || u.mode == CameraMode.CAR -> 0.3f
                    else -> 0.18f
                },
            )
            val out = withContext(Dispatchers.Default) { PhotoPipeline.process(raw, spec) }
            val uri = MediaStoreRepo.saveJpeg(ctx, out, quality, location)
            _ui.update { it.copy(busy = null, progress = null, lastUri = uri ?: it.lastUri, lastIsVideo = false, message = if (uri != null) null else "Не удалось сохранить") }
        } catch (t: Throwable) {
            _ui.update { it.copy(busy = null, progress = null, message = "Ошибка съёмки: ${t.message ?: t.javaClass.simpleName}") }
        }
    }

    private fun sharpness(b: Bitmap): Double {
        val s = ImageOps.limitSize(b, 400)
        val w = s.width; val h = s.height
        val px = IntArray(w * h); s.getPixels(px, 0, w, 0, 0, w, h)
        fun l(i: Int) = ((px[i] shr 8) and 255)
        var sum = 0.0; var sq = 0.0; var n = 0
        for (y in 1 until h - 1) for (x in 1 until w - 1) {
            val i = y * w + x
            val lap = (4 * l(i) - l(i - 1) - l(i + 1) - l(i - w) - l(i + w)).toDouble()
            sum += lap; sq += lap * lap; n++
        }
        val mean = sum / n
        return sq / n - mean * mean
    }
    // endregion

    // region серия (удержание затвора)
    private var burstJob: Job? = null

    fun startBurst() {
        val u = _ui.value
        if (u.mode.isVideo || u.busy != null || u.mode == CameraMode.PANORAMA || u.mode == CameraMode.DOCUMENT) return
        _ui.update { it.copy(burstCount = 0) }
        burstJob = viewModelScope.launch {
            val s = settings.value
            while (isActive) {
                val b = runCatching { controller.captureBitmap(s.mirrorFront) }.getOrNull() ?: break
                playShutter()
                val uri = withContext(Dispatchers.Default) {
                    // лёгкая обработка, чтобы серия была быстрой
                    val f = Filters.byId(_ui.value.filterId)
                    var out = ImageOps.colorMatrix(b, Filters.concat(Filters.adjustments(0f, -0.06f, 0f, 0f), Filters.blended(f, _ui.value.filterIntensity)))
                    out = ImageOps.vignetteAndFade(out, f.vignette, f.fade)
                    MediaStoreRepo.saveJpeg(ctx, out, if (s.photoQualityHigh) 94 else 86, name = MediaStoreRepo.newName("BURST"))
                }
                _ui.update { it.copy(burstCount = it.burstCount + 1, lastUri = uri ?: it.lastUri, lastIsVideo = false) }
                if (_ui.value.burstCount >= 30) break
            }
        }
    }

    fun stopBurst() {
        burstJob?.cancel()
        burstJob = null
        val n = _ui.value.burstCount
        if (n > 0) toast("Серия: $n фото")
        _ui.update { it.copy(burstCount = 0) }
    }
    // endregion

    /** Поворот телефона → правильная ориентация фото и видео. */
    fun setDeviceRotation(surfaceRotation: Int) = controller.setTargetRotation(surfaceRotation)

    // region видео
    private fun hasMic() = ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun startRecording() {
        val u = _ui.value
        val withAudio = hasMic() && u.mode != CameraMode.SLOW_MOTION
        if (u.flash == FlashMode.ON) controller.setTorch(true)
        if (settings.value.shutterSound) runCatching { sound.play(MediaActionSound.START_VIDEO_RECORDING) }
        val ok = controller.startRecording(withAudio) fin@{ uri, err ->
            controller.setTorch(false)
            if (settings.value.shutterSound) runCatching { sound.play(MediaActionSound.STOP_VIDEO_RECORDING) }
            if (uri == null) { toast(err ?: "Видео не сохранено"); return@fin }
            viewModelScope.launch { postProcessVideo(uri, u.mode, withAudio) }
        }
        if (!ok) toast("Запись недоступна")
    }

    fun stopRecording() = controller.stopRecording()
    fun pauseResume() = controller.pauseResume()

    private suspend fun postProcessVideo(uri: Uri, mode: CameraMode, withAudio: Boolean) {
        var result = uri
        val audio = if (mode == CameraMode.INTERVIEW) AudioMode.VOICE else settings.value.audioMode
        try {
            if (mode == CameraMode.SLOW_MOTION) {
                _ui.update { it.copy(busy = "Замедление видео…") }
                val tmp = File(ctx.cacheDir, "slowmo.mp4")
                val maxFps = cam.value.fpsOptions.maxOrNull() ?: 30
                val factor = maxOf(2f, maxFps / 30f)
                val ok = withContext(Dispatchers.IO) { VideoTools.slowMotion(ctx, uri, tmp, factor) }
                if (ok) MediaStoreRepo.saveVideoFile(ctx, tmp, MediaStoreRepo.newName("SLOMO"))?.let { n ->
                    runCatching { ctx.contentResolver.delete(uri, null, null) }; result = n
                }
                tmp.delete()
            } else if (withAudio && audio != AudioMode.NORMAL) {
                _ui.update { it.copy(busy = "AI Audio: ${audio.label}…") }
                val tmp = File(ctx.cacheDir, "audio_fx.mp4")
                val ok = withContext(Dispatchers.IO) { VideoTools.enhanceAudio(ctx, uri, tmp, audio) }
                if (ok) MediaStoreRepo.saveVideoFile(ctx, tmp)?.let { n ->
                    runCatching { ctx.contentResolver.delete(uri, null, null) }; result = n
                }
                tmp.delete()
            }
        } catch (t: Throwable) {
            toast("Постобработка пропущена: ${t.message}")
        }
        _ui.update { it.copy(busy = null, lastUri = result, lastIsVideo = true) }
    }
    // endregion

    // region таймлапс и панорама
    private var timelapseJob: Job? = null

    private fun startTimelapse() {
        _ui.update { it.copy(timelapseActive = true, timelapseFrames = 0, busy = "Таймлапс") }
        timelapseJob = viewModelScope.launch {
            val file = File(ctx.cacheDir, "timelapse.mp4")
            val enc = withContext(Dispatchers.IO) { TimelapseEncoder(file) }
            try {
                while (isActive && _ui.value.timelapseActive) {
                    val t0 = SystemClock.elapsedRealtime()
                    val b = runCatching { controller.captureBitmap(settings.value.mirrorFront) }.getOrNull()
                    if (b != null) {
                        withContext(Dispatchers.Default) { enc.addFrame(b) }
                        _ui.update { it.copy(timelapseFrames = it.timelapseFrames + 1) }
                    }
                    val wait = (_ui.value.timelapseInterval * 1000).toLong() - (SystemClock.elapsedRealtime() - t0)
                    if (wait > 0) delay(wait)
                }
            } finally {
                _ui.update { it.copy(busy = "Сборка видео…") }
                val ok = withContext(Dispatchers.Default) { enc.finish() }
                val uri = if (ok) MediaStoreRepo.saveVideoFile(ctx, file, MediaStoreRepo.newName("TIMELAPSE")) else null
                file.delete()
                _ui.update {
                    it.copy(timelapseActive = false, busy = null, lastUri = uri ?: it.lastUri, lastIsVideo = uri != null,
                        message = if (uri == null) "Слишком мало кадров для таймлапса" else null)
                }
            }
        }
    }

    private fun stopTimelapse() {
        _ui.update { it.copy(timelapseActive = false) }
    }

    private val panoFrames = mutableListOf<Bitmap>()
    private var panoJob: Job? = null

    private fun startPanorama() {
        panoFrames.clear()
        _ui.update { it.copy(panoramaActive = true, panoramaFrames = 0, busy = "Панорама") }
        playShutter()
        panoJob = viewModelScope.launch {
            while (isActive && _ui.value.panoramaActive && panoFrames.size < 16) {
                runCatching { controller.captureBitmap(false) }.getOrNull()?.let {
                    panoFrames += ImageOps.limitSize(it, 1600)
                    _ui.update { s -> s.copy(panoramaFrames = panoFrames.size) }
                }
                delay(350)
            }
            finishPanorama()
        }
    }

    private fun stopPanorama() {
        _ui.update { it.copy(panoramaActive = false) }
    }

    private suspend fun finishPanorama() {
        _ui.update { it.copy(panoramaActive = false, busy = "Склейка панорамы…") }
        val frames = panoFrames.toList()
        panoFrames.clear()
        val pano = withContext(Dispatchers.Default) { runCatching { VideoTools.stitchPanorama(frames) }.getOrNull() }
        val uri = pano?.let { MediaStoreRepo.saveJpeg(ctx, it, 94) }
        _ui.update {
            it.copy(busy = null, lastUri = uri ?: it.lastUri, lastIsVideo = false,
                message = if (uri == null) "Не удалось склеить панораму — двигайте камеру медленнее" else "Панорама сохранена")
        }
    }
    // endregion

    // region голос
    fun startVoice() {
        if (voice == null) voice = VoiceShutter(ctx) { cmd ->
            when (cmd) {
                "shoot" -> if (!_ui.value.mode.recordsVideo) shutter()
                "record" -> if (_ui.value.mode.recordsVideo && !cam.value.recording) shutter()
                "stop" -> if (cam.value.recording) stopRecording()
            }
        }
        if (voice?.available == true) { voice?.start(); _ui.update { it.copy(voiceListening = true) } }
        else toast("Распознавание речи недоступно на устройстве")
    }

    fun stopVoice() {
        voice?.stop()
        _ui.update { it.copy(voiceListening = false) }
    }
    // endregion

    // region обновления
    /** Ручная проверка из настроек. */
    fun checkUpdates() {
        viewModelScope.launch {
            sysToast("Проверяю обновления…")
            when (val r = com.maysk.camera.data.Updater.check()) {
                is com.maysk.camera.data.UpdateCheck.Available -> _ui.update { it.copy(updateAvailable = r.info) }
                is com.maysk.camera.data.UpdateCheck.UpToDate -> sysToast("У вас последняя версия ${com.maysk.camera.BuildConfig.VERSION_NAME}")
                is com.maysk.camera.data.UpdateCheck.Failed -> sysToast(r.reason)
            }
        }
    }

    private fun sysToast(msg: String) = android.widget.Toast.makeText(ctx, msg, android.widget.Toast.LENGTH_LONG).show()

    fun dismissUpdate() = _ui.update { it.copy(updateAvailable = null) }

    fun installUpdate() {
        val info = _ui.value.updateAvailable ?: return
        val U = com.maysk.camera.data.Updater
        if (U.needsInstallPermission(ctx)) {
            sysToast("Разрешите установку обновлений для Maysk Camera и нажмите «Обновить» ещё раз")
            U.openInstallPermissionSettings(ctx)
            return
        }
        viewModelScope.launch {
            _ui.update { it.copy(updateProgress = 0f) }
            val file = U.download(ctx, info) { p -> _ui.update { it.copy(updateProgress = p) } }
            _ui.update { it.copy(updateProgress = null) }
            if (file == null) { sysToast("Не удалось скачать обновление"); return@launch }
            _ui.update { it.copy(updateAvailable = null) }
            runCatching { U.install(ctx, file) }.onFailure { sysToast("Не удалось запустить установку: ${it.message}") }
        }
    }
    // endregion

    fun manualAwbModes(): List<Pair<Int, String>> = listOf(
        CameraMetadata.CONTROL_AWB_MODE_AUTO to "Авто",
        CameraMetadata.CONTROL_AWB_MODE_DAYLIGHT to "День",
        CameraMetadata.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT to "Облачно",
        CameraMetadata.CONTROL_AWB_MODE_INCANDESCENT to "Лампа",
        CameraMetadata.CONTROL_AWB_MODE_TWILIGHT to "Закат",
    )

    /** Для экрана «AI Авто»/«Анализ сцены»: план улучшения по последнему фото. */
    suspend fun planFor(uri: Uri) = MediaStoreRepo.loadBitmap(ctx, uri, 2400)?.let { b ->
        withContext(Dispatchers.Default) { b to AiEnhancer.plan(b) }
    }

    override fun onCleared() {
        stopVoice()
        controller.shutdown()
        sound.release()
        super.onCleared()
    }

    @Suppress("unused")
    private fun near(a: Float, b: Float) = abs(a - b) < 0.05f
}
