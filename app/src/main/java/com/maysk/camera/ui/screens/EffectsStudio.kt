package com.maysk.camera.ui.screens

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.maysk.camera.CameraUi
import com.maysk.camera.CameraViewModel
import com.maysk.camera.ar3d.ArCatalog
import com.maysk.camera.ar3d.EffectTemplates
import com.maysk.camera.camera.CamState
import com.maysk.camera.camera.Effect
import com.maysk.camera.camera.FrameSpec
import com.maysk.camera.camera.FrameStyle
import com.maysk.camera.camera.Frames
import com.maysk.camera.camera.Filters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// Палитра студии: розовый акцент Maysk + бирюзовое свечение выбранного эффекта
private val Pink = Color(0xFFFF2E63)
private val Teal = Color(0xFF63E6C2)
private val Glass = Color(0xE6110D16)
private val CardBg = Color(0xFF221D2A)
private val SelectedRing = Brush.linearGradient(listOf(Pink, Color(0xFFB04CFF), Teal))

/** Крупная кнопка «✦ Эффекты» на главном экране камеры. */
@Composable
fun EffectsButton(onClick: () -> Unit) {
    Row(
        Modifier
            .clip(RoundedCornerShape(22.dp))
            .background(Brush.horizontalGradient(listOf(Pink, Color(0xFFFF5C8A))))
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Filled.AutoAwesome, null, tint = Color.White, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(6.dp))
        Text("Эффекты", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
    }
}

/** Карточка в галерее студии. */
private class StudioItem(
    val key: String,                       // для избранного: "3d:cat", "tpl:fox", "flt:cinema", "bg:beach", "fr:film"
    val title: String,
    val image: String?,                    // путь в assets (превью) или null
    val selected: Boolean,
    val badge: String? = null,
    val render: (@Composable BoxScope.() -> Unit)? = null,
    val onClick: () -> Unit,
)

private val TABS = listOf("Популярные", "Все", "Животные", "Макияж", "3D", "Украшения", "Фильтры", "Фон", "Рамки", "Избранное")

/** Подборка «Популярные»: самые узнаваемые маски — первыми. */
private val POPULAR = listOf("tpl:cat", "tpl:puppy", "3d:dog", "tpl:bunny", "3d:cat", "tpl:fox", "tpl:panda", "tpl:crown", "tpl:glasses", "3d:crown", "tpl:hearts_cheeks", "tpl:flower", "tpl:visor", "3d:bunny", "tpl:blush", "tpl:halo")

/**
 * Студия эффектов — отдельный полноэкранный режим поверх живой камеры:
 * сверху — назад, название, смена камеры, настройки; в центре — живой кадр с эффектом;
 * снизу — категории, листаемая галерея карточек, «Настроить / Сбросить / Применить».
 * Эффект применяется сразу при касании карточки (без «Применить»), и попадает в фото и видео.
 */
