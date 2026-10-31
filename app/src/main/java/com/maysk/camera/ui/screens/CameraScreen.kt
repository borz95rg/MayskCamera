package com.maysk.camera.ui.screens

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.view.PreviewView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.displayCutoutPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material.icons.filled.Exposure
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.FlashAuto
import androidx.compose.material.icons.filled.FlashOff
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.HdrOff
import androidx.compose.material.icons.filled.HdrOn
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PhotoFilter
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.Timer10
import androidx.compose.material.icons.filled.Timer3
import androidx.compose.material.icons.filled.TimerOff
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Button
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.ui.graphics.Brush
import androidx.compose.material.icons.filled.CropFree
import androidx.compose.material.icons.filled.GridOn
import androidx.compose.material.icons.filled.Straighten
import androidx.compose.material.icons.filled.AspectRatio
import androidx.compose.material.icons.filled.Vibration
import androidx.compose.material.icons.filled.LocalFlorist
import androidx.compose.material.icons.filled.NightsStay
import androidx.compose.material.icons.filled.Portrait
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Mood
import androidx.compose.material.icons.filled.PictureInPicture
import androidx.compose.material.icons.filled.AllInclusive
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.asImageBitmap
import com.maysk.camera.ai.SceneType
import com.maysk.camera.ui.theme.MayskTheme
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.runtime.mutableIntStateOf
import android.view.OrientationEventListener
import android.view.Surface
import android.view.WindowManager
import kotlinx.coroutines.withTimeoutOrNull
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.LocalLifecycleOwner
import coil.compose.AsyncImage
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions
import com.google.mlkit.vision.documentscanner.GmsDocumentScanning
import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult
import com.maysk.camera.CameraViewModel
import com.maysk.camera.FlashMode
import com.maysk.camera.ai.ImageOps
import com.maysk.camera.camera.CameraMode
import com.maysk.camera.camera.Effect
import com.maysk.camera.camera.Filters
import com.maysk.camera.camera.ManualSettings
import com.maysk.camera.media.MediaStoreRepo
import com.maysk.camera.ui.components.AccentGradient
import com.maysk.camera.ui.components.ChoiceRow
import com.maysk.camera.ui.components.LabeledSlider
import com.maysk.camera.ui.components.Pill
import com.maysk.camera.ui.components.icon
import com.maysk.camera.ui.theme.Accent
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.atan2

private enum class Panel { NONE, FILTERS, EFFECTS, BEAUTY, PRO, EV, FRAMES, MASKS, SNAP, OVERLAY }

/** Угол поворота иконок при горизонтальном положении телефона. */
private val LocalIconRotation = androidx.compose.runtime.compositionLocalOf { 0f }

data class CameraNav(
    val openModes: () -> Unit,
    val openGallery: () -> Unit,
    val openViewer: (Uri, Boolean) -> Unit,
    val openSettings: () -> Unit,
    val openVideoSettings: () -> Unit,
    val openAiVideo: () -> Unit,
    val openProSettings: () -> Unit,
)

private val QuickModes = listOf(CameraMode.PORTRAIT, CameraMode.PHOTO, CameraMode.VIDEO, CameraMode.CINEMATIC, CameraMode.VLOG, CameraMode.NIGHT, CameraMode.AI_AUTO, CameraMode.PRO)

