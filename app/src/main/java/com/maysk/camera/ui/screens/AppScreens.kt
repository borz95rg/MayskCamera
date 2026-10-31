package com.maysk.camera.ui.screens

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material.icons.filled.Camera
import androidx.compose.material.icons.filled.Collections
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.GridOn
import androidx.compose.material.icons.filled.Hd
import androidx.compose.material.icons.filled.HighQuality
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PhotoFilter
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SaveAlt
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Straighten
import androidx.compose.material.icons.filled.Flip
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material.icons.filled.HdrOn
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.maysk.camera.BuildConfig
import com.maysk.camera.CameraViewModel
import com.maysk.camera.data.AudioMode
import com.maysk.camera.media.MediaStoreRepo
import com.maysk.camera.media.VideoTools
import com.maysk.camera.ui.components.AiBadge
import com.maysk.camera.ui.components.ChoiceRow
import com.maysk.camera.ui.components.PrimaryButton
import com.maysk.camera.ui.components.ScreenHeader
import com.maysk.camera.ui.components.SectionLabel
import com.maysk.camera.ui.components.SettingRow
import com.maysk.camera.ui.components.ToggleRow
import com.maysk.camera.ui.theme.Accent
import com.maysk.camera.ui.theme.AccentDark
import com.maysk.camera.ui.theme.AppTheme
import com.maysk.camera.ui.theme.Maysk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Логотип Maysk (объектив). */
@Composable
fun MayskLogo(size: Int = 96) {
    Box(
        Modifier
            .size(size.dp)
            .clip(RoundedCornerShape((size / 4).dp))
            .background(Brush.linearGradient(listOf(Color(0xFF2A1F2E), Color(0xFF0E0A10))))
            .border(1.dp, Accent.copy(alpha = 0.5f), RoundedCornerShape((size / 4).dp)),
        contentAlignment = Alignment.Center
    ) {
        Box(Modifier.size((size * 0.62f).dp).clip(CircleShape).background(Brush.sweepGradient(listOf(Accent, Color(0xFFFF8FB0), AccentDark, Accent))), contentAlignment = Alignment.Center) {
            Box(Modifier.size((size * 0.44f).dp).clip(CircleShape).background(Color(0xFF120D14)), contentAlignment = Alignment.Center) {
                Box(Modifier.size((size * 0.12f).dp).clip(CircleShape).background(Color(0xFFFF6F96)))
            }
        }
    }
}

@Composable
fun BrandTitle() {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text("Maysk", color = Color.White, fontSize = 44.sp, fontWeight = FontWeight.Bold)
        Text("Camera", color = Accent, fontSize = 38.sp, fontWeight = FontWeight.Bold)
    }
}

/** Экран приветствия. */
@Composable
fun WelcomeScreen(onStart: () -> Unit) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(Color(0xFF1B1430), Color(0xFF3A1730), Color(0xFF0E0B10))))
    ) {
        Column(
            Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            MayskLogo(110)
            Spacer(Modifier.height(20.dp))
            BrandTitle()
            Spacer(Modifier.height(14.dp))
            Text("Профессиональная камера\nс искусственным интеллектом", color = Color.White.copy(alpha = 0.85f), textAlign = TextAlign.Center, fontSize = 16.sp)
            Spacer(Modifier.height(28.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                Feature(Icons.Filled.AutoAwesome, "Умный\nAI")
                Feature(Icons.Filled.HdrOn, "HDR\nТехнологии")
                Feature(Icons.Filled.Hd, "4K\nВидео")
                Feature(Icons.Filled.Face, "AI\nКрасота")
            }
            Spacer(Modifier.height(40.dp))
            PrimaryButton("Начать", onClick = onStart)
        }
    }
}

@Composable
private fun Feature(icon: ImageVector, text: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(46.dp).clip(RoundedCornerShape(12.dp)).border(1.5.dp, Accent, RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = Accent)
        }
        Text(text, color = Color.White, fontSize = 11.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 4.dp))
    }
}

enum class Tab(val title: String, val icon: ImageVector) {
    CAMERA("Камера", Icons.Filled.PhotoCamera), GALLERY("Галерея", Icons.Filled.Collections),
    STUDIO("AI Studio", Icons.Filled.AutoAwesome), SETTINGS("Настройки", Icons.Filled.Settings)
}

