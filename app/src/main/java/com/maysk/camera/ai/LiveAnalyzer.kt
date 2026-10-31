package com.maysk.camera.ai

import android.graphics.Bitmap
import android.graphics.RectF
import android.os.SystemClock
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import kotlinx.coroutines.runBlocking
import kotlin.math.abs

/** Результат живого анализа кадра (для AI PRO, гистограммы, фокуса на лице). */
data class LiveScene(
    val mean: Float = 0.5f,
    val highlights: Float = 0f,
    val shadows: Float = 0f,
    val histogram: FloatArray = FloatArray(64),
    val faces: List<FaceInfo> = emptyList(),
    val faceLuma: Float = 0.5f,
    val labels: List<Pair<String, Float>> = emptyList(),
    val scene: SceneType = SceneType.GENERAL,
    val rotation: Int = 0,
    val shake: Float = 0f,
    val timestamp: Long = 0L,
)

/**
 * Анализатор кадров CameraX. Работает в фоне на маленьком кадре 640x480:
 * яркость/гистограмма каждый кадр, лица ~3 раза в секунду, сцена раз в 1.5 с.
 */
class LiveAnalyzer(
    private val enabled: () -> Boolean,
    private val onResult: (LiveScene) -> Unit,
    /** Для AR-масок лица ищутся на каждом кадре — маска «прилипает» к лицу. */
    private val realtimeFaces: () -> Boolean = { false },
    /** Маленький живой кадр (раз в ~2 с) — для миниатюр фильтров «на живом кадре». */
    private val onThumb: ((Bitmap) -> Unit)? = null,
) : ImageAnalysis.Analyzer {
    private var lastThumb = 0L

    private var lastFaces = 0L
    private var lastFaceMesh = 0L
    private var faceMeshes: List<FaceMeshInfo> = emptyList()
    private var lastLabels = 0L
    private var faces: List<FaceInfo> = emptyList()
    private var labels: List<Pair<String, Float>> = emptyList()
    private var prevMean = -1f
    private var shake = 0f

    override fun analyze(image: ImageProxy) {
        try {
            val now = SystemClock.elapsedRealtime()
            val rotation = image.imageInfo.rotationDegrees
            val bmp: Bitmap = image.toBitmap()
            val st = ImageOps.stats(bmp)
            if (onThumb != null && now - lastThumb > 2000) {
                lastThumb = now
                runCatching {
                    val small = ImageOps.limitSize(bmp, 200)
                    onThumb?.invoke(if (rotation != 0) ImageOps.rotate(small, rotation) else small)
                }
            }
            val diff = if (prevMean < 0) 0f else abs(st.mean - prevMean)
            prevMean = st.mean
            shake = shake * 0.8f + diff * 20f * 0.2f

            if (enabled()) {
                if (now - lastFaces > (if (realtimeFaces()) 0 else 330)) {
                    lastFaces = now
                    faces = runBlocking { Vision.faces(bmp, rotation, accurate = false) }
                }
                // Mesh inference is heavier than box/landmark detection; cap it at ~8 FPS.
                if (realtimeFaces() && now - lastFaceMesh >= 125) {
                    lastFaceMesh = now
                    faceMeshes = runBlocking { Vision.faceMeshes(bmp, rotation) }
                }
                if (realtimeFaces() && faceMeshes.isNotEmpty()) {
                    faces = faces.mapIndexed { index, face -> face.copy(mesh3d = faceMeshes.getOrNull(index)) }
                }
                if (now - lastLabels > 1500) {
                    lastLabels = now
                    labels = runBlocking { Vision.labels(bmp, rotation) }
                }
            } else {
                faces = emptyList(); labels = emptyList(); faceMeshes = emptyList()
            }
            val faceLuma = if (faces.isEmpty()) st.mean else faceLuminance(bmp, faces[0].box, rotation)
            onResult(
                LiveScene(
                    st.mean, st.highlights, st.shadows, st.histogram, faces, faceLuma, labels,
                    Vision.classify(labels, faces.size, st.mean), rotation, shake.coerceIn(0f, 1f), now
                )
            )
        } catch (ignored: Throwable) {
        } finally {
            image.close()
        }
    }

    /** Средняя яркость в области лица. Бокс в координатах повёрнутого кадра → переводим в буфер. */
    private fun faceLuminance(bmp: Bitmap, box: RectF, rotation: Int): Float {
        val w = bmp.width; val h = bmp.height
        var sum = 0f; var n = 0
        for (j in 1..6) for (i in 1..6) {
            val ux = box.left + box.width() * i / 7f
            val uy = box.top + box.height() * j / 7f
            val (bx, by) = when (rotation) {
                90 -> Pair(uy, 1f - ux)
                180 -> Pair(1f - ux, 1f - uy)
                270 -> Pair(1f - uy, ux)
                else -> Pair(ux, uy)
            }
            val x = (bx * w).toInt().coerceIn(0, w - 1); val y = (by * h).toInt().coerceIn(0, h - 1)
            val c = bmp.getPixel(x, y)
            sum += (((c shr 16) and 255) * 0.299f + ((c shr 8) and 255) * 0.587f + (c and 255) * 0.114f) / 255f
            n++
        }
        return if (n == 0) 0.5f else sum / n
    }
}
