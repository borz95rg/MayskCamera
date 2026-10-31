package com.maysk.camera.ui.screens

import android.hardware.camera2.CameraMetadata
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Hd
import androidx.compose.material.icons.filled.HdrOn
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Straighten
import androidx.compose.material.icons.filled.Theaters
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.maysk.camera.CameraViewModel
import com.maysk.camera.camera.CameraMode
import com.maysk.camera.camera.ManualSettings
import com.maysk.camera.camera.ModeGroup
import com.maysk.camera.data.AudioMode
import com.maysk.camera.data.Bitrate
import com.maysk.camera.data.ColorProfile
import com.maysk.camera.data.Stabilization
import com.maysk.camera.data.VideoRes
import com.maysk.camera.ui.components.CheckLine
import com.maysk.camera.ui.components.ChoiceRow
import com.maysk.camera.ui.components.LabeledSlider
import com.maysk.camera.ui.components.ModeTile
import com.maysk.camera.ui.components.OutlineButton
import com.maysk.camera.ui.components.PrimaryButton
import com.maysk.camera.ui.components.ScreenHeader
import com.maysk.camera.ui.components.SectionLabel
import com.maysk.camera.ui.components.Segmented
import com.maysk.camera.ui.components.SettingRow
import com.maysk.camera.ui.components.icon
import com.maysk.camera.ui.theme.Accent
import com.maysk.camera.ui.theme.AccentDark
import com.maysk.camera.ui.theme.Maysk

/** «Режимы съёмки»: вкладки Фото / Видео, сетка режимов. */
@Composable
fun ModesScreen(vm: CameraViewModel, onBack: () -> Unit, onSpecial: () -> Unit, onAiAuto: () -> Unit) {
    val ui by vm.ui.collectAsState()
    var tab by remember { mutableIntStateOf(if (ui.mode.isVideo) 1 else 0) }
    Column(Modifier.fillMaxSize().background(Maysk.colors.bg)) {
        ScreenHeader("Режимы съёмки", onBack = onBack)
        Segmented(listOf("Фото", "Видео"), tab, Modifier.padding(horizontal = 16.dp)) { tab = it }
        val camState by vm.cam.collectAsState()
        // макро не обещаем, если у телефона нет камеры ближней фокусировки
        val modes = CameraMode.entries.filter { if (tab == 0) it.group == ModeGroup.PHOTO || it.group == ModeGroup.SPECIAL else it.group == ModeGroup.VIDEO }
            .filter { it != CameraMode.MACRO || camState.macroAvailable }
        LazyVerticalGrid(
            GridCells.Fixed(2),
            modifier = Modifier.weight(1f).padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            items(modes) { m ->
                ModeTile(m.icon(), m.title, m.subtitle, m == ui.mode) {
                    if (m == CameraMode.AI_AUTO) onAiAuto() else { vm.setMode(m); onBack() }
                }
            }
        }
        Box(Modifier.padding(16.dp).navigationBarsPadding()) { OutlineButton("Специальные режимы", Modifier.fillMaxWidth(), onSpecial) }
    }
}

