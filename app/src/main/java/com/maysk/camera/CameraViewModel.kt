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
    val panoramaProgress: Float = 0f,
    /** Ночной рецепт для видео (кнопка «Ночь» в видеорежиме). */
    val nightVideo: Boolean = false,
    /** Фильтр поставлен режимом автоматически (а не выбран человеком). */
    val filterAuto: Boolean = false,
    /** Режим предлагает свой вид, но человек уже выбрал фильтр — показываем кнопку. */
    val modeLookOffer: String? = null,
    val panoramaHint: String? = null,
    /** Край предыдущего кадра панорамы (четверть ширины) — граница для совмещения. */
    val panoGhost: Bitmap? = null,
    /** Доля поворота от прошлого кадра (0..1 от угла обзора) — двигает «границу» по экрану. */
    val panoShift: Float = 0f,
    val panoDirRight: Boolean = true,
    /** Мини-лента уже снятых кадров. */
    val panoRibbon: List<Bitmap> = emptyList(),
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
    /** Рамка, которая добавляется к фото. */
    val frame: com.maysk.camera.camera.FrameSpec? = null,
    /** AR-маска на лице. */
    val arMask: com.maysk.camera.camera.ArMask = com.maysk.camera.camera.ArMask.NONE,
    /** Выбранная 3D-маска (id из assets/ar_models/catalog.json) или null. */
    val ar3d: String? = null,
    /** Лицо для 3D-маски сейчас найдено. */
    val ar3dTracking: Boolean = false,
    /** Плоский шаблон на лицо из пакета шаблонов (id) или null. */
    val faceTpl: String? = null,
    /** Живая замена фона: "blur" или id фона из шаблонов; null — выключено. */
    val bgId: String? = null,
    /** Интенсивность эффекта на лице (прозрачность шаблона). */
    val effectAlpha: Float = 1f,
    /** Face Beauty в студии эффектов: тон кожи, румянец, губы (0..1, живые — в превью, фото и видео). */
    val beautyTone: Float = 0f,
    val blush: Float = 0f,
    val lips: Float = 0f,
    val snapMode: Boolean = false,
    val snapSeconds: Int = 10,
    val snapCaption: String = "",
    val snapDrawing: List<List<android.graphics.PointF>> = emptyList(),
    val snapDrawMode: Boolean = false,
    val snapSticker: String = "",
    val snapStickerX: Float = 0.82f,
    val snapStickerY: Float = 0.12f,
    val snapStickerScale: Float = 1f,
    /** Раскладка двойной камеры. */
    val dualLayout: DualLayout = DualLayout.PIP,
)