@Composable
fun EffectsStudio(vm: CameraViewModel, ui: CameraUi, cam: CamState, onClose: () -> Unit) {
    val ctx = LocalContext.current
    var tab by remember { mutableStateOf("Популярные") }
    var showSettings by remember { mutableStateOf(false) }
    var favTick by remember { mutableIntStateOf(0) }
    val favs = remember(favTick) { studioFavorites(ctx) }
    val liveThumb by vm.liveThumb.collectAsState()
    val settings by vm.settings.collectAsState()
    val items = studioItems(ctx, vm, ui, tab, favs, liveThumb)
    val faceEffectOn = ui.ar3d != null || ui.faceTpl != null || ui.blush > 0.01f || ui.lips > 0.01f

    Box(Modifier.fillMaxSize()) {
        // ---------- верхняя панель ----------
        Row(
            Modifier
                .fillMaxWidth()
                .align(Alignment.TopCenter)
                .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.65f), Color.Transparent)))
                .statusBarsPadding()
                .padding(horizontal = 6.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            RoundIcon(Icons.AutoMirrored.Filled.ArrowBack, "Назад", onClick = { if (showSettings) showSettings = false else onClose() })
            Text(if (showSettings) "Настройка эффекта" else "Эффекты", color = Color.White, fontSize = 19.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f).padding(start = 6.dp))
            RoundIcon(Icons.Filled.Cameraswitch, "Сменить камеру") { vm.flipCamera() }
            Spacer(Modifier.width(6.dp))
            RoundIcon(Icons.Filled.Tune, "Настроить", active = showSettings) { showSettings = !showSettings }
        }

        // статус отслеживания лица
        if (faceEffectOn) Text(
            if (ui.ar3dTracking) "● лицо найдено" else "○ покажите лицо в кадр",
            color = if (ui.ar3dTracking) Teal else Color.White, fontSize = 12.sp,
            modifier = Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 58.dp)
                .clip(RoundedCornerShape(12.dp)).background(Color.Black.copy(alpha = 0.45f)).padding(horizontal = 10.dp, vertical = 4.dp)
        )
        if (!cam.effectsActive && cam.ready) Text(
            "На этой камере живые эффекты недоступны — GL-обработка не запустилась",
            color = Color.White, fontSize = 12.sp, textAlign = TextAlign.Center,
            modifier = Modifier.align(Alignment.Center).padding(24.dp).clip(RoundedCornerShape(12.dp)).background(Color(0xCC5D1A1A)).padding(10.dp)
        )

        // ---------- нижняя панель: категории, галерея, кнопки ----------
        Column(
            Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .clip(RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp))
                .background(Glass)
                .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp))
                .navigationBarsPadding()
                .padding(top = 10.dp, bottom = 10.dp)
        ) {
            Box(Modifier.align(Alignment.CenterHorizontally).size(40.dp, 4.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.25f)))
            Spacer(Modifier.height(10.dp))
            LazyRow(contentPadding = PaddingValues(horizontal = 14.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                items(TABS) { t ->
                    val sel = t == tab
                    Text(
                        t, color = if (sel) Color.White else Color.White.copy(alpha = 0.7f), fontSize = 13.sp,
                        fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Normal,
                        modifier = Modifier.clip(RoundedCornerShape(16.dp))
                            .background(if (sel) Pink else Color.White.copy(alpha = 0.07f))
                            .clickable { tab = t }
                            .padding(horizontal = 14.dp, vertical = 7.dp)
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            Gallery(items, onFav = { k -> toggleStudioFavorite(ctx, k); favTick++ }, favs = favs, emptyText = if (tab == "Избранное") "Долгое нажатие на карточку — в избранное ★" else "Здесь пока пусто")
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StudioButton("Настроить", Icons.Filled.Tune, Modifier.weight(1f), filled = false) { showSettings = true }
                StudioButton("Сбросить", Icons.Filled.Close, Modifier.weight(1f), filled = false) { vm.resetStudio() }
                StudioButton("Применить", null, Modifier.weight(1.2f), filled = true, onClick = onClose)
            }
        }

        // ---------- настройки эффекта ----------
        AnimatedVisibility(
            visible = showSettings,
            enter = slideInVertically { it } + fadeIn(), exit = slideOutVertically { it } + fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter)
        ) {
            BeautySheet(vm, ui, settings.beautySkin, settings.beautyFace, settings.beautyEyes, onClose = { showSettings = false })
        }
    }
}