/** «Специальные режимы» — крупные плитки. */
@Composable
fun SpecialModesScreen(vm: CameraViewModel, onBack: () -> Unit, onDone: () -> Unit) {
    val list = listOf(
        CameraMode.PANORAMA to listOf(Color(0xFF2E7D32), Color(0xFF1B5E20)),
        CameraMode.MACRO to listOf(Color(0xFFD81B60), Color(0xFF880E4F)),
        CameraMode.NIGHT to listOf(Color(0xFF283593), Color(0xFF0D1440)),
        CameraMode.PORTRAIT to listOf(Color(0xFF8D6E63), Color(0xFF4E342E)),
        CameraMode.FOOD to listOf(Color(0xFFEF6C00), Color(0xFF8D3B00)),
        CameraMode.CAR to listOf(Color(0xFF455A64), Color(0xFF1C272C)),
        CameraMode.PET to listOf(Color(0xFF6D4C41), Color(0xFF3E2723)),
        CameraMode.BABY to listOf(Color(0xFFF48FB1), Color(0xFFAD1457)),
        CameraMode.DOCUMENT to listOf(Color(0xFF546E7A), Color(0xFF263238)),
        CameraMode.LANDSCAPE to listOf(Color(0xFF00838F), Color(0xFF004D40)),
    )
    val camState by vm.cam.collectAsState()
    val shown = list.filter { it.first != CameraMode.MACRO || camState.macroAvailable }
    Column(Modifier.fillMaxSize().background(Maysk.colors.bg)) {
        ScreenHeader("Специальные режимы", onClose = onBack)
        LazyVerticalGrid(
            GridCells.Fixed(2),
            modifier = Modifier.fillMaxSize().padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            items(shown) { (m, colors) ->
                Box(
                    Modifier
                        .aspectRatio(1f)
                        .clip(RoundedCornerShape(16.dp))
                        .background(Brush.linearGradient(colors))
                        .clickable { vm.setMode(m); onDone() }
                ) {
                    Icon(m.icon(), null, tint = Color.White.copy(alpha = 0.35f), modifier = Modifier.size(90.dp).align(Alignment.Center))
                    Column(Modifier.align(Alignment.BottomStart).padding(12.dp)) {
                        Text(m.title, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                        Text(m.subtitle, color = Color.White.copy(alpha = 0.75f), fontSize = 11.sp)
                    }
                }
            }
        }
    }
}

/** «AI Авто». */
@Composable
fun AiAutoScreen(vm: CameraViewModel, onBack: () -> Unit, onShoot: () -> Unit) {
    var how by remember { mutableStateOf(false) }
    Column(
        Modifier
            .fillMaxSize()
            .background(Maysk.colors.bg)
            .verticalScroll(rememberScrollState())
    ) {
        ScreenHeader("AI Авто", onBack = onBack, accentIcon = Icons.Filled.AutoAwesome)
        Text(
            "Камера сама подберёт лучшие настройки для идеального кадра",
            color = Maysk.colors.muted, fontSize = 14.sp, modifier = Modifier.padding(horizontal = 20.dp)
        )
        Box(
            Modifier
                .padding(16.dp)
                .fillMaxWidth()
                .height(200.dp)
                .clip(RoundedCornerShape(18.dp))
                .background(Brush.linearGradient(listOf(Color(0xFFFF8A65), Accent, Color(0xFF311B92)))),
            contentAlignment = Alignment.Center
        ) { Icon(Icons.Filled.AutoAwesome, null, tint = Color.White, modifier = Modifier.size(72.dp)) }
        Column(Modifier.padding(horizontal = 20.dp)) {
            CheckLine("Определение сцены (ML Kit, на устройстве)")
            CheckLine("HDR и улучшение света")
            CheckLine("Подбор цветовой гаммы")
            CheckLine("Стабилизация изображения")
            CheckLine("Улучшение лица (если есть)")
            Spacer(Modifier.height(16.dp))
            PrimaryButton("Снять") { vm.setMode(CameraMode.AI_AUTO); onShoot() }
            Text(
                "Как это работает?", color = Accent, fontSize = 14.sp,
                modifier = Modifier.align(Alignment.CenterHorizontally).padding(14.dp).clickable { how = !how }
            )
            if (how) Text(
                "Во время съёмки AI анализирует кадр 3 раза в секунду: находит лица, оценивает свет, тени и пересветы, " +
                    "распознаёт сцену (еда, пейзаж, питомец, ночь…). Камера сразу подстраивает экспозицию по лицу, " +
                    "шумоподавление и цвет. После снимка применяется умная обработка: автоуровни, локальный HDR, " +
                    "естественная ретушь кожи, резкость и фильтр под сцену. Если телефон поддерживает аппаратный режим " +
                    "производителя (Auto/Night/HDR), Maysk использует и его.",
                color = Maysk.colors.muted, fontSize = 13.sp, modifier = Modifier.padding(bottom = 24.dp)
            )
        }
    }
}

/** «Профессиональные настройки». */
@Composable
fun ProSettingsScreen(vm: CameraViewModel, onBack: () -> Unit) {
    val ui by vm.ui.collectAsState()
    val cam by vm.cam.collectAsState()
    var tab by remember { mutableIntStateOf(0) }
    val m = ui.manual
    var focus by remember { mutableFloatStateOf(m.focusDiopters ?: 0f) }
    fun set(nm: ManualSettings) {
        if (ui.mode != CameraMode.PRO && !ui.mode.isVideo) vm.setMode(CameraMode.PRO)
        vm.setManual(nm)
    }
    Column(
        Modifier
            .fillMaxSize()
            .background(Maysk.colors.bg)
    ) {
        ScreenHeader("Профессиональные настройки", onBack = onBack)
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
        ) {
            Segmented(listOf("Фото", "Видео"), tab) { tab = it }
            if (!cam.manualSupported) Text(
                "Камера этого телефона не поддерживает ручные ISO/выдержку (Camera2 MANUAL_SENSOR). Баланс белого и фокус работают.",
                color = Color(0xFFFFB74D), fontSize = 12.sp, modifier = Modifier.padding(top = 10.dp)
            )
            SectionLabel("ISO")
            val isos = listOf(50, 100, 200, 400, 800, 1600)
            ChoiceRow(isos.map { "$it" }, isos.indexOf(m.iso ?: -1)) { i -> set(m.copy(iso = if (m.iso == isos[i]) null else isos[i])) }
            SectionLabel("Выдержка")
            val sh = if (tab == 0) listOf(8000, 2000, 500, 125, 30) else listOf(2000, 500, 250, 120, 60)
            ChoiceRow(sh.map { "1/$it" }, sh.indexOfFirst { m.exposureNs == 1_000_000_000L / it }) { i ->
                val ns = 1_000_000_000L / sh[i]
                set(m.copy(exposureNs = if (m.exposureNs == ns) null else ns))
            }
            SectionLabel("Диафрагма")
            if (cam.apertures.size > 1) {
                ChoiceRow(cam.apertures.map { "f/%.1f".format(it) }, cam.apertures.indexOf(m.aperture ?: -1f)) { i -> set(m.copy(aperture = cam.apertures[i])) }
            } else {
                Text(
                    "Фиксированная: " + (cam.apertures.firstOrNull()?.let { "f/%.1f".format(it) } ?: "—") +
                        ". Размытие фона — в режиме «Портрет» (AI).",
                    color = Maysk.colors.muted, fontSize = 13.sp
                )
            }
            SectionLabel("Баланс белого")
            val awb = vm.manualAwbModes()
            ChoiceRow(awb.map { it.second }, awb.indexOfFirst { it.first == m.awbMode }) { i -> set(m.copy(awbMode = awb[i].first)) }
            SectionLabel("Фокус")
            val minD = vm.controller.minFocusDiopters()
            val focusOpts = listOf("Авто", "Ручной", "Макро", "Беск.")
            val fIdx = when {
                m.macroFocus -> 2
                m.focusDiopters == 0f -> 3
                m.focusDiopters != null -> 1
                else -> 0
            }
            ChoiceRow(focusOpts, fIdx) { i ->
                when (i) {
                    0 -> set(m.copy(focusDiopters = null, macroFocus = false))
                    1 -> set(m.copy(focusDiopters = if (minD > 0f) minD / 2 else null, macroFocus = false))
                    2 -> set(m.copy(focusDiopters = null, macroFocus = true))
                    3 -> set(m.copy(focusDiopters = 0f, macroFocus = false))
                }
            }
            if (fIdx == 1 && minD > 0f) {
                LabeledSlider("Дистанция", focus, display = if (focus <= 0.01f) "∞" else "%.2f м".format(1f / focus), range = 0f..minD) {
                    focus = it; set(m.copy(focusDiopters = it))
                }
            }
            Spacer(Modifier.height(20.dp))
        }
        Column(Modifier.padding(16.dp).navigationBarsPadding()) {
            PrimaryButton("Сбросить") { vm.setManual(ManualSettings()); focus = 0f }
        }
    }
}