@Composable
fun CameraScreen(vm: CameraViewModel, nav: CameraNav) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val ui by vm.ui.collectAsState()
    val cam by vm.cam.collectAsState()
    val settings by vm.settings.collectAsState()
    val live by vm.live.collectAsState()
    val scope = rememberCoroutineScope()
    var panel by remember { mutableStateOf(Panel.NONE) }
    var focusPoint by remember { mutableStateOf<Offset?>(null) }

    val previewView = remember {
        PreviewView(context).apply {
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
    }

    DisposableEffect(lifecycleOwner) {
        vm.attach(lifecycleOwner, previewView)
        onDispose { vm.detach() }
    }
    LaunchedEffect(Unit) { vm.shutterEvents.collect { vm.shutter() } }
    LaunchedEffect(ui.message) { if (ui.message != null) { delay(2600); vm.consumeMessage() } }
    LaunchedEffect(focusPoint) { if (focusPoint != null) { delay(1200); focusPoint = null } }
    LaunchedEffect(settings.voiceControl) { if (settings.voiceControl) vm.startVoice() else vm.stopVoice() }

    val haptic = LocalHapticFeedback.current
    // Поворот телефона: иконки поворачиваются, фото/видео сохраняются в правильной ориентации
    var deviceRotation by remember { mutableIntStateOf(0) }
    DisposableEffect(Unit) {
        val listener = object : OrientationEventListener(context) {
            override fun onOrientationChanged(o: Int) {
                if (o == OrientationEventListener.ORIENTATION_UNKNOWN) return
                val r = when (o) { in 45..134 -> 270; in 135..224 -> 180; in 225..314 -> 90; else -> 0 }
                if (r != deviceRotation) {
                    deviceRotation = r
                    vm.setDeviceRotation(
                        when (r) { 90 -> Surface.ROTATION_90; 180 -> Surface.ROTATION_180; 270 -> Surface.ROTATION_270; else -> Surface.ROTATION_0 }
                    )
                }
            }
        }
        if (listener.canDetectOrientation()) listener.enable()
        onDispose { listener.disable() }
    }
    val iconRotation by animateFloatAsState(
        targetValue = when (deviceRotation) { 90 -> 90f; 270 -> -90f; 180 -> 180f; else -> 0f }, label = "iconRotation"
    )
    // Полноэкранный режим камеры: прячем строку состояния (появляется свайпом от края)
    DisposableEffect(Unit) {
        val activity = context as? android.app.Activity
        val ctl = activity?.let { androidx.core.view.WindowCompat.getInsetsController(it.window, it.window.decorView) }
        ctl?.systemBarsBehavior = androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        ctl?.hide(androidx.core.view.WindowInsetsCompat.Type.statusBars())
        onDispose { ctl?.show(androidx.core.view.WindowInsetsCompat.Type.statusBars()) }
    }

    // Вспышка экраном для селфи: максимальная яркость дисплея
    LaunchedEffect(ui.screenFlash) {
        val w = (context as? android.app.Activity)?.window ?: return@LaunchedEffect
        val lp = w.attributes
        lp.screenBrightness = if (ui.screenFlash) 1f else WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        w.attributes = lp
    }

    // Сканер документов (ML Kit)
    val scanLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { res ->
        val r = GmsDocumentScanningResult.fromActivityResultIntent(res.data) ?: return@rememberLauncherForActivityResult
        scope.launch {
            var last: Uri? = null
            r.pages?.forEach { p -> MediaStoreRepo.loadBitmap(context, p.imageUri)?.let { b -> last = MediaStoreRepo.saveJpeg(context, b, 95, name = MediaStoreRepo.newName("SCAN")) } }
            r.pdf?.uri?.let { MediaStoreRepo.savePdf(context, it) }
            vm.toast(if (last != null) "Документ сохранён (JPEG + PDF)" else "Сканирование отменено")
        }
    }
    fun launchScanner() {
        val activity = context as? ComponentActivity ?: return
        val opts = GmsDocumentScannerOptions.Builder()
            .setGalleryImportAllowed(true)
            .setPageLimit(30)
            .setResultFormats(GmsDocumentScannerOptions.RESULT_FORMAT_JPEG, GmsDocumentScannerOptions.RESULT_FORMAT_PDF)
            .setScannerMode(GmsDocumentScannerOptions.SCANNER_MODE_FULL)
            .build()
        GmsDocumentScanning.getClient(opts).getStartScanIntent(activity)
            .addOnSuccessListener { sender -> scanLauncher.launch(IntentSenderRequest.Builder(sender).build()) }
            .addOnFailureListener { vm.toast("Сканер недоступен: ${it.message}") }
    }
    LaunchedEffect(ui.mode) { if (ui.mode == CameraMode.DOCUMENT) launchScanner() }

    val isVideo = ui.mode.recordsVideo
    val recordingNow = cam.recording || ui.timelapseActive || ui.panoramaActive
    val aspect = if (isVideo) settings.videoAspect else settings.photoAspect
    var drawerOpen by remember { mutableStateOf(false) }
    var dismissedSuggestion by remember { mutableStateOf<SceneType?>(null) }

    // Кадр меняет размер (формат/поворот) → перепривязываем камеру, чтобы фото/видео совпадали с экраном
    DisposableEffect(previewView) {
        var lastW = 0; var lastH = 0
        val l = android.view.View.OnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
            if (v.width > 0 && v.height > 0 && (v.width != lastW || v.height != lastH)) {
                val first = lastW == 0
                lastW = v.width; lastH = v.height
                if (!first && !vm.cam.value.recording) v.post { vm.rebind() }
            }
        }
        previewView.addOnLayoutChangeListener(l)
        onDispose { previewView.removeOnLayoutChangeListener(l) }
    }

    // Свайп вверх — открыть шторку, вниз — закрыть
    val swipeModifier = Modifier.pointerInput(Unit) {
        var total = 0f
        detectVerticalDragGestures(
            onDragStart = { total = 0f },
            onDragEnd = {
                if (total < -60f) drawerOpen = true
                if (total > 60f) { drawerOpen = false; panel = Panel.NONE }
            },
            onVerticalDrag = { _, d -> total += d }
        )
    }

    androidx.compose.runtime.CompositionLocalProvider(LocalIconRotation provides iconRotation) {
    Box(Modifier.fillMaxSize().background(Color.Black)) {

        // ================= КАДР НА ВЕСЬ ЭКРАН =================
        val previewModifier = when (aspect) {
            com.maysk.camera.data.Aspect.FULL -> Modifier.fillMaxSize()
            com.maysk.camera.data.Aspect.R16_9 -> Modifier.fillMaxWidth().aspectRatio(9f / 16f).align(Alignment.Center)
            com.maysk.camera.data.Aspect.R4_3 -> Modifier.fillMaxWidth().aspectRatio(3f / 4f).align(Alignment.Center)
        }
        Box(previewModifier) {
            AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
            Box(
                Modifier
                    .fillMaxSize()
                    .cameraGestures(
                        onTap = { off ->
                            if (drawerOpen || panel != Panel.NONE) { drawerOpen = false; panel = Panel.NONE }
                            else {
                                focusPoint = off
                                vm.controller.tapToFocus(previewView, off.x, off.y)
                            }
                        },
                        onZoom = { z -> vm.controller.setZoom(vm.cam.value.zoom * z) },
                        onSwipe = { dy ->
                            if (cam.recording) {
                                val factor = kotlin.math.exp((-dy * 0.0025f).coerceIn(-0.35f, 0.35f))
                                vm.controller.setZoom((vm.cam.value.zoom * factor).coerceIn(vm.cam.value.minZoom, vm.cam.value.maxZoom))
                            } else if (dy < 0) drawerOpen = true else { drawerOpen = false; panel = Panel.NONE }
                        }
                    )
            )
            // Рамка видна в кадре ещё до снимка
            // рамка рисуется в самом кадре (GL); Compose-слой — только запасной вариант или «размытый фон» фото
            ui.frame?.takeIf { !(isVideo && it.style.photoOnly) && (!cam.effectsActive || it.style.photoOnly) }?.let { FrameLiveOverlay(it) }
            if (settings.grid) GridOverlay()
            if (settings.horizon && !recordingNow) HorizonOverlay()
            if (ui.arMask != com.maysk.camera.camera.ArMask.NONE) ArMaskOverlay(live.faces, ui.arMask, cam.front)
            else FaceOverlay(live.faces.map { it.box }, cam.front)
            if (ui.snapDrawing.isNotEmpty() || ui.snapDrawMode) Canvas(
                Modifier.fillMaxSize().pointerInput(ui.snapDrawMode) {
                    if (ui.snapDrawMode) detectDragGestures(
                        onDragStart = { p -> vm.addSnapPoint(p.x / size.width, p.y / size.height, true) },
                        onDrag = { change, _ -> change.consume(); vm.addSnapPoint(change.position.x / size.width, change.position.y / size.height) }
                    )
                }
            ) {
                ui.snapDrawing.forEach { stroke ->
                    stroke.zipWithNext().forEach { (a, b) ->
                        drawLine(Color.Cyan, Offset(a.x * size.width, a.y * size.height), Offset(b.x * size.width, b.y * size.height), strokeWidth = size.width * 0.009f)
                    }
                }
            }
            if (ui.snapCaption.isNotBlank()) Text(
                ui.snapCaption, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 90.dp)
            )
            if (ui.snapSticker.isNotBlank()) Text(ui.snapSticker, fontSize = 34.sp, modifier = Modifier.align(Alignment.TopEnd).padding(18.dp))
            focusPoint?.let { p ->
                Box(
                    Modifier
                        .offset { IntOffset(p.x.toInt() - 70, p.y.toInt() - 70) }
                        .size(48.dp)
                        .border(1.5.dp, Color(0xFFFFD54F), RoundedCornerShape(8.dp))
                )
            }
            if (ui.countdown > 0) Text("${ui.countdown}", color = Color.White, fontSize = 96.sp, fontWeight = FontWeight.Bold, modifier = Modifier.align(Alignment.Center))
            if (ui.panoramaActive || ui.mode == CameraMode.PANORAMA) PanoramaGuide(ui, Modifier.fillMaxSize())
            ui.busy?.takeIf { !ui.timelapseActive && !ui.panoramaActive }?.let { BusyOverlay(it, ui.progress) }
        }

        // ================= ВЕРХ =================
        Column(
            Modifier
                .fillMaxWidth()
                .align(Alignment.TopCenter)
                .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.55f), Color.Transparent)))
                .statusBarsPadding()
                .displayCutoutPadding()
        ) {
            if (!recordingNow) {
                TopBar(vm, ui.flash, ui.hdr, ui.aiPro, ui.timer, isVideo, cam.recording, ui.voiceListening, settings.voiceControl,
                    onSettings = { if (isVideo) nav.openVideoSettings() else nav.openSettings() })
            } else {
                // во время записи — только REC и время
                Row(
                    Modifier
                        .align(Alignment.CenterHorizontally)
                        .padding(top = 10.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(Color(0xCCE53935))
                        .padding(horizontal = 14.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(Modifier.size(9.dp).clip(CircleShape).background(Color.White))
                    Spacer(Modifier.width(8.dp))
                    val label = when {
                        ui.timelapseActive -> "TIMELAPSE ${ui.timelapseFrames}"
                        ui.panoramaActive -> "ПАНОРАМА ${ui.panoramaFrames}"
                        else -> "REC " + formatTime(cam.recordSeconds) + if (cam.paused) " ⏸" else ""
                    }
                    Text(label, color = Color.White, fontWeight = FontWeight.SemiBold)
                }
                if (cam.recording && vm.stabilizer.level.software) {
                    Text(
                        "Стабилизация: " + (if (vm.stabilizer.level == com.maysk.camera.data.Stabilization.AUTO) "Авто · " + vm.stabilizer.autoLabel else vm.stabilizer.level.label),
                        color = Color.White.copy(alpha = 0.75f), fontSize = 11.sp,
                        modifier = Modifier.align(Alignment.CenterHorizontally).padding(top = 4.dp)
                    )
                }
            }
            // AI подсказка + умный помощник
            if (!recordingNow) Column(Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
                if ((ui.aiPro || ui.mode == CameraMode.AI_AUTO) && ui.aiHint != null) {
                    Row(
                        Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color.Black.copy(alpha = 0.5f))
                            .padding(horizontal = 10.dp, vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Filled.AutoAwesome, null, tint = Accent, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(4.dp))
                        Text((ui.aiHint ?: "") + ui.aiActions.joinToString("") { " · $it" }, color = Color.White, fontSize = 12.sp)
                    }
                }
                val suggestion = suggestMode(live.scene, ui.mode, cam.macroAvailable)
                if (suggestion != null && dismissedSuggestion != live.scene) {
                    Row(
                        Modifier
                            .padding(top = 6.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(Accent.copy(alpha = 0.9f))
                            .clickable { haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove); vm.setMode(suggestion.second) }
                            .padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(suggestion.first, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                        Text("  ✕", color = Color.White, fontSize = 12.sp, modifier = Modifier.clickable { dismissedSuggestion = live.scene }.padding(4.dp))
                    }
                }
                ui.modeLookOffer?.let { id ->
                    Text(
                        "Применить вид режима «${Filters.byId(id).name}»", color = Color.White, fontSize = 12.sp,
                        modifier = Modifier.padding(top = 6.dp).clip(RoundedCornerShape(14.dp)).background(Color.Black.copy(alpha = 0.55f))
                            .border(1.dp, Accent, RoundedCornerShape(14.dp)).clickable { vm.applyModeLook() }.padding(horizontal = 12.dp, vertical = 5.dp)
                    )
                }
                if (ui.mode == CameraMode.PRO) Histogram(live.histogram, Modifier.padding(top = 6.dp))
            }
        }

        // ================= НИЗ =================
        Column(
            Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.6f))))
                .navigationBarsPadding()
                .then(swipeModifier)
        ) {
            if (ui.mode == CameraMode.PRO && !recordingNow) ProInfoRow(cam.liveIso, cam.liveExposureNs, cam.liveAperture ?: cam.apertures.firstOrNull(), Modifier)

            // во время записи фильтр, эффект и рамку можно менять — сразу видно и пишется в файл
            val liveDuringRec = cam.recording && !ui.timelapseActive && !ui.panoramaActive
            if (liveDuringRec) Row(
                Modifier.fillMaxWidth().padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally)
            ) {
                listOf(Panel.FILTERS to "Фильтр", Panel.EFFECTS to "Эффект", Panel.FRAMES to "Рамка").forEach { (p, t) ->
                    Pill(t, panel == p) { panel = if (panel == p) Panel.NONE else p }
                }
            }
            if (!cam.effectsActive && cam.ready) Text(
                "Живой фильтр недоступен на этой камере — фильтр будет применён к фото после снимка",
                color = Color.White, fontSize = 11.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp)
                    .clip(RoundedCornerShape(8.dp)).background(Color(0xAA5D1A1A)).padding(6.dp)
            )

            // панель выбранного инструмента
            val panelAllowed = !recordingNow || (liveDuringRec && (panel == Panel.FILTERS || panel == Panel.EFFECTS || panel == Panel.FRAMES))
            androidx.compose.animation.AnimatedVisibility(visible = panel != Panel.NONE && panelAllowed) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(Color(0xCC0E0B10))
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                ) {
                    when (panel) {
                        Panel.FILTERS -> FiltersPanel(ui.filterId, ui.filterIntensity, vm.liveThumb.collectAsState().value, vm::setFilter, vm::setFilterIntensity)
                        Panel.EFFECTS -> EffectsPanel(ui.effect, vm::setEffect)
                        Panel.BEAUTY -> BeautyPanel(vm, ui.beautyOn, settings.beautySkin, settings.beautyEyes, settings.beautyFace)
                        Panel.PRO -> ProPanel(vm, ui.manual, cam.isoRange, cam.manualSupported, onMore = nav.openProSettings)
                        Panel.EV -> CompactSlider(
                            "EV", cam.evIndex.toFloat(), "%+.1f".format(cam.evIndex * cam.evStep),
                            cam.evMin.toFloat()..maxOf(cam.evMax.toFloat(), cam.evMin + 1f)
                        ) { vm.controller.setExposureIndex(it.toInt()) }
                        Panel.FRAMES -> MayskTheme(com.maysk.camera.ui.theme.AppTheme.DARK) { FramePicker(ui.frame, videoMode = isVideo) { vm.setFrame(it) } }
                        Panel.MASKS -> MasksPanel(ui.arMask) { vm.setArMask(it) }
                        Panel.SNAP -> SnapPanel(vm, ui.snapMode, ui.snapSeconds, nav.openViewer)
                        Panel.OVERLAY -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("Текст на фотографии", color = Color.White, fontWeight = FontWeight.SemiBold)
                            OutlinedTextField(value = ui.snapCaption, onValueChange = vm::setSnapCaption,
                                modifier = Modifier.fillMaxWidth(), singleLine = true, placeholder = { Text("Добавить подпись…") })
                            Text("Подпись, дата, стикер и рисунок будут встроены в сохранённое фото.", color = Color.LightGray, fontSize = 11.sp)
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Pill(if (ui.snapDrawMode) "Рисуй пальцем: ВКЛ" else "Рисовать пальцем", ui.snapDrawMode) { vm.setSnapDrawMode(!ui.snapDrawMode) }
                                Pill("Очистить рисунок", false) { vm.clearSnapDrawing() }
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                listOf("", "✨", "❤️", "🔥", "⭐", "🌸", "🦋").forEach { sticker ->
                                    Pill(if (sticker.isBlank()) "Без" else sticker, ui.snapSticker == sticker) { vm.setSnapSticker(sticker) }
                                }
                            }
                        }
                        Panel.NONE -> Unit
                    }
                }
            }

            // шторка с инструментами
            androidx.compose.animation.AnimatedVisibility(visible = drawerOpen && !recordingNow) {
                ToolsDrawer(
                    vm = vm, ui = ui, settings = settings, isVideo = isVideo, panel = panel,
                    onPanel = { p -> panel = if (panel == p) Panel.NONE else p; drawerOpen = false },
                    onModes = nav.openModes, onPro = nav.openProSettings, onVideoSettings = nav.openVideoSettings,
                    onAiVideo = nav.openAiVideo,
                )
            }

            if (ui.mode == CameraMode.DUAL && !recordingNow) {
                LazyRow(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally)) {
                    items(com.maysk.camera.DualLayout.entries) { l -> Pill(l.title, ui.dualLayout == l) { vm.setDualLayout(l) } }
                }
            }
            if (!recordingNow) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)) {
                Pill(if (ui.snapMode) "Snap: ВКЛ" else "Snap", ui.snapMode) { vm.setSnapMode(!ui.snapMode); panel = Panel.SNAP }
                Pill("Текст / дата", panel == Panel.OVERLAY || ui.snapCaption.isNotBlank()) { panel = if (panel == Panel.OVERLAY) Panel.NONE else Panel.OVERLAY }
                Pill("Мои Snap", panel == Panel.SNAP) { panel = if (panel == Panel.SNAP) Panel.NONE else Panel.SNAP }
            }
            ZoomBar(cam.zoom, cam.minZoom, cam.maxZoom, cam.hasUltraWide, ui.ultraWide, Modifier.align(Alignment.CenterHorizontally).padding(vertical = 6.dp)) { vm.selectZoom(it) }

            if (!recordingNow) {
                // ручка шторки
                Box(
                    Modifier
                        .align(Alignment.CenterHorizontally)
                        .clickable { drawerOpen = !drawerOpen; if (!drawerOpen) panel = Panel.NONE }
                        .padding(horizontal = 30.dp, vertical = 2.dp)
                ) {
                    Text(if (drawerOpen) "︾" else "︽", color = Color.White.copy(alpha = 0.8f), fontSize = 14.sp)
                }
                ModeStrip(ui.mode, onSelect = {
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    panel = Panel.NONE; drawerOpen = false; vm.setMode(it)
                }, onMore = nav.openModes)
            }

            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 28.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                if (!recordingNow) {
                    Box(
                        Modifier
                            .size(50.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color(0x66222222))
                            .border(1.dp, Color.White.copy(alpha = 0.5f), RoundedCornerShape(12.dp))
                            .clickable { ui.lastUri?.let { nav.openViewer(it, ui.lastIsVideo) } ?: nav.openGallery() },
                        contentAlignment = Alignment.Center
                    ) {
                        if (ui.lastUri != null) AsyncImage(model = ui.lastUri, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize().rotate(iconRotation))
                    }
                } else Spacer(Modifier.size(50.dp))
                ShutterButton(
                    video = isVideo, recording = recordingNow,
                    busy = ui.busy != null && !ui.timelapseActive && !ui.panoramaActive,
                    burst = ui.burstCount,
                    onLongStart = if (isVideo) ({ haptic.performHapticFeedback(HapticFeedbackType.LongPress); vm.startRecordingHold() }) else ({ haptic.performHapticFeedback(HapticFeedbackType.LongPress); vm.startBurst() }),
                    onLongEnd = { if (isVideo) vm.stopRecordingHold() else vm.stopBurst() },
                ) {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    drawerOpen = false
                    if (ui.mode == CameraMode.DOCUMENT) launchScanner() else vm.shutter()
                }
                if (cam.recording) {
                    CircleIcon(if (cam.paused) Icons.Filled.PlayArrow else Icons.Filled.Pause) { vm.pauseResume() }
                } else {
                    CircleIcon(Icons.Filled.Cameraswitch) { vm.flipCamera() }
                }
            }
        }

        // всплывающее сообщение
        ui.message?.let { msg ->
            Box(
                Modifier
                    .align(Alignment.Center)
                    .padding(horizontal = 24.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(Color(0xE6222026))
                    .padding(horizontal = 16.dp, vertical = 10.dp)
            ) { Text(msg, color = Color.White, fontSize = 13.sp) }
        }
        // белый экран — вспышка для фронтальной камеры
        if (ui.screenFlash) Box(Modifier.fillMaxSize().background(Color(0xFFFFF8F0)))
    }
    }
}