@Composable
private fun Gallery(items: List<StudioItem>, favs: Set<String>, emptyText: String, onFav: (String) -> Unit) {
    val state = rememberLazyListState()
    val scope = rememberCoroutineScope()
    if (items.isEmpty()) {
        Text(emptyText, color = Color.White.copy(alpha = 0.6f), fontSize = 12.sp, modifier = Modifier.fillMaxWidth().padding(vertical = 34.dp), textAlign = TextAlign.Center)
        return
    }
    Box {
        LazyRow(state = state, contentPadding = PaddingValues(horizontal = 30.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(items, key = { it.key }) { item -> Card(item, item.key in favs) { onFav(item.key) } }
        }
        // стрелки листания
        val canBack by remember { derivedStateOf { state.firstVisibleItemIndex > 0 } }
        val canFwd by remember { derivedStateOf { state.layoutInfo.visibleItemsInfo.lastOrNull()?.index?.let { it < state.layoutInfo.totalItemsCount - 1 } ?: false } }
        if (canBack) ArrowBtn(Icons.Filled.ChevronLeft, Modifier.align(Alignment.CenterStart)) {
            scope.launch { state.animateScrollToItem((state.firstVisibleItemIndex - 4).coerceAtLeast(0)) }
        }
        if (canFwd) ArrowBtn(Icons.Filled.ChevronRight, Modifier.align(Alignment.CenterEnd)) {
            scope.launch { state.animateScrollToItem((state.firstVisibleItemIndex + 4).coerceAtMost(items.size - 1)) }
        }
    }
    // точки «страниц»
    val pages = ((items.size + 3) / 4).coerceAtLeast(1)
    val page by remember(items.size) { derivedStateOf { (state.firstVisibleItemIndex / 4).coerceIn(0, pages - 1) } }
    if (pages > 1) Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.Center) {
        repeat(minOf(pages, 8)) { i ->
            Box(Modifier.padding(horizontal = 3.dp).size(if (i == page) 16.dp else 6.dp, 6.dp).clip(CircleShape)
                .background(if (i == page) Pink else Color.White.copy(alpha = 0.3f)))
        }
    }
}

@Composable
private fun Card(item: StudioItem, fav: Boolean, onLong: () -> Unit) {
    val shape = RoundedCornerShape(16.dp)
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.width(70.dp).pointerInput(item.key) {
            detectTapGestures(onTap = { item.onClick() }, onLongPress = { onLong() })
        }
    ) {
        Box(
            Modifier
                .size(68.dp, 86.dp)
                .border(if (item.selected) 2.5.dp else 1.dp, if (item.selected) Pink else Color.White.copy(alpha = 0.1f), shape)
                .padding(if (item.selected) 3.dp else 0.dp)
                .clip(RoundedCornerShape(if (item.selected) 13.dp else 16.dp))
                .background(CardBg)
        ) {
            when {
                item.render != null -> item.render.invoke(this)
                item.image != null -> AsyncImage(model = "file:///android_asset/" + item.image, contentDescription = item.title, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                else -> Text(item.badge ?: "✕", color = Color.White, fontSize = 24.sp, modifier = Modifier.align(Alignment.Center))
            }
            if (item.badge != null && item.image != null) Text(
                item.badge, color = Color.White, fontSize = 8.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.align(Alignment.BottomStart).padding(4.dp).clip(RoundedCornerShape(6.dp)).background(Color.Black.copy(alpha = 0.55f)).padding(horizontal = 4.dp, vertical = 1.dp)
            )
            if (fav) Text("★", color = Color(0xFFFFD54F), fontSize = 12.sp, modifier = Modifier.align(Alignment.TopEnd).padding(4.dp))
        }
        Text(
            item.title, color = if (item.selected) Pink else Color.White, fontSize = 11.sp, maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            fontWeight = if (item.selected) FontWeight.SemiBold else FontWeight.Normal, modifier = Modifier.padding(top = 4.dp)
        )
    }
}

@Composable
private fun RoundIcon(icon: ImageVector, desc: String, active: Boolean = false, onClick: () -> Unit) {
    Box(
        Modifier.size(42.dp).clip(CircleShape).background(if (active) Pink else Color.Black.copy(alpha = 0.35f)).clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) { Icon(icon, desc, tint = Color.White, modifier = Modifier.size(22.dp)) }
}

@Composable
private fun ArrowBtn(icon: ImageVector, modifier: Modifier, onClick: () -> Unit) {
    Box(
        modifier.padding(horizontal = 2.dp).size(28.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.6f)).clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) { Icon(icon, null, tint = Color.White, modifier = Modifier.size(20.dp)) }
}

