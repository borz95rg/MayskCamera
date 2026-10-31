package com.maysk.camera.ui.screens

import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material.icons.filled.Fullscreen
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.Brightness6
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Tonality
import androidx.compose.material.icons.filled.Crop
import androidx.compose.material.icons.filled.CropFree
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material.icons.filled.EmojiEmotions
import androidx.compose.material.icons.filled.Details
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.HdrOn
import androidx.compose.material.icons.filled.InvertColors
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PhotoFilter
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.RotateRight
import androidx.compose.material.icons.filled.Thermostat
import androidx.compose.material.icons.filled.Undo
import androidx.compose.material.icons.filled.Wallpaper
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.maysk.camera.ai.AiEnhancer
import com.maysk.camera.ai.EnhancePlan
import com.maysk.camera.ai.ImageOps
import com.maysk.camera.ai.Vision
import com.maysk.camera.camera.Effect
import com.maysk.camera.camera.FilterCategory
import com.maysk.camera.camera.Filters
import com.maysk.camera.media.MediaStoreRepo
import com.maysk.camera.ui.components.AiBadge
import com.maysk.camera.ui.components.ChoiceRow
import com.maysk.camera.ui.components.LabeledSlider
import com.maysk.camera.ui.components.OutlineButton
import com.maysk.camera.ui.components.Pill
import com.maysk.camera.ui.components.PrimaryButton
import com.maysk.camera.ui.components.ScreenHeader
import com.maysk.camera.ui.components.Segmented
import com.maysk.camera.ui.components.icon
import com.maysk.camera.ui.theme.Accent
import com.maysk.camera.ui.theme.Maysk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class EditTool(val title: String) {
    NONE(""), CROP("Обрезка"), BRIGHT("Яркость"), CONTRAST("Контраст"), SAT("Насыщенность"),
    FILTER("Фильтры"), LIGHT("Свет"), COLOR("Цвет"), DETAIL("Детали"), FRAME("Рамки"), REMOVE("Удалить объект"),
    TEXT("Текст"), STICKER("Стикеры"), BACKGROUND("Фон"), SKY("Небо")
}

/** Лёгкая модель параметров редактора. */
private data class Adjust(
    val brightness: Float = 0f, val contrast: Float = 0f, val saturation: Float = 0f, val warmth: Float = 0f,
    val shadows: Float = 0f, val highlights: Float = 0f, val detail: Float = 0f,
    val filterId: String = "original", val filterIntensity: Float = 1f,
    val crop: Int = 0, val rotation: Int = 0,
    val frame: com.maysk.camera.camera.FrameSpec? = null,
    val overlays: List<ImageOps.TextOverlay> = emptyList(),
) {
    fun matrix(): FloatArray {
        val adj = Filters.adjustments(brightness, contrast, saturation, warmth)
        val f = Filters.byId(filterId)
        return if (f == Filters.ORIGINAL) adj else Filters.concat(adj, Filters.blended(f, filterIntensity))
    }
    fun pre(): FloatArray = Filters.adjustments(brightness, contrast, saturation, warmth)
    /** Полный рендер цвета: правки + фильтр (матрица и тональная кривая) + виньетка. */
    fun render(b: Bitmap): Bitmap = Filters.render(b, Filters.byId(filterId), if (filterId == "original") 0f else filterIntensity, pre = pre())
    val colorKey get() = listOf(brightness, contrast, saturation, warmth, filterId, filterIntensity)
}

private val cropOptions = listOf("Ориг." to 0f, "1:1" to 1f, "4:5" to 0.8f, "3:4" to 0.75f, "16:9" to 16f / 9f, "9:16" to 9f / 16f)

private fun cropRect(w: Int, h: Int, aspect: Float): RectF {
    if (aspect <= 0f) return RectF(0f, 0f, 1f, 1f)
    val cur = w.toFloat() / h
    return if (cur > aspect) {
        val nw = aspect / cur; RectF((1 - nw) / 2, 0f, (1 + nw) / 2, 1f)
    } else {
        val nh = cur / aspect; RectF(0f, (1 - nh) / 2, 1f, (1 + nh) / 2)
    }
}