/** Умный помощник: подсказывает режим под сцену. */
private fun suggestMode(scene: SceneType, current: CameraMode, macroOk: Boolean = true): Pair<String, CameraMode>? {
    if (current != CameraMode.PHOTO && current != CameraMode.AI_AUTO) return null
    return when (scene) {
        SceneType.NIGHT -> "🌙 Обнаружена ночь → Night Pro" to CameraMode.NIGHT
        SceneType.DOCUMENT -> "📄 Документ → Сканер" to CameraMode.DOCUMENT
        SceneType.FOOD -> "🍔 Еда → режим «Еда»" to CameraMode.FOOD
        SceneType.PET -> "🐕 Питомец → быстрый затвор" to CameraMode.PET
        SceneType.LANDSCAPE, SceneType.SKY -> "🌄 Пейзаж → HDR пейзаж" to CameraMode.LANDSCAPE
        SceneType.FLOWER -> if (macroOk) "🌸 Крупный план → Макро" to CameraMode.MACRO else null
        else -> null
    }
}

/** Шторка инструментов (открывается свайпом вверх). */
@Composable
private fun ToolsDrawer(
    vm: CameraViewModel, ui: com.maysk.camera.CameraUi, settings: com.maysk.camera.data.AppSettings, isVideo: Boolean, panel: Panel,
    onPanel: (Panel) -> Unit, onModes: () -> Unit, onPro: () -> Unit, onVideoSettings: () -> Unit, onAiVideo: () -> Unit,
) {
    data class Tile(val icon: ImageVector, val label: String, val active: Boolean, val onClick: () -> Unit)
    val aspectNow = if (isVideo) settings.videoAspect else settings.photoAspect
    val tiles = buildList {
        add(Tile(Icons.Filled.AutoAwesome, "AI", ui.aiPro) { vm.toggleAiPro() })
        add(Tile(Icons.Filled.PhotoFilter, "Фильтры", panel == Panel.FILTERS || ui.filterId != "original") { onPanel(Panel.FILTERS) })
        add(Tile(Icons.Filled.Face, "Красота", ui.beautyOn) { onPanel(Panel.BEAUTY) })
        add(Tile(Icons.Filled.AutoFixHigh, "Эффекты", ui.effect != Effect.NONE) { onPanel(Panel.EFFECTS) })
        add(Tile(Icons.Filled.Mood, "AR маски", ui.arMask != com.maysk.camera.camera.ArMask.NONE) { onPanel(Panel.MASKS) })
        add(Tile(Icons.Filled.Timer, "Snap", ui.snapMode) { vm.setSnapMode(!ui.snapMode); onPanel(Panel.SNAP) })
        add(Tile(Icons.Filled.AutoFixHigh, "Текст / дата", ui.snapCaption.isNotBlank()) { onPanel(Panel.OVERLAY) })
        add(Tile(Icons.Filled.Exposure, "EV", panel == Panel.EV) { onPanel(Panel.EV) })
        add(Tile(Icons.Filled.CropFree, "Рамки", ui.frame != null) { onPanel(Panel.FRAMES) })
        if (!isVideo) add(Tile(Icons.Filled.HdrOn, "HDR", ui.hdr) { vm.toggleHdr() })
        add(Tile(Icons.Filled.Settings, "ISO / Pro", ui.mode == CameraMode.PRO) { if (!isVideo) { vm.setMode(CameraMode.PRO); onPanel(Panel.PRO) } else onPro() })
        add(Tile(Icons.Filled.GridOn, "Сетка", settings.grid) { vm.updateSettings({ it.copy(grid = !it.grid) }) })
        add(Tile(Icons.Filled.Straighten, "Горизонт", settings.horizon) { vm.updateSettings({ it.copy(horizon = !it.horizon) }) })
        add(Tile(Icons.Filled.AspectRatio, aspectNow.label.substringBefore(" ("), aspectNow == com.maysk.camera.data.Aspect.FULL) {
            val next = com.maysk.camera.data.Aspect.entries[(aspectNow.ordinal + 1) % com.maysk.camera.data.Aspect.entries.size]
            vm.updateSettings({ if (isVideo) it.copy(videoAspect = next) else it.copy(photoAspect = next) })
        })
        if (isVideo) add(Tile(Icons.Filled.Vibration, "Антитряска", settings.stabilization != com.maysk.camera.data.Stabilization.OFF) { onVideoSettings() })
        if (isVideo) add(Tile(Icons.Filled.AutoAwesome, "AI видео", settings.aiVideoEnabled) { onAiVideo() })
        if (vm.cam.value.macroAvailable) add(Tile(Icons.Filled.LocalFlorist, "Макро", ui.mode == CameraMode.MACRO) { vm.setMode(CameraMode.MACRO) })
        add(Tile(Icons.Filled.NightsStay, "Ночь", ui.mode == CameraMode.NIGHT) { vm.setMode(CameraMode.NIGHT) })
        add(Tile(Icons.Filled.Portrait, "Портрет", ui.mode == CameraMode.PORTRAIT) { vm.setMode(CameraMode.PORTRAIT) })
        add(Tile(Icons.Filled.Mic, "Голос", settings.voiceControl) { vm.updateSettings({ it.copy(voiceControl = !it.voiceControl) }) })
        add(Tile(Icons.Filled.PictureInPicture, "Двойная", ui.mode == CameraMode.DUAL) { vm.setMode(CameraMode.DUAL) })
        add(Tile(Icons.Filled.AllInclusive, "Бумеранг", ui.mode == CameraMode.BOOMERANG) { vm.setMode(CameraMode.BOOMERANG) })
        add(Tile(Icons.Filled.Apps, "Все режимы", false) { onModes() })
    }
    androidx.compose.foundation.lazy.grid.LazyVerticalGrid(
        androidx.compose.foundation.lazy.grid.GridCells.Fixed(5),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(Color(0xD90E0B10))
            .padding(8.dp)
            .height(if (tiles.size > 20) 300.dp else if (tiles.size > 15) 250.dp else 190.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        items(tiles.size) { i ->
            val t = tiles[i]
            Column(
                Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .clickable(onClick = t.onClick)
                    .padding(vertical = 6.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Box(
                    Modifier
                        .size(38.dp)
                        .clip(CircleShape)
                        .background(if (t.active) Accent else Color.White.copy(alpha = 0.12f)),
                    contentAlignment = Alignment.Center
                ) { Icon(t.icon, t.label, tint = Color.White, modifier = Modifier.size(20.dp).rotate(LocalIconRotation.current)) }
                Text(t.label, color = Color.White, fontSize = 10.sp, maxLines = 1, modifier = Modifier.padding(top = 3.dp))
            }
        }
    }
}

// ================= части экрана =================

@Composable
private fun TopBar(
    vm: CameraViewModel, flash: FlashMode, hdr: Boolean, ai: Boolean, timer: Int, isVideo: Boolean, recording: Boolean,
    voiceOn: Boolean, voiceEnabled: Boolean, onSettings: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        IconButton(onClick = { vm.cycleFlash() }) {
            Icon(when (flash) { FlashMode.OFF -> Icons.Filled.FlashOff; FlashMode.ON -> Icons.Filled.FlashOn; FlashMode.AUTO -> Icons.Filled.FlashAuto }, "Вспышка", tint = Color.White)
        }
        TopChip("AI", ai) { vm.toggleAiPro() }
        if (!isVideo) IconButton(onClick = { vm.toggleHdr() }) {
            Icon(if (hdr) Icons.Filled.HdrOn else Icons.Filled.HdrOff, "HDR", tint = if (hdr) Accent else Color.White)
        }
        if (isVideo) {
            val nightOn = vm.ui.collectAsState().value.nightVideo
            TopChip("🌙 Ночь", nightOn) { vm.toggleNightVideo() }
        }
        if (!isVideo) IconButton(onClick = { vm.cycleTimer() }) {
            Icon(when (timer) { 3 -> Icons.Filled.Timer3; 10 -> Icons.Filled.Timer10; 0 -> Icons.Filled.TimerOff; else -> Icons.Filled.Timer }, "Таймер", tint = if (timer > 0) Accent else Color.White)
        }
        if (voiceEnabled) IconButton(onClick = { if (voiceOn) vm.stopVoice() else vm.startVoice() }) {
            Icon(if (voiceOn) Icons.Filled.Mic else Icons.Filled.MicOff, "Голос", tint = if (voiceOn) Accent else Color.White)
        }
        IconButton(onClick = onSettings, enabled = !recording) { Icon(Icons.Filled.Settings, "Настройки", tint = Color.White) }
    }
}

@Composable
private fun TopChip(text: String, active: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .clip(RoundedCornerShape(8.dp))
            .border(1.5.dp, if (active) Accent else Color.White, RoundedCornerShape(8.dp))
            .background(if (active) Accent.copy(alpha = 0.2f) else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 3.dp)
    ) { Text(text, color = if (active) Accent else Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp) }
}

