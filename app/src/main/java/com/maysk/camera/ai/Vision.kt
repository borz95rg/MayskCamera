package com.maysk.camera.ai

import android.graphics.Bitmap
import android.graphics.PointF
import android.graphics.RectF
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.google.mlkit.vision.face.FaceLandmark
import com.google.mlkit.vision.facemesh.FaceMeshDetection
import com.google.mlkit.vision.facemesh.FaceMeshDetector
import com.google.mlkit.vision.facemesh.FaceMeshDetectorOptions
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

/**
 * Лицо в нормализованных координатах (0..1) вертикального кадра.
 * [roll] — наклон головы (градусы), [nose]/[mouth] — точки для AR-масок.
 */
data class FaceMeshInfo(
    /** 468 points in normalized image coordinates; each point is [x, y, z]. */
    val vertices: List<FloatArray>,
    /** Triangle vertex indices supplied by ML Kit. */
    val triangles: List<IntArray>,
) {
    companion object { const val POINT_COUNT = 468 }
}

data class FaceInfo(
    val box: RectF,
    val eyes: List<PointF>,
    val smiling: Float,
    val nose: PointF? = null,
    val mouth: PointF? = null,
    val mouthLeft: PointF? = null,
    val mouthRight: PointF? = null,
    val leftEar: PointF? = null,
    val rightEar: PointF? = null,
    val roll: Float = 0f,
    val yaw: Float = 0f,
    /** Optional 468-point mesh; null when mesh tracking is disabled or unavailable. */
    val mesh3d: FaceMeshInfo? = null,
)

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
                .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
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
    private val faceMeshDetector: FaceMeshDetector by lazy {
        FaceMeshDetection.getClient()
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
            fun lm(type: Int): PointF? = f.getLandmark(type)?.let { PointF(it.position.x / uprightW, it.position.y / uprightH) }
            val eyes = listOfNotNull(lm(FaceLandmark.LEFT_EYE), lm(FaceLandmark.RIGHT_EYE))
            FaceInfo(
                RectF(b.left.toFloat() / uprightW, b.top.toFloat() / uprightH, b.right.toFloat() / uprightW, b.bottom.toFloat() / uprightH),
                eyes, f.smilingProbability ?: 0f,
                nose = lm(FaceLandmark.NOSE_BASE),
                mouth = lm(FaceLandmark.MOUTH_BOTTOM),
                mouthLeft = lm(FaceLandmark.MOUTH_LEFT),
                mouthRight = lm(FaceLandmark.MOUTH_RIGHT),
                leftEar = lm(FaceLandmark.LEFT_EAR),
                rightEar = lm(FaceLandmark.RIGHT_EAR),
                roll = f.headEulerAngleZ,
                yaw = f.headEulerAngleY,
            )
        }
    }


    /**
     * Detect 468 3D face landmarks and triangle topology.
     * Coordinates are normalized to the upright input image so they can be
     * transformed into the camera compositor coordinate space.
     */
    suspend fun faceMeshes(bitmap: Bitmap, rotation: Int = 0): List<FaceMeshInfo> {
        val uprightW = if (rotation % 180 == 0) bitmap.width else bitmap.height
        val uprightH = if (rotation % 180 == 0) bitmap.height else bitmap.width
        return try {
            // Declare the ML Kit result type explicitly: this avoids Kotlin inference
            // ambiguity around the Java Task<List<FaceMesh>> API.
            val detectedMeshes: List<com.google.mlkit.vision.facemesh.FaceMesh> =
                faceMeshDetector.process(InputImage.fromBitmap(bitmap, rotation)).await()

            val output = ArrayList<FaceMeshInfo>(detectedMeshes.size)
            for (mesh in detectedMeshes) {
                // API ML Kit face-mesh 16.0.0-beta: FaceMesh.getAllPoints(): List<FaceMeshPoint>,
                // FaceMeshPoint.getIndex(): Int (0..467), getPosition(): PointF3D,
                // FaceMesh.getAllTriangles(): List<Triangle<FaceMeshPoint>>, Triangle.getAllPoints(): List<T>.
                val points: List<com.google.mlkit.vision.facemesh.FaceMeshPoint> = mesh.allPoints
                if (points.isEmpty()) continue
                // вершины раскладываем по стабильному индексу точки, а не по порядку в списке
                val vertices = Array(FaceMeshInfo.POINT_COUNT) { FloatArray(3) }
                for (point in points) {
                    val idx: Int = point.index
                    if (idx < 0 || idx >= FaceMeshInfo.POINT_COUNT) continue
                    val position: com.google.mlkit.vision.common.PointF3D = point.position
                    vertices[idx][0] = position.x / uprightW
                    vertices[idx][1] = position.y / uprightH
                    vertices[idx][2] = position.z / uprightW
                }
                val triangles = ArrayList<IntArray>(mesh.allTriangles.size)
                for (triangle in mesh.allTriangles) {
                    val corners: List<com.google.mlkit.vision.facemesh.FaceMeshPoint> = triangle.allPoints
                    if (corners.size != 3) continue
                    triangles.add(intArrayOf(corners[0].index, corners[1].index, corners[2].index))
                }
                output.add(FaceMeshInfo(vertices.toList(), triangles))
            }
            output
        } catch (_: Exception) {
            emptyList()
        }
    }

    suspend fun labels(bitmap: Bitmap, rotation: Int = 0): List<Pair<String, Float>> =
        runCatching { labeler.process(InputImage.fromBitmap(bitmap, rotation)).await() }
            .getOrDefault(emptyList()).map { it.text to it.confidence }

    private val streamSegmenter by lazy {
        Segmentation.getClient(SelfieSegmenterOptions.Builder().setDetectorMode(SelfieSegmenterOptions.STREAM_MODE).build())
    }

    /**
     * Маска человека для видеопотока: байты 0..255, вертикальный кадр (как точки лица),
     * уменьшена до ~240 px по ширине. Если ML Kit вернул маску в ориентации буфера — поворачиваем.
     */
    suspend fun personMaskStream(bitmap: Bitmap, rotation: Int): Triple<ByteArray, Int, Int>? = runCatching {
        val m = streamSegmenter.process(InputImage.fromBitmap(bitmap, rotation)).await()
        val mw = m.width; val mh = m.height
        val buf = m.buffer.order(ByteOrder.nativeOrder()); buf.rewind()
        val fb = buf.asFloatBuffer()
        val uprightW = if (rotation % 180 == 0) bitmap.width else bitmap.height
        val uprightH = if (rotation % 180 == 0) bitmap.height else bitmap.width
        // маска уже вертикальная, если её стороны совпадают с вертикальным кадром
        val needRotate = rotation % 180 != 0 && !(mw * uprightH == mh * uprightW)
        val ow = if (needRotate) mh else mw; val oh = if (needRotate) mw else mh
        val step = maxOf(1, ow / 240)
        val tw = ow / step; val th = oh / step
        val out = ByteArray(tw * th)
        for (y in 0 until th) for (x in 0 until tw) {
            val ux = x * step; val uy = y * step
            val (sx, sy) = if (!needRotate) ux to uy else when (rotation) {
                90 -> uy to (mh - 1 - ux)
                270 -> (mw - 1 - uy) to ux
                else -> ux to uy
            }
            val v = fb.get(sy.coerceIn(0, mh - 1) * mw + sx.coerceIn(0, mw - 1))
            out[y * tw + x] = (v.coerceIn(0f, 1f) * 255f).toInt().toByte()
        }
        Triple(out, tw, th)
    }.getOrNull()

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
        val names = labels.filter { it.second >= 0.65f }.map { it.first.lowercase() }
        val strong = labels.filter { it.second >= 0.85f }.map { it.first.lowercase() }
        fun has(vararg k: String) = names.any { n -> k.any { n.contains(it) } }
        fun hasStrong(vararg k: String) = strong.any { n -> k.any { n.contains(it) } }
        return when {
            meanLuma < 0.16f -> SceneType.NIGHT
            has("baby", "infant", "toddler") -> SceneType.BABY
            faces >= 3 -> SceneType.GROUP
            faces >= 1 -> SceneType.PORTRAIT
            has("food", "dish", "cuisine", "dessert", "fruit", "cake", "bread", "pizza", "meal", "vegetable") -> SceneType.FOOD
            has("dog", "cat", "pet", "bird", "horse", "animal") -> SceneType.PET
            has("car", "vehicle", "wheel", "motorcycle") -> SceneType.CAR
            hasStrong("receipt", "document", "menu") || (hasStrong("paper") && hasStrong("text")) -> SceneType.DOCUMENT
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