/** Раскладки двойной камеры. */
enum class DualLayout(val title: String) { PIP("Картинка в картинке"), SPLIT_V("50/50 вертикально"), SPLIT_H("50/50 рядом"), ROUND("Круглая мини-камера") }

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

    /** Гироскопная стабилизация видео. */
    val stabilizer = com.maysk.camera.camera.GyroStabilizer(app)
    private var fov: Pair<Float, Float> = 0.45f to 0.6f

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
        controller.processor.stabilizer = stabilizer
        // зум меняет угол обзора — пересчитываем компенсацию стабилизации
        viewModelScope.launch {
            cam.collect { c -> val (tu, tv) = fov; stabilizer.tanHalfFovU = tu / c.zoom.coerceAtLeast(0.3f); stabilizer.tanHalfFovV = tv / c.zoom.coerceAtLeast(0.3f) }
        }
        com.maysk.camera.camera.Frames.imageLoader = { path -> com.maysk.camera.ar3d.EffectTemplates.bitmap(ctx, path) }
        controller.analyzer = LiveAnalyzer(
            enabled = { settings.value.smartAi || _ui.value.aiPro || needsFaces() },
            onResult = { scene -> live.value = scene; updateAr3d(scene); viewModelScope.launch { onLiveScene(scene) } },
            realtimeFaces = { _ui.value.arMask != com.maysk.camera.camera.ArMask.NONE || arActive() },
            wantSegmentation = { _ui.value.bgId != null },
            onPersonMask = { m, w, h -> onPersonMask(m, w, h) },
            onThumb = { b -> liveThumb.value = b }
        )
    }

    /** Живой кадр для миниатюр фильтров (обновляется раз в ~2 с). */
    val liveThumb = kotlinx.coroutines.flow.MutableStateFlow<Bitmap?>(null)

    private fun needsFaces() = _ui.value.arMask != com.maysk.camera.camera.ArMask.NONE || arActive() || _ui.value.mode in setOf(CameraMode.PORTRAIT, CameraMode.INTERVIEW, CameraMode.CINEMATIC, CameraMode.VLOG, CameraMode.AI_AUTO)

    // region привязка камеры
    fun attach(owner: LifecycleOwner, pv: PreviewView) {
        this.owner = owner
        previewView = pv
        viewModelScope.launch {
            runCatching { controller.init() }.onFailure { toast("Камера недоступна: ${it.message}") }
            rebind()
        }
    }

    /** Уровень стабилизации с учётом режима: Кино — Gimbal, Влог — Ультра. */
    fun effectiveStabilization(): com.maysk.camera.data.Stabilization {
        val u = _ui.value
        if (!u.mode.recordsVideo) return com.maysk.camera.data.Stabilization.OFF
        return when (u.mode) {
            CameraMode.CINEMATIC -> com.maysk.camera.data.Stabilization.GIMBAL
            CameraMode.VLOG -> com.maysk.camera.data.Stabilization.ULTRA
            else -> settings.value.stabilization
        }
    }

    private fun configureStabilizer() {
        val s = settings.value
        val level = effectiveStabilization()
        fov = controller.fovTangents()
        stabilizer.front = _ui.value.front
        stabilizer.invertX = s.stabInvertX
        stabilizer.invertY = s.stabInvertY
        stabilizer.tanHalfFovU = fov.first / cam.value.zoom.coerceAtLeast(0.3f)
        stabilizer.tanHalfFovV = fov.second / cam.value.zoom.coerceAtLeast(0.3f)
        val use = level.software && cam.value.effectsActive && stabilizer.available
        stabilizer.level = if (use) level else com.maysk.camera.data.Stabilization.OFF
        if (use || panoHold) stabilizer.start() else stabilizer.stop()
    }

    fun detach() {
        stabilizer.stop()
        stopVoice()
        controller.unbind()
        owner = null
        previewView = null
    }

    fun rebind() {
        val o = owner ?: return
        val pv = previewView ?: return
        // ViewPort (кадр = экран) известен только после разметки — ждём её
        if (pv.width == 0 || pv.height == 0) { pv.post { rebind() }; return }
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
                fps = if (u.mode == CameraMode.VIDEO_4K && 60 in cam.value.fpsOptions) 60 else s.fps.takeIf { it in cam.value.fpsOptions } ?: 30,
                stabilization = effectiveStabilization(),
                bitrateMbps = s.bitrate.mbpsPer1080,
                flashMode = u.flash.captureMode,
                liveEffects = true,
                useExtension = u.mode != CameraMode.PRO,
                lowResCapture = u.mode == CameraMode.PANORAMA || u.mode == CameraMode.TIMELAPSE || u.mode == CameraMode.BOOMERANG,
                extensionOverride = hdrExt,
            )
        )
        controller.applyRequestOptions(u.manual)
        configureStabilizer()
        if (u.mode == CameraMode.VIDEO_8K) toast("Запись в максимальном качестве, которое телефон отдаёт приложениям (обычно 4K)")
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
            // выбор человека (фильтр, эффект, рамка) не сбрасывается; режим лишь предлагает свой вид
            val userChose = it.filterId != "original" && !it.filterAuto
            it.copy(
                mode = m, front = front,
                filterId = if (userChose) it.filterId else (m.filterId ?: "original"),
                filterAuto = if (userChose) false else m.filterId != null,
                modeLookOffer = if (userChose && m.filterId != null && m.filterId != it.filterId) m.filterId else null,
                beautyOn = m == CameraMode.PORTRAIT || m == CameraMode.VLOG || it.beautyOn,
                manual = if (m == CameraMode.PRO) it.manual else ManualSettings(),
                ultraWide = if (m.recordsVideo || m == CameraMode.PORTRAIT) false else it.ultraWide,
            )
        }
        if (m == CameraMode.MACRO && !cam.value.macroAvailable) toast("На этом телефоне нет камеры ближней фокусировки — макро недоступно")
        else m.tip?.let { toast(it) }
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
    fun setFilter(id: String) { _ui.update { it.copy(filterId = id, filterAuto = false, modeLookOffer = null) }; updateEffect() }

    // ---------- режимы как рецепты одной трубы ----------
    fun modeSkinTone(m: CameraMode) = when (m) { CameraMode.PORTRAIT, CameraMode.VLOG, CameraMode.INTERVIEW -> 0.6f; else -> 0f }
    fun modeWarmSat(m: CameraMode) = if (m == CameraMode.FOOD) 0.7f else 0f
    /** Кино: плашки 2.39 в превью и в файле. */
    fun modeFrame(m: CameraMode): com.maysk.camera.camera.FrameSpec? =
        if (m == CameraMode.CINEMATIC) com.maysk.camera.camera.FrameSpec(com.maysk.camera.camera.FrameStyle.CINEMA, android.graphics.Color.BLACK) else null

    private val noFocus = floatArrayOf(0.5f, 0.5f, 0.2f, 0f)

    /** Центр лица в координатах кадра GL (y вверх) для мягкого фона в портрете. */
    private fun portraitFocus(u: CameraUi, l: com.maysk.camera.ai.LiveScene): FloatArray {
        if (u.mode != CameraMode.PORTRAIT) return noFocus
        val face = l.faces.maxByOrNull { it.box.width() }?.box ?: return floatArrayOf(0.5f, 0.55f, 0.22f, 0.55f)
        val pv = previewView
        val viewAspect = if (pv != null && pv.height > 0) pv.width.toFloat() / pv.height else 0.5f
        val frameAspect = 0.75f
        val fx = if (viewAspect < frameAspect) viewAspect / frameAspect else 1f
        val fy = if (viewAspect > frameAspect) frameAspect / viewAspect else 1f
        var cx = (face.centerX() - (1 - fx) / 2) / fx
        if (u.front) cx = 1f - cx
        val cy = (face.centerY() - (1 - fy) / 2) / fy
        val r = (face.width() / fx) * viewAspect * 0.9f
        return floatArrayOf(cx, 1f - cy, r.coerceIn(0.08f, 0.5f), 0.75f)
    }

    fun toggleNightVideo() {
        _ui.update { it.copy(nightVideo = !it.nightVideo) }
        toast(if (_ui.value.nightVideo) "🌙 Ночь: тени светлее, шумодав по кадрам, баланс белого фиксируется при записи" else "Ночной рецепт выключен")
        updateEffect()
    }

    /** Применить «вид режима» (фильтр-рецепт режима) одной кнопкой. */
    fun applyModeLook() {
        val id = _ui.value.modeLookOffer ?: return
        _ui.update { it.copy(filterId = id, filterAuto = true, modeLookOffer = null) }
        updateEffect()
    }

    /**
     * Общий «вид кадра» для всего, что снимается мимо видеокодера (таймлапс, бумеранг, серия, панорама):
     * тот же фильтр, эффект и рамка, что видны в превью.
     */
    fun renderLook(b: Bitmap, withFrame: Boolean = true): Bitmap {
        val u = _ui.value
        val f0 = Filters.byId(u.filterId)
        val f = f0.copy(skinTone = maxOf(f0.skinTone, modeSkinTone(u.mode)), warmSat = maxOf(f0.warmSat, modeWarmSat(u.mode)))
        var out = Filters.render(b, f, u.filterIntensity, pre = Filters.adjustments(0f, -0.06f, 0f, 0f))
        if (u.effect != Effect.NONE && u.effect != Effect.BG_BLUR) out = ImageOps.applyEffect(out, u.effect)
        if (withFrame) u.frame?.let { fr -> out = com.maysk.camera.camera.Frames.apply(out, fr) }
        if (withFrame && u.frame == null) modeFrame(u.mode)?.let { fr -> out = com.maysk.camera.camera.Frames.apply(out, fr) }
        return out
    }
    fun setFilterIntensity(v: Float) { _ui.update { it.copy(filterIntensity = v) }; updateEffect() }
    fun setFrame(f: com.maysk.camera.camera.FrameSpec?) { _ui.update { it.copy(frame = f) }; updateEffect() }
    fun setArMask(m: com.maysk.camera.camera.ArMask) {
        _ui.update { it.copy(arMask = m, ar3d = if (m != com.maysk.camera.camera.ArMask.NONE) null else it.ar3d) }
        if (m != com.maysk.camera.camera.ArMask.NONE) setAr3d(null)
    }

    // region 3D-маски (GLB + Face Mesh, рисуются в GL-кадр → превью, фото и видео)
    private var ar3dModel: com.maysk.camera.ar3d.GlbModel? = null
    private val faceTracker = com.maysk.camera.ar3d.FaceTracker()
    private var lastMesh: com.maysk.camera.ai.FaceMeshInfo? = null
    private var lastMeshPose: com.maysk.camera.ar3d.FacePose? = null
    private var lastMeshView: FloatArray = FloatArray(0)
    private var faceTris: ShortArray = ShortArray(0)

    fun setAr3d(id: String?) {
        if (id == null) {
            _ui.update { it.copy(ar3d = null, ar3dTracking = false) }
            ar3dModel = null; refreshAr()
            return
        }
        viewModelScope.launch {
            val model = withContext(Dispatchers.IO) {
                runCatching { com.maysk.camera.ar3d.ArCatalog.load(ctx, id) }.onFailure { android.util.Log.e("MayskAR", "load $id", it) }.getOrNull()
            }
            if (model == null) { toast("Не удалось загрузить 3D-маску"); return@launch }
            ar3dModel = model; faceTracker.reset(); lastMesh = null; tplSprites = emptyList()
            _ui.update { it.copy(ar3d = id, faceTpl = null, arMask = com.maysk.camera.camera.ArMask.NONE) }
            if (!cam.value.effectsActive) toast("3D-маски работают только при живом GL-эффекте — на этой камере он не запустился")
        }
    }

    /** На лице что-то рисуется: 3D-маска, шаблон или живой макияж. */
    fun arActive(): Boolean { val u = _ui.value; return u.ar3d != null || u.faceTpl != null || u.blush > 0.01f || u.lips > 0.01f }

    @Volatile private var tplSprites: List<com.maysk.camera.ar3d.Sprite> = emptyList()
    @Volatile private var beautySprites: List<com.maysk.camera.ar3d.Sprite> = emptyList()

    /** Плоский шаблон на лицо (ушки, короны, очки, макияж) из пакета шаблонов. */
    fun setFaceTemplate(id: String?) {
        if (id == null) { tplSprites = emptyList(); _ui.update { it.copy(faceTpl = null) }; refreshAr(); return }
        viewModelScope.launch {
            val face = com.maysk.camera.ar3d.EffectTemplates.pack(ctx).faces.firstOrNull { it.id == id } ?: return@launch
            val sp = withContext(Dispatchers.IO) { com.maysk.camera.ar3d.EffectTemplates.sprites(ctx, face, _ui.value.effectAlpha) }
            tplSprites = sp
            // один «слот лица»: шаблон заменяет 3D-маску
            ar3dModel = null
            _ui.update { it.copy(faceTpl = id, ar3d = null, arMask = com.maysk.camera.camera.ArMask.NONE) }
            refreshAr()
        }
    }

    fun setEffectAlpha(a: Float) {
        _ui.update { it.copy(effectAlpha = a) }
        tplSprites = tplSprites.map { com.maysk.camera.ar3d.Sprite(it.key, it.bitmap, it.at, it.width, it.aspect, it.bottomAligned, a) }
        if (_ui.value.filterId != "original") setFilterIntensity(a)
    }

    /** Живой макияж: румянец и губы — те же шаблоны, прозрачность = значение ползунка. */
    fun setFaceBeauty(tone: Float? = null, blush: Float? = null, lips: Float? = null) {
        _ui.update { it.copy(beautyTone = tone ?: it.beautyTone, blush = blush ?: it.blush, lips = lips ?: it.lips) }
        val u = _ui.value
        viewModelScope.launch {
            val pack = com.maysk.camera.ar3d.EffectTemplates.pack(ctx)
            val list = withContext(Dispatchers.IO) {
                buildList {
                    if (u.blush > 0.01f) pack.faces.firstOrNull { it.id == "blush" }?.let { addAll(com.maysk.camera.ar3d.EffectTemplates.sprites(ctx, it, u.blush)) }
                    if (u.lips > 0.01f) pack.faces.firstOrNull { it.id == "lipgloss" }?.let { addAll(com.maysk.camera.ar3d.EffectTemplates.sprites(ctx, it, u.lips)) }
                }
            }
            beautySprites = list
            refreshAr()
        }
        updateEffect()
    }

    private fun refreshAr() {
        if (!arActive()) { controller.processor.arFrame = null; faceTracker.reset(); lastMesh = null }
        if (arActive() && !cam.value.effectsActive) toast("Эффекты на лице работают только при живом GL-эффекте — на этой камере он не запустился")
    }

    /** Сброс всего, что выбрано в студии эффектов. */
    fun resetStudio() {
        setAr3d(null); setFaceTemplate(null); setBackground(null); setFrame(null); setEffect(Effect.NONE); setFilter("original")
        _ui.update { it.copy(effectAlpha = 1f) }
        setFaceBeauty(0f, 0f, 0f)
    }

    // ---------- живая замена фона ----------
    private var bgImage: Bitmap? = null
    private var bgVersion = 0L

    fun setBackground(id: String?) {
        if (id == null) { _ui.update { it.copy(bgId = null) }; bgImage = null; controller.processor.background = null; return }
        viewModelScope.launch {
            bgImage = if (id == "blur") null else withContext(Dispatchers.IO) {
                val b = com.maysk.camera.ar3d.EffectTemplates.pack(ctx).backgrounds.firstOrNull { it.id == id }
                    ?.let { com.maysk.camera.ar3d.EffectTemplates.bitmap(ctx, it.jpg) } ?: return@withContext null
                // обрезка по пропорциям экрана (cover)
                val pv = previewView
                val aspect = if (pv != null && pv.height > 0) pv.width.toFloat() / pv.height else 9f / 19.5f
                val bw = b.width; val bh = b.height
                val cw = minOf(bw, (bh * aspect).toInt()); val ch = minOf(bh, (bw / aspect).toInt())
                Bitmap.createBitmap(b, (bw - cw) / 2, (bh - ch) / 2, cw, ch)
            }
            if (id != "blur" && bgImage == null) { toast("Фон не загрузился"); return@launch }
            _ui.update { it.copy(bgId = id) }
            if (!cam.value.effectsActive) toast("Замена фона работает только при живом GL-эффекте")
            else toast("Замена фона: отойдите так, чтобы были видны плечи — так маска точнее")
        }
    }

    private fun onPersonMask(m: ByteArray, w: Int, h: Int) {
        val u = _ui.value
        if (u.bgId == null) { controller.processor.background = null; return }
        val pv = previewView
        val aspect = if (pv != null && pv.height > 0) pv.width.toFloat() / pv.height else 9f / 19.5f
        controller.processor.background = com.maysk.camera.camera.gl.GlEffectProcessor.Background(m, w, h, bgImage, aspect, u.front, ++bgVersion)
    }

    /** Вызывается из потока анализа: обновляет позу головы и отдаёт сцену GL-процессору. */
    private fun updateAr3d(scene: com.maysk.camera.ai.LiveScene) {
        val id = _ui.value.ar3d ?: _ui.value.faceTpl ?: "beauty"
        val model = ar3dModel
        val sprites = tplSprites + beautySprites
        if (!arActive() || (model == null && sprites.isEmpty())) { controller.processor.arFrame = null; return }
        val pv = previewView
        val aspect = if (pv != null && pv.height > 0) pv.width.toFloat() / pv.height else 9f / 19.5f
        val map = com.maysk.camera.ar3d.ViewMapping(aspect, mirror = _ui.value.front)
        val face = scene.faces.maxByOrNull { it.box.width() }
        val mesh = face?.mesh3d
        var meshPose: com.maysk.camera.ar3d.FacePose? = null
        if (mesh != null && mesh !== lastMesh) {
            meshPose = com.maysk.camera.ar3d.FaceTransform.pose(mesh.vertices, map)
            if (meshPose != null) {
                lastMesh = mesh; lastMeshPose = meshPose
                lastMeshView = com.maysk.camera.ar3d.FaceTransform.meshToView(mesh.vertices, map)
                if (faceTris.size != mesh.triangles.size * 3) {
                    faceTris = ShortArray(mesh.triangles.size * 3).also { a ->
                        mesh.triangles.forEachIndexed { t, tri -> for (k in 0 until 3) a[t * 3 + k] = tri.getOrElse(k) { 0 }.toShort() }
                    }
                }
            }
        }
        val boxC = face?.let { map.boxCenter(it.box.centerX(), it.box.centerY()) }
        val boxW = face?.let { map.scaleX(it.box.width()) } ?: 0f
        val pose = faceTracker.update(meshPose, boxC, boxW, android.os.SystemClock.elapsedRealtime())
        val tracking = pose != null
        if (_ui.value.ar3dTracking != tracking) _ui.update { it.copy(ar3dTracking = tracking) }
        if (pose == null) { controller.processor.arFrame = null; return }
        // «невидимую голову» переносим из позы сетки в текущую сглаженную позу
        val mp = lastMeshPose
        val occ = if (mp != null && lastMeshView.isNotEmpty()) movedMesh(lastMeshView, mp, pose) else FloatArray(0)
        controller.processor.arFrame = com.maysk.camera.ar3d.ArFrame(id, model, pose, occ, if (occ.isEmpty()) ShortArray(0) else faceTris, aspect, sprites)
    }

    private fun movedMesh(src: FloatArray, from: com.maysk.camera.ar3d.FacePose, to: com.maysk.camera.ar3d.FacePose): FloatArray {
        val out = FloatArray(src.size)
        val k = to.scale / from.scale.coerceAtLeast(1e-6f)
        for (i in 0 until src.size / 3) {
            val dx = src[i * 3] - from.origin[0]; val dy = src[i * 3 + 1] - from.origin[1]; val dz = src[i * 3 + 2] - from.origin[2]
            // координаты в базисе исходной позы → в базисе новой
            val a = dx * from.right[0] + dy * from.right[1] + dz * from.right[2]
            val b = dx * from.up[0] + dy * from.up[1] + dz * from.up[2]
            val c = dx * from.forward[0] + dy * from.forward[1] + dz * from.forward[2]
            for (j in 0 until 3) out[i * 3 + j] = to.origin[j] + k * (a * to.right[j] + b * to.up[j] + c * to.forward[j])
        }
        return out
    }

    /** Фото с 3D-маской: берётся тот же обработанный GL-кадр, что видно в превью. */
    private suspend fun takeArPhoto() {
        _ui.update { it.copy(busy = "Снимок с 3D-маской…") }
        playShutter()
        val hadMask = controller.processor.arFrame != null || !arActive()
        val bmp = kotlinx.coroutines.withTimeoutOrNull(2000) {
            kotlinx.coroutines.suspendCancellableCoroutine<Bitmap?> { cont ->
                controller.processor.requestSnapshot { b -> if (cont.isActive) cont.resumeWith(Result.success(b)) }
            }
        }
        if (bmp == null) { _ui.update { it.copy(busy = null, message = "Не удалось получить кадр с маской") }; return }
        val uri = withContext(Dispatchers.IO) { MediaStoreRepo.saveJpeg(ctx, bmp, 95, name = MediaStoreRepo.newName("AR")) }
        _ui.update {
            it.copy(busy = null, lastUri = uri ?: it.lastUri, lastIsVideo = false,
                message = if (uri == null) "Фото не сохранено" else if (hadMask) "Фото с эффектами сохранено" else "Лицо не найдено — снимок без маски")
        }
    }
    // endregion
    fun setSnapMode(enabled: Boolean) = _ui.update { it.copy(snapMode = enabled) }
    fun setSnapDuration(seconds: Int) = _ui.update { it.copy(snapSeconds = if (seconds in setOf(3, 5, 10, 30)) seconds else 10) }
    fun setSnapCaption(value: String) = _ui.update { it.copy(snapCaption = value.take(80)) }
    fun setSnapDrawMode(enabled: Boolean) = _ui.update { it.copy(snapDrawMode = enabled) }
    fun clearSnapDrawing() = _ui.update { it.copy(snapDrawing = emptyList()) }
    fun addSnapPoint(x: Float, y: Float, newStroke: Boolean = false) = _ui.update { state ->
        val point = android.graphics.PointF(x.coerceIn(0f, 1f), y.coerceIn(0f, 1f))
        val strokes = state.snapDrawing.toMutableList()
        if (newStroke || strokes.isEmpty()) strokes.add(mutableListOf(point)) else strokes[strokes.lastIndex] = strokes.last() + point
        state.copy(snapDrawing = strokes.takeLast(30))
    }
    fun setSnapSticker(value: String) = _ui.update { it.copy(snapSticker = value.take(8)) }
    fun moveSnapSticker(x: Float, y: Float) = _ui.update { it.copy(snapStickerX = x.coerceIn(0f, 0.92f), snapStickerY = y.coerceIn(0f, 0.88f)) }
    fun scaleSnapSticker(delta: Float) = _ui.update { it.copy(snapStickerScale = (it.snapStickerScale + delta).coerceIn(0.55f, 2.5f)) }
    private val snapVault by lazy { com.maysk.camera.media.SnapVault(ctx) }
    fun pendingSnaps() = snapVault.pending()
    fun consumeSnap(id: String) = snapVault.consumeOnce(id)
    fun saveSnapToGallery(id: String) = snapVault.saveToGallery(id)
    fun deleteSnap(id: String) = snapVault.delete(id)
    fun startRecordingHold() { if (!_ui.value.mode.recordsVideo) return; if (!cam.value.recording) startRecording() }
    fun stopRecordingHold() { if (cam.value.recording) stopRecording() }
    fun setDualLayout(l: DualLayout) = _ui.update { it.copy(dualLayout = l) }
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
        // Во время записи нельзя переключать физическую камеру (это перезапуск записи) —
        // используем плавный цифровой/логический зум без остановки видео
        if (c.recording) {
            viewModelScope.launch {
                when {
                    z < 0.95f && c.minZoom < 0.95f -> controller.animateZoom(maxOf(z, c.minZoom), 450)
                    z < 0.95f -> toast("0.5x на этом телефоне — отдельная камера. Включите её до начала записи")
                    _ui.value.ultraWide -> toast("Запись идёт на сверхширокой камере. Переключение — после остановки")
                    else -> controller.animateZoom(z, 450)
                }
            }
            return
        }
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
        val veryDark = l.mean < 0.12f
        // ночной рецепт: кнопка «Ночь» в видео, режим «Ночь» (превью до снимка) или авто в темноте
        val nightRecipe = (video && (u.nightVideo || controller.lowLight)) || u.mode == CameraMode.NIGHT
        // Яркость всего кадра НЕ умножаем (gain только для фото-превью при AI и слегка) — поднимаем лишь тени
        val tGain = when {
            !video && aiOn && dark && u.mode != CameraMode.NIGHT -> 1.05f
            else -> 1f
        }
        val tDenoise = when {
            nightRecipe -> if (veryDark) 0.45f else 0.3f      // пространственно — умеренно, основную работу делает шумодав по времени
            aiVideo && s.aiVideoNoise && dark -> 0.3f
            aiVideo && s.aiVideoNoise -> 0.08f
            aiOn && dark -> 0.25f
            else -> 0f
        }
        val tLift = when {
            nightRecipe -> if (veryDark) 0.6f else 0.4f
            aiVideo && s.aiVideoLight && dark -> 0.25f
            aiOn && dark -> 0.15f
            else -> 0f
        }
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
            lut = if (f.tone.isIdentity) null else Filters.lut(f),
            lutMix = if (f.tone.isIdentity) 0f else u.filterIntensity,
            grain = f.grain * u.filterIntensity,
            leak = f.leak * u.filterIntensity,
            // рецепт режима поверх фильтра человека (а не вместо него)
            skinTone = maxOf(f.skinTone * u.filterIntensity, modeSkinTone(u.mode), u.beautyTone),
            warmSat = maxOf(f.warmSat * u.filterIntensity, modeWarmSat(u.mode)),
            focus = portraitFocus(u, l),
            frame = u.frame?.takeIf { !it.style.photoOnly } ?: modeFrame(u.mode),
            // ночное видео: накопление кадров по времени убирает «кипящий» шум в тенях
            // шумодав по времени: вес ~0.65 ≈ усреднение 3–4 последних кадров, движущееся берётся из текущего
            temporal = when {
                nightRecipe -> 0.65f
                video && dark && s.aiVideoNoise -> 0.5f
                else -> 0f
            },
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
        // Ночное видео: включаем/выключаем с запасом, чтобы не дёргать камеру
        if (u.mode.recordsVideo && settings.value.aiVideoLight) {
            val want = if (controller.lowLight) l.mean < 0.24f else l.mean < 0.14f
            if (want != controller.lowLight && !cam.value.recording) {
                controller.lowLight = want
                if (want && !u.nightVideo) toast("🌙 Темно — включён ночной рецепт видео")
            }
        } else if (controller.lowLight) {
            controller.lowLight = false
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
            u.mode == CameraMode.DUAL -> captureJob = viewModelScope.launch { takeDual() }
            u.mode == CameraMode.BOOMERANG -> captureJob = viewModelScope.launch { takeBoomerang() }
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
        // эффекты на лице или замена фона — снимок из того же GL-кадра, что видно в превью
        if ((arActive() || _ui.value.bgId != null) && cam.value.effectsActive) { takeArPhoto(); return }
        val u = _ui.value
        val s = settings.value
        val c = cam.value
        val mirror = s.mirrorFront
        try {
            _ui.update { it.copy(busy = "Съёмка…") }
            val ext = c.activeExtension
            val raw: Bitmap = when {
                u.mode == CameraMode.NIGHT && ext == null -> {
                    // Night Pro: 6 кадров (в полной темноте 8) — меньше времени держать телефон
                    val count = if (live.value.mean < 0.1f) 8 else 6
                    _ui.update { it.copy(busy = "Ночной режим: серия из $count кадров", progress = 0f) }
                    playShutter()
                    val frames = controller.captureBurst(count, mirror) { n -> _ui.update { it.copy(progress = n / count.toFloat()) } }
                    _ui.update { it.copy(busy = "AI обработка ночи…", progress = null) }
                    // опорный — самый резкий кадр, смазанные кадры отбрасываются
                    withContext(Dispatchers.Default) { ImageOps.nightProcess(ImageOps.smartStack(frames)) }
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
                        ImageOps.denoise(b, 0.25f)
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
                        ImageOps.denoise(b, 0.2f)
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
                aiAuto = u.mode != CameraMode.NIGHT && (u.mode == CameraMode.AI_AUTO || (u.aiPro && s.smartAi && u.mode != CameraMode.PRO)),
                // Реально подключаем коррекцию бочки для сверхширокого объектива к сохранённому фото.
                undistort = if (u.ultraWide || c.zoom < 0.95f) 0.22f else 0f,
                portraitBlur = u.mode == CameraMode.PORTRAIT && ext != ExtensionMode.BOKEH,
                sharpen = if (u.mode == CameraMode.MACRO || u.mode == CameraMode.CAR) 0.2f else 0f,
                clarity = when {
                    u.front -> 0.1f
                    u.mode == CameraMode.LANDSCAPE || u.mode == CameraMode.CAR -> 0.3f
                    else -> 0.18f
                },
                // AI Super Detail: сила по сцене
                detail = when {
                    u.mode == CameraMode.MACRO || u.mode == CameraMode.FOOD -> 0.55f
                    live.value.scene == com.maysk.camera.ai.SceneType.DOCUMENT -> 0.7f
                    u.mode == CameraMode.LANDSCAPE || live.value.scene == com.maysk.camera.ai.SceneType.LANDSCAPE -> 0.5f
                    u.front || u.mode == CameraMode.PORTRAIT || beauty -> 0.22f
                    else -> 0.38f
                },
                frame = u.frame ?: modeFrame(u.mode),
                modeSkinTone = modeSkinTone(u.mode),
                modeWarmSat = modeWarmSat(u.mode),
                arMask = u.arMask,
            )
            val processed = withContext(Dispatchers.Default) { PhotoPipeline.process(raw, spec) }
            val out = if (u.snapCaption.isNotBlank() || u.snapDrawing.isNotEmpty() || u.snapSticker.isNotBlank() || (s.geotag && location != null)) withContext(Dispatchers.Default) {
                com.maysk.camera.media.SnapOverlay.render(
                    processed, u.snapCaption, System.currentTimeMillis(), u.snapSticker, u.snapDrawing,
                    u.snapStickerX, u.snapStickerY, u.snapStickerScale,
                    if (s.geotag && location != null) "GPS %.4f, %.4f".format(location.latitude, location.longitude) else ""
                )
            } else processed
            if (u.snapMode) {
                val entry = withContext(Dispatchers.IO) { snapVault.save(out, u.snapSeconds) }
                _ui.update { it.copy(busy = null, progress = null, message = "Snap создан · просмотр один раз · ${u.snapSeconds} сек. · ${entry.id.takeLast(6)}") }
            } else {
                val uri = MediaStoreRepo.saveJpeg(ctx, out, quality, location)
                _ui.update { it.copy(busy = null, progress = null, lastUri = uri ?: it.lastUri, lastIsVideo = false, message = if (uri != null) null else "Не удалось сохранить") }
            }
        } catch (t: Throwable) {
            _ui.update { it.copy(busy = null, progress = null, message = "Ошибка съёмки: ${t.message ?: t.javaClass.simpleName}") }
        }
    }

    /** Двойное фото: основная + фронтальная камера, склейка по выбранной раскладке. */
    private suspend fun takeDual() {
        val s = settings.value
        val startFront = _ui.value.front
        try {
            _ui.update { it.copy(busy = "Снимок 1 из 2…") }
            playShutter()
            val first = controller.captureBitmap(s.mirrorFront)
            _ui.update { it.copy(busy = "Переключаю камеру…", front = !startFront, ultraWide = false) }
            rebind()
            delay(900)   // даём камере навести фокус и экспозицию
            _ui.update { it.copy(busy = "Снимок 2 из 2…") }
            playShutter()
            val second = controller.captureBitmap(s.mirrorFront)
            _ui.update { it.copy(front = startFront, busy = "Склейка…") }
            rebind()
            val main = if (startFront) second else first
            val selfie = if (startFront) first else second
            val out = withContext(Dispatchers.Default) {
                val m = PhotoPipeline.process(ImageOps.limitSize(main, 4000), PhotoSpec(filterId = _ui.value.filterId, filterIntensity = _ui.value.filterIntensity))
                val sf = PhotoPipeline.process(ImageOps.limitSize(selfie, 2000), PhotoSpec(beautySkin = if (_ui.value.beautyOn) s.beautySkin else 0.2f, detail = 0.2f))
                com.maysk.camera.camera.Dual.compose(m, sf, _ui.value.dualLayout)
            }
            val uri = MediaStoreRepo.saveJpeg(ctx, out, if (s.photoQualityHigh) 95 else 88, name = MediaStoreRepo.newName("DUAL"))
            _ui.update { it.copy(busy = null, lastUri = uri ?: it.lastUri, lastIsVideo = false) }
        } catch (t: Throwable) {
            _ui.update { it.copy(busy = null, front = startFront, message = "Двойной снимок не удался: ${t.message}") }
            rebind()
        }
    }

    /** Бумеранг: 10 быстрых кадров → видео вперёд-назад, повторённое 3 раза. */
    private suspend fun takeBoomerang() {
        val s = settings.value
        val frames = mutableListOf<Bitmap>()
        try {
            playShutter()
            for (i in 0 until 10) {
                _ui.update { it.copy(busy = "Бумеранг ${i + 1}/10", progress = (i + 1) / 10f) }
                runCatching { controller.captureBitmap(s.mirrorFront) }.getOrNull()?.let { frames += ImageOps.limitSize(it, 1280) }
            }
            if (frames.size < 4) { _ui.update { it.copy(busy = null, progress = null, message = "Не удалось снять кадры") }; return }
            _ui.update { it.copy(busy = "Собираю бумеранг…", progress = null) }
            val file = java.io.File(ctx.cacheDir, "boomerang.mp4")
            val ok = withContext(Dispatchers.Default) {
                val f = Filters.byId(_ui.value.filterId)
                val processed = frames.map { renderLook(it) }
                val seq = processed + processed.reversed().drop(1).dropLast(1)
                val enc = TimelapseEncoder(file, fps = 15)
                repeat(3) { seq.forEach { enc.addFrame(it) } }
                enc.finish()
            }
            val uri = if (ok) MediaStoreRepo.saveVideoFile(ctx, file, MediaStoreRepo.newName("BOOMERANG")) else null
            file.delete()
            _ui.update { it.copy(busy = null, lastUri = uri ?: it.lastUri, lastIsVideo = uri != null) }
        } catch (t: Throwable) {
            _ui.update { it.copy(busy = null, progress = null, message = "Бумеранг не удался: ${t.message}") }
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
                    val out = renderLook(b)
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
        // ночью баланс белого фиксируется на всю запись
        if (u.nightVideo || controller.lowLight) controller.setAwbLock(true)
        val ok = controller.startRecording(withAudio) fin@{ uri, err ->
            controller.setTorch(false)
            controller.setAwbLock(false)
            if (settings.value.shutterSound) runCatching { sound.play(MediaActionSound.STOP_VIDEO_RECORDING) }
            if (uri == null) { toast(err ?: "Видео не сохранено"); return@fin }
            viewModelScope.launch { postProcessVideo(uri, u.mode, withAudio, u.snapMode, u.snapSeconds) }
        }
        if (!ok) toast("Запись недоступна")
    }

    fun stopRecording() = controller.stopRecording()
    fun pauseResume() = controller.pauseResume()

    private suspend fun postProcessVideo(uri: Uri, mode: CameraMode, withAudio: Boolean, snapMode: Boolean = false, snapSeconds: Int = 10) {
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
        if (snapMode) {
            _ui.update { it.copy(busy = "Шифрование исчезающего видео…") }
            val entry = withContext(Dispatchers.IO) { snapVault.saveVideo(result, snapSeconds) }
            if (entry != null) {
                runCatching { ctx.contentResolver.delete(result, null, null) }
                _ui.update { it.copy(busy = null, lastUri = null, lastIsVideo = true, message = "Видео-Snap создано · просмотр один раз · ${snapSeconds} сек.") }
            } else {
                _ui.update { it.copy(busy = null, lastUri = result, lastIsVideo = true, message = "Не удалось зашифровать Snap-видео; обычное видео сохранено") }
            }
        } else {
            _ui.update { it.copy(busy = null, lastUri = result, lastIsVideo = true) }
        }
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
                        withContext(Dispatchers.Default) { enc.addFrame(renderLook(ImageOps.limitSize(b, 1920))) }
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
    private val panoYaws = mutableListOf<Float>()
    private var panoJob: Job? = null
    private var panoHold = false

    /**
     * Панорама по гироскопу: кадр снимается автоматически, когда камера повернулась
     * примерно на 30% угла обзора. Экспозиция и баланс белого зафиксированы — швов не видно.
     */
    private var panoDir = 0
    private var panoStepFrac = 0.3f
    private var lastMoveAt = 0L

    private fun startPanorama() {
        panoFrames.clear(); panoYaws.clear()
        panoDir = 0; lastMoveAt = SystemClock.elapsedRealtime()
        _ui.update { it.copy(panoGhost = null, panoRibbon = emptyList(), panoShift = 0f, panoDirRight = true) }
        val gyroOk = stabilizer.available
        panoHold = true
        if (gyroOk) stabilizer.start()
        controller.setAeAwbLock(true)
        val tanU = (controller.fovTangents().first / cam.value.zoom.coerceAtLeast(0.3f)).coerceIn(0.15f, 1.5f)
        val fov = 2f * kotlin.math.atan(tanU)
        // следующий кадр — после поворота на ~15–20°, но с перекрытием не меньше ~55%
        val step = (fov * 0.3f).coerceIn(0.26f, 0.35f).coerceAtMost(fov * 0.45f)
        panoStepFrac = step / fov
        _ui.update { it.copy(panoramaActive = true, panoramaFrames = 0, panoramaProgress = 0f, busy = "Панорама", panoramaHint = "Плавно поворачивайте телефон в одну сторону") }
        playShutter()
        panoJob = viewModelScope.launch {
            try {
                delay(250)   // даём зафиксировать экспозицию
                suspend fun grab(): Boolean {
                    val y0 = stabilizer.rawYaw
                    val b = runCatching { controller.captureBitmap(false) }.getOrNull() ?: return false
                    val y1 = stabilizer.rawYaw
                    val fr = ImageOps.limitSize(b, 1280)
                    panoFrames += fr
                    panoYaws += (y0 + y1) / 2f
                    // край кадра (четверть ширины) — полупрозрачная «граница» для следующего кадра, и мини-лента
                    val small = ImageOps.limitSize(fr, 360)
                    val qw = small.width / 4
                    val dirRight = panoDir >= 0
                    val ghost = Bitmap.createBitmap(small, if (dirRight) small.width - qw else 0, 0, qw, small.height)
                    val thumb = ImageOps.limitSize(fr, 120)
                    _ui.update { s -> s.copy(panoramaFrames = panoFrames.size, panoGhost = ghost, panoRibbon = s.panoRibbon + thumb, panoShift = 0f) }
                    return true
                }
                grab()
                val startYaw = panoYaws.firstOrNull() ?: 0f
                val startPitch = stabilizer.rawPitch
                var lastYaw = startYaw
                val maxFrames = 12
                val total = (Math.PI.toFloat() * 1.2f)   // до ~215°
                while (isActive && _ui.value.panoramaActive && panoFrames.size < maxFrames) {
                    if (!gyroOk) { delay(700); grab(); continue }
                    delay(25)
                    val yaw = stabilizer.rawYaw
                    // направление: рост угла = поворот влево (гироскоп Android, ось Y вверх)
                    if (panoDir == 0 && kotlin.math.abs(yaw - startYaw) > 0.05f) {
                        panoDir = if (yaw < startYaw) 1 else -1
                        // пересобираем «границу» под выбранное направление
                        panoFrames.lastOrNull()?.let { fr ->
                            val small = ImageOps.limitSize(fr, 360); val qw = small.width / 4
                            val g = Bitmap.createBitmap(small, if (panoDir > 0) small.width - qw else 0, 0, qw, small.height)
                            _ui.update { it.copy(panoGhost = g, panoDirRight = panoDir > 0) }
                        }
                    }
                    val moved = kotlin.math.abs(yaw - lastYaw)
                    val tilt = kotlin.math.abs(stabilizer.rawPitch - startPitch)
                    if (stabilizer.rate > 0.08f) lastMoveAt = SystemClock.elapsedRealtime()
                    val still = SystemClock.elapsedRealtime() - lastMoveAt > 1500
                    val hint = when {
                        stabilizer.rate > 1.0f -> "Медленнее"
                        tilt > 0.12f -> "Держите телефон ровно по горизонту"
                        still -> if (panoDir < 0) "← Ведите влево" else "Ведите вправо →"
                        else -> "Совместите кадр с полупрозрачной полосой"
                    }
                    val shift = (moved / fov).coerceIn(0f, 1f)
                    if (kotlin.math.abs(_ui.value.panoShift - shift) > 0.005f) _ui.update { it.copy(panoShift = shift) }
                    val progress = (kotlin.math.abs(yaw - startYaw) / total).coerceIn(0f, 1f)
                    if (_ui.value.panoramaHint != hint || kotlin.math.abs(_ui.value.panoramaProgress - progress) > 0.01f)
                        _ui.update { it.copy(panoramaHint = hint, panoramaProgress = progress) }
                    if (moved >= step && stabilizer.rate < 1.6f) {
                        if (grab()) lastYaw = panoYaws.last()
                    }
                    if (progress >= 1f) break
                }
            } finally {
                controller.setAeAwbLock(false)
                panoHold = false
                configureStabilizer()
            }
            finishPanorama(tanU)
        }
    }

    private fun stopPanorama() {
        _ui.update { it.copy(panoramaActive = false) }
    }

    private suspend fun finishPanorama(tanU: Float) {
        _ui.update { it.copy(panoramaActive = false, busy = "Склейка панорамы…", panoramaHint = null, panoGhost = null, panoRibbon = emptyList()) }
        val frames = panoFrames.toList(); val yaws = panoYaws.toFloatArray()
        panoFrames.clear(); panoYaws.clear()
        if (frames.size < 2) {
            _ui.update { it.copy(busy = null, message = "Поверните телефон дальше — нужно минимум 2 кадра") }
            return
        }
        val pano = withContext(Dispatchers.Default) {
            runCatching {
                VideoTools.stitchPanorama(frames, if (stabilizer.available) yaws else null, tanU)
                    ?.let { renderLook(it, withFrame = false) }   // выбранный фильтр и на панораме
            }.getOrNull()
        }
        val uri = pano?.let { MediaStoreRepo.saveJpeg(ctx, it, 94, name = MediaStoreRepo.newName("PANO")) }
        _ui.update {
            it.copy(busy = null, lastUri = uri ?: it.lastUri, lastIsVideo = false,
                message = if (uri == null) "Не удалось склеить панораму — поворачивайте телефон медленнее и ровнее" else "Панорама сохранена · ${frames.size} кадров")
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
        stabilizer.stop()
        stopVoice()
        controller.shutdown()
        sound.release()
        super.onCleared()
    }

    @Suppress("unused")
    private fun near(a: Float, b: Float) = abs(a - b) < 0.05f
}
