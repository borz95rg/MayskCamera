package com.maysk.camera.camera

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.TotalCaptureResult
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.provider.MediaStore
import android.util.Log
import android.util.Range
import android.util.Size
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.Camera
import androidx.camera.core.CameraEffect
import androidx.camera.core.CameraInfo
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.MirrorMode
import androidx.camera.core.Preview
import androidx.camera.core.SurfaceOrientedMeteringPointFactory
import androidx.camera.core.UseCase
import androidx.camera.core.UseCaseGroup
import androidx.camera.core.ZoomState
import androidx.lifecycle.LiveData
import androidx.lifecycle.Observer
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.extensions.ExtensionMode
import androidx.camera.extensions.ExtensionsManager
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.MediaStoreOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.google.common.util.concurrent.ListenableFuture
import com.maysk.camera.ai.ImageOps
import com.maysk.camera.camera.gl.GlEffectProcessor
import com.maysk.camera.data.Stabilization
import com.maysk.camera.data.VideoRes
import com.maysk.camera.media.MediaStoreRepo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/** Ручные настройки (режим «Профи»). null = авто. */
data class ManualSettings(
    val iso: Int? = null,
    val exposureNs: Long? = null,
    val aperture: Float? = null,
    val awbMode: Int = CameraMetadata.CONTROL_AWB_MODE_AUTO,
    val focusDiopters: Float? = null,
    val macroFocus: Boolean = false,
)

data class BindConfig(
    val mode: CameraMode,
    val front: Boolean,
    val ultraWideLens: Boolean = false,
    val videoRes: VideoRes = VideoRes.FHD,
    val fps: Int = 30,
    val stabilization: Stabilization = Stabilization.STANDARD,
    val bitrateMbps: Int = 16,
    val flashMode: Int = ImageCapture.FLASH_MODE_OFF,
    val liveEffects: Boolean = true,
    val useExtension: Boolean = true,
    val lowResCapture: Boolean = false,
    /** Принудительное расширение производителя (например HDR из верхней панели). */
    val extensionOverride: Int? = null,
)

data class CamState(
    val ready: Boolean = false,
    val front: Boolean = false,
    val zoom: Float = 1f,
    val minZoom: Float = 1f,
    val maxZoom: Float = 1f,
    val hasUltraWide: Boolean = false,
    val usingUltraWideLens: Boolean = false,
    val activeExtension: Int? = null,
    val backExtensions: Set<Int> = emptySet(),
    val frontExtensions: Set<Int> = emptySet(),
    val evIndex: Int = 0,
    val evMin: Int = 0,
    val evMax: Int = 0,
    val evStep: Float = 0f,
    val supportedRes: List<VideoRes> = listOf(VideoRes.HD, VideoRes.FHD),
    val fpsOptions: List<Int> = listOf(30),
    val isoRange: IntRange? = null,
    val exposureRangeNs: LongRange? = null,
    val apertures: List<Float> = emptyList(),
    val manualSupported: Boolean = false,
    val liveIso: Int? = null,
    val liveExposureNs: Long? = null,
    val liveAperture: Float? = null,
    val recording: Boolean = false,
    val paused: Boolean = false,
    val recordSeconds: Long = 0,
    val error: String? = null,
    val effectsActive: Boolean = false,
)

private data class LensInfo(val id: String, val front: Boolean, val fov: Float, val info: CameraInfo)

@OptIn(ExperimentalCamera2Interop::class)
class CameraController(private val context: Context) {

    val processor = GlEffectProcessor()
    private val main = ContextCompat.getMainExecutor(context)
    private val analysisExecutor = Executors.newSingleThreadExecutor()
    private val captureExecutor = Executors.newSingleThreadExecutor()

    private var provider: ProcessCameraProvider? = null
    private var extensions: ExtensionsManager? = null
    private var lenses: List<LensInfo> = emptyList()

    var camera: Camera? = null
        private set
    private var imageCapture: ImageCapture? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var analysis: ImageAnalysis? = null
    private var recording: Recording? = null
    private var lastConfig: BindConfig? = null

