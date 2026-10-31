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
    val clarity: Float = 0.2f,
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
        val shadows = ((0.4f - st.mean) * 1.6f + st.shadows * 1.5f).coerceIn(0f, 0.5f)
        val highlights = (st.highlights * 6f).coerceIn(0f, 0.7f)
        val saturation = ((0.3f - st.saturation) * 1.5f).coerceIn(-0.1f, 0.35f) + if (v.scene == SceneType.FOOD) 0.15f else 0f
        val warmth = (-warmCast * 1.5f).coerceIn(-0.4f, 0.4f) + if (v.scene == SceneType.PORTRAIT) 0.05f else 0f
        // HAL уже давит шум — дополнительно только чуть-чуть, иначе фото «замыливается»
        val denoise = if (st.mean < 0.25f) 0.3f else 0.06f
        // Резкость умеренная, детали добавляет «чёткость» (локальный контраст)
        val sharpen = when (v.scene) {
            SceneType.LANDSCAPE, SceneType.CITY, SceneType.DOCUMENT, SceneType.CAR -> 0.3f
            SceneType.PORTRAIT, SceneType.BABY, SceneType.GROUP -> 0.08f
            else -> 0.18f
        }
        val clarity = if (st.mean < 0.22f) 0.05f else when (v.scene) {
            SceneType.LANDSCAPE, SceneType.CITY, SceneType.CAR, SceneType.SKY -> 0.35f
            SceneType.PORTRAIT, SceneType.BABY, SceneType.GROUP -> 0.12f
            else -> 0.25f
        }
        val skin = if (v.faces.isNotEmpty()) 0.25f else 0f
        val faceLight = if (faceDark) 0.45f else if (v.faces.isNotEmpty()) 0.1f else 0f
        val eyes = if (v.faces.isNotEmpty()) 0.25f else 0f
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
        if (clarity > 0.2f) rec += "Чёткость"
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
            sharpen, denoise, skin, faceLight, eyes, filterId, v.faces, suggested, clarity
        )
    }

    /** Применяет план к изображению. */
    fun apply(src: Bitmap, p: EnhancePlan, withFilter: Boolean = true): Bitmap {
        var out = src
        if (p.denoise > 0.05f) out = ImageOps.denoise(out, p.denoise)
        out = ImageOps.colorMatrix(out, ImageOps.autoLevelsMatrix(ImageOps.stats(out), minOf(p.levels, 0.6f)))
        out = ImageOps.localToneMap(out, p.shadows, p.highlights, 0.12f)
        // чуть ниже контраст — мягкая, естественная картинка
        out = ImageOps.colorMatrix(out, Filters.adjustments(0f, -0.08f, p.saturation, p.warmth))
        out = ImageOps.clarity(out, p.clarity)
        val faces = p.faces.map { it.box }
        if (p.skin > 0f || p.faceLight > 0f) out = ImageOps.skinSmooth(out, p.skin, faces, p.faceLight)
        val eyes = p.faces.flatMap { it.eyes }
        if (eyes.isNotEmpty()) out = ImageOps.enhanceEyes(out, eyes, max(0.01f, (p.faces.maxOf { it.box.width() }) * 0.09f), p.eyes)
        out = ImageOps.smartSharpen(out, p.sharpen * 1.3f)
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
    /** Чёткость средних тонов (детали без пересвета). */
    val clarity: Float = 0.15f,
    /** Общая коррекция контраста: отрицательное — мягче. */
    val contrast: Float = -0.06f,
    /** AI Super Detail (умная резкость без ореолов). */
    val detail: Float = 0.35f,
    /** Рамка для фото. */
    val frame: com.maysk.camera.camera.FrameSpec? = null,
    /** AR-маска, которая дорисовывается на лица. */
    val arMask: com.maysk.camera.camera.ArMask = com.maysk.camera.camera.ArMask.NONE,
)