@Composable
private fun ToolIcon(icon: ImageVector, label: String, active: Boolean, onClick: () -> Unit) {
    Column(Modifier.clickable(onClick = onClick).padding(4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(icon, label, tint = if (active) Accent else Color.White, modifier = Modifier.size(22.dp).rotate(LocalIconRotation.current))
        Text(label, color = if (active) Accent else Color.White.copy(alpha = 0.75f), fontSize = 10.sp)
    }
}

@Composable
private fun CircleIcon(icon: ImageVector, onClick: () -> Unit) {
    Box(
        Modifier
            .size(52.dp)
            .clip(CircleShape)
            .background(Color(0xFF222222))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) { Icon(icon, null, tint = Color.White, modifier = Modifier.rotate(LocalIconRotation.current)) }
}

@Composable
private fun ShutterButton(
    video: Boolean, recording: Boolean, busy: Boolean, burst: Int = 0,
    onLongStart: (() -> Unit)? = null, onLongEnd: () -> Unit = {}, onClick: () -> Unit,
) {
    val latestClick by androidx.compose.runtime.rememberUpdatedState(onClick)
    val latestLong by androidx.compose.runtime.rememberUpdatedState(onLongStart)
    val latestEnd by androidx.compose.runtime.rememberUpdatedState(onLongEnd)
    val latestBusy by androidx.compose.runtime.rememberUpdatedState(busy)
    Box(
        Modifier
            .size(80.dp)
            .clip(CircleShape)
            .border(4.dp, if (video || recording) Accent else Color.White, CircleShape)
            .pointerInput(Unit) {
                detectTapGestures(onPress = {
                    if (latestBusy) return@detectTapGestures
                    val long = latestLong
                    if (long == null) {
                        if (tryAwaitRelease()) latestClick()
                    } else {
                        // короткое нажатие — снимок, удержание > 0.45 с — серийная съёмка
                        val released = withTimeoutOrNull(450) { tryAwaitRelease() }
                        if (released != null) {
                            if (released) latestClick()
                        } else {
                            long()
                            tryAwaitRelease()
                            latestEnd()
                        }
                    }
                })
            },
        contentAlignment = Alignment.Center
    ) {
        when {
            burst > 0 -> Box(Modifier.size(64.dp).clip(CircleShape).background(Accent), contentAlignment = Alignment.Center) {
                Text("$burst", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 20.sp)
            }
            busy -> CircularProgressIndicator(color = Accent, modifier = Modifier.size(56.dp))
            recording -> Box(Modifier.size(30.dp).clip(RoundedCornerShape(6.dp)).background(Accent))
            video -> Box(Modifier.size(62.dp).clip(CircleShape).background(Accent))
            else -> Box(Modifier.size(64.dp).clip(CircleShape).background(Color.White))
        }
    }
}

@Composable
private fun ModeStrip(current: CameraMode, onSelect: (CameraMode) -> Unit, onMore: () -> Unit) {
    val modes = if (current in QuickModes) QuickModes else QuickModes + current
    val state = rememberLazyListState()
    LazyRow(
        state = state,
        modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically
    ) {
        items(modes) { m ->
            val sel = m == current
            Box(
                Modifier
                    .clip(RoundedCornerShape(16.dp))
                    .background(if (sel) Accent else Color.Transparent)
                    .clickable { onSelect(m) }
                    .padding(horizontal = 14.dp, vertical = 6.dp)
            ) { Text(m.title, color = if (sel) Color.White else Color.White.copy(alpha = 0.75f), fontSize = 14.sp, fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Normal) }
        }
        item {
            Box(
                Modifier
                    .clip(RoundedCornerShape(16.dp))
                    .border(1.dp, Color.White.copy(alpha = 0.3f), RoundedCornerShape(16.dp))
                    .clickable(onClick = onMore)
                    .padding(horizontal = 14.dp, vertical = 6.dp)
            ) { Text("Ещё", color = Color.White, fontSize = 14.sp) }
        }
    }
}

@Composable
private fun ZoomBar(zoom: Float, min: Float, max: Float, hasUw: Boolean, usingUw: Boolean, modifier: Modifier, onSelect: (Float) -> Unit) {
    val stops = buildList {
        if (hasUw || min < 0.95f) add(0.5f)
        add(1f)
        if (max >= 2f) add(2f)
        if (max >= 3f) add(3f)
        if (max >= 5f) add(5f)
    }
    Row(
        modifier
            .clip(RoundedCornerShape(20.dp))
            .background(Color.Black.copy(alpha = 0.45f))
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        stops.forEach { s ->
            val effective = if (usingUw) 0.5f else zoom
            val active = if (s == 0.5f) effective < 0.95f else abs(effective - s) < 0.3f || (s == stops.last() && effective > s)
            Box(
                Modifier
                    .size(38.dp)
                    .clip(CircleShape)
                    .background(if (active) Color.Black.copy(alpha = 0.7f) else Color.Transparent)
                    .border(if (active) 1.5.dp else 0.dp, if (active) Accent else Color.Transparent, CircleShape)
                    .clickable { onSelect(s) },
                contentAlignment = Alignment.Center
            ) {
                val label = if (active && s >= 1f && abs(effective - s) > 0.05f) "%.1f".format(effective) else if (s == 0.5f) ".5" else "${s.toInt()}"
                Text(label + if (active) "x" else "", color = if (active) Accent else Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun GridOverlay() {
    Canvas(Modifier.fillMaxSize()) {
        val c = Color.White.copy(alpha = 0.25f)
        for (i in 1..2) {
            drawLine(c, Offset(size.width * i / 3f, 0f), Offset(size.width * i / 3f, size.height), 1.5f)
            drawLine(c, Offset(0f, size.height * i / 3f), Offset(size.width, size.height * i / 3f), 1.5f)
        }
    }
}

/** Индикатор горизонта по акселерометру. */
@Composable
private fun HorizonOverlay() {
    val context = LocalContext.current
    var angle by remember { mutableFloatStateOf(0f) }
    DisposableEffect(Unit) {
        val sm = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val acc = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        val l = object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent) {
                val a = Math.toDegrees(atan2(e.values[0].toDouble(), e.values[1].toDouble())).toFloat()
                angle = angle * 0.8f + a * 0.2f
            }
            override fun onAccuracyChanged(s: Sensor?, a: Int) {}
        }
        if (acc != null) sm.registerListener(l, acc, SensorManager.SENSOR_DELAY_UI)
        onDispose { sm.unregisterListener(l) }
    }
    val level = abs(angle) < 1.5f
    if (abs(angle) < 25f) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Canvas(Modifier.width(140.dp).height(2.dp).rotate(-angle)) {
                drawLine(if (level) Accent else Color.White.copy(alpha = 0.8f), Offset(0f, 0f), Offset(size.width, 0f), 4f,
                    pathEffect = if (level) null else PathEffect.dashPathEffect(floatArrayOf(14f, 10f)))
            }
        }
    }
}

@Composable
private fun FaceOverlay(faces: List<android.graphics.RectF>, mirror: Boolean) {
    if (faces.isEmpty()) return
    Canvas(Modifier.fillMaxSize()) {
        // кадр анализа 3:4, экран может быть уже/шире — учитываем обрезку FILL_CENTER
        val frameAspect = 0.75f
        val viewAspect = size.width / size.height
        val fx = if (viewAspect < frameAspect) viewAspect / frameAspect else 1f
        val fy = if (viewAspect > frameAspect) frameAspect / viewAspect else 1f
        fun mx(x: Float) = ((x - (1 - fx) / 2) / fx) * size.width
        fun my(y: Float) = ((y - (1 - fy) / 2) / fy) * size.height
        faces.forEach { r ->
            val left = if (mirror) 1f - r.right else r.left
            val l = mx(left); val t = my(r.top)
            val w = r.width() / fx * size.width
            val h = r.height() / fy * size.height
            val k = w * 0.22f
            val sw = 4f
            val col = Accent
            drawLine(col, Offset(l, t), Offset(l + k, t), sw); drawLine(col, Offset(l, t), Offset(l, t + k), sw)
            drawLine(col, Offset(l + w, t), Offset(l + w - k, t), sw); drawLine(col, Offset(l + w, t), Offset(l + w, t + k), sw)
            drawLine(col, Offset(l, t + h), Offset(l + k, t + h), sw); drawLine(col, Offset(l, t + h), Offset(l, t + h - k), sw)
            drawLine(col, Offset(l + w, t + h), Offset(l + w - k, t + h), sw); drawLine(col, Offset(l + w, t + h), Offset(l + w, t + h - k), sw)
        }
    }
}

/** Живой предпросмотр рамки поверх кадра камеры. */
@Composable
private fun FrameLiveOverlay(spec: com.maysk.camera.camera.FrameSpec) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val density = androidx.compose.ui.platform.LocalDensity.current
        val wPx = with(density) { maxWidth.toPx() }.toInt().coerceAtLeast(16) / 2
        val hPx = with(density) { maxHeight.toPx() }.toInt().coerceAtLeast(16) / 2
        var overlay by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
        LaunchedEffect(spec, wPx, hPx) {
            overlay = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                runCatching { com.maysk.camera.camera.Frames.previewOverlay(wPx, hPx, spec) }.getOrNull()
            }
        }
        overlay?.let {
            androidx.compose.foundation.Image(
                it.asImageBitmap(), contentDescription = null,
                contentScale = ContentScale.FillBounds, modifier = Modifier.fillMaxSize()
            )
        }
    }
}

