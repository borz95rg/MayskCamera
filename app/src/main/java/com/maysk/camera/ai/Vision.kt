package com.maysk.camera.ai

import android.graphics.Bitmap
import android.graphics.PointF
import android.graphics.RectF
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.google.mlkit.vision.face.FaceLandmark
import com.google.mlkit.vision.label.ImageLabeling
import com.google.mlkit.vision.label.defaults.ImageLabelerOptions
import com.google.mlkit.vision.segmentation.Segmentation
import com.google.mlkit.vision.segmentation.selfie.SelfieSegmenterOptions
import kotlinx.coroutines.tasks.await
import java.nio.ByteOrder

/** Тип сцены, определённый AI. */
enum class SceneType(val title: String) {
    GENERAL("Сцена"), PORTRAIT("Портрет"), GROUP("Группа людей"), FOOD("Еда"), PET("Питомец"), LANDSCAPE("Пейзаж"),
    CITY("Город"), NIGHT("Ночь"), SUNSET("Закат"), FLOWER("Цветы / макро"), DOCUMENT("Документ"), CAR("Автомобиль"),
    BABY("Ребёнок"), SKY("Небо")
}

data class FaceInfo(val box: RectF, val eyes: List<PointF>, val smiling: Float)

data class VisionResult(
    val faces: List<FaceInfo>,
    val labels: List<Pair<String, Float>>,
    val scene: SceneType,
)

/** Обёртка над ML Kit (лица, метки сцены, сегментация человека). Всё работает офлайн на устройстве. */
object Vision {
    private val fastFaces by lazy {
        FaceDetection.getClient(
            FaceDetectorOptions.Builder()
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
                .setMinFaceSize(0.12f)
                .build()
        )
    }
    private val accurateFaces by lazy {
        FaceDetection.getClient(
            FaceDetectorOptions.Builder()
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
                .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
                .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL)
                .setMinFaceSize(0.08f)
                .build()
        )
    }
    private val labeler by lazy { ImageLabeling.getClient(ImageLabelerOptions.Builder().setConfidenceThreshold(0.55f).build()) }
    private val segmenter by lazy {
        Segmentation.getClient(SelfieSegmenterOptions.Builder().setDetectorMode(SelfieSegmenterOptions.SINGLE_IMAGE_MODE).build())
    }

    /** Лица в нормализованных координатах (0..1) уже повёрнутого изображения. */
    suspend fun faces(bitmap: Bitmap, rotation: Int = 0, accurate: Boolean = true): List<FaceInfo> {
        val uprightW = if (rotation % 180 == 0) bitmap.width else bitmap.height
        val uprightH = if (rotation % 180 == 0) bitmap.height else bitmap.width
        val client = if (accurate) accurateFaces else fastFaces
        val list = runCatching { client.process(InputImage.fromBitmap(bitmap, rotation)).await() }.getOrDefault(emptyList())
        return list.map { f ->
            val b = f.boundingBox
            val eyes = listOfNotNull(f.getLandmark(FaceLandmark.LEFT_EYE), f.getLandmark(FaceLandmark.RIGHT_EYE))
                .map { PointF(it.position.x / uprightW, it.position.y / uprightH) }
            FaceInfo(
                RectF(b.left.toFloat() / uprightW, b.top.toFloat() / uprightH, b.right.toFloat() / uprightW, b.bottom.toFloat() / uprightH),
                eyes, f.smilingProbability ?: 0f
            )
        }
    }

    suspend fun labels(bitmap: Bitmap, rotation: Int = 0): List<Pair<String, Float>> =
        runCatching { labeler.process(InputImage.fromBitmap(bitmap, rotation)).await() }
            .getOrDefault(emptyList()).map { it.text to it.confidence }

    /** Маска человека (вероятность 0..1). */
    suspend fun personMask(bitmap: Bitmap): Triple<FloatArray, Int, Int>? = runCatching {
        val m = segmenter.process(InputImage.fromBitmap(bitmap, 0)).await()
        val buf = m.buffer.order(ByteOrder.nativeOrder())
        buf.rewind()
        val arr = FloatArray(m.width * m.height)
        buf.asFloatBuffer().get(arr)
        Triple(arr, m.width, m.height)
    }.getOrNull()

    fun classify(labels: List<Pair<String, Float>>, faces: Int, meanLuma: Float): SceneType {
        val names = labels.map { it.first.lowercase() }
        fun has(vararg k: String) = names.any { n -> k.any { n.contains(it) } }
        return when {
            meanLuma < 0.16f -> SceneType.NIGHT
            has("baby", "infant", "toddler") -> SceneType.BABY
            faces >= 3 -> SceneType.GROUP
            faces >= 1 -> SceneType.PORTRAIT
            has("food", "dish", "cuisine", "dessert", "fruit", "cake", "bread", "pizza", "meal", "vegetable") -> SceneType.FOOD
            has("dog", "cat", "pet", "bird", "horse", "animal") -> SceneType.PET
            has("car", "vehicle", "wheel", "motorcycle") -> SceneType.CAR
            has("paper", "receipt", "document", "text", "poster", "menu") -> SceneType.DOCUMENT
            has("flower", "petal", "plant", "insect", "butterfly") -> SceneType.FLOWER
            has("sunset", "dusk", "sunrise") -> SceneType.SUNSET
            has("building", "skyscraper", "city", "tower", "bridge") -> SceneType.CITY
            has("mountain", "lake", "beach", "sea", "river", "forest", "tree", "waterfall", "field") -> SceneType.LANDSCAPE
            has("sky", "cloud") -> SceneType.SKY
            else -> SceneType.GENERAL
        }
    }

    suspend fun analyze(bitmap: Bitmap): VisionResult {
        val small = ImageOps.limitSize(bitmap, 1280)
        val f = faces(small)
        val l = labels(small)
        val st = ImageOps.stats(small)
        return VisionResult(f, l, classify(l, f.size, st.mean))
    }
}
