package com.maysk.camera.ar3d

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory

/**
 * Шаблоны эффектов из пакета MayskCamera_Effect_Templates (assets/effects/templates.json,
 * собирается tools/effect_templates/build_templates.py).
 *  - faces: плоские накладки на лицо (ушки, короны, очки, макияж), каждая из «кусочков»
 *    с привязкой к точке лица — рисуются в GL-кадр вместе с 3D-масками (превью = фото = видео);
 *  - frames: картинки-рамки (рисуются в кадр как обычные рамки);
 *  - backgrounds: фоны для живой замены фона (сегментация человека ML Kit).
 */
object EffectTemplates {
    @Volatile private var pack: EffectTemplateData.Pack? = null
    fun pack(ctx: Context): EffectTemplateData.Pack = pack ?: runCatching {
        EffectTemplateData.parse(ctx.assets.open("effects/templates.json").bufferedReader(Charsets.UTF_8).use { it.readText() })
    }.getOrDefault(EffectTemplateData.Pack(emptyList(), emptyList(), emptyList())).also { pack = it }

    private val bitmaps = object : android.util.LruCache<String, Bitmap>(24 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    /** Картинка из assets (кэш). Вызывать не в UI-потоке. */
    fun bitmap(ctx: Context, path: String): Bitmap? {
        bitmaps.get(path)?.let { return it }
        val b = runCatching { ctx.assets.open(path).use { BitmapFactory.decodeStream(it) } }.getOrNull() ?: return null
        bitmaps.put(path, b)
        return b
    }

    /** Кусочки шаблона лица → спрайты для рендера. [alpha] — интенсивность эффекта. */
    fun sprites(ctx: Context, face: EffectTemplateData.Face, alpha: Float): List<Sprite> = face.pieces.mapNotNull { p ->
        val b = bitmap(ctx, p.png) ?: return@mapNotNull null
        Sprite(p.png, b, p.at, p.w, p.aspect, p.align == "bottom", alpha, p.anchor)
    }
}

/** Плоская картинка, прикреплённая к лицу (в плоскости лица, поворачивается вместе с головой). */
class Sprite(
    val key: String,
    val bitmap: Bitmap,
    val at: FloatArray,      // точка на лице, лицевые единицы (базовые, для «среднего» лица)
    val width: Float,        // ширина, лицевые единицы
    val aspect: Float,       // высота / ширина
    val bottomAligned: Boolean,
    val alpha: Float,
    /** forehead / eyes / nose / mouth / cheeks / center — для авто-подстройки под овал */
    val anchor: String = "center",
)