/** AR-маска поверх живого кадра (с учётом обрезки полноэкранного превью). */
@Composable
private fun ArMaskOverlay(faces: List<com.maysk.camera.ai.FaceInfo>, mask: com.maysk.camera.camera.ArMask, mirror: Boolean) {
    val t by rememberInfiniteTransition(label = "ar").animateFloat(
        initialValue = 0f, targetValue = 1000f,
        animationSpec = infiniteRepeatable(tween(1_000_000, easing = LinearEasing)), label = "arTime"
    )
    if (faces.isEmpty()) return
    Canvas(Modifier.fillMaxSize()) {
        val frameAspect = 0.75f
        val va = size.width / size.height
        val fx = if (va < frameAspect) va / frameAspect else 1f
        val fy = if (va > frameAspect) frameAspect / va else 1f
        val w = size.width / fx
        val h = size.height / fy
        val offX = -(1 - fx) / 2 * w
        val offY = -(1 - fy) / 2 * h
        drawIntoCanvas { c -> faces.forEach { f -> com.maysk.camera.camera.ArMasks.draw(c.nativeCanvas, mask, f, w, h, offX, offY, mirror, t) } }
    }
}

/** Выбор AR-маски. */
@Composable
private fun SnapPanel(vm: CameraViewModel, enabled: Boolean, seconds: Int, openViewer: (Uri, Boolean) -> Unit) {
    var refresh by remember { mutableIntStateOf(0) }
    val entries = remember(refresh) { vm.pendingSnaps() }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
            Text("Исчезающие снимки", color = Color.White, fontWeight = FontWeight.SemiBold)
            Pill(if (enabled) "Режим включён" else "Режим выключен", enabled) { vm.setSnapMode(!enabled) }
        }
        Text("Фото хранится зашифрованным до 24 часов; после открытия удаляется по выбранному таймеру. Внешняя запись экрана не блокируется.", color = Color.LightGray, fontSize = 11.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(3, 5, 10, 30).forEach { n -> Pill("${n}с", seconds == n) { vm.setSnapDuration(n) } }
        }
        Text("Ожидают просмотра: ${entries.size}", color = Color.White, fontSize = 12.sp)
        entries.forEach { entry ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Snap ${entry.id.takeLast(6)} · просмотр ${entry.durationSeconds} сек.", color = Color.White, fontSize = 12.sp)
                Button(onClick = { vm.consumeSnap(entry.id)?.let { openViewer(it, false) }; refresh++ }) { Text("Открыть 1 раз") }
            }
        }
    }
}