object PhotoPipeline {
    suspend fun process(input: Bitmap, spec: PhotoSpec): Bitmap {
        var out = input
        val needFaces = spec.beautySkin > 0f || spec.beautyEyes > 0f || spec.beautyFace > 0f
        val wantsBlur = spec.portraitBlur || spec.effect == Effect.BG_BLUR
        val faces = if (needFaces || spec.aiAuto || wantsBlur) Vision.faces(ImageOps.limitSize(out, 1600)) else emptyList()

        if (spec.undistort > 0f) out = ImageOps.undistort(out, spec.undistort)
        if (spec.aiAuto) {
            var plan = AiEnhancer.plan(out)
            // если включена «Красота», ретушь делает только она — без двойной обработки кожи
            if (needFaces) plan = plan.copy(skin = 0f, eyes = 0f, faceLight = minOf(plan.faceLight, 0.2f))
            out = AiEnhancer.apply(out, plan, withFilter = spec.filterId == "original")
        }
        // Портретное размытие — только если в кадре есть лицо (документы и предметы не размываются)
        if (spec.portraitBlur && faces.isNotEmpty()) {
            Vision.personMask(ImageOps.limitSize(out, 512))?.let { (m, w, h) -> out = ImageOps.blurBackground(out, m, w, h, 0.55f) }
        }
        if (faces.isNotEmpty()) {
            // ползунки 0..100% переводятся в естественный диапазон, чтобы даже 100% не давало «маску»
            if (spec.beautyFace > 0f) faces.forEach { out = ImageOps.slimFace(out, it.box, spec.beautyFace * 0.45f) }
            if (spec.beautySkin > 0f) out = ImageOps.skinSmooth(out, spec.beautySkin, faces.map { it.box }, spec.beautyFace * 0.4f)
            val eyes = faces.flatMap { it.eyes }
            if (spec.beautyEyes > 0f && eyes.isNotEmpty())
                out = ImageOps.enhanceEyes(out, eyes, faces.maxOf { it.box.width() } * 0.07f, spec.beautyEyes * 0.6f)
        }
        if (!spec.aiAuto) {
            if (spec.contrast != 0f) out = ImageOps.colorMatrix(out, Filters.adjustments(0f, spec.contrast, 0f, 0f))
            out = ImageOps.clarity(out, spec.clarity)
        }
        // AI Super Detail — по умолчанию для всех снимков
        // (в AI-режиме резкость уже добавлена планом — не удваиваем, иначе белые контуры)
        if (spec.detail > 0f && !spec.aiAuto) out = ImageOps.smartSharpen(out, spec.detail)
        if (spec.sharpen > 0f) out = ImageOps.smartSharpen(out, spec.sharpen)
        val f = Filters.byId(spec.filterId)
        if (f != Filters.ORIGINAL && spec.filterIntensity > 0f) {
            out = ImageOps.colorMatrix(out, Filters.blended(f, spec.filterIntensity))
            out = ImageOps.vignetteAndFade(out, f.vignette * spec.filterIntensity, f.fade * spec.filterIntensity)
        }
        if (spec.effect == Effect.BG_BLUR && !spec.portraitBlur && faces.isNotEmpty()) {
            Vision.personMask(ImageOps.limitSize(out, 512))?.let { (m, w, h) -> out = ImageOps.blurBackground(out, m, w, h, 0.55f) }
        }
        out = ImageOps.applyEffect(out, spec.effect)
        if (spec.arMask != com.maysk.camera.camera.ArMask.NONE) {
            val maskFaces = Vision.faces(ImageOps.limitSize(out, 1600))
            if (maskFaces.isNotEmpty()) {
                out = ImageOps.ensureMutable(out)
                val canvas = android.graphics.Canvas(out)
                maskFaces.forEach { f -> com.maysk.camera.camera.ArMasks.draw(canvas, spec.arMask, f, out.width.toFloat(), out.height.toFloat(), time = 0.5f) }
            }
        }
        spec.frame?.let { out = com.maysk.camera.camera.Frames.apply(out, it) }
        return out
    }
}
