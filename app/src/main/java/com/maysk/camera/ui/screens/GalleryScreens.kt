package com.maysk.camera.ui.screens

import android.app.RecoverableSecurityException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.widget.MediaController
import android.widget.VideoView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.item
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.PhotoFilter
import androidx.compose.material.icons.filled.PlayCircleFilled
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import coil.compose.AsyncImage
import com.maysk.camera.ai.SceneType
import com.maysk.camera.ai.Vision
import com.maysk.camera.data.AudioMode
import com.maysk.camera.media.MediaItem
import com.maysk.camera.media.MediaStoreRepo
import com.maysk.camera.media.VideoTools
import com.maysk.camera.ui.components.ChoiceRow
import com.maysk.camera.ui.components.Pill
import com.maysk.camera.ui.components.ScreenHeader
import com.maysk.camera.ui.components.Segmented
import com.maysk.camera.ui.theme.Accent
import com.maysk.camera.ui.theme.Maysk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private val categories = listOf(
    "Все" to emptySet(),
    "Люди" to setOf(SceneType.PORTRAIT, SceneType.GROUP, SceneType.BABY),
    "Путешествия" to setOf(SceneType.LANDSCAPE, SceneType.CITY, SceneType.SUNSET, SceneType.SKY),
    "Еда" to setOf(SceneType.FOOD),
    "Питомцы" to setOf(SceneType.PET),
    "Документы" to setOf(SceneType.DOCUMENT),
)

/** Кэш AI-меток галереи. */
private object GalleryTags {
    fun get(ctx: Context, id: Long): SceneType? =
        ctx.getSharedPreferences("gallery_tags", Context.MODE_PRIVATE).getString(id.toString(), null)
            ?.let { n -> SceneType.entries.firstOrNull { it.name == n } }

    fun put(ctx: Context, id: Long, t: SceneType) =
        ctx.getSharedPreferences("gallery_tags", Context.MODE_PRIVATE).edit().putString(id.toString(), t.name).apply()
}

/** Избранное (хранится локально). */
object Favorites {
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("favorites", Context.MODE_PRIVATE)
    fun all(ctx: Context): Set<String> = prefs(ctx).getStringSet("uris", emptySet()) ?: emptySet()
    fun isFav(ctx: Context, uri: Uri) = all(ctx).contains(uri.toString())
    fun toggle(ctx: Context, uri: Uri): Boolean {
        val set = all(ctx).toMutableSet()
        val now = if (set.remove(uri.toString())) false else { set.add(uri.toString()); true }
        prefs(ctx).edit().putStringSet("uris", set).apply()
        return now
    }
}