@Composable
private fun MasksPanel(current: com.maysk.camera.camera.ArMask, onSelect: (com.maysk.camera.camera.ArMask) -> Unit) {
    Column {
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(com.maysk.camera.camera.ArMask.entries) { m ->
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(62.dp).clickable { onSelect(m) }) {
                    Box(
                        Modifier
                            .size(50.dp)
                            .clip(CircleShape)
                            .background(if (m == current) Accent else Color.White.copy(alpha = 0.12f)),
                        contentAlignment = Alignment.Center
                    ) { Text(m.emoji, fontSize = 22.sp) }
                    Text(m.title, color = if (m == current) Accent else Color.White, fontSize = 10.sp, maxLines = 1, modifier = Modifier.padding(top = 3.dp))
                }
            }
        }
        Text("Маски отслеживают лицо в реальном времени. Фото сохраняется с маской; в текущей версии видеозаписи маска видна в предпросмотре, но не в итоговом файле.", color = Color.White.copy(alpha = 0.65f), fontSize = 10.sp, modifier = Modifier.padding(top = 6.dp))
    }
}

@Composable
private fun StatusPill(text: String, modifier: Modifier) {
    Box(
        modifier
            .padding(top = 10.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(Color.Black.copy(alpha = 0.6f))
            .padding(horizontal = 12.dp, vertical = 5.dp)
    ) { Text(text, color = Color.White, fontSize = 12.sp) }
}

@Composable
private fun PanoramaGuide(ui: com.maysk.camera.CameraUi, modifier: Modifier) {
    val active = ui.panoramaActive
    BoxWithConstraints(modifier) {
        val W = maxWidth
        // полупрозрачная полоса края предыдущего кадра: едет по экрану вместе с поворотом — это и есть граница
        ui.panoGhost?.takeIf { active }?.let { g ->
            val startFrac = if (ui.panoDirRight) 0.75f - ui.panoShift else ui.panoShift
            Box(Modifier.offset(x = W * startFrac).width(W * 0.25f).fillMaxHeight()) {
                androidx.compose.foundation.Image(
                    g.asImageBitmap(), null, contentScale = ContentScale.FillBounds,
                    alpha = 0.45f, modifier = Modifier.fillMaxSize()
                )
                Box(Modifier.fillMaxHeight().width(2.dp).background(Color.White.copy(alpha = 0.9f))
                    .align(if (ui.panoDirRight) Alignment.CenterStart else Alignment.CenterEnd))
                Text(
                    if (ui.panoDirRight) "➜" else "⬅", color = Color.White, fontSize = 34.sp,
                    modifier = Modifier.align(Alignment.Center)
                )
            }
        }
        Column(
            Modifier.align(Alignment.BottomCenter).padding(bottom = 150.dp, start = 24.dp, end = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                if (active) (ui.panoramaHint ?: "Плавно поворачивайте телефон") else "Нажмите затвор и плавно поворачивайте телефон — кадры снимаются сами",
                color = Color.White, fontSize = 13.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                modifier = Modifier.clip(RoundedCornerShape(10.dp)).background(Color.Black.copy(alpha = 0.55f)).padding(horizontal = 10.dp, vertical = 4.dp)
            )
            if (active) {
                Spacer(Modifier.height(8.dp))
                // растущая лента уже снятого
                Row(
                    Modifier.height(44.dp).clip(RoundedCornerShape(6.dp)).background(Color.Black.copy(alpha = 0.4f)).padding(2.dp),
                    horizontalArrangement = Arrangement.spacedBy(1.dp)
                ) {
                    val list = if (ui.panoDirRight) ui.panoRibbon else ui.panoRibbon.reversed()
                    list.takeLast(10).forEach { t ->
                        androidx.compose.foundation.Image(t.asImageBitmap(), null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxHeight().width(22.dp))
                    }
                    Box(Modifier.fillMaxHeight().width(3.dp).background(Accent))
                }
                Text("Кадров: ${ui.panoramaFrames} · затвор — закончить", color = Color.White.copy(alpha = 0.8f), fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp))
            }
        }
    }
}

