package com.maysk.camera.ai

import android.graphics.Bitmap
import com.maysk.camera.camera.Effect
import com.maysk.camera.camera.Filters
import kotlin.math.max

/** План AI-улучшения: что найдено и что рекомендуется. Все числа — понятные ползунки редактора. */
data class EnhancePlan(
    val scene: SceneType,
    val detected: List<String>,
    val recommendations: List<String>,
    val levels: Float,
    val shadows: Float,
    val highlights: Float,
    val saturation: Float,
    val warmth: Float,
    val sharpen: Float,
    val denoise: Float,
    val skin: Float,
    val faceLight: Float,
    val eyes: Float,
    val filterId: String?,
    val faces: List<FaceInfo>,
    val suggestedSettings: List<Pair<String, String>>,
)

object AiEnhancer {

    suspend fun plan(bitmap: Bitmap): EnhancePlan {
        val v = Vision.analyze(bitmap)
        val st = ImageOps.stats(bitmap)
        val detected = mutableListOf<String>()
        val rec = mutableListOf<String>()

        if (v.faces.isNotEmpty()) detected += if (v.faces.size == 1) "Человек" else "Люди: ${v.faces.size}"
        detected += v.scene.title.takeIf { v.scene != SceneType.PORTRAIT && v.scene != SceneType.GENERAL } ?: ""
        when {
            st.mean < 0.25f -> detected += "Слабый свет"
            st.mean > 0.7f -> detected += "Пересвет"
            else -> detected += "Хорошее освещение"
        }
        if (st.highlights > 0.06f && st.shadows > 0.08f) detected += "Высокий контраст сцены"
        if (st.saturation < 0.15f) detected += "Блёклые цвета"
        val faceDark = v.faces.isNotEmpty() && st.mean < 0.4f
        if (faceDark) detected += "Тёмное лицо"
        val warmCast = st.rAvg - st.bAvg
        if (warmCast > 0.12f) detected += "Жёлтый оттенок" else if (warmCast < -0.1f) detected += "Холодный оттенок"

        val levels = if (st.p2 > 0.06f || st.p98 < 0.9f) 0.8f else 0.4f
        val shadows = ((0.4f - st.mean) * 2f + st.shadows * 2f).coerceIn(0f, 0.8f)
        val highlights = (st.highlights * 6f).coerceIn(0f, 0.7f)
        val saturation = ((0.3f - st.saturation) * 1.5f).coerceIn(-0.1f, 0.35f) + if (v.scene == SceneType.FOOD) 0.15f else 0f
        val warmth = (-warmCast * 1.5f).coerceIn(-0.4f, 0.4f) + if (v.scene == SceneType.PORTRAIT) 0.05f else 0f
        val denoise = if (st.mean < 0.3f) 0.6f else 0.15f
        val sharpen = when (v.scene) {
            SceneType.LANDSCAPE, SceneType.CITY, SceneType.DOCUMENT, SceneType.CAR -> 0.8f
            SceneType.PORTRAIT, SceneType.BABY -> 0.25f
            else -> 0.5f
        }
        val skin = if (v.faces.isNotEmpty()) 0.35f else 0f
        val faceLight = if (faceDark) 0.6f else if (v.faces.isNotEmpty()) 0.2f else 0f
        val eyes = if (v.faces.isNotEmpty()) 0.35f else 0f
        val filterId = when (v.scene) {
            SceneType.FOOD -> "food"
            SceneType.LANDSCAPE, SceneType.SKY -> "nature"
            SceneType.SUNSET -> "golden"
            SceneType.PORTRAIT, SceneType.GROUP -> "natural_skin"
            SceneType.BABY -> "soft_portrait"
            SceneType.NIGHT -> "night"
            SceneType.CAR, SceneType.CITY -> "hollywood"
            SceneType.PET, SceneType.FLOWER -> "vivid"
            else -> null
        }

        if (shadows > 0.2f || highlights > 0.2f) rec += "HDR"
        if (faceLight > 0.3f) rec += "Осветление лица"
        if (skin > 0f) rec += "Естественная ретушь кожи"
        if (denoise > 0.3f) rec += "Шумоподавление"
        if (warmth > 0.08f) rec += "Тёплый цвет" else if (warmth < -0.08f) rec += "Холодный цвет"
        if (saturation > 0.1f) rec += "Насыщенность +"
        if (sharpen > 0.6f) rec += "Детализация"
        filterId?.let { rec += "Фильтр «${Filters.byId(it).name}»" }

        val iso = when { st.mean < 0.15f -> "1600"; st.mean < 0.3f -> "800"; st.mean < 0.5f -> "200"; else -> "100" }
        val shutter = when { st.mean < 0.15f -> "1/15"; st.mean < 0.3f -> "1/60"; v.scene == SceneType.PET -> "1/500"; else -> "1/250" }
        val suggested = listOf(
            "ISO" to iso,
            "Выдержка" to shutter,
            "Диафрагма" to if (v.scene == SceneType.PORTRAIT) "f/1.8" else "f/2.2",
            "Баланс белого" to when { warmCast > 0.12f -> "Тень (холоднее)"; warmCast < -0.1f -> "Облачно (теплее)"; else -> "Дневной свет" },
            "HDR" to if (shadows > 0.2f || highlights > 0.2f) "Вкл." else "Авто",
        )

        return EnhancePlan(
            v.scene, detected.filter { it.isNotBlank() }, rec, levels, shadows, highlights, saturation, warmth,
            sharpen, denoise, skin, faceLight, eyes, filterId, v.faces, suggested
        )
    }