@Composable
fun MayskBottomBar(current: Tab, onSelect: (Tab) -> Unit) {
    NavigationBar(containerColor = Maysk.colors.surface) {
        Tab.entries.forEach { t ->
            NavigationBarItem(
                selected = t == current, onClick = { onSelect(t) },
                icon = { Icon(t.icon, t.title) }, label = { Text(t.title, fontSize = 11.sp) },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = Color.White, selectedTextColor = Accent, indicatorColor = Accent,
                    unselectedIconColor = Maysk.colors.muted, unselectedTextColor = Maysk.colors.muted
                )
            )
        }
    }
}

/** «Maysk AI Studio». */
@Composable
fun AiStudioScreen(onBack: () -> Unit, openEditor: (Uri, String?) -> Unit, openFilters: (Uri) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var pending by remember { mutableStateOf<String?>(null) }
    var videoUri by remember { mutableStateOf<Uri?>(null) }
    var busy by remember { mutableStateOf<String?>(null) }
    val pickPhoto = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) when (val p = pending) {
            "filters" -> openFilters(uri)
            else -> openEditor(uri, p)
        }
    }
    val pickVideo = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> videoUri = uri }

    Column(Modifier.fillMaxSize().background(Maysk.colors.bg)) {
        ScreenHeader("Maysk AI Studio", onClose = onBack) { AiBadge() }
        Text("Профессиональная обработка фото и видео прямо на телефоне", color = Maysk.colors.muted, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 20.dp))
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            StudioItem(Icons.Filled.AutoFixHigh, "Улучшение фото", "Свет, цвет, HDR, резкость, лицо — в одно касание") {
                pending = "enhance"; pickPhoto.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            }
            StudioItem(Icons.Filled.Videocam, "Улучшение видео", "AI Audio: убрать шум и ветер, усилить голос") {
                pickVideo.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly))
            }
            if (videoUri != null) Column(Modifier.clip(RoundedCornerShape(14.dp)).background(Maysk.colors.surface).padding(12.dp)) {
                Text("Режим звука:", color = Maysk.colors.text, fontSize = 13.sp)
                val modes = AudioMode.entries.drop(1)
                ChoiceRow(modes.map { it.label }, -1) { i ->
                    val src = videoUri ?: return@ChoiceRow
                    videoUri = null
                    busy = "AI Audio: ${modes[i].label}…"
                    scope.launch {
                        val tmp = File(ctx.cacheDir, "studio_video.mp4")
                        val ok = withContext(Dispatchers.IO) { VideoTools.enhanceAudio(ctx, src, tmp, modes[i]) }
                        if (ok) MediaStoreRepo.saveVideoFile(ctx, tmp, MediaStoreRepo.newName("VID_AI"))
                        tmp.delete()
                        busy = null
                        Toast.makeText(ctx, if (ok) "Готово! Новое видео в галерее" else "В видео нет звуковой дорожки", Toast.LENGTH_LONG).show()
                    }
                }
            }
            StudioItem(Icons.Filled.Brush, "Удаление объектов", "Закрасьте лишнее — AI заполнит фон") {
                pending = "remove"; pickPhoto.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            }
            StudioItem(Icons.Filled.Restore, "Восстановление фото", "Старые фото: шум, желтизна, контраст, цвет") {
                pending = "restore"; pickPhoto.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            }
            StudioItem(Icons.Filled.Face, "Ретушь лица", "Естественная кожа и выразительные глаза") {
                pending = "retouch"; pickPhoto.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            }
            StudioItem(Icons.Filled.Camera, "Размытие фона", "Портретный эффект для любого фото с человеком") {
                pending = "background"; pickPhoto.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            }
            StudioItem(Icons.Filled.PhotoFilter, "Стили и фильтры", "20+ кино-, портретных и соц-фильтров") {
                pending = "filters"; pickPhoto.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            }
            busy?.let {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(8.dp)) {
                    CircularProgressIndicator(color = Accent, modifier = Modifier.size(22.dp)); Spacer(Modifier.width(10.dp))
                    Text(it, color = Maysk.colors.text)
                }
            }
        }
    }
}

@Composable
private fun StudioItem(icon: ImageVector, title: String, sub: String, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Maysk.colors.surface)
            .border(1.dp, Maysk.colors.border, RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(44.dp).clip(RoundedCornerShape(12.dp)).background(Accent.copy(alpha = 0.18f)), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = Accent)
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = Maysk.colors.text, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            Text(sub, color = Maysk.colors.muted, fontSize = 12.sp)
        }
    }
}