@Composable
fun GalleryScreen(onBack: () -> Unit, onOpen: (MediaItem) -> Unit, hasReadPermission: Boolean, requestPermission: () -> Unit) {
    val ctx = LocalContext.current
    var tab by remember { mutableIntStateOf(0) }
    var cat by remember { mutableIntStateOf(0) }
    var items by remember { mutableStateOf<List<MediaItem>>(emptyList()) }
    var all by remember { mutableStateOf<List<MediaItem>>(emptyList()) }
    var album by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }
    val tags = remember { mutableStateMapOf<Long, SceneType>() }
    var favOnly by remember { mutableStateOf(false) }
    val favs = remember(items) { Favorites.all(ctx) }

    LaunchedEffect(Unit) {
        items = MediaStoreRepo.queryMaysk(ctx)
        loading = false
        items.forEach { m -> GalleryTags.get(ctx, m.id)?.let { tags[m.id] = it } }
    }
    LaunchedEffect(tab, hasReadPermission) {
        if (tab == 2 && hasReadPermission && all.isEmpty()) all = MediaStoreRepo.queryAll(ctx)
    }
    // Фоновая AI-разметка фото для категорий
    LaunchedEffect(cat, items) {
        if (cat == 0) return@LaunchedEffect
        withContext(Dispatchers.Default) {
            items.filter { !it.isVideo && tags[it.id] == null }.take(60).forEach { m ->
                val thumb = MediaStoreRepo.thumbnail(ctx, m.uri, 384) ?: return@forEach
                val r = Vision.analyze(thumb)
                GalleryTags.put(ctx, m.id, r.scene)
                withContext(Dispatchers.Main) { tags[m.id] = r.scene }
            }
        }
    }

    Column(Modifier.fillMaxSize().background(Maysk.colors.bg)) {
        ScreenHeader(if (album != null) album!! else "Галерея", onBack = { if (album != null) album = null else onBack() })
        Segmented(listOf("Фото", "Видео", "Альбомы"), tab, Modifier.padding(horizontal = 16.dp)) { tab = it; album = null }
        if (tab == 0) {
            LazyRow(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                item { Pill("♥ Избранное", favOnly) { favOnly = !favOnly } }
                items(categories.indices.toList()) { i -> Pill(categories[i].first, cat == i) { cat = i } }
            }
        }
        val list: List<MediaItem> = when (tab) {
            0 -> items.filter {
                !it.isVideo && (cat == 0 || tags[it.id]?.let { t -> t in categories[cat].second } == true) &&
                    (!favOnly || favs.contains(it.uri.toString()))
            }
            1 -> items.filter { it.isVideo }
            else -> if (album != null) all.filter { it.bucket == album } else emptyList()
        }
        when {
            loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Accent) }
            tab == 2 && !hasReadPermission -> Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Чтобы показать альбомы телефона, разрешите доступ к фото", color = Maysk.colors.text)
                Text("Разрешить", color = Accent, modifier = Modifier.padding(16.dp).clickable(onClick = requestPermission))
            }
            tab == 2 && album == null -> {
                val buckets = all.groupBy { it.bucket }.toList().sortedByDescending { it.second.size }
                LazyVerticalGrid(GridCells.Fixed(2), Modifier.fillMaxSize().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(buckets) { (name, media) ->
                        Column(Modifier.clickable { album = name }) {
                            AsyncImage(media.first().uri, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(14.dp)))
                            Text(name, color = Maysk.colors.text, fontSize = 14.sp, modifier = Modifier.padding(top = 4.dp))
                            Text("${media.size}", color = Maysk.colors.muted, fontSize = 12.sp)
                        }
                    }
                }
            }
            list.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(if (cat != 0 && tab == 0) "AI сортирует фото…" else "Здесь пока пусто", color = Maysk.colors.muted)
            }
            else -> LazyVerticalGrid(GridCells.Fixed(3), Modifier.fillMaxSize().padding(horizontal = 4.dp), horizontalArrangement = Arrangement.spacedBy(3.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                items(list, key = { it.uri.toString() }) { m ->
                    Box(Modifier.aspectRatio(1f).clip(RoundedCornerShape(6.dp)).clickable { onOpen(m) }) {
                        AsyncImage(m.uri, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                        if (m.isVideo) Row(Modifier.align(Alignment.BottomEnd).padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.PlayCircleFilled, null, tint = Color.White, modifier = Modifier.size(16.dp))
                            Text(formatTime(m.durationMs / 1000), color = Color.White, fontSize = 11.sp)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ViewerScreen(
    uri: Uri, isVideo: Boolean, onBack: () -> Unit,
    onEdit: () -> Unit, onFilters: () -> Unit, onEffects: () -> Unit, onAnalyze: () -> Unit,
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf<String?>(null) }
    var audioMenu by remember { mutableStateOf(false) }
    var fav by remember { mutableStateOf(Favorites.isFav(ctx, uri)) }
    val deleteLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { r ->
        if (r.resultCode == android.app.Activity.RESULT_OK) onBack()
    }
    fun delete() {
        try {
            if (Build.VERSION.SDK_INT >= 30) {
                // в системную корзину — можно восстановить в течение 30 дней
                val pi = MediaStore.createTrashRequest(ctx.contentResolver, listOf(uri), true)
                deleteLauncher.launch(IntentSenderRequest.Builder(pi.intentSender).build())
            } else {
                ctx.contentResolver.delete(uri, null, null); onBack()
            }
        } catch (e: RecoverableSecurityException) {
            deleteLauncher.launch(IntentSenderRequest.Builder(e.userAction.actionIntent.intentSender).build())
        } catch (ignored: Throwable) {
        }
    }
    fun share() {
        val i = Intent(Intent.ACTION_SEND).apply {
            type = if (isVideo) "video/*" else "image/*"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        ctx.startActivity(Intent.createChooser(i, "Поделиться"))
    }

    Column(Modifier.fillMaxSize().background(Color.Black)) {
        ScreenHeader("", onBack = onBack)
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            if (isVideo) {
                AndroidView(factory = { c ->
                    VideoView(c).apply {
                        setVideoURI(uri)
                        setMediaController(MediaController(c).also { it.setAnchorView(this) })
                        setOnPreparedListener { it.isLooping = true; start() }
                    }
                }, modifier = Modifier.fillMaxSize())
            } else {
                AsyncImage(uri, null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
            }
            busy?.let {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.background(Color.Black.copy(alpha = 0.6f)).padding(20.dp)) {
                    CircularProgressIndicator(color = Accent); Text(it, color = Color.White, modifier = Modifier.padding(top = 8.dp))
                }
            }
        }
        if (audioMenu) Column(Modifier.background(Color(0xFF151318)).padding(16.dp)) {
            Text("AI Audio: улучшить звук ролика", color = Color.White, fontSize = 14.sp)
            val modes = AudioMode.entries.drop(1)
            ChoiceRow(modes.map { it.label }, -1) { i ->
                audioMenu = false
                busy = "AI Audio: ${modes[i].label}…"
                scope.launch {
                    val tmp = File(ctx.cacheDir, "studio_audio.mp4")
                    val ok = withContext(Dispatchers.IO) { VideoTools.enhanceAudio(ctx, uri, tmp, modes[i]) }
                    if (ok) MediaStoreRepo.saveVideoFile(ctx, tmp, MediaStoreRepo.newName("VID_AI"))
                    tmp.delete()
                    busy = null
                    android.widget.Toast.makeText(ctx, if (ok) "Сохранено новое видео" else "В видео нет звука для обработки", android.widget.Toast.LENGTH_SHORT).show()
                }
            }
        }
        Row(
            Modifier.fillMaxWidth().navigationBarsPadding().padding(vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            if (!isVideo) {
                ViewerAction(Icons.Filled.AutoFixHigh, "Редакт.", onEdit)
                ViewerAction(Icons.Filled.PhotoFilter, "Фильтры", onFilters)
                ViewerAction(Icons.Filled.AutoAwesome, "AI анализ", onAnalyze)
                ViewerAction(Icons.Filled.Edit, "Эффекты", onEffects)
            } else {
                ViewerAction(Icons.Filled.GraphicEq, "AI Audio") { audioMenu = !audioMenu }
            }
            ViewerAction(if (fav) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder, "Избранное") { fav = Favorites.toggle(ctx, uri) }
            ViewerAction(Icons.Filled.Share, "Поделиться") { share() }
            ViewerAction(Icons.Filled.Delete, if (Build.VERSION.SDK_INT >= 30) "В корзину" else "Удалить") { delete() }
        }
    }
}

@Composable
private fun ViewerAction(icon: ImageVector, label: String, onClick: () -> Unit) {
    Column(Modifier.clickable(onClick = onClick).padding(6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(icon, label, tint = Color.White)
        Text(label, color = Color.White, fontSize = 11.sp)
    }
}