/** «AI Редактирование». tool: enhance | remove | background | retouch | restore | null */
@Composable
fun EditorScreen(uri: Uri, startTool: String?, onBack: () -> Unit, onSaved: (Uri) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var base by remember { mutableStateOf<Bitmap?>(null) }        // полное (до 3000px)
    var original by remember { mutableStateOf<Bitmap?>(null) }    // для сравнения «до»
    var preview by remember { mutableStateOf<Bitmap?>(null) }     // база для экрана (≤1280)
    var processed by remember { mutableStateOf<Bitmap?>(null) }   // превью со светом/деталями
    val undo = remember { mutableStateListOf<Bitmap>() }
    var adj by remember { mutableStateOf(Adjust()) }
    var tool by remember { mutableStateOf(EditTool.NONE) }
    var busy by remember { mutableStateOf<String?>(null) }
    var split by remember { mutableFloatStateOf(1f) }
    val strokes = remember { mutableStateListOf<Offset>() }
    var brush by remember { mutableFloatStateOf(0.04f) }
    var plan by remember { mutableStateOf<EnhancePlan?>(null) }
    var framed by remember { mutableStateOf<Bitmap?>(null) }
    var fullView by remember { mutableStateOf(false) }
    var toned by remember { mutableStateOf<Pair<Any, Bitmap>?>(null) }   // точный цвет (с кривой фильтра)
    var selectedOverlay by remember { mutableStateOf<Long?>(null) }
    var textDraft by remember { mutableStateOf("") }

    fun setBase(b: Bitmap, pushUndo: Boolean = true) {
        base?.let { if (pushUndo) { undo.add(it); if (undo.size > 5) undo.removeAt(0) } }
        base = b
        preview = ImageOps.limitSize(b, 1280)
    }

    /** Запуск AI-операции: прогресс, понятное сообщение об ошибке, показ «до/после». */
    fun runAi(label: String, done: String? = null, op: suspend (Bitmap) -> Bitmap) {
        val b = base ?: return
        busy = label
        scope.launch {
            val r = runCatching { withContext(Dispatchers.Default) { op(b) } }
            r.onSuccess { res ->
                setBase(res); split = 0.5f
                Toast.makeText(ctx, (done ?: "Готово") + " · проведите по фото, чтобы сравнить до/после", Toast.LENGTH_LONG).show()
            }.onFailure { e ->
                Toast.makeText(ctx, e.message ?: "Не удалось обработать", Toast.LENGTH_LONG).show()
            }
            busy = null
        }
    }

    fun enhance() = runAi("AI улучшение…", "AI улучшил свет, цвет и детали") { b ->
        val p = AiEnhancer.plan(b); plan = p
        // для заметного результата — без лишнего шумоподавления, с чёткостью
        AiEnhancer.apply(b, p.copy(levels = maxOf(p.levels, 0.6f), clarity = maxOf(p.clarity, 0.2f)))
    }
    fun removeBg() = runAi("Размытие фона…", "Фон размыт") { b ->
        val m = Vision.personMask(ImageOps.limitSize(b, 512))
        if (m != null && ImageOps.personMaskUsable(m.first)) ImageOps.blurBackground(b, m.first, m.second, m.third, 0.7f)
        else {
            withContext(Dispatchers.Main) { Toast.makeText(ctx, "Человек не найден — размываю края кадра", Toast.LENGTH_SHORT).show() }
            ImageOps.radialFocusBlur(b, 0.8f)
        }
    }
    fun replaceBg(b: ImageOps.Backdrop) = runAi("Замена фона…", "Фон заменён: ${b.title}") { src ->
        val m = Vision.personMask(ImageOps.limitSize(src, 512)) ?: error("Человек не найден. Замена фона работает на фото с людьми")
        if (!ImageOps.personMaskUsable(m.first)) error("Человек не найден. Замена фона работает на фото с людьми")
        ImageOps.replaceBackground(src, m.first, m.second, m.third, b)
    }
    fun replaceSky(sky: ImageOps.Sky) = runAi("Замена неба…", "Небо: ${sky.title}") { src ->
        ImageOps.replaceSky(src, sky) ?: error("Небо не найдено. Снимите кадр, где видно светлое небо сверху")
    }
    fun retouch() = runAi("Ретушь лица…", "Кожа и глаза улучшены") { b ->
        val faces = Vision.faces(ImageOps.limitSize(b, 1600))
        if (faces.isEmpty()) error("Лицо не найдено. Ретушь работает на фото с людьми")
        var r = ImageOps.skinSmooth(b, 0.55f, faces.map { it.box }, 0.3f)
        val eyes = faces.flatMap { it.eyes }
        if (eyes.isNotEmpty()) r = ImageOps.enhanceEyes(r, eyes, faces.maxOf { it.box.width() } * 0.09f, 0.5f)
        r
    }
    fun restore() = runAi("Восстановление фото…", "Фото восстановлено: шум, цвет, контраст") { b -> ImageOps.restoreOldPhoto(b) }
    fun applyRemoval() {
        val b = base ?: return
        if (strokes.isEmpty()) return
        val pts = strokes.toList()
        strokes.clear()
        runAi("AI удаляет объект и дорисовывает фон…", "Объект удалён") { _ ->
            val mask = Bitmap.createBitmap(b.width, b.height, Bitmap.Config.ARGB_8888)
            val c = Canvas(mask)
            val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.WHITE }
            val r = brush * b.width / 2f
            pts.forEach { o -> c.drawCircle(o.x * b.width, o.y * b.height, r, p) }
            ImageOps.inpaint(b, mask)
        }
    }

    LaunchedEffect(uri) {
        val b = MediaStoreRepo.loadBitmap(ctx, uri, 3000)
        if (b == null) { Toast.makeText(ctx, "Не удалось открыть фото", Toast.LENGTH_SHORT).show(); onBack(); return@LaunchedEffect }
        original = ImageOps.limitSize(b, 1280)
        setBase(b, pushUndo = false)
        when (startTool) {
            "enhance" -> enhance()
            "remove" -> { tool = EditTool.REMOVE; Toast.makeText(ctx, "Закрасьте пальцем объект, затем «Удалить»", Toast.LENGTH_LONG).show() }
            "sky" -> tool = EditTool.SKY
            "bgreplace" -> tool = EditTool.BACKGROUND
            "text" -> tool = EditTool.TEXT
            "frame" -> tool = EditTool.FRAME
            "background" -> removeBg()
            "retouch" -> retouch()
            "restore" -> restore()
        }
    }
    // Пересчёт света/деталей в превью (с задержкой)
    LaunchedEffect(preview, adj.shadows, adj.highlights, adj.detail) {
        val p = preview ?: return@LaunchedEffect
        delay(120)
        processed = withContext(Dispatchers.Default) {
            var r = ImageOps.localToneMap(p, adj.shadows, adj.highlights, 0.2f)
            if (adj.detail > 0f) r = ImageOps.smartSharpen(r, adj.detail * 1.2f)
            r
        }
    }

    // Превью с рамкой (цвет уже «вшит», чтобы рамка не окрашивалась фильтром)
    LaunchedEffect(processed, preview, adj) {
        val f = adj.frame
        val p = processed ?: preview
        if (f == null || p == null) { framed = null; return@LaunchedEffect }
        delay(80)
        framed = withContext(Dispatchers.Default) { com.maysk.camera.camera.Frames.apply(adj.render(p), f) }
    }

    // Точный цвет превью: ColorFilter даёт мгновенный отклик, а через ~60 мс показываем
    // настоящий рендер с тональной кривой фильтра.
    LaunchedEffect(processed, preview, adj.colorKey) {
        val p = processed ?: preview ?: return@LaunchedEffect
        val a = adj
        val key = listOf(p, a.colorKey)
        delay(60)
        val r = withContext(Dispatchers.Default) { a.render(p) }
        toned = key to r
    }

    fun save() {
        val b = base ?: return
        busy = "Сохранение…"
        scope.launch {
            val out = withContext(Dispatchers.Default) {
                var r = ImageOps.crop(b, cropRect(b.width, b.height, cropOptions[adj.crop].second))
                r = ImageOps.localToneMap(r, adj.shadows, adj.highlights, 0.2f)
                if (adj.detail > 0f) r = ImageOps.smartSharpen(r, adj.detail * 1.2f)
                r = adj.render(r)
                r = ImageOps.drawOverlays(r, adj.overlays)
                adj.frame?.let { fr -> r = com.maysk.camera.camera.Frames.apply(r, fr) }
                r
            }
            val saved = MediaStoreRepo.saveJpeg(ctx, out, 95, name = MediaStoreRepo.newName("EDIT"))
            busy = null
            if (saved != null) { Toast.makeText(ctx, "Сохранено как новое фото", Toast.LENGTH_SHORT).show(); onSaved(saved) }
        }
    }

    Column(Modifier.fillMaxSize().background(Maysk.colors.bg)) {
        if (!fullView) ScreenHeader("AI Редактор", onBack = onBack, accentIcon = Icons.Filled.AutoAwesome) {
            IconButton(onClick = { fullView = true }) { Icon(Icons.Filled.Fullscreen, "Во весь экран", tint = Maysk.colors.text) }
            if (undo.isNotEmpty()) IconButton(onClick = { val last = undo.removeAt(undo.lastIndex); setBase(last, pushUndo = false) }) {
                Icon(Icons.Filled.Undo, "Отменить", tint = Maysk.colors.text)
            }
            Text("Сохранить", color = Accent, fontWeight = FontWeight.SemiBold, modifier = Modifier.clickable { save() }.padding(12.dp))
        }

        // ---------- изображение ----------
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().then(if (fullView) Modifier.background(Color.Black).statusBarsPadding() else Modifier.padding(horizontal = 4.dp)), contentAlignment = Alignment.Center) {
            val showFrame = framed != null && tool != EditTool.REMOVE && tool != EditTool.CROP
            val raw = processed ?: preview
            val exact = toned?.takeIf { it.first == listOf(raw, adj.colorKey) }?.second
            val shown = (if (showFrame) framed else null) ?: exact ?: raw
            if (shown != null) {
                val aspect = shown.width.toFloat() / shown.height
                val cr = cropOptions[adj.crop].second
                val cf = if (showFrame || exact != null) null else ColorFilter.colorMatrix(ColorMatrix(adj.matrix()))
                Box(
                    Modifier
                        .aspectRatio(aspect, matchHeightConstraintsFirst = maxWidth / maxHeight > aspect)
                        .clip(RoundedCornerShape(12.dp))
                ) {
                    val imgMod = Modifier.fillMaxSize()
                    Image(shown.asImageBitmap(), null, contentScale = ContentScale.FillBounds, colorFilter = cf, modifier = imgMod)
                    // «до»
                    if (split < 1f) original?.let { o ->
                        Image(o.asImageBitmap(), null, contentScale = ContentScale.FillBounds, modifier = imgMod.drawWithContent {
                            clipRect(right = size.width * split) { this@drawWithContent.drawContent() }
                        })
                    }
                    // рамка кадрирования
                    if (cr > 0f) {
                        val r = cropRect((aspect * 1000).toInt(), 1000, cr)
                        Box(Modifier.fillMaxSize().drawWithContent {
                            drawContent()
                            val dim = Color.Black.copy(alpha = 0.55f)
                            drawRect(dim, Offset.Zero, androidx.compose.ui.geometry.Size(size.width, r.top * size.height))
                            drawRect(dim, Offset(0f, r.bottom * size.height), androidx.compose.ui.geometry.Size(size.width, size.height * (1 - r.bottom)))
                            drawRect(dim, Offset(0f, 0f), androidx.compose.ui.geometry.Size(r.left * size.width, size.height))
                            drawRect(dim, Offset(r.right * size.width, 0f), androidx.compose.ui.geometry.Size(size.width * (1 - r.right), size.height))
                        })
                    }
                    // надписи и стикеры (перетаскиваются пальцем)
                    if (tool != EditTool.REMOVE && tool != EditTool.CROP) BoxWithConstraints(Modifier.fillMaxSize()) {
                        val bw = maxWidth; val bh = maxHeight
                        adj.overlays.forEach { o ->
                            val density = androidx.compose.ui.platform.LocalDensity.current
                            val fontPx = o.size * with(density) { bw.toPx() }
                            Text(
                                o.text,
                                color = Color(o.color),
                                fontSize = with(density) { fontPx.toSp() },
                                fontWeight = FontWeight.Bold,
                                style = androidx.compose.ui.text.TextStyle(shadow = androidx.compose.ui.graphics.Shadow(Color.Black.copy(alpha = 0.6f), Offset(0f, 3f), 8f)),
                                modifier = Modifier
                                    .align(Alignment.TopStart)
                                    .offset(x = bw * o.x, y = bh * o.y)
                                    .offset { androidx.compose.ui.unit.IntOffset(-(fontPx * o.text.length * 0.3f).toInt(), -(fontPx * 0.6f).toInt()) }
                                    .border(if (selectedOverlay == o.id) 1.dp else 0.dp, if (selectedOverlay == o.id) Accent else Color.Transparent, RoundedCornerShape(4.dp))
                                    .pointerInput(o.id) {
                                        detectDragGestures(onDragStart = { selectedOverlay = o.id }) { ch, drag ->
                                            ch.consume()
                                            val wPx = bw.toPx(); val hPx = bh.toPx()
                                            adj = adj.copy(overlays = adj.overlays.map {
                                                if (it.id == o.id) it.copy(x = (it.x + drag.x / wPx).coerceIn(0f, 1f), y = (it.y + drag.y / hPx).coerceIn(0f, 1f)) else it
                                            })
                                        }
                                    }
                                    .clickable { selectedOverlay = o.id }
                            )
                        }
                    }
                    // кисть удаления
                    if (tool == EditTool.REMOVE) {
                        Box(
                            Modifier
                                .fillMaxSize()
                                .pointerInput(Unit) {
                                    detectDragGestures(
                                        onDragStart = { o -> strokes.add(Offset(o.x / size.width, o.y / size.height)) },
                                        onDrag = { ch, _ -> strokes.add(Offset(ch.position.x / size.width, ch.position.y / size.height)) }
                                    )
                                }
                                .drawWithContent {
                                    drawContent()
                                    strokes.forEach { s ->
                                        drawCircle(Accent.copy(alpha = 0.45f), brush * size.width / 2f, Offset(s.x * size.width, s.y * size.height))
                                    }
                                }
                        )
                    } else if (split < 1f) {
                        // ползунок «до / после»
                        Box(
                            Modifier
                                .fillMaxSize()
                                .pointerInput(Unit) {
                                    detectHorizontalDragGestures { ch, _ -> split = (ch.position.x / size.width).coerceIn(0f, 1f) }
                                }
                                .drawWithContent {
                                    drawContent()
                                    val x = size.width * split
                                    drawLine(Color.White, Offset(x, 0f), Offset(x, size.height), 4f)
                                    drawCircle(Color.White, 14f, Offset(x, size.height / 2))
                                }
                        ) {
                            Text("До", color = Color.White, fontSize = 11.sp, modifier = Modifier.align(Alignment.TopStart).padding(8.dp).background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(6.dp)).padding(horizontal = 6.dp, vertical = 2.dp))
                            Text("После", color = Color.White, fontSize = 11.sp, modifier = Modifier.align(Alignment.TopEnd).padding(8.dp).background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(6.dp)).padding(horizontal = 6.dp, vertical = 2.dp))
                        }
                    }
                }
            } else CircularProgressIndicator(color = Accent)
            busy?.let {
                Column(Modifier.clip(RoundedCornerShape(14.dp)).background(Color.Black.copy(alpha = 0.7f)).padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(color = Accent)
                    Text(it, color = Color.White, modifier = Modifier.padding(top = 8.dp))
                }
            }
            if (fullView) Text(
                "✕  Выйти из полноэкранного", color = Color.White, fontSize = 13.sp,
                modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(16.dp)
                    .clip(RoundedCornerShape(20.dp)).background(Color.Black.copy(alpha = 0.6f))
                    .clickable { fullView = false }.padding(horizontal = 16.dp, vertical = 8.dp)
            )
        }
        if (!fullView) {

        plan?.let { p ->
            if (p.recommendations.isNotEmpty()) Text(
                "AI применил: " + p.recommendations.joinToString(" · "),
                color = Maysk.colors.muted, fontSize = 11.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
            )
        }

        // ---------- параметры инструмента (компактно, с прокруткой) ----------
        Box(Modifier.fillMaxWidth().heightIn(max = 210.dp).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
            when (tool) {
                EditTool.CROP -> Column {
                    ChoiceRow(cropOptions.map { it.first }, adj.crop) { adj = adj.copy(crop = it) }
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp).clickable {
                        base?.let { b -> setBase(ImageOps.rotate(b, 90)) }
                        original = original?.let { o -> ImageOps.rotate(o, 90) }
                    }) {
                        Icon(Icons.Filled.RotateRight, null, tint = Maysk.colors.text); Text(" Повернуть", color = Maysk.colors.text)
                    }
                }
                EditTool.BRIGHT -> LabeledSlider("Яркость", adj.brightness, "%+d".format((adj.brightness * 100).toInt()), -1f..1f) { adj = adj.copy(brightness = it) }
                EditTool.CONTRAST -> LabeledSlider("Контраст", adj.contrast, "%+d".format((adj.contrast * 100).toInt()), -1f..1f) { adj = adj.copy(contrast = it) }
                EditTool.SAT -> LabeledSlider("Насыщенность", adj.saturation, "%+d".format((adj.saturation * 100).toInt()), -1f..1f) { adj = adj.copy(saturation = it) }
                EditTool.COLOR -> LabeledSlider("Теплота", adj.warmth, "%+d".format((adj.warmth * 100).toInt()), -1f..1f) { adj = adj.copy(warmth = it) }
                EditTool.LIGHT -> Column {
                    LabeledSlider("Тени", adj.shadows) { adj = adj.copy(shadows = it) }
                    LabeledSlider("Света", adj.highlights) { adj = adj.copy(highlights = it) }
                }
                EditTool.DETAIL -> LabeledSlider("Детали / резкость", adj.detail) { adj = adj.copy(detail = it) }
                EditTool.FILTER -> Column {
                    val cur = Filters.byId(adj.filterId)
                    val fl = remember { Filters.search("", null) }
                    val tb = remember(preview) { preview?.let { ImageOps.limitSize(it, 200) } }
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(fl, key = { it.id }) { f ->
                            com.maysk.camera.ui.components.FilterTile(tb, f, f.id == adj.filterId, 56.dp, Maysk.colors.text) {
                                adj = adj.copy(filterId = f.id)
                            }
                        }
                    }
                    if (adj.filterId != "original") LabeledSlider("Интенсивность", adj.filterIntensity) { adj = adj.copy(filterIntensity = it) }
                }
                EditTool.FRAME -> FramePicker(adj.frame, preview) { adj = adj.copy(frame = it) }
                EditTool.TEXT -> Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        androidx.compose.material3.OutlinedTextField(
                            value = textDraft, onValueChange = { textDraft = it }, singleLine = true,
                            placeholder = { Text("Ваш текст") }, modifier = Modifier.weight(1f)
                        )
                        Spacer(Modifier.width(8.dp))
                        Box(Modifier.width(110.dp)) {
                            PrimaryButton("Добавить", enabled = textDraft.isNotBlank()) {
                                val o = ImageOps.TextOverlay(System.nanoTime(), textDraft.trim(), 0.5f, 0.5f, 0.08f)
                                adj = adj.copy(overlays = adj.overlays + o); selectedOverlay = o.id; textDraft = ""
                            }
                        }
                    }
                    OverlayControls(adj.overlays.firstOrNull { it.id == selectedOverlay }, onChange = { n -> adj = adj.copy(overlays = adj.overlays.map { if (it.id == n.id) n else it }) },
                        onDelete = { id -> adj = adj.copy(overlays = adj.overlays.filter { it.id != id }); selectedOverlay = null })
                }
                EditTool.STICKER -> Column {
                    val stickers = listOf("😍", "🔥", "❤️", "⭐", "😎", "🎉", "👑", "🌸", "🦋", "🌈", "☀️", "🌙", "💎", "🍀", "🎂", "🎈", "💋", "👍", "😂", "🥳", "🐱", "🐶", "✨", "📸")
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(stickers) { e ->
                            Text(e, fontSize = 30.sp, modifier = Modifier.clickable {
                                val o = ImageOps.TextOverlay(System.nanoTime(), e, 0.3f + (adj.overlays.size % 5) * 0.1f, 0.4f, 0.16f, emoji = true)
                                adj = adj.copy(overlays = adj.overlays + o); selectedOverlay = o.id
                            }.padding(4.dp))
                        }
                    }
                    Text("Перетаскивайте стикер пальцем", color = Maysk.colors.muted, fontSize = 11.sp)
                    OverlayControls(adj.overlays.firstOrNull { it.id == selectedOverlay }, onChange = { n -> adj = adj.copy(overlays = adj.overlays.map { if (it.id == n.id) n else it }) },
                        onDelete = { id -> adj = adj.copy(overlays = adj.overlays.filter { it.id != id }); selectedOverlay = null })
                }
                EditTool.BACKGROUND -> Column {
                    Text("AI найдёт человека и заменит фон за ним", color = Maysk.colors.muted, fontSize = 12.sp)
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 6.dp)) {
                        items(ImageOps.Backdrop.entries) { b -> Pill(b.title, false) { replaceBg(b) } }
                    }
                }
                EditTool.SKY -> Column {
                    Text("AI найдёт небо и заменит его (лучше всего — на пейзажах)", color = Maysk.colors.muted, fontSize = 12.sp)
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 6.dp)) {
                        items(ImageOps.Sky.entries) { k -> Pill(k.title, false) { replaceSky(k) } }
                    }
                }
                EditTool.REMOVE -> Column {
                    Text("Закрасьте объект пальцем, затем нажмите «Удалить»", color = Maysk.colors.muted, fontSize = 12.sp)
                    LabeledSlider("Размер кисти", brush, "${(brush * 100).toInt()}", 0.01f..0.12f) { brush = it }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlineButton("Очистить", Modifier.weight(1f)) { strokes.clear() }
                        Box(Modifier.weight(1f)) { PrimaryButton("Удалить", enabled = strokes.isNotEmpty()) { applyRemoval() } }
                    }
                }
                EditTool.NONE -> Unit
            }
        }

        // ---------- AI инструменты (скрыты, пока открыт инструмент — фото остаётся крупным) ----------
        if (tool == EditTool.NONE) Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
            EditAction(Icons.Filled.AutoFixHigh, "Улучшить", accent = true) { tool = EditTool.NONE; enhance() }
            EditAction(Icons.Filled.Brush, "Удалить объект", tool == EditTool.REMOVE) { tool = if (tool == EditTool.REMOVE) EditTool.NONE else EditTool.REMOVE }
            EditAction(Icons.Filled.Wallpaper, "Фон", tool == EditTool.BACKGROUND) { tool = if (tool == EditTool.BACKGROUND) EditTool.NONE else EditTool.BACKGROUND }
            EditAction(Icons.Filled.Cloud, "Небо", tool == EditTool.SKY) { tool = if (tool == EditTool.SKY) EditTool.NONE else EditTool.SKY }
            EditAction(Icons.Filled.Face, "Ретушь лица") { tool = EditTool.NONE; retouch() }
            EditAction(Icons.Filled.Restore, "Реставрация") { tool = EditTool.NONE; restore() }
        }
        LazyRow(
            Modifier.fillMaxWidth().navigationBarsPadding().padding(vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp)
        ) {
            val tools = listOf(
                EditTool.CROP to Icons.Filled.Crop, EditTool.BRIGHT to Icons.Filled.Brightness6, EditTool.CONTRAST to Icons.Filled.Tonality,
                EditTool.SAT to Icons.Filled.InvertColors, EditTool.FILTER to Icons.Filled.PhotoFilter, EditTool.LIGHT to Icons.Filled.WbSunny,
                EditTool.COLOR to Icons.Filled.Thermostat, EditTool.DETAIL to Icons.Filled.Details,
                EditTool.FRAME to Icons.Filled.CropFree,
                EditTool.TEXT to Icons.Filled.TextFields,
                EditTool.STICKER to Icons.Filled.EmojiEmotions,
            )
            items(tools) { (t, ic) -> EditAction(ic, t.title, tool == t) { tool = if (tool == t) EditTool.NONE else t } }
        }
        }
    }
}

