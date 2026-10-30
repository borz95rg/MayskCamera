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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
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

private enum class Panel { NONE, FILTERS, EFFECTS, BEAUTY, PRO, EV }

data class CameraNav(
    val openModes: () -> Unit,
    val openGallery: () -> Unit,
    val openViewer: (Uri, Boolean) -> Unit,
    val openSettings: () -> Unit,
    val openVideoSettings: () -> Unit,
    val openAiVideo: () -> Unit,
    val openProSettings: () -> Unit,
)

private val QuickModes = listOf(CameraMode.PORTRAIT, CameraMode.PHOTO, CameraMode.VIDEO, CameraMode.PRO)

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

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        Column(Modifier.fillMaxSize()) {
            TopBar(vm, ui.flash, ui.hdr, ui.aiPro, ui.timer, isVideo, cam.recording, ui.voiceListening, settings.voiceControl,
                onSettings = { if (isVideo) nav.openVideoSettings() else nav.openSettings() })

            // ---------- превью ----------
            BoxWithConstraints(
                Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center
            ) {
                val ratio = if (isVideo) 9f / 16f else 3f / 4f
                Box(
                    Modifier
                        .fillMaxWidth()
                        .aspectRatio(ratio, matchHeightConstraintsFirst = maxWidth / maxHeight > ratio)
                        .clip(RoundedCornerShape(18.dp))
                ) {
                    AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
                    Box(
                        Modifier
                            .fillMaxSize()
                            .pointerInputTap { off ->
                                focusPoint = off
                                panel = Panel.NONE
                                vm.controller.tapToFocus(previewView, off.x, off.y)
                            }
                            .pointerInputZoom { z -> vm.controller.setZoom(cam.zoom * z) }
                    )
                    if (settings.grid) GridOverlay()
                    if (settings.horizon) HorizonOverlay()
                    FaceOverlay(live.faces.map { it.box }, cam.front)
                    focusPoint?.let { p ->
                        Box(
                            Modifier
                                .offset { IntOffset(p.x.toInt() - 70, p.y.toInt() - 70) }
                                .size(48.dp)
                                .border(1.5.dp, Color(0xFFFFD54F), RoundedCornerShape(8.dp))
                        )
                    }
                    // AI подсказка
                    Column(Modifier.align(Alignment.TopStart).padding(10.dp)) {
                        if ((ui.aiPro || ui.mode == CameraMode.AI_AUTO) && ui.aiHint != null) {
                            Row(
                                Modifier
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(Color.Black.copy(alpha = 0.55f))
                                    .padding(horizontal = 10.dp, vertical = 5.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Filled.AutoAwesome, null, tint = Accent, modifier = Modifier.size(14.dp))
                                Spacer(Modifier.width(4.dp))
                                Text(ui.aiHint ?: "", color = Color.White, fontSize = 12.sp)
                            }
                            ui.aiActions.forEach {
                                Text("↳ $it", color = Color.White.copy(alpha = 0.85f), fontSize = 11.sp, modifier = Modifier.padding(start = 6.dp, top = 2.dp))
                            }
                        }
                        if (cam.activeExtension != null) {
                            Text("Аппаратный режим телефона", color = Color.White.copy(alpha = 0.7f), fontSize = 10.sp, modifier = Modifier.padding(top = 4.dp))
                        }
                    }
                    // запись
                    if (cam.recording) {
                        Row(
                            Modifier
                                .align(Alignment.TopCenter)
                                .padding(top = 10.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(Color(0xCCE53935))
                                .padding(horizontal = 12.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(Modifier.size(8.dp).clip(CircleShape).background(Color.White))
                            Spacer(Modifier.width(6.dp))
                            Text(formatTime(cam.recordSeconds) + if (cam.paused) " ⏸" else "", color = Color.White, fontWeight = FontWeight.SemiBold)
                        }
                    }
                    if (ui.timelapseActive) StatusPill("Таймлапс: ${ui.timelapseFrames} кадр. · ${"%.1f".format(ui.timelapseFrames / 30f)} с видео", Modifier.align(Alignment.TopCenter))
                    if (ui.panoramaActive || ui.mode == CameraMode.PANORAMA) PanoramaGuide(ui.panoramaActive, ui.panoramaFrames, Modifier.align(Alignment.BottomCenter))
                    if (ui.countdown > 0) Text("${ui.countdown}", color = Color.White, fontSize = 96.sp, fontWeight = FontWeight.Bold, modifier = Modifier.align(Alignment.Center))
                    if (ui.mode == CameraMode.PRO) ProInfoRow(cam.liveIso, cam.liveExposureNs, cam.liveAperture ?: cam.apertures.firstOrNull(), Modifier.align(Alignment.BottomCenter))
                    if (ui.mode == CameraMode.PRO) Histogram(live.histogram, Modifier.align(Alignment.TopEnd).padding(10.dp))
                    // зум
                    ZoomBar(cam.zoom, cam.minZoom, cam.maxZoom, cam.hasUltraWide, ui.ultraWide, Modifier.align(Alignment.BottomCenter).padding(bottom = if (ui.mode == CameraMode.PRO) 52.dp else 12.dp)) { vm.selectZoom(it) }
                    ui.busy?.takeIf { !ui.timelapseActive && !ui.panoramaActive }?.let { BusyOverlay(it, ui.progress) }
                }
            }

            // ---------- панели ----------
            AnimatedVisibility(panel != Panel.NONE) {
                Box(Modifier.fillMaxWidth().background(Color(0xF0101012)).padding(12.dp)) {
                    when (panel) {
                        Panel.FILTERS -> FiltersPanel(ui.filterId, ui.filterIntensity, ui.lastUri, vm::setFilter, vm::setFilterIntensity)
                        Panel.EFFECTS -> EffectsPanel(ui.effect, vm::setEffect)
                        Panel.BEAUTY -> BeautyPanel(vm, ui.beautyOn, settings.beautySkin, settings.beautyEyes, settings.beautyFace)
                        Panel.PRO -> ProPanel(vm, ui.manual, cam.isoRange, cam.manualSupported, onMore = nav.openProSettings)
                        Panel.EV -> LabeledSlider(
                            "Экспозиция", cam.evIndex.toFloat(),
                            display = "%+.1f EV".format(cam.evIndex * cam.evStep),
                            range = cam.evMin.toFloat()..maxOf(cam.evMax.toFloat(), cam.evMin + 1f)
                        ) { vm.controller.setExposureIndex(it.toInt()) }
                        Panel.NONE -> Unit
                    }
                }
            }

            // ---------- инструменты ----------
            Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                ToolIcon(Icons.Filled.PhotoFilter, "Фильтры", panel == Panel.FILTERS) { panel = if (panel == Panel.FILTERS) Panel.NONE else Panel.FILTERS }
                ToolIcon(Icons.Filled.AutoFixHigh, "Эффекты", panel == Panel.EFFECTS) { panel = if (panel == Panel.EFFECTS) Panel.NONE else Panel.EFFECTS }
                ToolIcon(Icons.Filled.Face, "Красота", panel == Panel.BEAUTY || ui.beautyOn) { panel = if (panel == Panel.BEAUTY) Panel.NONE else Panel.BEAUTY }
                ToolIcon(Icons.Filled.Exposure, "EV", panel == Panel.EV) { panel = if (panel == Panel.EV) Panel.NONE else Panel.EV }
                if (isVideo) ToolIcon(Icons.Filled.AutoAwesome, "AI видео", settings.aiVideoEnabled) { nav.openAiVideo() }
                else ToolIcon(Icons.Filled.Settings, "Профи", panel == Panel.PRO) {
                    if (ui.mode != CameraMode.PRO) vm.setMode(CameraMode.PRO)
                    panel = if (panel == Panel.PRO) Panel.NONE else Panel.PRO
                }
            }

            // ---------- режимы ----------
            ModeStrip(ui.mode, onSelect = { panel = Panel.NONE; vm.setMode(it) }, onMore = nav.openModes)

            // ---------- затвор ----------
            Row(
                Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = 28.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Box(
                    Modifier
                        .size(52.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color(0xFF222222))
                        .clickable { ui.lastUri?.let { nav.openViewer(it, ui.lastIsVideo) } ?: nav.openGallery() },
                    contentAlignment = Alignment.Center
                ) {
                    if (ui.lastUri != null) AsyncImage(model = ui.lastUri, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                }
                ShutterButton(
                    video = isVideo, recording = cam.recording || ui.timelapseActive || ui.panoramaActive,
                    busy = ui.busy != null && !ui.timelapseActive && !ui.panoramaActive
                ) { if (ui.mode == CameraMode.DOCUMENT) launchScanner() else vm.shutter() }
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
                    .align(Alignment.TopCenter)
                    .statusBarsPadding()
                    .padding(top = 64.dp, start = 24.dp, end = 24.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(Color(0xE6222026))
                    .padding(horizontal = 16.dp, vertical = 10.dp)
            ) { Text(msg, color = Color.White, fontSize = 13.sp) }
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
            .statusBarsPadding()
            .padding(horizontal = 10.dp, vertical = 4.dp),
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
        Icon(icon, label, tint = if (active) Accent else Color.White, modifier = Modifier.size(22.dp))
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
    ) { Icon(icon, null, tint = Color.White) }
}

@Composable
private fun ShutterButton(video: Boolean, recording: Boolean, busy: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .size(80.dp)
            .clip(CircleShape)
            .border(4.dp, if (video || recording) Accent else Color.White, CircleShape)
            .clickable(enabled = !busy, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        when {
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
        faces.forEach { r ->
            val l = (if (mirror) 1f - r.right else r.left) * size.width
            val t = r.top * size.height
            val w = r.width() * size.width
            val h = r.height() * size.height
            val k = w * 0.22f
            val st = Stroke(width = 4f)
            val col = Accent
            // уголки рамки
            drawLine(col, Offset(l, t), Offset(l + k, t), st.width); drawLine(col, Offset(l, t), Offset(l, t + k), st.width)
            drawLine(col, Offset(l + w, t), Offset(l + w - k, t), st.width); drawLine(col, Offset(l + w, t), Offset(l + w, t + k), st.width)
            drawLine(col, Offset(l, t + h), Offset(l + k, t + h), st.width); drawLine(col, Offset(l, t + h), Offset(l, t + h - k), st.width)
            drawLine(col, Offset(l + w, t + h), Offset(l + w - k, t + h), st.width); drawLine(col, Offset(l + w, t + h), Offset(l + w, t + h - k), st.width)
        }
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
private fun PanoramaGuide(active: Boolean, frames: Int, modifier: Modifier) {
    Column(modifier.padding(bottom = 64.dp, start = 30.dp, end = 30.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            if (active) "Плавно двигайте камеру вправо →" else "Нажмите затвор и плавно ведите камеру вправо",
            color = Color.White, fontSize = 13.sp,
            modifier = Modifier.clip(RoundedCornerShape(10.dp)).background(Color.Black.copy(alpha = 0.5f)).padding(horizontal = 10.dp, vertical = 4.dp)
        )
        Spacer(Modifier.height(8.dp))
        LinearProgressIndicator(progress = { frames / 16f }, color = Accent, trackColor = Color.White.copy(alpha = 0.3f), modifier = Modifier.fillMaxWidth())
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
private fun FiltersPanel(selected: String, intensity: Float, thumb: Uri?, onSelect: (String) -> Unit, onIntensity: (Float) -> Unit) {
    Column {
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(Filters.all) { f ->
                val sel = f.id == selected
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.clickable { onSelect(f.id) }) {
                    Box(
                        Modifier
                            .size(58.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .border(2.dp, if (sel) Accent else Color.Transparent, RoundedCornerShape(10.dp))
                            .background(AccentGradient)
                    ) {
                        if (thumb != null) AsyncImage(
                            model = thumb, contentDescription = null, contentScale = ContentScale.Crop,
                            colorFilter = ColorFilter.colorMatrix(ColorMatrix(f.matrix)), modifier = Modifier.fillMaxSize()
                        )
                    }
                    Text(f.name, color = if (sel) Accent else Color.White, fontSize = 11.sp, modifier = Modifier.padding(top = 3.dp))
                }
            }
        }
        if (selected != "original") LabeledSlider("Интенсивность", intensity, onChange = onIntensity)
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
            Text("AI Красота", color = Color.White, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            Pill(if (on) "Вкл." else "Выкл.", on) { vm.setBeauty(!on) }
        }
        LabeledSlider("Кожа", skin) { v -> vm.updateSettings({ it.copy(beautySkin = v) }) }
        LabeledSlider("Глаза", eyes) { v -> vm.updateSettings({ it.copy(beautyEyes = v) }) }
        LabeledSlider("Лицо", face) { v -> vm.updateSettings({ it.copy(beautyFace = v) }) }
        Text("Естественная обработка: текстура кожи сохраняется", color = Color.White.copy(alpha = 0.6f), fontSize = 11.sp)
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