/** «Настройки видео». */
@Composable
fun VideoSettingsScreen(vm: CameraViewModel, onBack: () -> Unit) {
    val s by vm.settings.collectAsState()
    val cam by vm.cam.collectAsState()
    var open by remember { mutableStateOf<String?>(null) }
    Column(Modifier.fillMaxSize().background(Maysk.colors.bg)) {
        ScreenHeader("Настройки видео", onBack = onBack)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 12.dp)) {
            SettingRow(Icons.Filled.Hd, "Качество", subtitle = "${s.videoRes.label} (${s.fps} fps)") { open = if (open == "q") null else "q" }
            if (open == "q") Column(Modifier.padding(horizontal = 12.dp)) {
                val res = VideoRes.entries
                ChoiceRow(res.map { it.label + if (it !in cam.supportedRes) "*" else "" }, res.indexOf(s.videoRes)) { i ->
                    vm.updateSettings({ it.copy(videoRes = res[i]) })
                }
                Text("* не поддерживается этой камерой — будет выбрано ближайшее качество", color = Maysk.colors.muted, fontSize = 11.sp)
                SectionLabel("FPS")
                // только частоты, которые камера реально отдаёт приложению
                val fpsList = cam.fpsOptions.filter { it in listOf(24, 30, 60, 120) }.ifEmpty { listOf(30) }
                ChoiceRow(fpsList.map { "$it" }, fpsList.indexOf(s.fps).coerceAtLeast(0)) { i ->
                    vm.updateSettings({ it.copy(fps = fpsList[i]) })
                }
            }
            SettingRow(Icons.Filled.Straighten, "Стабилизация (антитряска)", subtitle = s.stabilization.label + " — " + s.stabilization.hint) { open = if (open == "s") null else "s" }
            if (open == "s") Column(Modifier.padding(horizontal = 12.dp)) {
                Stabilization.entries.forEach { st ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(if (st == s.stabilization) Accent.copy(alpha = 0.18f) else Color.Transparent)
                            .clickable { vm.updateSettings({ it.copy(stabilization = st) }, rebindAfter = true) }
                            .padding(horizontal = 12.dp, vertical = 9.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(st.label, color = if (st == s.stabilization) Accent else Maysk.colors.text, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                            Text(st.hint, color = Maysk.colors.muted, fontSize = 12.sp)
                        }
                        if (st.software) Text("AI", color = Accent, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                }
                Text(
                    "AI-уровни немного увеличивают кадр (запас 7–14%) и компенсируют тряску по гироскопу. " +
                        "Если на вашем телефоне картинка при движении «уезжает» не туда — включите калибровку:",
                    color = Maysk.colors.muted, fontSize = 11.sp, modifier = Modifier.padding(top = 6.dp)
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 6.dp)) {
                    com.maysk.camera.ui.components.Pill("Инверсия ←→", s.stabInvertX) { vm.updateSettings({ it.copy(stabInvertX = !it.stabInvertX) }, rebindAfter = true) }
                    com.maysk.camera.ui.components.Pill("Инверсия ↑↓", s.stabInvertY) { vm.updateSettings({ it.copy(stabInvertY = !it.stabInvertY) }, rebindAfter = true) }
                }
            }
            SettingRow(Icons.Filled.Videocam, "Формат кадра видео", subtitle = s.videoAspect.label) { open = if (open == "va") null else "va" }
            if (open == "va") Box(Modifier.padding(horizontal = 12.dp)) {
                ChoiceRow(com.maysk.camera.data.Aspect.entries.map { it.label }, s.videoAspect.ordinal) { i ->
                    vm.updateSettings({ it.copy(videoAspect = com.maysk.camera.data.Aspect.entries[i]) })
                }
            }
            SettingRow(Icons.Filled.HdrOn, "Профиль цвета", subtitle = s.colorProfile.label) { open = if (open == "c") null else "c" }
            if (open == "c") Box(Modifier.padding(horizontal = 12.dp)) {
                ChoiceRow(ColorProfile.entries.map { it.label }, s.colorProfile.ordinal) { i -> vm.updateSettings({ it.copy(colorProfile = ColorProfile.entries[i]) }) }
            }
            SettingRow(Icons.Filled.Speed, "Битрейт", subtitle = s.bitrate.label) { open = if (open == "b") null else "b" }
            if (open == "b") Box(Modifier.padding(horizontal = 12.dp)) {
                ChoiceRow(Bitrate.entries.map { it.label }, s.bitrate.ordinal) { i -> vm.updateSettings({ it.copy(bitrate = Bitrate.entries[i]) }) }
            }
            SettingRow(Icons.Filled.Theaters, "Формат", subtitle = "MP4 · кодек выбирает устройство (обычно H.264/HEVC)") { }
            SettingRow(Icons.Filled.Mic, "Микрофон (AI Audio)", subtitle = s.audioMode.label + " — " + s.audioMode.hint) { open = if (open == "a") null else "a" }
            if (open == "a") Box(Modifier.padding(horizontal = 12.dp)) {
                ChoiceRow(AudioMode.entries.map { it.label }, s.audioMode.ordinal) { i -> vm.updateSettings({ it.copy(audioMode = AudioMode.entries[i]) }) }
            }
            SettingRow(Icons.Filled.Videocam, "Режим видео", subtitle = vm.ui.value.mode.title) { }
        }
        Box(Modifier.padding(16.dp).navigationBarsPadding()) {
            PrimaryButton("Применить") { vm.rebind(); onBack() }
        }
    }
}

