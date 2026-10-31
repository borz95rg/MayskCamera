package com.maysk.camera.ui.components

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.maysk.camera.camera.Filter
import com.maysk.camera.camera.Filters
import com.maysk.camera.ui.theme.Accent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Миниатюра фильтра, отрисованная тем же конвейером, что и фото
 * (матрица + тональная кривая + виньетка) — видно реальную разницу между фильтрами.
 */
@Composable
fun FilterThumb(base: Bitmap?, f: Filter, modifier: Modifier = Modifier) {
    val bmp by produceState<Bitmap?>(null, base, f.id) {
        value = base?.let { b -> withContext(Dispatchers.Default) { runCatching { Filters.thumbnail(b, f) }.getOrNull() } }
    }
    Box(modifier.background(AccentGradient)) {
        bmp?.let { Image(it.asImageBitmap(), null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
    }
}

/** Плитка фильтра с подписью. */
@Composable
fun FilterTile(base: Bitmap?, f: Filter, selected: Boolean, size: Dp = 58.dp, textColor: Color = Color.White, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(size + 6.dp).clickable(onClick = onClick)) {
        FilterThumb(
            base, f,
            Modifier
                .size(size)
                .clip(RoundedCornerShape(10.dp))
                .border(2.dp, if (selected) Accent else Color.Transparent, RoundedCornerShape(10.dp))
        )
        Text(f.name, color = if (selected) Accent else textColor, fontSize = 10.sp, maxLines = 1, modifier = Modifier.padding(top = 3.dp))
    }
}

/** Пример-сцена для миниатюр, если ещё нет снимков: небо, солнце, зелень, кожа, тени. */
val filterSample: Bitmap by lazy {
    val w = 160; val h = 160
    val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val c = android.graphics.Canvas(b)
    val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
    p.shader = android.graphics.LinearGradient(0f, 0f, 0f, h * 0.62f, 0xFF4A86D8.toInt(), 0xFFF2C9A0.toInt(), android.graphics.Shader.TileMode.CLAMP)
    c.drawRect(0f, 0f, w.toFloat(), h * 0.62f, p)
    p.shader = null
    p.color = 0xFFFFE9B0.toInt(); c.drawCircle(w * 0.75f, h * 0.3f, 14f, p)
    p.color = 0xFF2F6B35.toInt(); c.drawRect(0f, h * 0.6f, w.toFloat(), h.toFloat(), p)
    p.color = 0xFF1B3A20.toInt(); c.drawOval(-20f, h * 0.55f, w * 0.55f, h * 0.75f, p)
    p.color = 0xFFD9A07E.toInt(); c.drawCircle(w * 0.38f, h * 0.62f, 22f, p)
    p.color = 0xFF2A2026.toInt(); c.drawRect(w * 0.22f, h * 0.76f, w * 0.54f, h.toFloat(), p)
    p.color = 0xFFC8303A.toInt(); c.drawRect(w * 0.62f, h * 0.7f, w * 0.9f, h * 0.92f, p)
    b
}