/** Настройки выбранной надписи/стикера: размер, цвет, стиль, удаление. */
@Composable
private fun OverlayControls(o: ImageOps.TextOverlay?, onChange: (ImageOps.TextOverlay) -> Unit, onDelete: (Long) -> Unit) {
    if (o == null) return
    Column(Modifier.padding(top = 6.dp)) {
        LabeledSlider("Размер", o.size, "${(o.size * 100).toInt()}", 0.03f..0.4f) { onChange(o.copy(size = it)) }
        if (!o.emoji) {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(com.maysk.camera.camera.Frames.palette) { col ->
                    Box(
                        Modifier.size(26.dp).clip(CircleShape).background(Color(col))
                            .border(2.dp, if (o.color == col) Accent else Color.Gray.copy(alpha = 0.5f), CircleShape)
                            .clickable { onChange(o.copy(color = col)) }
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 6.dp)) {
                listOf("Тень", "Плашка", "Обводка").forEachIndexed { i, t -> Pill(t, o.style == i) { onChange(o.copy(style = i)) } }
            }
        }
        Text("Удалить", color = Accent, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp).clickable { onDelete(o.id) })
    }
}

/** Выбор рамки по картинкам: категории, миниатюры, свёрнутые настройки (цвет, толщина, подпись…). */
@Composable
fun FramePicker(
    current: com.maysk.camera.camera.FrameSpec?,
    sample: Bitmap? = null,
    videoMode: Boolean = false,
    onChange: (com.maysk.camera.camera.FrameSpec?) -> Unit,
) {
    val F = com.maysk.camera.camera.Frames
    val FS = com.maysk.camera.camera.FrameStyle
    var cat by remember { mutableStateOf<String?>(null) }
    var tune by remember { mutableStateOf(false) }
    val base = remember(sample) {
        val src = sample ?: com.maysk.camera.ui.components.filterSample
        // миниатюра 3:4
        val w = src.width; val h = src.height
        val cw = minOf(w, h * 3 / 4); val ch = minOf(h, w * 4 / 3)
        Bitmap.createScaledBitmap(Bitmap.createBitmap(src, (w - cw) / 2, (h - ch) / 2, cw, ch), 120, 160, true)
    }
    val styles = remember(cat, videoMode) { FS.entries.filter { (cat == null || it.category == cat) && !(videoMode && it.photoOnly) } }
    Column {
        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            item { Pill("Все ${FS.entries.size}", cat == null) { cat = null } }
            items(FS.categories) { c -> Pill(c, cat == c) { cat = c } }
        }
        LazyRow(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(60.dp).clickable { onChange(null) }) {
                    Box(
                        Modifier.size(54.dp, 72.dp).clip(RoundedCornerShape(8.dp)).background(Color(0xFF2A2630))
                            .border(2.dp, if (current == null) Accent else Color.Transparent, RoundedCornerShape(8.dp)),
                        contentAlignment = Alignment.Center
                    ) { Icon(Icons.Filled.Block, null, tint = Color.White) }
                    Text("Без", color = if (current == null) Accent else Maysk.colors.text, fontSize = 10.sp, maxLines = 1)
                }
            }
            items(styles, key = { it.name }) { st ->
                val spec = if (current?.style == st) current else F.default(st)
                val thumb by androidx.compose.runtime.produceState<Bitmap?>(null, base, st) {
                    value = withContext(Dispatchers.Default) { runCatching { F.thumbnail(base, F.default(st)) }.getOrNull() }
                }
                val sel = current?.style == st
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(60.dp).clickable { onChange(spec) }) {
                    Box(
                        Modifier.size(54.dp, 72.dp).clip(RoundedCornerShape(8.dp)).background(Color(0xFF2A2630))
                            .border(2.dp, if (sel) Accent else Color.Transparent, RoundedCornerShape(8.dp))
                    ) { thumb?.let { Image(it.asImageBitmap(), null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) } }
                    Text(st.title, color = if (sel) Accent else Maysk.colors.text, fontSize = 10.sp, maxLines = 1)
                }
            }
        }
        if (current != null) {
            Text(
                if (tune) "Скрыть настройки ▴" else "Настроить рамку ▾", color = Accent, fontSize = 12.sp,
                modifier = Modifier.padding(top = 4.dp).clickable { tune = !tune }.padding(vertical = 4.dp)
            )
            if (tune) {
                LazyRow(Modifier.padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(F.palette) { col ->
                        Box(
                            Modifier.size(26.dp).clip(CircleShape).background(Color(col))
                                .border(2.dp, if (current.color == col) Accent else Color.Gray.copy(alpha = 0.5f), CircleShape)
                                .clickable { onChange(current.copy(color = col)) }
                        )
                    }
                }
                LabeledSlider("Толщина", current.thickness) { onChange(current.copy(thickness = it)) }
                LabeledSlider("Скругление", current.radius) { onChange(current.copy(radius = it)) }
                LabeledSlider("Прозрачность", 1f - current.opacity, "${((1f - current.opacity) * 100).toInt()}") { onChange(current.copy(opacity = 1f - it)) }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    androidx.compose.material3.OutlinedTextField(
                        value = current.caption, onValueChange = { onChange(current.copy(caption = it.take(40))) }, singleLine = true,
                        placeholder = { Text("Подпись на рамке") }, modifier = Modifier.weight(1f)
                    )
                    Spacer(Modifier.width(8.dp))
                    Pill("Тень", current.shadow) { onChange(current.copy(shadow = !current.shadow)) }
                }
            }
        }
    }
}