@Composable
private fun StudioButton(text: String, icon: ImageVector?, modifier: Modifier, filled: Boolean, onClick: () -> Unit) {
    Row(
        modifier
            .height(44.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(if (filled) Brush.horizontalGradient(listOf(Pink, Color(0xFFFF5C8A))) else Brush.horizontalGradient(listOf(Color.White.copy(alpha = 0.08f), Color.White.copy(alpha = 0.08f))))
            .clickable(onClick = onClick),
        horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically
    ) {
        if (icon != null) { Icon(icon, null, tint = Color.White, modifier = Modifier.size(16.dp)); Spacer(Modifier.width(5.dp)) }
        Text(text, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    }
}

/**
 * «Настройка эффекта» (как на эскизе): ползунки со значениями, переключатель «Макияж»,
 * ряд фонов и большая кнопка «Применить». Всё меняется сразу в живом кадре и пишется в видео.
 */
@Composable
private fun BeautySheet(vm: CameraViewModel, ui: CameraUi, skin: Float, light: Float, eyes: Float, onClose: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp))
            .background(Color(0xF7120E17))
            .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp))
            .navigationBarsPadding()
            .padding(horizontal = 20.dp, vertical = 14.dp)
            .verticalScroll(rememberScrollState())
    ) {
        Box(Modifier.align(Alignment.CenterHorizontally).size(40.dp, 4.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.25f)))
        Spacer(Modifier.height(8.dp))
        BeautySlider("Сглаживание кожи", skin) { v -> if (!ui.beautyOn) vm.setBeauty(true); vm.updateSettings({ it.copy(beautySkin = v) }) }
        BeautySlider("Тон кожи", ui.beautyTone) { vm.setFaceBeauty(tone = it) }
        BeautySlider("Осветление", light) { v -> if (!ui.beautyOn) vm.setBeauty(true); vm.updateSettings({ it.copy(beautyFace = v) }) }
        BeautySlider("Увеличение глаз", eyes, hint = "на фото") { v -> if (!ui.beautyOn) vm.setBeauty(true); vm.updateSettings({ it.copy(beautyEyes = v) }) }
        BeautySlider("Интенсивность эффекта", ui.effectAlpha) { vm.setEffectAlpha(it) }
        // «Макияж»: румянец + блеск губ одним переключателем, тонкая настройка — ползунками ниже
        val makeup = ui.blush > 0.01f || ui.lips > 0.01f || ui.lipGloss > 0.01f || ui.eyeShadow > 0.01f || ui.teethWhite > 0.01f || ui.eyeWhite > 0.01f || ui.brighten > 0.01f
        Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Макияж", color = Color.White, fontSize = 14.sp, modifier = Modifier.weight(1f))
            androidx.compose.material3.Switch(
                checked = makeup,
                onCheckedChange = { on ->
                    if (on) vm.setFaceBeauty(blush = 0.45f, lips = 0.5f)
                    else { vm.setFaceBeauty(blush = 0f, lips = 0f); vm.setMakeup(gloss = 0f, teeth = 0f, eyeWhite = 0f, shadow = 0f, brighten = 0f) }
                },
                colors = androidx.compose.material3.SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = Pink, uncheckedTrackColor = Color.White.copy(alpha = 0.15f))
            )
        }
        if (makeup) {
            BeautySlider("Румянец", ui.blush) { vm.setFaceBeauty(blush = it) }
            BeautySlider("Помада", ui.lips) { vm.setFaceBeauty(lips = it) }
            ShadeRow("Цвет помады", com.maysk.camera.ar3d.MakeupPalette.LIPS, ui.lipShade) { vm.setMakeup(lipShade = it) }
            BeautySlider("Блеск губ", ui.lipGloss) { vm.setMakeup(gloss = it) }
            BeautySlider("Тени для век", ui.eyeShadow) { vm.setMakeup(shadow = it) }
            ShadeRow("Цвет теней", com.maysk.camera.ar3d.MakeupPalette.SHADOWS, ui.shadowShade) { vm.setMakeup(shadowShade = it) }
            BeautySlider("Свежесть кожи", ui.brighten) { vm.setMakeup(brighten = it) }
            BeautySlider("Белые зубы", ui.teethWhite) { vm.setMakeup(teeth = it) }
            BeautySlider("Белки глаз", ui.eyeWhite) { vm.setMakeup(eyeWhite = it) }
            Text("Макияж рисуется по сетке лица и попадает в фото и видео. Нужно, чтобы лицо было видно целиком.", color = Color.White.copy(alpha = 0.5f), fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp))
        }
        Text("Фон", color = Color.White, fontSize = 14.sp, modifier = Modifier.padding(top = 10.dp, bottom = 8.dp))
        val ctx = LocalContext.current
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            item { BgChip("Без фона", null, ui.bgId == null) { vm.setBackground(null) } }
            item { BgChip("Размытие", null, ui.bgId == "blur") { vm.setBackground("blur") } }
            items(EffectTemplates.pack(ctx).backgrounds, key = { it.id }) { b -> BgChip(b.title, b.thumb, ui.bgId == b.id) { vm.setBackground(b.id) } }
        }
        Spacer(Modifier.height(14.dp))
        StudioButton("Применить", null, Modifier.fillMaxWidth(), filled = true, onClick = onClose)
        Text(
            "Сбросить всё", color = Color.White.copy(alpha = 0.6f), fontSize = 13.sp, textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().clickable { vm.resetStudio() }.padding(top = 12.dp, bottom = 2.dp)
        )
    }
}

