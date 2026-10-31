package com.maysk.camera.ar3d

import android.content.Context

/** Каталог 3D-масок из assets/ar_models/catalog.json (генерируется tools/ar_models/generate_models.py). */
/** Запись каталога 3D-масок. */
data class ArCatalogEntry(val id: String, val title: String, val category: String, val file: String, val preview: String, val triangles: Int)

/** Разбор catalog.json без Android-зависимостей (проверяется JVM-тестами). */
object ArCatalogData {
    fun parse(text: String): List<ArCatalogEntry> = MiniJson.parse(text).list().map { e ->
        val o = e.obj()
        ArCatalogEntry(o["id"] as String, o["title"] as? String ?: "", o["category"] as? String ?: "", o["file"] as String, o["preview"] as? String ?: "", o["triangles"].int())
    }
}

object ArCatalog {

    /** Порядок категорий в каталоге. «Новые» и «Избранное» — служебные. */
    val categories = listOf("Новые", "Избранное", "Кошки", "Собаки", "Животные", "Дикие животные", "Очки", "Короны и головные уборы", "Фантазия")

    @Volatile private var cache: List<ArCatalogEntry>? = null
    private val models = HashMap<String, GlbModel>()

    fun entries(ctx: Context): List<ArCatalogEntry> = cache ?: runCatching {
        val text = ctx.assets.open("ar_models/catalog.json").bufferedReader(Charsets.UTF_8).use { it.readText() }
        parseCatalog(text)
    }.getOrDefault(emptyList()).also { cache = it }

    fun parseCatalog(text: String): List<ArCatalogEntry> = ArCatalogData.parse(text)

    fun byCategory(ctx: Context, cat: String): List<ArCatalogEntry> {
        val all = entries(ctx)
        return when (cat) {
            "Новые" -> all
            "Избранное" -> { val f = favorites(ctx); all.filter { it.id in f } }
            else -> all.filter { it.category == cat }
        }
    }

    /** Загрузка модели (кэшируется). Вызывать не в UI-потоке. */
    fun load(ctx: Context, id: String): GlbModel? = synchronized(models) {
        models[id] ?: run {
            val e = entries(ctx).firstOrNull { it.id == id } ?: return null
            val bytes = ctx.assets.open(e.file).use { it.readBytes() }
            GlbLoader.parse(bytes).also { models[id] = it }
        }
    }

    private const val PREFS = "ar_favorites"
    fun favorites(ctx: Context): Set<String> = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getStringSet("ids", emptySet()) ?: emptySet()
    fun toggleFavorite(ctx: Context, id: String) {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val s = (p.getStringSet("ids", emptySet()) ?: emptySet()).toMutableSet()
        if (!s.add(id)) s.remove(id)
        p.edit().putStringSet("ids", s).apply()
    }
}