@Composable
private fun EditAction(icon: ImageVector, label: String, selected: Boolean = false, accent: Boolean = false, onClick: () -> Unit) {
    Column(Modifier.clickable(onClick = onClick).padding(horizontal = 4.dp, vertical = 2.dp).width(58.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier.size(36.dp).clip(CircleShape).background(if (selected || accent) Accent else Maysk.colors.surface2),
            contentAlignment = Alignment.Center
        ) { Icon(icon, label, tint = Color.White, modifier = Modifier.size(20.dp)) }
        Text(label, color = if (selected) Accent else Maysk.colors.text, fontSize = 10.sp, maxLines = 1, modifier = Modifier.padding(top = 2.dp))
    }
}

// ================= Фильтры =================

@Composable
fun FiltersScreen(uri: Uri, onBack: () -> Unit, onSaved: (Uri) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var img by remember { mutableStateOf<Bitmap?>(null) }
    var thumb by remember { mutableStateOf<Bitmap?>(null) }
    var cat by remember { mutableIntStateOf(0) }
    var sel by remember { mutableStateOf("original") }
    var intensity by remember { mutableFloatStateOf(0.8f) }
    var busy by remember { mutableStateOf(false) }
    LaunchedEffect(uri) {
        img = MediaStoreRepo.loadBitmap(ctx, uri, 1600)
        thumb = img?.let { ImageOps.limitSize(it, 240) }
    }
    val cats = listOf<FilterCategory?>(null) + FilterCategory.entries.filter { c -> Filters.all.any { it.category == c } }
    Column(Modifier.fillMaxSize().background(Maysk.colors.bg)) {
        ScreenHeader("Фильтры", onClose = onBack)
        val shownImg by androidx.compose.runtime.produceState<Bitmap?>(null, img, sel, intensity) {
            val i = img ?: return@produceState
            delay(40)
            value = withContext(Dispatchers.Default) { Filters.render(ImageOps.limitSize(i, 1200), Filters.byId(sel), intensity) }
        }
        Box(Modifier.fillMaxWidth().height(320.dp).padding(horizontal = 16.dp), contentAlignment = Alignment.Center) {
            (shownImg ?: img)?.let {
                Image(it.asImageBitmap(), null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(14.dp)))
            } ?: CircularProgressIndicator(color = Accent)
        }
        LazyRow(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(cats.indices.toList()) { i -> Pill(cats[i]?.title ?: "Все", cat == i) { cat = i } }
        }
        var query by remember { mutableStateOf("") }
        androidx.compose.material3.OutlinedTextField(
            value = query, onValueChange = { query = it }, singleLine = true,
            placeholder = { Text("Поиск среди ${Filters.all.size} фильтров") },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)
        )
        val list = remember(cat, query) { Filters.search(query, cats[cat]) }
        LazyVerticalGrid(GridCells.Fixed(3), Modifier.weight(1f).padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(list, key = { it.id }) { f ->
                val on = sel == f.id
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.clickable { sel = f.id }) {
                    com.maysk.camera.ui.components.FilterThumb(
                        thumb, f,
                        Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(10.dp))
                            .border(2.dp, if (on) Accent else Color.Transparent, RoundedCornerShape(10.dp))
                    )
                    Text(f.name, color = if (on) Accent else Maysk.colors.text, fontSize = 12.sp, maxLines = 1)
                }
            }
        }
        Column(Modifier.padding(16.dp).navigationBarsPadding()) {
            LabeledSlider("Интенсивность", intensity) { intensity = it }
            PrimaryButton(if (busy) "Сохранение…" else "Сохранить", enabled = !busy && sel != "original") {
                busy = true
                scope.launch {
                    val full = MediaStoreRepo.loadBitmap(ctx, uri, 4000) ?: return@launch
                    val f = Filters.byId(sel)
                    val out = withContext(Dispatchers.Default) {
                        Filters.render(full, f, intensity)
                    }
                    val u = MediaStoreRepo.saveJpeg(ctx, out, 95, name = MediaStoreRepo.newName("FILTER"))
                    busy = false
                    u?.let(onSaved)
                }
            }
        }
    }
}