/** Ряд цветных кружков для выбора оттенка. */
@Composable
private fun ShadeRow(title: String, shades: List<com.maysk.camera.ar3d.MakeupPalette.Shade>, selected: Int, onPick: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, color = Color.White.copy(alpha = 0.7f), fontSize = 12.sp, modifier = Modifier.padding(end = 12.dp))
        shades.forEachIndexed { i, sh ->
            Box(
                Modifier
                    .padding(end = 10.dp)
                    .size(30.dp)
                    .clip(CircleShape)
                    .background(if (i == selected) Color.White else Color.Transparent)
                    .padding(3.dp)
                    .clip(CircleShape)
                    .background(Color(sh.argb))
                    .clickable { onPick(i) }
            )
        }
    }
}

@Composable
private fun BeautySlider(label: String, value: Float, hint: String? = null, onChange: (Float) -> Unit) {
    Column(Modifier.padding(top = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, color = Color.White, fontSize = 14.sp)
            if (hint != null) Text("  · $hint", color = Color.White.copy(alpha = 0.45f), fontSize = 11.sp)
            Spacer(Modifier.weight(1f))
            Text("${(value * 100).toInt()}", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        }
        Slider(
            value = value, onValueChange = onChange, valueRange = 0f..1f,
            colors = SliderDefaults.colors(thumbColor = Pink, activeTrackColor = Pink, inactiveTrackColor = Color.White.copy(alpha = 0.15f)),
            modifier = Modifier.height(28.dp)
        )
    }
}

@Composable
private fun BgChip(title: String, thumb: String?, selected: Boolean, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(66.dp).clickable(onClick = onClick)) {
        Box(
            Modifier.size(62.dp)
                .then(if (selected) Modifier.border(2.dp, SelectedRing, RoundedCornerShape(14.dp)) else Modifier)
                .clip(RoundedCornerShape(14.dp)).background(CardBg),
            contentAlignment = Alignment.Center
        ) {
            if (thumb != null) AsyncImage(model = "file:///android_asset/$thumb", contentDescription = title, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            else Text(if (title == "Размытие") "◌" else "✕", color = Color.White, fontSize = 20.sp)
        }
        Text(title, color = if (selected) Teal else Color.White, fontSize = 10.sp, maxLines = 1)
    }
}

// ---------------------------------------------------------------- данные галереи