/** «AI Улучшение видео». */
@Composable
fun AiVideoScreen(vm: CameraViewModel, onBack: () -> Unit) {
    val s by vm.settings.collectAsState()
    Column(Modifier.fillMaxSize().background(Maysk.colors.bg)) {
        ScreenHeader("AI Улучшение видео", onBack = onBack, accentIcon = Icons.Filled.AutoAwesome)
        Box(
            Modifier
                .padding(16.dp)
                .fillMaxWidth()
                .height(190.dp)
                .clip(RoundedCornerShape(18.dp))
                .background(Brush.linearGradient(listOf(Color(0xFF4FC3F7), Color(0xFF1A237E), AccentDark))),
            contentAlignment = Alignment.Center
        ) { Icon(Icons.Filled.Videocam, null, tint = Color.White, modifier = Modifier.size(64.dp)) }
        Column(Modifier.weight(1f).padding(horizontal = 20.dp)) {
            CheckLine("AI стабилизация движения (${s.stabilization.label})", s.stabilization != Stabilization.OFF) {
                vm.updateSettings({ it.copy(stabilization = if (it.stabilization == Stabilization.OFF) Stabilization.AUTO else Stabilization.OFF) }, rebindAfter = true)
            }
            CheckLine("Улучшение освещения", s.aiVideoLight) { vm.updateSettings({ it.copy(aiVideoLight = !it.aiVideoLight) }) }
            CheckLine("Подавление шума", s.aiVideoNoise) { vm.updateSettings({ it.copy(aiVideoNoise = !it.aiVideoNoise) }) }
            CheckLine("Улучшение цвета", s.aiVideoColor) { vm.updateSettings({ it.copy(aiVideoColor = !it.aiVideoColor) }) }
            CheckLine("Сглаживание кожи (опция)", s.aiVideoSkin) { vm.updateSettings({ it.copy(aiVideoSkin = !it.aiVideoSkin) }) }
            Spacer(Modifier.height(8.dp))
            Text(
                "Обработка идёт на GPU прямо во время записи и попадает в итоговый ролик.",
                color = Maysk.colors.muted, fontSize = 12.sp
            )
        }
        Row(Modifier.padding(16.dp).navigationBarsPadding()) {
            PrimaryButton(if (s.aiVideoEnabled) "Выключить" else "Включить") {
                vm.updateSettings({ it.copy(aiVideoEnabled = !it.aiVideoEnabled) })
                onBack()
            }
        }
    }
}

@Suppress("unused")
private val awbHint = CameraMetadata.CONTROL_AWB_MODE_AUTO

@Suppress("unused")
private fun spacer() = Modifier.width(1.dp)