// ================= Эффекты =================

@Composable
fun EffectsScreen(uri: Uri?, onBack: () -> Unit, onSaved: (Uri) -> Unit, onUseInCamera: (Effect) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var tab by remember { mutableIntStateOf(if (uri == null) 1 else 0) }
    var img by remember { mutableStateOf<Bitmap?>(null) }
    var shown by remember { mutableStateOf<Bitmap?>(null) }
    var sel by remember { mutableStateOf(Effect.NONE) }
    var busy by remember { mutableStateOf(false) }
    LaunchedEffect(uri) { if (uri != null) { img = MediaStoreRepo.loadBitmap(ctx, uri, 1200); shown = img } }
    LaunchedEffect(sel, img) {
        val b = img ?: return@LaunchedEffect
        shown = withContext(Dispatchers.Default) {
            if (sel == Effect.BG_BLUR) Vision.personMask(ImageOps.limitSize(b, 512))?.let { (m, w, h) -> ImageOps.blurBackground(b, m, w, h, 0.6f) } ?: b
            else ImageOps.applyEffect(b, sel, 7L)
        }
    }
    Column(Modifier.fillMaxSize().background(Maysk.colors.bg)) {
        ScreenHeader("Эффекты", onClose = onBack)
        Segmented(listOf("Фото", "Видео"), tab, Modifier.padding(horizontal = 16.dp)) { tab = it }
        if (tab == 0) {
            Box(Modifier.fillMaxWidth().height(260.dp).padding(16.dp), contentAlignment = Alignment.Center) {
                shown?.let { Image(it.asImageBitmap(), null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(14.dp))) }
                    ?: Text("Откройте фото из галереи", color = Maysk.colors.muted)
            }
        } else {
            Text(
                "Видеоэффекты работают в реальном времени в камере и записываются в ролик. Выберите эффект — откроется камера в режиме видео.",
                color = Maysk.colors.muted, fontSize = 13.sp, modifier = Modifier.padding(16.dp)
            )
        }
        LazyVerticalGrid(GridCells.Fixed(3), Modifier.weight(1f).padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(Effect.entries.drop(1)) { e ->
                Column(
                    Modifier
                        .clip(RoundedCornerShape(14.dp))
                        .background(if (sel == e) Accent else Maysk.colors.surface)
                        .clickable { if (tab == 1) onUseInCamera(e) else sel = e }
                        .padding(vertical = 14.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(e.icon(), null, tint = Color.White, modifier = Modifier.size(28.dp))
                    Text(e.title, color = Color.White, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp))
                }
            }
        }
        if (tab == 0) Row(Modifier.padding(16.dp).navigationBarsPadding(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlineButton("Сбросить", Modifier.weight(1f)) { sel = Effect.NONE }
            Box(Modifier.weight(1f)) {
                PrimaryButton(if (busy) "…" else "Применить", enabled = !busy && sel != Effect.NONE && uri != null) {
                    busy = true
                    scope.launch {
                        val full = MediaStoreRepo.loadBitmap(ctx, uri!!, 3000) ?: return@launch
                        val out = withContext(Dispatchers.Default) {
                            if (sel == Effect.BG_BLUR) Vision.personMask(ImageOps.limitSize(full, 512))?.let { (m, w, h) -> ImageOps.blurBackground(full, m, w, h, 0.6f) } ?: full
                            else ImageOps.applyEffect(full, sel, 7L)
                        }
                        val u = MediaStoreRepo.saveJpeg(ctx, out, 95, name = MediaStoreRepo.newName("FX"))
                        busy = false
                        u?.let(onSaved)
                    }
                }
            }
        }
    }
}