@Composable
private fun studioItems(ctx: Context, vm: CameraViewModel, ui: CameraUi, tab: String, favs: Set<String>, live: Bitmap?): List<StudioItem> {
    val pack = remember { EffectTemplates.pack(ctx) }
    val models = remember { ArCatalog.entries(ctx) }
    fun tpl(f: com.maysk.camera.ar3d.EffectTemplateData.Face) = StudioItem("tpl:${f.id}", f.title, f.thumb, ui.faceTpl == f.id) { vm.setFaceTemplate(if (ui.faceTpl == f.id) null else f.id) }
    fun model(e: com.maysk.camera.ar3d.ArCatalogEntry) = StudioItem("3d:${e.id}", e.title, e.preview, ui.ar3d == e.id, badge = "3D") { vm.setAr3d(if (ui.ar3d == e.id) null else e.id) }
    val animalCats = setOf("Кошки", "Собаки", "Животные", "Дикие животные")
    val none = StudioItem("none", "Без эффекта", "effects/thumbs/none.jpg", ui.ar3d == null && ui.faceTpl == null) {
        vm.setAr3d(null); vm.setFaceTemplate(null)
    }
    val faces: List<StudioItem> = pack.faces.map(::tpl) + models.map(::model)
    val byKey = faces.associateBy { it.key }
    val filters = Filters.all.map { f ->
        StudioItem("flt:${f.id}", f.name, null, ui.filterId == f.id, render = {
            com.maysk.camera.ui.components.FilterThumb(live ?: com.maysk.camera.ui.components.filterSample, f, Modifier.fillMaxSize())
        }) { vm.setFilter(f.id) }
    }
    val bgs = listOf(
        StudioItem("bg:none", "Без фона", null, ui.bgId == null, badge = "✕") { vm.setBackground(null) },
        StudioItem("bg:blur", "Размытие", null, ui.bgId == "blur", badge = "◌") { vm.setBackground("blur") },
    ) + pack.backgrounds.map { b -> StudioItem("bg:${b.id}", b.title, b.thumb, ui.bgId == b.id) { vm.setBackground(b.id) } }
    val frames = listOf(StudioItem("fr:none", "Без рамки", null, ui.frame == null, badge = "✕") { vm.setFrame(null) }) +
        pack.frames.map { fr ->
            StudioItem("fr:${fr.id}", fr.title, fr.thumb, ui.frame?.image == fr.png) {
                vm.setFrame(FrameSpec(FrameStyle.IMAGE, image = fr.png, imageMode = fr.mode))
            }
        } +
        listOf(FrameStyle.POLAROID, FrameStyle.CINEMA, FrameStyle.FILM_STRIP, FrameStyle.NEON, FrameStyle.HEARTS, FrameStyle.GOLD, FrameStyle.FLORAL, FrameStyle.BIRTHDAY).map { st ->
            StudioItem("fr:${st.name}", st.title, null, ui.frame?.style == st && ui.frame?.image == null, render = { FrameThumb(st) }) { vm.setFrame(Frames.default(st)) }
        }
    return when (tab) {
        "Популярные" -> listOf(none) + POPULAR.mapNotNull { byKey[it] }
        "Все" -> listOf(none) + faces
        "Животные" -> listOf(none) + pack.faces.filter { it.category == "Животные" }.map(::tpl) + models.filter { it.category in animalCats }.map(::model)
        "Макияж" -> listOf(none) + pack.faces.filter { it.category == "Макияж" }.map(::tpl)
        "3D" -> listOf(none) + models.map(::model) + pack.faces.filter { it.category == "3D-эффекты" }.map(::tpl)
        "Украшения" -> listOf(none) + pack.faces.filter { it.category == "Украшения" }.map(::tpl) + models.filter { it.category !in animalCats }.map(::model)
        "Фильтры" -> filters
        "Фон" -> bgs
        "Рамки" -> frames
        "Избранное" -> (faces + filters + bgs + frames).filter { it.key in favs }
        else -> faces
    }
}

/** Миниатюра встроенной рамки (рисуется тем же кодом Frames, что и кадр). */
@Composable
private fun FrameThumb(st: FrameStyle) {
    val bmp by produceState<Bitmap?>(null, st) {
        value = withContext(Dispatchers.Default) {
            runCatching {
                val base = Bitmap.createScaledBitmap(com.maysk.camera.ui.components.filterSample, 120, 160, true)
                Frames.thumbnail(base, Frames.default(st))
            }.getOrNull()
        }
    }
    bmp?.let { Image(it.asImageBitmap(), null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
}

private fun studioFavorites(ctx: Context): Set<String> =
    ctx.getSharedPreferences("studio_fav", Context.MODE_PRIVATE).getStringSet("keys", emptySet()) ?: emptySet()

private fun toggleStudioFavorite(ctx: Context, key: String) {
    val p = ctx.getSharedPreferences("studio_fav", Context.MODE_PRIVATE)
    val s = (p.getStringSet("keys", emptySet()) ?: emptySet()).toMutableSet()
    if (!s.add(key)) s.remove(key)
    p.edit().putStringSet("keys", s).apply()
}