@Composable
private fun BusyOverlay(text: String, progress: Float?) {
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.35f)), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            if (progress != null) CircularProgressIndicator(progress = { progress }, color = Accent)
            else CircularProgressIndicator(color = Accent)
            Spacer(Modifier.height(10.dp))
            Text(text, color = Color.White, fontSize = 14.sp)
            if (text.contains("Ноч") || text.contains("HDR") || text.contains("0.5")) Text("Не двигайте камеру", color = Color.White.copy(alpha = 0.7f), fontSize = 12.sp)
        }
    }
}

@Composable
private fun ProInfoRow(iso: Int?, expNs: Long?, aperture: Float?, modifier: Modifier) {
    Row(
        modifier
            .fillMaxWidth()
            .background(Color.Black.copy(alpha = 0.5f))
            .padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceEvenly
    ) {
        InfoCell("ISO", iso?.toString() ?: "Авто")
        InfoCell("Выдержка", expNs?.let { formatShutter(it) } ?: "Авто")
        InfoCell("Диафр.", aperture?.let { "F%.1f".format(it) } ?: "—")
        InfoCell("WB", "Авто")
    }
}

@Composable
private fun InfoCell(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, color = Color.White.copy(alpha = 0.6f), fontSize = 10.sp)
        Text(value, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
fun Histogram(hist: FloatArray, modifier: Modifier = Modifier) {
    Canvas(
        modifier
            .size(width = 110.dp, height = 46.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(Color.Black.copy(alpha = 0.45f))
    ) {
        val mx = (hist.maxOrNull() ?: 0f).coerceAtLeast(1e-4f)
        val bw = size.width / hist.size
        hist.forEachIndexed { i, v ->
            val h = v / mx * size.height
            drawRect(Color.White.copy(alpha = 0.75f), Offset(i * bw, size.height - h), Size(bw, h))
        }
    }
}

// ---------- панели ----------

@Composable
private fun FiltersPanel(selected: String, intensity: Float, live: android.graphics.Bitmap?, onSelect: (String) -> Unit, onIntensity: (Float) -> Unit) {
    val cur = Filters.byId(selected)
    var cat by remember { mutableStateOf<com.maysk.camera.camera.FilterCategory?>(null) }
    val list = remember(cat) { Filters.search("", cat) }
    // миниатюры — на живом кадре камеры (или на встроенном примере, пока кадра нет)
    val base = live ?: com.maysk.camera.ui.components.filterSample
    Column {
        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(bottom = 6.dp)) {
            item { Pill("Все ${Filters.all.size - 1}", cat == null) { cat = null } }
            items(com.maysk.camera.camera.FilterCategory.entries.filter { c -> Filters.all.any { it.category == c } }) { c -> Pill(c.title, cat == c) { cat = c } }
        }
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(list, key = { it.id }) { f ->
                com.maysk.camera.ui.components.FilterTile(base, f, f.id == cur.id) { onSelect(f.id) }
            }
        }
        if (selected != "original") CompactSlider("Сила", intensity, "${(intensity * 100).toInt()}", onChange = onIntensity)
    }
}