// ================= Анализ сцены =================

@Composable
fun SceneAnalysisScreen(uri: Uri, onBack: () -> Unit, onSaved: (Uri) -> Unit, onEdit: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var img by remember { mutableStateOf<Bitmap?>(null) }
    var plan by remember { mutableStateOf<EnhancePlan?>(null) }
    var framed by remember { mutableStateOf<Bitmap?>(null) }
    var selectedOverlay by remember { mutableStateOf<Long?>(null) }
    var textDraft by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    LaunchedEffect(uri) {
        val b = MediaStoreRepo.loadBitmap(ctx, uri, 2400) ?: return@LaunchedEffect
        img = b
        plan = withContext(Dispatchers.Default) { AiEnhancer.plan(b) }
    }
    Column(Modifier.fillMaxSize().background(Maysk.colors.bg)) {
        ScreenHeader("Анализ сцены", onBack = onBack, accentIcon = Icons.Filled.AutoAwesome)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
            Box(Modifier.fillMaxWidth().height(240.dp).clip(RoundedCornerShape(16.dp))) {
                img?.let { Image(it.asImageBitmap(), null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
                plan?.let { p ->
                    Row(Modifier.align(Alignment.BottomStart).padding(10.dp)) { AiBadge(p.scene.title) }
                }
            }
            if (plan == null) {
                Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(color = Accent, modifier = Modifier.size(22.dp)); Spacer(Modifier.width(10.dp))
                    Text("AI анализирует кадр…", color = Maysk.colors.text)
                }
            }
            plan?.let { p ->
                Text("Обнаружено:", color = Maysk.colors.text, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 16.dp, bottom = 6.dp))
                p.detected.forEach { d ->
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
                        Icon(
                            when {
                                d.contains("Челов") || d.contains("Люди") || d.contains("лицо") -> Icons.Filled.Person
                                d.contains("свет") || d.contains("Пересвет") -> Icons.Filled.WbSunny
                                d.contains("контраст") -> Icons.Filled.HdrOn
                                d.contains("оттен") || d.contains("цвет") -> Icons.Filled.Palette
                                else -> Icons.Filled.CameraAlt
                            }, null, tint = Accent, modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(10.dp)); Text(d, color = Maysk.colors.text, fontSize = 14.sp)
                    }
                }
                Text("Рекомендуемые настройки:", color = Maysk.colors.text, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 16.dp, bottom = 6.dp))
                p.suggestedSettings.forEach { (k, v) ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
                        Text(k, color = Maysk.colors.muted, fontSize = 14.sp, modifier = Modifier.weight(1f))
                        Text(v, color = Maysk.colors.text, fontSize = 14.sp)
                    }
                }
                Text("AI-обработка:", color = Maysk.colors.text, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 16.dp, bottom = 6.dp))
                Text(p.recommendations.joinToString(" + ").ifBlank { "Кадр уже хорош" }, color = Maysk.colors.muted, fontSize = 13.sp)
                Spacer(Modifier.height(16.dp))
            }
        }
        Column(Modifier.padding(16.dp).navigationBarsPadding(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            PrimaryButton(if (busy) "Обработка…" else "Применить", enabled = plan != null && !busy) {
                val p = plan ?: return@PrimaryButton
                busy = true
                scope.launch {
                    val full = MediaStoreRepo.loadBitmap(ctx, uri, 4000) ?: return@launch
                    val out = withContext(Dispatchers.Default) {
                        // координаты лиц в плане нормализованы — подходят для полного размера
                        AiEnhancer.apply(full, p, withFilter = true)
                    }
                    val u = MediaStoreRepo.saveJpeg(ctx, out, 95, name = MediaStoreRepo.newName("AI"))
                    busy = false
                    u?.let(onSaved)
                }
            }
            OutlineButton("Открыть в редакторе", Modifier.fillMaxWidth(), onEdit)
        }
    }
}