/** «Настройки». */
@Composable
fun SettingsScreen(vm: CameraViewModel, onBack: () -> Unit, onVideo: () -> Unit, onAbout: () -> Unit, onHelp: () -> Unit, requestLocation: () -> Unit) {
    val s by vm.settings.collectAsState()
    Column(Modifier.fillMaxSize().background(Maysk.colors.bg)) {
        ScreenHeader("Настройки", onClose = onBack)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 12.dp)) {
            SectionLabel("  Основные")
            SettingRow(Icons.Filled.HighQuality, "Качество фото", if (s.photoQualityHigh) "Высокое" else "Стандартное") {
                vm.updateSettings({ it.copy(photoQualityHigh = !it.photoQualityHigh) })
            }
            SettingRow(Icons.Filled.Videocam, "Качество видео", s.videoRes.label, onClick = onVideo)
            ToggleRow(Icons.Filled.GridOn, "Сетка", s.grid) { v -> vm.updateSettings({ it.copy(grid = v) }) }
            ToggleRow(Icons.Filled.Straighten, "Горизонт", s.horizon) { v -> vm.updateSettings({ it.copy(horizon = v) }) }
            SettingRow(Icons.Filled.Straighten, "Стабилизация", "AI " + s.stabilization.label, onClick = onVideo)
            ToggleRow(Icons.Filled.VolumeUp, "Звук затвора", s.shutterSound) { v -> vm.updateSettings({ it.copy(shutterSound = v) }) }
            ToggleRow(Icons.Filled.PhotoCamera, "Затвор кнопками громкости", s.volumeShutter) { v -> vm.updateSettings({ it.copy(volumeShutter = v) }) }
            ToggleRow(Icons.Filled.Mic, "Голосовое управление", s.voiceControl, subtitle = "«Снимок», «Сыр», «Запись», «Стоп»") { v -> vm.updateSettings({ it.copy(voiceControl = v) }) }
            ToggleRow(Icons.Filled.AutoAwesome, "Умный AI", s.smartAi, subtitle = "Анализ сцены и автообработка") { v -> vm.updateSettings({ it.copy(smartAi = v) }) }
            ToggleRow(Icons.Filled.LocationOn, "Геолокация", s.geotag) { v ->
                if (v) requestLocation()
                vm.updateSettings({ it.copy(geotag = v) })
            }
            ToggleRow(Icons.Filled.SaveAlt, "Сохранять оригиналы", s.saveOriginals, subtitle = "Копия без обработки рядом с фото") { v -> vm.updateSettings({ it.copy(saveOriginals = v) }) }
            ToggleRow(Icons.Filled.Flip, "Зеркалить селфи", s.mirrorFront) { v -> vm.updateSettings({ it.copy(mirrorFront = v) }) }

            SectionLabel("  Тема")
            Box(Modifier.padding(horizontal = 12.dp)) {
                ChoiceRow(listOf("Светлая", "Тёмная", "OLED"), s.theme.ordinal) { i -> vm.updateSettings({ it.copy(theme = AppTheme.entries[i]) }) }
            }
            SettingRow(Icons.Filled.Folder, "Папка хранения", MediaStoreRepo.FOLDER) { }

            SectionLabel("  Дополнительно")
            SettingRow(Icons.Filled.SystemUpdate, "Проверить обновления", "v" + BuildConfig.VERSION_NAME) { vm.checkUpdates() }
            SettingRow(Icons.Filled.School, "Помощь и обучение", onClick = onHelp)
            SettingRow(Icons.Filled.Info, "О приложении", "v" + BuildConfig.VERSION_NAME, onClick = onAbout)
            SettingRow(Icons.Filled.Refresh, "Сбросить настройки") {
                vm.updateSettings({ com.maysk.camera.data.AppSettings(onboardingDone = true) }, rebindAfter = true)
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

/** «О приложении» / промо-экран. */
@Composable
fun AboutScreen(onBack: () -> Unit, onTry: () -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(Color(0xFF221828), Color(0xFF0E0B10))))
            .verticalScroll(rememberScrollState())
    ) {
        ScreenHeader("", onBack = onBack)
        Column(Modifier.padding(horizontal = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            MayskLogo(84)
            Spacer(Modifier.height(10.dp))
            BrandTitle()
            Text("Больше, чем просто камера", color = Color.White, fontSize = 18.sp, modifier = Modifier.padding(top = 10.dp))
            Spacer(Modifier.height(20.dp))
            listOf(
                Icons.Filled.Settings to "Профессиональные настройки: ISO, выдержка, баланс белого, фокус",
                Icons.Filled.AutoAwesome to "AI улучшение фото и видео прямо во время съёмки",
                Icons.Filled.PhotoFilter to "Фильтры и эффекты в реальном времени",
                Icons.Filled.Hd to "Видео 4K, стабилизация, LOG, slow-motion, таймлапс",
                Icons.Filled.Face to "Естественная AI-ретушь кожи, глаз и лица",
                Icons.Filled.Restore to "AI Studio: удаление объектов и реставрация фото",
            ).forEach { (ic, t) ->
                Row(Modifier.fillMaxWidth().padding(vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(ic, null, tint = Accent, modifier = Modifier.size(22.dp)); Spacer(Modifier.width(12.dp))
                    Text(t, color = Color.White, fontSize = 14.sp)
                }
            }
            Spacer(Modifier.height(10.dp))
            Text(
                "Вся AI-обработка выполняется на устройстве — фото не отправляются в интернет.",
                color = Color.White.copy(alpha = 0.6f), fontSize = 12.sp, textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(20.dp))
            PrimaryButton("Попробовать сейчас", onClick = onTry)
            Text("Снимай. Создавай. Вдохновляй.", color = Color.White.copy(alpha = 0.6f), fontSize = 12.sp, modifier = Modifier.padding(16.dp))
        }
    }
}

/** «Помощь и обучение». */
@Composable
fun HelpScreen(onBack: () -> Unit) {
    val tips = listOf(
        "AR маски" to "Шторка → «AR маски»: 16 масок (очки, ушки, корона, сердечки, робот…). Маска следует за лицом и сохраняется на фото.",
        "Двойная" to "Шторка → «Двойная»: снимок основной и фронтальной камерой. Раскладки: картинка в картинке, 50/50, круг.",
        "Бумеранг" to "Шторка → «Бумеранг»: 10 кадров превращаются в зацикленное видео туда-обратно.",
        "Шторка" to "Свайп вверх по кадру открывает все инструменты: фильтры, красоту, рамки, HDR, ISO, формат кадра, антитряску. Свайп вниз — скрыть.",
        "Антитряска" to "В видео работает AI-стабилизация по гироскопу: Ходьба, Машина, Бег, Ультра, Gimbal или Авто (сама выбирает уровень). Настраивается в шторке → «Антитряска».",
        "Весь экран" to "Кадр занимает весь экран, фото и видео сохраняются в том же формате. Для максимальной детализации фото выберите формат 4:3 в шторке.",
        "AI" to "Кнопка AI сверху включает AI PRO: камера в реальном времени находит лица и сцену, подстраивает свет и шумоподавление.",
        "0.5x" to "Сверхширокий угол снимается серией кадров: AI складывает их, убирает шум, исправляет дисторсию и добавляет резкость.",
        "Ночь" to "Держите телефон неподвижно 2–3 секунды: 6 кадров выравниваются и складываются в один чистый снимок.",
        "HDR" to "HDR делает 3 кадра с разной экспозицией и склеивает их, чтобы было видно и небо, и тени.",
        "Профи" to "В режиме «Профи» задайте ISO, выдержку, баланс белого и фокус вручную. Гистограмма — в углу превью.",
        "Видео" to "Фильтры, AI-красота и эффекты записываются прямо в ролик. AI Audio очищает звук после записи.",
        "Панорама" to "Нажмите затвор и медленно ведите камеру вправо. Нажмите ещё раз, чтобы закончить.",
        "Таймлапс" to "Кадр делается каждую секунду (настраивается), 30 кадров = 1 секунда видео.",
        "Голос" to "Включите голосовое управление в настройках и скажите «Снимок» или «Сыр».",
    )
    Column(Modifier.fillMaxSize().background(Maysk.colors.bg)) {
        ScreenHeader("Помощь и обучение", onBack = onBack)
        Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            tips.forEach { (t, d) ->
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Maysk.colors.surface).padding(14.dp)) {
                    Text(t, color = Accent, fontWeight = FontWeight.SemiBold)
                    Text(d, color = Maysk.colors.text, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp))
                }
            }
        }
    }
}