@Composable
private fun EffectsPanel(selected: Effect, onSelect: (Effect) -> Unit) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        items(Effect.entries) { e ->
            val sel = e == selected
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.clickable { onSelect(e) }.width(66.dp)) {
                Box(
                    Modifier
                        .size(50.dp)
                        .clip(CircleShape)
                        .background(if (sel) Accent else Color(0xFF26222B)),
                    contentAlignment = Alignment.Center
                ) { Icon(e.icon(), null, tint = Color.White) }
                Text(e.title, color = if (sel) Accent else Color.White, fontSize = 10.sp, maxLines = 2, modifier = Modifier.padding(top = 3.dp))
            }
        }
    }
}

@Composable
private fun BeautyPanel(vm: CameraViewModel, on: Boolean, skin: Float, eyes: Float, face: Float) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("AI Красота", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, modifier = Modifier.weight(1f))
            Pill(if (on) "Вкл." else "Выкл.", on) { vm.setBeauty(!on) }
        }
        // три ползунка в один ряд — панель низкая, кадр остаётся большим
        Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            MiniSlider("Кожа", skin, Modifier.weight(1f)) { v -> if (!on) vm.setBeauty(true); vm.updateSettings({ it.copy(beautySkin = v) }) }
            MiniSlider("Глаза", eyes, Modifier.weight(1f)) { v -> if (!on) vm.setBeauty(true); vm.updateSettings({ it.copy(beautyEyes = v) }) }
            MiniSlider("Лицо", face, Modifier.weight(1f)) { v -> if (!on) vm.setBeauty(true); vm.updateSettings({ it.copy(beautyFace = v) }) }
        }
        Text("Естественная обработка: текстура кожи сохраняется", color = Color.White.copy(alpha = 0.55f), fontSize = 10.sp)
    }
}

/** Узкий ползунок с подписью и значением над ним. */
@Composable
private fun MiniSlider(label: String, value: Float, modifier: Modifier, onChange: (Float) -> Unit) {
    Column(modifier) {
        Row(Modifier.fillMaxWidth()) {
            Text(label, color = Color.White, fontSize = 12.sp, modifier = Modifier.weight(1f))
            Text("${(value * 100).toInt()}", color = Color.White.copy(alpha = 0.7f), fontSize = 11.sp)
        }
        androidx.compose.material3.Slider(
            value = value, onValueChange = onChange, modifier = Modifier.fillMaxWidth().height(28.dp),
            colors = androidx.compose.material3.SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = Accent, inactiveTrackColor = Color.White.copy(alpha = 0.25f))
        )
    }
}

/** Компактный ползунок в одну строку: подпись — ползунок — значение. */
@Composable
private fun CompactSlider(label: String, value: Float, display: String, range: ClosedFloatingPointRange<Float> = 0f..1f, onChange: (Float) -> Unit) {
    Row(Modifier.fillMaxWidth().height(36.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = Color.White, fontSize = 13.sp, modifier = Modifier.width(52.dp))
        androidx.compose.material3.Slider(
            value = value, onValueChange = onChange, valueRange = range, modifier = Modifier.weight(1f),
            colors = androidx.compose.material3.SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = Accent, inactiveTrackColor = Color.White.copy(alpha = 0.25f))
        )
        Text(display, color = Color.White.copy(alpha = 0.8f), fontSize = 12.sp, modifier = Modifier.width(40.dp).padding(start = 6.dp))
    }
}

@Composable
private fun ProPanel(vm: CameraViewModel, m: ManualSettings, isoRange: IntRange?, supported: Boolean, onMore: () -> Unit) {
    Column {
        if (!supported) Text("Телефон не даёт ручное управление сенсором — доступны баланс белого и фокус", color = Color(0xFFFFB74D), fontSize = 11.sp)
        val isos = listOf(0, 100, 200, 400, 800, 1600).filter { it == 0 || isoRange == null || it in isoRange }
        Text("ISO", color = Color.White.copy(alpha = 0.7f), fontSize = 12.sp)
        ChoiceRow(isos.map { if (it == 0) "Авто" else "$it" }, isos.indexOf(m.iso ?: 0).coerceAtLeast(0)) { i ->
            vm.setManual(m.copy(iso = isos[i].takeIf { it > 0 }))
        }
        Spacer(Modifier.height(6.dp))
        val shutters = listOf(0L, 1_000_000_000L / 1000, 1_000_000_000L / 250, 1_000_000_000L / 60, 1_000_000_000L / 15, 1_000_000_000L / 4)
        Text("Выдержка", color = Color.White.copy(alpha = 0.7f), fontSize = 12.sp)
        ChoiceRow(shutters.map { if (it == 0L) "Авто" else formatShutter(it) }, shutters.indexOf(m.exposureNs ?: 0L).coerceAtLeast(0)) { i ->
            vm.setManual(m.copy(exposureNs = shutters[i].takeIf { it > 0 }))
        }
        Spacer(Modifier.height(6.dp))
        val awb = vm.manualAwbModes()
        Text("Баланс белого", color = Color.White.copy(alpha = 0.7f), fontSize = 12.sp)
        ChoiceRow(awb.map { it.second }, awb.indexOfFirst { it.first == m.awbMode }.coerceAtLeast(0)) { i -> vm.setManual(m.copy(awbMode = awb[i].first)) }
        Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.End) {
            Text("Все настройки →", color = Accent, fontSize = 13.sp, modifier = Modifier.clickable(onClick = onMore))
        }
    }
}

// ---------- утилиты ----------

/** Жесты кадра: касание — фокус, два пальца — зум, свайп вверх/вниз — шторка. */
private fun Modifier.cameraGestures(onTap: (Offset) -> Unit, onZoom: (Float) -> Unit, onSwipe: (Float) -> Unit) =
    this.then(Modifier.pointerInput(Unit) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            val start = down.position
            var dx = 0f; var dy = 0f
            var multi = false
            while (true) {
                val ev = awaitPointerEvent()
                val pressed = ev.changes.filter { it.pressed }
                if (pressed.isEmpty()) break
                if (pressed.size >= 2) {
                    multi = true
                    val z = ev.calculateZoom()
                    if (z != 1f) onZoom(z)
                    ev.changes.forEach { it.consume() }
                } else if (!multi) {
                    val c = pressed[0]
                    dx = c.position.x - start.x; dy = c.position.y - start.y
                }
            }
            if (!multi) {
                val slop = viewConfiguration.touchSlop
                if (abs(dy) > slop * 4 && abs(dy) > abs(dx) * 1.5f) onSwipe(dy)
                else if (abs(dx) < slop && abs(dy) < slop) onTap(start)
            }
        }
    })

private fun Modifier.pointerInputTap(onTap: (Offset) -> Unit) =
    this.then(Modifier.pointerInput(Unit) { detectTapGestures(onTap = onTap) })

private fun Modifier.pointerInputZoom(onZoom: (Float) -> Unit) =
    this.then(Modifier.pointerInput(Unit) { detectTransformGestures { _, _, zoom, _ -> if (zoom != 1f) onZoom(zoom) } })

fun formatTime(sec: Long) = "%02d:%02d".format(sec / 60, sec % 60)

fun formatShutter(ns: Long): String {
    val s = ns / 1e9
    return if (s >= 0.5) "%.1f\"".format(s) else "1/${(1 / s).toInt()}"
}

@Suppress("unused")
private fun unusedImageOps() = ImageOps