    var analyzer: ImageAnalysis.Analyzer? = null
    var manual: ManualSettings = ManualSettings()
        private set

    private val _state = MutableStateFlow(CamState())
    val state: StateFlow<CamState> = _state

    private var lastLiveUpdate = 0L

    suspend fun init() {
        if (provider != null) return
        val p = ProcessCameraProvider.getInstance(context).await()
        provider = p
        extensions = runCatching { ExtensionsManager.getInstanceAsync(context, p).await() }.getOrNull()
        lenses = p.availableCameraInfos.mapNotNull { info ->
            runCatching {
                val c2 = Camera2CameraInfo.from(info)
                val facing = c2.getCameraCharacteristic(CameraCharacteristics.LENS_FACING)
                val focal = c2.getCameraCharacteristic(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.minOrNull() ?: 4f
                val sensor = c2.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
                val fov = (sensor?.width ?: 5f) / focal
                LensInfo(c2.cameraId, facing == CameraCharacteristics.LENS_FACING_FRONT, fov, info)
            }.getOrNull()
        }
        val ext = extensions
        fun extSet(sel: CameraSelector): Set<Int> = if (ext == null) emptySet() else
            listOf(ExtensionMode.NIGHT, ExtensionMode.HDR, ExtensionMode.BOKEH, ExtensionMode.FACE_RETOUCH, ExtensionMode.AUTO)
                .filter { m -> runCatching { ext.isExtensionAvailable(sel, m) }.getOrDefault(false) }.toSet()
        _state.update {
            it.copy(
                backExtensions = extSet(CameraSelector.DEFAULT_BACK_CAMERA),
                frontExtensions = extSet(CameraSelector.DEFAULT_FRONT_CAMERA),
            )
        }
    }

    private fun mainBack(): LensInfo? = lenses.firstOrNull { !it.front }
    private fun ultraWideLens(): LensInfo? {
        val m = mainBack() ?: return null
        return lenses.filter { !it.front && it.id != m.id }.maxByOrNull { it.fov }?.takeIf { it.fov > m.fov * 1.3f }
    }

    private fun selectorFor(cfg: BindConfig): CameraSelector {
        if (!cfg.front && cfg.ultraWideLens) {
            ultraWideLens()?.let { uw ->
                return CameraSelector.Builder().addCameraFilter { infos ->
                    infos.filter { runCatching { Camera2CameraInfo.from(it).cameraId == uw.id }.getOrDefault(false) }
                }.build()
            }
        }
        return if (cfg.front) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
    }

    @SuppressLint("RestrictedApi")
    fun bind(owner: LifecycleOwner, previewView: PreviewView, cfg: BindConfig) {
        val prov = provider ?: return
        if (recording != null) return
        lastConfig = cfg
        prov.unbindAll()

        var selector = selectorFor(cfg)
        var ext: Int? = null
        val extMode = cfg.extensionOverride ?: cfg.mode.extension
        val em = extensions
        if (cfg.useExtension && extMode != null && !cfg.mode.recordsVideo && em != null && !cfg.ultraWideLens) {
            if (runCatching { em.isExtensionAvailable(selector, extMode) }.getOrDefault(false)) {
                selector = em.getExtensionEnabledCameraSelector(selector, extMode)
                ext = extMode
            }
        }

        val aspect = if (cfg.mode.recordsVideo) AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY
        else AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY

        val previewBuilder = Preview.Builder()
            .setResolutionSelector(ResolutionSelector.Builder().setAspectRatioStrategy(aspect).build())
        Camera2Interop.Extender(previewBuilder).setSessionCaptureCallback(object : CameraCaptureSession.CaptureCallback() {
            override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
                val now = SystemClock.elapsedRealtime()
                if (now - lastLiveUpdate < 300) return
                lastLiveUpdate = now
                val iso = result.get(android.hardware.camera2.CaptureResult.SENSOR_SENSITIVITY)
                val exp = result.get(android.hardware.camera2.CaptureResult.SENSOR_EXPOSURE_TIME)
                val ap = result.get(android.hardware.camera2.CaptureResult.LENS_APERTURE)
                _state.update { it.copy(liveIso = iso, liveExposureNs = exp, liveAperture = ap) }
            }
        })
        val preview = previewBuilder.build().also { it.setSurfaceProvider(previewView.surfaceProvider) }

        val useCases = mutableListOf<UseCase>(preview)

        if (!cfg.mode.recordsVideo) {
            val res = if (cfg.lowResCapture)
                ResolutionSelector.Builder().setAspectRatioStrategy(aspect)
                    .setResolutionStrategy(ResolutionStrategy(Size(1920, 1440), ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER)).build()
            else ResolutionSelector.Builder().setAspectRatioStrategy(aspect)
                .setResolutionStrategy(ResolutionStrategy.HIGHEST_AVAILABLE_STRATEGY).build()
            val fast = cfg.mode == CameraMode.PET || cfg.mode == CameraMode.BABY || cfg.mode == CameraMode.PANORAMA || cfg.mode == CameraMode.NIGHT
            imageCapture = ImageCapture.Builder()
                .setCaptureMode(if (fast) ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY else ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                .setResolutionSelector(res)
                .setFlashMode(cfg.flashMode)
                .setJpegQuality(96)
                .build()
            useCases += imageCapture!!
            videoCapture = null
        } else {
            imageCapture = null
            val q = when (cfg.videoRes) {
                VideoRes.HD -> Quality.HD
                VideoRes.FHD -> Quality.FHD
                VideoRes.UHD, VideoRes.UHD8K -> Quality.UHD
            }
            val pixelsFactor = when (cfg.videoRes) { VideoRes.HD -> 0.5f; VideoRes.FHD -> 1f; else -> 3f } * (cfg.fps / 30f).coerceAtLeast(1f)
            val recorder = Recorder.Builder()
                .setQualitySelector(QualitySelector.from(q, FallbackStrategy.lowerQualityOrHigherThan(q)))
                .setTargetVideoEncodingBitRate((cfg.bitrateMbps * pixelsFactor * 1_000_000).roundToInt())
                .build()
            videoCapture = VideoCapture.Builder(recorder)
                .setMirrorMode(MirrorMode.MIRROR_MODE_ON_FRONT_ONLY)
                .build()
            useCases += videoCapture!!
        }

        analysis = analyzer?.let { a ->
            ImageAnalysis.Builder()
                .setResolutionSelector(
                    ResolutionSelector.Builder().setResolutionStrategy(
                        ResolutionStrategy(Size(640, 480), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER)
                    ).build()
                )
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .build().also { it.setAnalyzer(analysisExecutor, a) }
        }

        val effect: CameraEffect? = if (cfg.liveEffects && ext == null)
            processor.createEffect(if (cfg.mode.recordsVideo) CameraEffect.PREVIEW or CameraEffect.VIDEO_CAPTURE else CameraEffect.PREVIEW)
        else null

        fun tryBind(withAnalysis: Boolean, withEffect: Boolean): Camera? = runCatching {
            prov.unbindAll()
            val g = UseCaseGroup.Builder()
            useCases.forEach { g.addUseCase(it) }
            if (withAnalysis) analysis?.let { g.addUseCase(it) }
            if (withEffect) effect?.let { g.addEffect(it) }
            previewView.viewPort?.let { g.setViewPort(it) }
            prov.bindToLifecycle(owner, selector, g.build())
        }.onFailure { Log.w(TAG, "bind failed (analysis=$withAnalysis effect=$withEffect)", it) }.getOrNull()

        var effectsOn = effect != null
        val cam = tryBind(true, true) ?: tryBind(false, true) ?: run { effectsOn = false; tryBind(true, false) } ?: tryBind(false, false)
        camera = cam
        if (cam == null) {
            _state.update { it.copy(ready = false, error = "Не удалось запустить камеру") }
            return
        }
        imageCapture?.targetRotation = targetRotation
        videoCapture?.targetRotation = targetRotation
        observeCamera(cam, cfg, ext, effectsOn)
        applyRequestOptions()
    }

    private fun observeCamera(cam: Camera, cfg: BindConfig, ext: Int?, effectsOn: Boolean) {
        val info = cam.cameraInfo
        val c2 = runCatching { Camera2CameraInfo.from(info) }.getOrNull()
        val zs = info.zoomState.value
        val es = info.exposureState
        val supportedRes = runCatching {
            val caps = Recorder.getVideoCapabilities(info)
            val qs = caps.getSupportedQualities(androidx.camera.core.DynamicRange.SDR)
            buildList {
                if (qs.contains(Quality.HD)) add(VideoRes.HD)
                if (qs.contains(Quality.FHD)) add(VideoRes.FHD)
                if (qs.contains(Quality.UHD)) add(VideoRes.UHD)
            }
        }.getOrDefault(listOf(VideoRes.HD, VideoRes.FHD))
        val fpsRanges = c2?.getCameraCharacteristic(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
        val fpsOptions = (fpsRanges?.filter { it.lower == it.upper }?.map { it.upper }?.filter { it in listOf(24, 30, 60, 120, 240) }
            ?: emptyList()).plus(30).distinct().sorted()
        val iso = c2?.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
        val expo = c2?.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
        val caps = c2?.getCameraCharacteristic(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
        val manualOk = caps?.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR) == true
        val apertures = c2?.getCameraCharacteristic(CameraCharacteristics.LENS_INFO_AVAILABLE_APERTURES)?.toList() ?: emptyList()
        val uw = ultraWideLens() != null || (zs?.minZoomRatio ?: 1f) < 0.95f

        _state.update {
            it.copy(
                ready = true, front = cfg.front, error = null,
                zoom = zs?.zoomRatio ?: 1f, minZoom = zs?.minZoomRatio ?: 1f, maxZoom = zs?.maxZoomRatio ?: 1f,
                hasUltraWide = uw, usingUltraWideLens = cfg.ultraWideLens && !cfg.front && ultraWideLens() != null,
                activeExtension = ext,
                evIndex = es.exposureCompensationIndex, evMin = es.exposureCompensationRange.lower,
                evMax = es.exposureCompensationRange.upper, evStep = es.exposureCompensationStep.toFloat(),
                supportedRes = supportedRes, fpsOptions = fpsOptions,
                isoRange = iso?.let { r -> r.lower..r.upper }, exposureRangeNs = expo?.let { r -> r.lower..r.upper },
                apertures = apertures, manualSupported = manualOk, effectsActive = effectsOn,
            )
        }
        zoomObserver?.let { o -> zoomSource?.removeObserver(o) }
        val obs = Observer<ZoomState> { z ->
            _state.update { it.copy(zoom = z.zoomRatio, minZoom = z.minZoomRatio, maxZoom = z.maxZoomRatio) }
        }
        info.zoomState.observeForever(obs)
        zoomSource = info.zoomState
        zoomObserver = obs
    }

    private var zoomObserver: Observer<ZoomState>? = null
    private var zoomSource: LiveData<ZoomState>? = null

    /** Собирает все Camera2-параметры запроса в одном месте. */
    fun applyRequestOptions(m: ManualSettings = manual) {
        manual = m
        val cam = camera ?: return
        val cfg = lastConfig ?: return
        if (_state.value.activeExtension != null) return
        val c2 = runCatching { Camera2CameraInfo.from(cam.cameraInfo) }.getOrNull() ?: return
        val b = CaptureRequestOptions.Builder()

        // FPS
        val ranges = c2.getCameraCharacteristic(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
        val wantFps = when (cfg.mode) {
            CameraMode.CINEMATIC -> 24
            CameraMode.SLOW_MOTION -> ranges?.maxOfOrNull { it.upper } ?: 30
            else -> if (cfg.mode.recordsVideo) cfg.fps else 0
        }
        if (wantFps > 0) {
            ranges?.filter { it.upper == wantFps }?.maxByOrNull { it.lower }?.let {
                b.setCaptureRequestOption(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, Range(it.lower, it.upper))
            }
        }

        // Стабилизация: собственная гироскопная (EIS, работает через GL-конвейер) или встроенная в телефон
        if (cfg.mode.recordsVideo) {
            val modes = c2.getCameraCharacteristic(CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES)?.toList() ?: emptyList()
            val stab = cfg.stabilization
            val softwareActive = stab.software && _state.value.effectsActive
            val previewStab = Build.VERSION.SDK_INT >= 33 && modes.contains(CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_PREVIEW_STABILIZATION)
            val v = when {
                stab == Stabilization.OFF -> CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_OFF
                softwareActive -> CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_OFF   // считаем сами, без двойной компенсации
                stab == Stabilization.STANDARD -> CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_ON
                previewStab -> CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_PREVIEW_STABILIZATION
                else -> CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_ON
            }
            if (modes.contains(v)) b.setCaptureRequestOption(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, v)
        }
        val ois = c2.getCameraCharacteristic(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION)?.toList() ?: emptyList()
        if (ois.contains(CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_ON) && cfg.stabilization != Stabilization.OFF)
            b.setCaptureRequestOption(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE, CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_ON)

        // Детализация: аппаратная резкость высокого качества для фото
        val edges = c2.getCameraCharacteristic(CameraCharacteristics.EDGE_AVAILABLE_EDGE_MODES)?.toList() ?: emptyList()
        if (!cfg.mode.recordsVideo && edges.contains(CameraMetadata.EDGE_MODE_HIGH_QUALITY))
            b.setCaptureRequestOption(CaptureRequest.EDGE_MODE, CameraMetadata.EDGE_MODE_HIGH_QUALITY)

        // Исправление дисторсии (особенно важно для 0.5x)
        val dist = distortionModes(c2)
        if (dist.contains(CameraMetadata.DISTORTION_CORRECTION_MODE_HIGH_QUALITY))
            b.setCaptureRequestOption(CaptureRequest.DISTORTION_CORRECTION_MODE, CameraMetadata.DISTORTION_CORRECTION_MODE_HIGH_QUALITY)

        // Шумоподавление и детализация
        val nr = c2.getCameraCharacteristic(CameraCharacteristics.NOISE_REDUCTION_AVAILABLE_NOISE_REDUCTION_MODES)?.toList() ?: emptyList()
        if (!cfg.mode.recordsVideo && nr.contains(CameraMetadata.NOISE_REDUCTION_MODE_HIGH_QUALITY))
            b.setCaptureRequestOption(CaptureRequest.NOISE_REDUCTION_MODE, CameraMetadata.NOISE_REDUCTION_MODE_HIGH_QUALITY)

        // Сцена производителя
        val scenes = c2.getCameraCharacteristic(CameraCharacteristics.CONTROL_AVAILABLE_SCENE_MODES)?.toList() ?: emptyList()
        val scene = when (cfg.mode) {
            CameraMode.PORTRAIT -> CameraMetadata.CONTROL_SCENE_MODE_PORTRAIT
            CameraMode.LANDSCAPE -> CameraMetadata.CONTROL_SCENE_MODE_LANDSCAPE
            CameraMode.NIGHT -> CameraMetadata.CONTROL_SCENE_MODE_NIGHT
            CameraMode.PET, CameraMode.CAR -> CameraMetadata.CONTROL_SCENE_MODE_SPORTS
            else -> null
        }
        val manualActive = m.iso != null || m.exposureNs != null || m.focusDiopters != null || m.awbMode != CameraMetadata.CONTROL_AWB_MODE_AUTO
        if (scene != null && scenes.contains(scene) && !manualActive) {
            b.setCaptureRequestOption(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_USE_SCENE_MODE)
            b.setCaptureRequestOption(CaptureRequest.CONTROL_SCENE_MODE, scene)
        }

        // Макро
        val afModes = c2.getCameraCharacteristic(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES)?.toList() ?: emptyList()
        if ((cfg.mode == CameraMode.MACRO || m.macroFocus) && afModes.contains(CameraMetadata.CONTROL_AF_MODE_MACRO) && m.focusDiopters == null)
            b.setCaptureRequestOption(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_MACRO)

        // Профи
        if (m.iso != null || m.exposureNs != null) {
            val isoR = c2.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
            val expR = c2.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
            val iso = (m.iso ?: _state.value.liveIso ?: 100).let { v -> isoR?.let { v.coerceIn(it.lower, it.upper) } ?: v }
            val exp = (m.exposureNs ?: _state.value.liveExposureNs ?: 10_000_000L).let { v -> expR?.let { v.coerceIn(it.lower, it.upper) } ?: v }
            b.setCaptureRequestOption(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_OFF)
            b.setCaptureRequestOption(CaptureRequest.SENSOR_SENSITIVITY, iso)
            b.setCaptureRequestOption(CaptureRequest.SENSOR_EXPOSURE_TIME, exp)
            b.setCaptureRequestOption(CaptureRequest.SENSOR_FRAME_DURATION, max(exp, 33_333_333L))
        }
        if (m.awbMode != CameraMetadata.CONTROL_AWB_MODE_AUTO) {
            val awb = c2.getCameraCharacteristic(CameraCharacteristics.CONTROL_AWB_AVAILABLE_MODES)?.toList() ?: emptyList()
            if (awb.contains(m.awbMode)) b.setCaptureRequestOption(CaptureRequest.CONTROL_AWB_MODE, m.awbMode)
        }
        m.focusDiopters?.let { d ->
            val minFocus = c2.getCameraCharacteristic(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f
            if (minFocus > 0f) {
                b.setCaptureRequestOption(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_OFF)
                b.setCaptureRequestOption(CaptureRequest.LENS_FOCUS_DISTANCE, d.coerceIn(0f, minFocus))
            }
        }
        m.aperture?.let { a -> if (_state.value.apertures.size > 1) b.setCaptureRequestOption(CaptureRequest.LENS_APERTURE, a) }

        runCatching { Camera2CameraControl.from(cam.cameraControl).setCaptureRequestOptions(b.build()) }
            .onFailure { Log.w(TAG, "request options", it) }
    }

    private fun distortionModes(c2: Camera2CameraInfo): List<Int> =
        c2.getCameraCharacteristic(CameraCharacteristics.DISTORTION_CORRECTION_AVAILABLE_MODES)?.toList() ?: emptyList()

    /** Делает ли сам телефон коррекцию дисторсии (тогда наша программная не нужна). */
    fun hardwareDistortionCorrection(): Boolean = camera?.let { c ->
        runCatching { distortionModes(Camera2CameraInfo.from(c.cameraInfo)).contains(CameraMetadata.DISTORTION_CORRECTION_MODE_HIGH_QUALITY) }.getOrDefault(false)
    } ?: false

    /**
     * tan(половины угла обзора) по горизонтали и вертикали вертикального кадра для текущей камеры
     * (без учёта зума). Нужен гироскопной стабилизации.
     */
    fun fovTangents(): Pair<Float, Float> {
        val cam = camera ?: return 0.45f to 0.6f
        return runCatching {
            val c2 = Camera2CameraInfo.from(cam.cameraInfo)
            val f = c2.getCameraCharacteristic(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.minOrNull() ?: return 0.45f to 0.6f
            val size = c2.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE) ?: return 0.45f to 0.6f
            val orient = c2.getCameraCharacteristic(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
            // сенсор «лежит» горизонтально: в вертикальном кадре ширина = короткая сторона
            val (wu, hv) = if (orient % 180 == 90) size.height to size.width else size.width to size.height
            (wu / (2 * f)) to (hv / (2 * f))
        }.getOrDefault(0.45f to 0.6f)
    }

    fun minFocusDiopters(): Float = camera?.let {
        runCatching { Camera2CameraInfo.from(it.cameraInfo).getCameraCharacteristic(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) }.getOrNull()
    } ?: 0f

    // region управление
    fun setZoom(ratio: Float) {
        val s = _state.value
        camera?.cameraControl?.setZoomRatio(ratio.coerceIn(s.minZoom, s.maxZoom))
    }

    /** Плавный переход зума (1x → 2x без «скачка» картинки). */
    suspend fun animateZoom(target: Float, durationMs: Long = 350) {
        val cam = camera ?: return
        val s = _state.value
        val from = s.zoom
        val to = target.coerceIn(s.minZoom, s.maxZoom)
        val steps = 12
        for (i in 1..steps) {
            val t = i / steps.toFloat()
            val e = t * t * (3 - 2 * t)
            cam.cameraControl.setZoomRatio(from + (to - from) * e)
            kotlinx.coroutines.delay(durationMs / steps)
        }
    }

    fun setExposureIndex(i: Int) {
        val s = _state.value
        val v = i.coerceIn(s.evMin, s.evMax)
        camera?.cameraControl?.setExposureCompensationIndex(v)
        _state.update { it.copy(evIndex = v) }
    }

    private var targetRotation: Int = android.view.Surface.ROTATION_0

    /** Ориентация устройства (Surface.ROTATION_*) для правильного поворота фото/видео. */
    fun setTargetRotation(rotation: Int) {
        if (rotation == targetRotation) return
        targetRotation = rotation
        imageCapture?.targetRotation = rotation
        if (recording == null) videoCapture?.targetRotation = rotation
    }

    fun setFlash(mode: Int) {
        imageCapture?.flashMode = mode
    }

    fun setTorch(on: Boolean) {
        camera?.cameraControl?.enableTorch(on)
    }

    fun tapToFocus(previewView: PreviewView, x: Float, y: Float) {
        val cam = camera ?: return
        val point = previewView.meteringPointFactory.createPoint(x, y)
        cam.cameraControl.startFocusAndMetering(
            FocusMeteringAction.Builder(point, FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE)
                .setAutoCancelDuration(4, TimeUnit.SECONDS).build()
        )
    }

    /** Фокус на лицо по нормализованным координатам «вертикального» кадра анализа. */
    fun focusOnUpright(xn: Float, yn: Float, rotation: Int) {
        val cam = camera ?: return
        val a = analysis ?: return
        val (bx, by) = when (rotation) {
            90 -> Pair(yn, 1f - xn)
            180 -> Pair(1f - xn, 1f - yn)
            270 -> Pair(1f - yn, xn)
            else -> Pair(xn, yn)
        }
        val f = SurfaceOrientedMeteringPointFactory(1f, 1f, a)
        // только автофокус: повторный замер экспозиции вызывал «прыжки» яркости
        cam.cameraControl.startFocusAndMetering(
            FocusMeteringAction.Builder(f.createPoint(bx, by), FocusMeteringAction.FLAG_AF)
                .setAutoCancelDuration(2, TimeUnit.SECONDS).build()
        )
    }
    // endregion

    // region съёмка
    /** Один кадр, уже повёрнутый вертикально. */
    suspend fun captureBitmap(mirrorFront: Boolean): Bitmap {
        val ic = imageCapture ?: throw IllegalStateException("Фото недоступно в этом режиме")
        val front = _state.value.front
        return suspendCancellableCoroutine { cont ->
            ic.takePicture(captureExecutor, object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(image: ImageProxy) {
                    try {
                        val bmp = image.toBitmap()
                        val rot = image.imageInfo.rotationDegrees
                        image.close()
                        cont.resume(ImageOps.rotate(bmp, rot, front && mirrorFront))
                    } catch (t: Throwable) {
                        image.close()
                        cont.resumeWithException(t)
                    }
                }

                override fun onError(exception: ImageCaptureException) {
                    cont.resumeWithException(exception)
                }
            })
        }
    }

    /** Серия кадров (ночь, 0.5x super-resolution, шумоподавление). */
    suspend fun captureBurst(count: Int, mirrorFront: Boolean, onProgress: (Int) -> Unit = {}): List<Bitmap> {
        val out = ArrayList<Bitmap>(count)
        for (i in 0 until count) {
            val b = captureBitmap(mirrorFront)
            out += ImageOps.limitSize(b, 4000)
            onProgress(i + 1)
        }
        return out
    }

    /** Брекетинг экспозиции для HDR (−, 0, +). */
    suspend fun captureBracket(mirrorFront: Boolean): List<Bitmap> {
        val cam = camera ?: return emptyList()
        val s = _state.value
        val step = if (s.evStep > 0f) (1f / s.evStep).roundToInt().coerceAtLeast(1) else 0
        if (step == 0 || s.evMax <= 0) return listOf(captureBitmap(mirrorFront))
        val base = s.evIndex
        val list = mutableListOf<Bitmap>()
        for (k in listOf(-2 * step, 0, 2 * step)) {
            val idx = (base + k).coerceIn(s.evMin, s.evMax)
            runCatching { cam.cameraControl.setExposureCompensationIndex(idx).await() }
            kotlinx.coroutines.delay(150)
            list += ImageOps.limitSize(captureBitmap(mirrorFront), 4000)
        }
        runCatching { cam.cameraControl.setExposureCompensationIndex(base).await() }
        return list
    }
    // endregion

    // region видео
    @SuppressLint("MissingPermission")
    fun startRecording(withAudio: Boolean, onFinish: (Uri?, String?) -> Unit): Boolean {
        val vc = videoCapture ?: return false
        if (recording != null) return false
        val opts = MediaStoreOutputOptions.Builder(context.contentResolver, MediaStore.Video.Media.EXTERNAL_CONTENT_URI)
            .setContentValues(MediaStoreRepo.videoContentValues())
            .build()
        var pending = vc.output.prepareRecording(context, opts)
        if (withAudio) pending = pending.withAudioEnabled()
        recording = pending.start(main) { e ->
            when (e) {
                is VideoRecordEvent.Start -> _state.update { it.copy(recording = true, paused = false, recordSeconds = 0) }
                is VideoRecordEvent.Pause -> _state.update { it.copy(paused = true) }
                is VideoRecordEvent.Resume -> _state.update { it.copy(paused = false) }
                is VideoRecordEvent.Status -> {
                    val sec = TimeUnit.NANOSECONDS.toSeconds(e.recordingStats.recordedDurationNanos)
                    if (sec != _state.value.recordSeconds) _state.update { it.copy(recordSeconds = sec) }
                }
                is VideoRecordEvent.Finalize -> {
                    recording = null
                    _state.update { it.copy(recording = false, paused = false) }
                    val ok = !e.hasError() || e.error == VideoRecordEvent.Finalize.ERROR_SOURCE_INACTIVE
                    onFinish(if (ok) e.outputResults.outputUri.takeIf { it != Uri.EMPTY } else null,
                        if (ok) null else "Ошибка записи (${e.error})")
                }
                else -> Unit
            }
        }
        return true
    }

    fun stopRecording() {
        recording?.stop()
    }

    fun pauseResume() {
        val r = recording ?: return
        if (_state.value.paused) r.resume() else r.pause()
    }
    // endregion

    fun unbind() {
        provider?.unbindAll()
        camera = null
        _state.update { it.copy(ready = false) }
    }

    fun shutdown() {
        unbind()
        processor.release()
        analysisExecutor.shutdown()
        captureExecutor.shutdown()
    }

    companion object {
        private const val TAG = "MayskCamera"
    }
}

suspend fun <T> ListenableFuture<T>.await(): T = suspendCancellableCoroutine { cont ->
    addListener({
        try {
            cont.resume(get())
        } catch (t: Throwable) {
            cont.resumeWithException(t.cause ?: t)
        }
    }, Runnable::run)
    cont.invokeOnCancellation { cancel(false) }
}

@Suppress("unused")
private fun closeTo(a: Float, b: Float) = abs(a - b) < 0.01f