    /** Применяет план к изображению. */
    fun apply(src: Bitmap, p: EnhancePlan, withFilter: Boolean = true): Bitmap {
        var out = src
        if (p.denoise > 0.05f) out = ImageOps.denoise(out, p.denoise)
        out = ImageOps.colorMatrix(out, ImageOps.autoLevelsMatrix(ImageOps.stats(out), p.levels))
        out = ImageOps.localToneMap(out, p.shadows, p.highlights, 0.3f)
        out = ImageOps.colorMatrix(out, Filters.adjustments(0f, 0.05f, p.saturation, p.warmth))
        val faces = p.faces.map { it.box }
        if (p.skin > 0f || p.faceLight > 0f) out = ImageOps.skinSmooth(out, p.skin, faces, p.faceLight)
        val eyes = p.faces.flatMap { it.eyes }
        if (eyes.isNotEmpty()) out = ImageOps.enhanceEyes(out, eyes, max(0.01f, (p.faces.maxOf { it.box.width() }) * 0.09f), p.eyes)
        out = ImageOps.sharpen(out, p.sharpen)
        if (withFilter && p.filterId != null) out = ImageOps.colorMatrix(out, Filters.blended(Filters.byId(p.filterId), 0.6f))
        return out
    }
}

/** Параметры, с которыми обрабатывается снятое фото. */
data class PhotoSpec(
    val filterId: String = "original",
    val filterIntensity: Float = 1f,
    val effect: Effect = Effect.NONE,
    val beautySkin: Float = 0f,
    val beautyEyes: Float = 0f,
    val beautyFace: Float = 0f,
    val aiAuto: Boolean = false,
    val portraitBlur: Boolean = false,
    val undistort: Float = 0f,
    val sharpen: Float = 0f,
)

object PhotoPipeline {
    suspend fun process(input: Bitmap, spec: PhotoSpec): Bitmap {
        var out = input
        val needFaces = spec.beautySkin > 0f || spec.beautyEyes > 0f || spec.beautyFace > 0f
        val faces = if (needFaces || spec.aiAuto) Vision.faces(ImageOps.limitSize(out, 1600)) else emptyList()

        if (spec.undistort > 0f) out = ImageOps.undistort(out, spec.undistort)
        if (spec.aiAuto) {
            val plan = AiEnhancer.plan(out)
            out = AiEnhancer.apply(out, plan, withFilter = spec.filterId == "original")
        }
        if (spec.portraitBlur) {
            Vision.personMask(ImageOps.limitSize(out, 512))?.let { (m, w, h) -> out = ImageOps.blurBackground(out, m, w, h, 0.8f) }
        }
        if (faces.isNotEmpty()) {
            if (spec.beautyFace > 0f) faces.forEach { out = ImageOps.slimFace(out, it.box, spec.beautyFace) }
            if (spec.beautySkin > 0f) out = ImageOps.skinSmooth(out, spec.beautySkin, faces.map { it.box }, spec.beautyFace * 0.3f)
            val eyes = faces.flatMap { it.eyes }
            if (spec.beautyEyes > 0f && eyes.isNotEmpty())
                out = ImageOps.enhanceEyes(out, eyes, faces.maxOf { it.box.width() } * 0.09f, spec.beautyEyes)
        }
        if (spec.sharpen > 0f) out = ImageOps.sharpen(out, spec.sharpen)
        val f = Filters.byId(spec.filterId)
        if (f != Filters.ORIGINAL && spec.filterIntensity > 0f) {
            out = ImageOps.colorMatrix(out, Filters.blended(f, spec.filterIntensity))
            out = ImageOps.vignetteAndFade(out, f.vignette * spec.filterIntensity, f.fade * spec.filterIntensity)
        }
        if (spec.effect == Effect.BG_BLUR && !spec.portraitBlur) {
            Vision.personMask(ImageOps.limitSize(out, 512))?.let { (m, w, h) -> out = ImageOps.blurBackground(out, m, w, h, 0.7f) }
        }
        out = ImageOps.applyEffect(out, spec.effect)
        return out
    }
}
