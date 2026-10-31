package com.maysk.camera.ar3d

import android.opengl.GLES20
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.ShortBuffer

/**
 * Состояние AR-сцены на текущий кадр. Передаётся из UI-потока в GL-поток (иммутабельно).
 * Все координаты — в экранном пространстве превью (см. [ViewMapping]).
 */
class ArFrame(
    val modelId: String,
    /** 3D-модель (GLB) или null, если выбран только плоский шаблон. */
    val model: GlbModel?,
    val pose: FacePose,
    /** 468 точек лица в экранных координатах — «невидимая голова» для перекрытия (может быть пустым). */
    val faceMesh: FloatArray,
    val faceTriangles: ShortArray,
    val viewAspect: Float,
    /** Плоские накладки на лицо (шаблоны эффектов, макияж). */
    val sprites: List<Sprite> = emptyList(),
)

/**
 * Рендер 3D-маски (GLB) прямо в тот же буфер OpenGL, что уходит в превью и в видеокодер,
 * а также в снимок — поэтому маска одинаково видна на экране, на фото и в MP4.
 *
 * Глубина: сначала в буфер глубины рисуется сетка лица (без цвета) — части маски, оказавшиеся
 * «за головой» (дальнее ухо, дужки очков), закрываются самим лицом.
 */
class ArRenderer {
    private var program = 0
    private var aPos = -1; private var aNrm = -1; private var aCol = -1
    private var uModel = -1; private var uRot = -1; private var uPrevToOut = -1; private var uAspect = -1
    private var uBias = -1; private var uColorOn = -1; private var uFade = -1

    private class GpuPart(val vbo: Int, val ibo: Int, val count: Int, val blend: Boolean, val showWhen: String?)
    private var loadedId: String? = null
    private var gpuParts: List<GpuPart> = emptyList()
    private var faceVbo = 0; private var faceIbo = 0; private var faceCount = 0
    private var faceBuf: FloatBuffer? = null

    private val rot = FloatArray(9)

    fun init(): Boolean {
        if (program != 0) return true
        return runCatching {
            program = link(VS, FS)
            aPos = GLES20.glGetAttribLocation(program, "aPos")
            aNrm = GLES20.glGetAttribLocation(program, "aNrm")
            aCol = GLES20.glGetAttribLocation(program, "aCol")
            uModel = GLES20.glGetUniformLocation(program, "uModel")
            uRot = GLES20.glGetUniformLocation(program, "uRot")
            uPrevToOut = GLES20.glGetUniformLocation(program, "uPrevToOut")
            uAspect = GLES20.glGetUniformLocation(program, "uAspect")
            uBias = GLES20.glGetUniformLocation(program, "uBias")
            uColorOn = GLES20.glGetUniformLocation(program, "uColorOn")
            uFade = GLES20.glGetUniformLocation(program, "uFade")
            true
        }.getOrElse { Log.e("MayskAR", "AR shader failed", it); program = 0; false }
    }

    /** Загрузка модели в видеопамять (только при смене модели). */
    private fun ensureModel(id: String, m: GlbModel) {
        if (id == loadedId) return
        releaseModel()
        gpuParts = m.parts.map { part ->
            val n = part.vertexCount
            val inter = FloatArray(n * 10)            // pos(3) nrm(3) col(4)
            for (i in 0 until n) {
                System.arraycopy(part.positions, i * 3, inter, i * 10, 3)
                System.arraycopy(part.normals, i * 3, inter, i * 10 + 3, 3)
                System.arraycopy(part.colors, i * 4, inter, i * 10 + 6, 4)
            }
            val ids = IntArray(2); GLES20.glGenBuffers(2, ids, 0)
            GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, ids[0])
            GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, inter.size * 4, floatBuf(inter), GLES20.GL_STATIC_DRAW)
            // GLES 2: индексы 16 бит (модели Maysk < 65536 вершин на часть)
            val sh = ShortArray(part.indices.size) { part.indices[it].toShort() }
            GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, ids[1])
            GLES20.glBufferData(GLES20.GL_ELEMENT_ARRAY_BUFFER, sh.size * 2, shortBuf(sh), GLES20.GL_STATIC_DRAW)
            GpuPart(ids[0], ids[1], sh.size, part.blend, part.showWhen)
        }
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
        GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, 0)
        loadedId = id
    }

    private fun ensureFace(f: ArFrame) {
        if (f.faceMesh.isEmpty() || f.faceTriangles.isEmpty()) { faceCount = 0; return }
        if (faceVbo == 0) {
            val ids = IntArray(2); GLES20.glGenBuffers(2, ids, 0); faceVbo = ids[0]; faceIbo = ids[1]
        }
        val inter = FloatArray(f.faceMesh.size / 3 * 10)
        for (i in 0 until f.faceMesh.size / 3) {
            inter[i * 10] = f.faceMesh[i * 3]; inter[i * 10 + 1] = f.faceMesh[i * 3 + 1]; inter[i * 10 + 2] = f.faceMesh[i * 3 + 2]
            inter[i * 10 + 5] = -1f; inter[i * 10 + 9] = 1f
        }
        val fb = faceBuf?.takeIf { it.capacity() >= inter.size } ?: ByteBuffer.allocateDirect(inter.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().also { faceBuf = it }
        fb.clear(); fb.put(inter); fb.position(0)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, faceVbo)
        GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, inter.size * 4, fb, GLES20.GL_DYNAMIC_DRAW)
        if (faceCount != f.faceTriangles.size) {
            GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, faceIbo)
            GLES20.glBufferData(GLES20.GL_ELEMENT_ARRAY_BUFFER, f.faceTriangles.size * 2, shortBuf(f.faceTriangles), GLES20.GL_STATIC_DRAW)
            faceCount = f.faceTriangles.size
        }
    }

    /**
     * Рисует маску в текущий буфер. [prevToOut] — матрица из текстурных координат превью в координаты
     * этого выхода (для превью — единичная, для видео/снимка — своя), так геометрия совпадает с кадром.
     */
    fun draw(f: ArFrame, prevToOut: FloatArray, hasDepth: Boolean) {
        if (!init()) return
        val model = f.model
        if (model != null) ensureModel(f.modelId, model) else releaseModel()
        ensureFace(f)
        GLES20.glUseProgram(program)
        GLES20.glUniformMatrix4fv(uPrevToOut, 1, false, prevToOut, 0)
        GLES20.glUniform1f(uAspect, f.viewAspect)
        GLES20.glUniform1f(uFade, 1f)
        if (hasDepth) {
            GLES20.glEnable(GLES20.GL_DEPTH_TEST)
            GLES20.glDepthFunc(GLES20.GL_LEQUAL)
            GLES20.glDepthMask(true)
            GLES20.glClearDepthf(1f)
            GLES20.glClear(GLES20.GL_DEPTH_BUFFER_BIT)
        }
        GLES20.glDisable(GLES20.GL_CULL_FACE)

        // 1) «Невидимая голова»: только глубина, чуть отодвинута назад, чтобы детали на коже не тонули
        if (hasDepth && faceCount > 0) {
            GLES20.glColorMask(false, false, false, false)
            GLES20.glUniformMatrix4fv(uModel, 1, false, IDENTITY, 0)
            GLES20.glUniformMatrix3fv(uRot, 1, false, IDENTITY3, 0)
            GLES20.glUniform1f(uBias, 0.06f * f.pose.scale)
            GLES20.glUniform1f(uColorOn, 0f)
            bindAndDraw(faceVbo, faceIbo, faceCount)
            GLES20.glColorMask(true, true, true, true)
        }

        // 2) Модель + процедурная анимация
        val tSec = (System.nanoTime() / 1_000_000_000.0).toFloat()
        val baseM = f.pose.matrix()
        val animSt = if (model != null) ModelAnim.evaluate(f.modelId, tSec, f.pose) else ModelAnim.State()
        val m = if (model != null) ModelAnim.applyToPoseMatrix(baseM, animSt) else baseM
        val parts = if (model != null) gpuParts else emptyList()
        for (k in 0 until 3) { rot[k] = f.pose.right[k]; rot[3 + k] = f.pose.up[k]; rot[6 + k] = f.pose.forward[k] }
        GLES20.glUniformMatrix4fv(uModel, 1, false, m, 0)
        GLES20.glUniformMatrix3fv(uRot, 1, false, rot, 0)
        GLES20.glUniform1f(uBias, 0f)
        GLES20.glUniform1f(uColorOn, 1f)
        GLES20.glUniform1f(uFade, animSt.fade)
        for (gp in parts) {
            when (gp.showWhen) {
                "mouthOpen" -> if (f.pose.mouthOpen < 0.35f) continue
                "mouthClosed" -> if (f.pose.mouthOpen > 0.25f) continue
                "eyeOpen" -> if (f.pose.eyeOpen < 0.4f) continue
                "eyeClosed" -> if (f.pose.eyeOpen > 0.35f) continue
            }
            if (gp.blend) {
                GLES20.glEnable(GLES20.GL_BLEND)
                GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
                GLES20.glDepthMask(false)
            }
            bindAndDraw(gp.vbo, gp.ibo, gp.count)
            if (gp.blend) {
                GLES20.glDisable(GLES20.GL_BLEND)
                GLES20.glDepthMask(true)
            }
        }
        GLES20.glUniform1f(uFade, 1f)
        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
        GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, 0)
        if (aNrm >= 0) GLES20.glDisableVertexAttribArray(aNrm)
        if (aCol >= 0) GLES20.glDisableVertexAttribArray(aCol)

        // 3) Плоские шаблоны в плоскости лица (поверх, без глубины — как 2D-линзы)
        if (f.sprites.isNotEmpty()) drawSprites(f, prevToOut, m)
    }

    // ---------- спрайты ----------
    private var sprProgram = 0
    private var sPos = -1; private var sUv = -1
    private var suModel = -1; private var suPrevToOut = -1; private var suAspect = -1; private var suTex = -1; private var suAlpha = -1; private var suLight = -1
    private var sprVertBuf: FloatBuffer? = null
    private val textures = HashMap<String, Int>()

    private fun texture(s: Sprite): Int {
        textures[s.key]?.let { return it }
        val t = IntArray(1); GLES20.glGenTextures(1, t, 0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, t[0])
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        android.opengl.GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, s.bitmap, 0)   // премультиплицированная альфа
        textures[s.key] = t[0]
        return t[0]
    }

    private fun drawSprites(f: ArFrame, prevToOut: FloatArray, model: FloatArray) {
        if (sprProgram == 0) {
            sprProgram = runCatching { link(SVS, SFS) }.getOrElse { Log.e("MayskAR", "sprite shader", it); return }
            sPos = GLES20.glGetAttribLocation(sprProgram, "aPos"); sUv = GLES20.glGetAttribLocation(sprProgram, "aUv")
            suModel = GLES20.glGetUniformLocation(sprProgram, "uModel"); suPrevToOut = GLES20.glGetUniformLocation(sprProgram, "uPrevToOut")
            suAspect = GLES20.glGetUniformLocation(sprProgram, "uAspect"); suTex = GLES20.glGetUniformLocation(sprProgram, "uTex")
            suAlpha = GLES20.glGetUniformLocation(sprProgram, "uAlpha"); suLight = GLES20.glGetUniformLocation(sprProgram, "uLight")
        }
        // лишние текстуры (сменили шаблон) — освобождаем
        val keys = f.sprites.map { it.key }.toSet()
        textures.keys.filter { it !in keys }.forEach { k -> GLES20.glDeleteTextures(1, intArrayOf(textures.remove(k)!!), 0) }
        GLES20.glUseProgram(sprProgram)
        GLES20.glUniformMatrix4fv(suModel, 1, false, model, 0)
        GLES20.glUniformMatrix4fv(suPrevToOut, 1, false, prevToOut, 0)
        GLES20.glUniform1f(suAspect, f.viewAspect)
        // лёгкий свет: чуть ярче «к камере»
        val light = (1f + 0.12f * f.pose.forward[2].coerceIn(-1f, 1f)).coerceIn(0.85f, 1.15f)
        GLES20.glUniform1f(suLight, light)
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE7)
        GLES20.glUniform1i(suTex, 7)
        // время для дыхания / моргания (секунды)
        val tSec = (System.nanoTime() / 1_000_000_000.0).toFloat()
        // faceRatio: взрослый ~2.1–2.4, ребёнок ~1.6–1.9
        val ratio = f.pose.faceRatio.coerceIn(1.4f, 3.0f)
        // 0 = чистый «детский» шаблон, 1 = взрослый
        val adultT = ((ratio - 1.7f) / (2.2f - 1.7f)).coerceIn(0f, 1f)
        val yScale = (ratio / 2.1f).coerceIn(0.75f, 1.25f)
        for (s in f.sprites) {
            if (s.alpha <= 0.01f) continue
            val (sxAnim, syAnim, aMul) = FaceAnim.spriteAnim(s.key, tSec, f.pose.eyeOpen, f.pose.yaw)
            val lift = FaceAnim.topLift(s.key, tSec)
            // выбор adult / kids координат + плавная интерполяция
            val atA = s.at
            val atK = s.atKids ?: s.at
            val wA = s.width
            val wK = if (s.wKids > 0f) s.wKids else s.width
            val baseX = atK[0] + (atA[0] - atK[0]) * adultT
            val baseY0 = atK[1] + (atA[1] - atK[1]) * adultT
            val baseZ = atK.getOrElse(2) { 0f } + (atA.getOrElse(2) { 0f } - atK.getOrElse(2) { 0f }) * adultT
            val baseW = wK + (wA - wK) * adultT
            val baseY = when (s.anchor) {
                "forehead" -> baseY0 * yScale
                "eyes"     -> baseY0 * (0.5f + 0.5f * yScale)
                "nose"     -> baseY0 * (0.7f + 0.3f * yScale)
                "mouth"    -> baseY0 * yScale
                "cheeks"   -> baseY0 * (0.8f + 0.2f * yScale)
                else       -> baseY0
            }
            val hw = baseW * sxAnim / 2
            val h = baseW * s.aspect * syAnim
            val cy = (if (s.bottomAligned) baseY + h / 2 else baseY) + lift
            val x = baseX; val z = baseZ
            // 4 вершины: позиция в лицевых единицах (xyz) + UV
            val v = floatArrayOf(
                x - hw, cy + h / 2, z, 0f, 0f,
                x + hw, cy + h / 2, z, 1f, 0f,
                x - hw, cy - h / 2, z, 0f, 1f,
                x + hw, cy - h / 2, z, 1f, 1f,
            )
            val buf = sprVertBuf?.takeIf { it.capacity() >= v.size }
                ?: ByteBuffer.allocateDirect(v.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().also { sprVertBuf = it }
            buf.clear(); buf.put(v); buf.position(0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture(s))
            GLES20.glUniform1f(suAlpha, s.alpha * aMul)
            buf.position(0)
            GLES20.glEnableVertexAttribArray(sPos)
            GLES20.glVertexAttribPointer(sPos, 3, GLES20.GL_FLOAT, false, 20, buf)
            buf.position(3)
            GLES20.glEnableVertexAttribArray(sUv)
            GLES20.glVertexAttribPointer(sUv, 2, GLES20.GL_FLOAT, false, 20, buf)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        }
        GLES20.glDisableVertexAttribArray(sPos); GLES20.glDisableVertexAttribArray(sUv)
        GLES20.glDisable(GLES20.GL_BLEND)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
    }

    private fun bindAndDraw(vbo: Int, ibo: Int, count: Int) {
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vbo)
        GLES20.glEnableVertexAttribArray(aPos)
        GLES20.glVertexAttribPointer(aPos, 3, GLES20.GL_FLOAT, false, 40, 0)
        if (aNrm >= 0) { GLES20.glEnableVertexAttribArray(aNrm); GLES20.glVertexAttribPointer(aNrm, 3, GLES20.GL_FLOAT, false, 40, 12) }
        if (aCol >= 0) { GLES20.glEnableVertexAttribArray(aCol); GLES20.glVertexAttribPointer(aCol, 4, GLES20.GL_FLOAT, false, 40, 24) }
        GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, ibo)
        GLES20.glDrawElements(GLES20.GL_TRIANGLES, count, GLES20.GL_UNSIGNED_SHORT, 0)
    }

    private fun releaseModel() {
        for (gp in gpuParts) GLES20.glDeleteBuffers(2, intArrayOf(gp.vbo, gp.ibo), 0)
        gpuParts = emptyList(); loadedId = null
    }

    /** Освобождение всех ресурсов (вызывать в GL-потоке при остановке камеры). */
    fun release() {
        releaseModel()
        textures.values.forEach { GLES20.glDeleteTextures(1, intArrayOf(it), 0) }; textures.clear()
        if (sprProgram != 0) { GLES20.glDeleteProgram(sprProgram); sprProgram = 0 }
        if (faceVbo != 0) { GLES20.glDeleteBuffers(2, intArrayOf(faceVbo, faceIbo), 0); faceVbo = 0; faceIbo = 0; faceCount = 0 }
        if (program != 0) { GLES20.glDeleteProgram(program); program = 0 }
    }

    private fun link(vs: String, fs: String): Int {
        fun sh(type: Int, src: String): Int {
            val s = GLES20.glCreateShader(type)
            GLES20.glShaderSource(s, src); GLES20.glCompileShader(s)
            val ok = IntArray(1); GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, ok, 0)
            check(ok[0] != 0) { "AR shader: " + GLES20.glGetShaderInfoLog(s) }
            return s
        }
        val p = GLES20.glCreateProgram()
        GLES20.glAttachShader(p, sh(GLES20.GL_VERTEX_SHADER, vs))
        GLES20.glAttachShader(p, sh(GLES20.GL_FRAGMENT_SHADER, fs))
        GLES20.glLinkProgram(p)
        val ok = IntArray(1); GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, ok, 0)
        check(ok[0] != 0) { "AR link: " + GLES20.glGetProgramInfoLog(p) }
        return p
    }

    companion object {
        private val IDENTITY = floatArrayOf(1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f)
        private val IDENTITY3 = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)
        private fun floatBuf(a: FloatArray): FloatBuffer =
            ByteBuffer.allocateDirect(a.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply { put(a); position(0) }
        private fun shortBuf(a: ShortArray): ShortBuffer =
            ByteBuffer.allocateDirect(a.size * 2).order(ByteOrder.nativeOrder()).asShortBuffer().apply { put(a); position(0) }

        /** Модель → экран view → текстурные координаты превью → координаты выхода → NDC. */
        const val VS = """
attribute vec3 aPos;
attribute vec3 aNrm;
attribute vec4 aCol;
uniform mat4 uModel;
uniform mat3 uRot;
uniform mat4 uPrevToOut;
uniform float uAspect;
uniform float uBias;
varying vec4 vCol;
varying vec3 vN;
void main() {
    vec4 s = uModel * vec4(aPos, 1.0);
    vec4 uv = uPrevToOut * vec4(s.x / uAspect, 1.0 - s.y, 0.0, 1.0);
    float z = clamp((s.z + uBias) * 1.2, -0.98, 0.98);
    gl_Position = vec4(uv.x * 2.0 - 1.0, uv.y * 2.0 - 1.0, z, 1.0);
    vN = uRot * aNrm;
    vCol = aCol;
}
"""
        /** Спрайт в плоскости лица: та же цепочка координат, что у 3D-модели. */
        const val SVS = """
attribute vec3 aPos;
attribute vec2 aUv;
uniform mat4 uModel;
uniform mat4 uPrevToOut;
uniform float uAspect;
varying vec2 vUv;
void main() {
    vec4 s = uModel * vec4(aPos, 1.0);
    vec4 uv = uPrevToOut * vec4(s.x / uAspect, 1.0 - s.y, 0.0, 1.0);
    gl_Position = vec4(uv.x * 2.0 - 1.0, uv.y * 2.0 - 1.0, 0.0, 1.0);
    vUv = aUv;
}
"""
        const val SFS = """
precision mediump float;
varying vec2 vUv;
uniform sampler2D uTex;
uniform float uAlpha;
uniform float uLight; // 0.85..1.15 лёгкий tint от направления головы
void main() {
    vec4 c = texture2D(uTex, vUv);
    // мягкое затемнение краёв спрайта (меньше вид «наклейки»)
    float edge = smoothstep(0.0, 0.08, vUv.x) * smoothstep(1.0, 0.92, vUv.x)
               * smoothstep(0.0, 0.08, vUv.y) * smoothstep(1.0, 0.92, vUv.y);
    c.rgb *= uLight;
    c *= uAlpha;
    c.a *= 0.65 + 0.35 * edge;
    gl_FragColor = c;
}
"""

        /** Освещение: мягкий свет со стороны камеры сверху-слева + блик; двусторонние грани. */
        const val FS = """
precision mediump float;
varying vec4 vCol;
varying vec3 vN;
uniform float uColorOn;
uniform float uFade;
void main() {
    vec3 n = normalize(vN);
    vec3 L = normalize(vec3(-0.35, -0.55, -1.0));
    float diff = abs(dot(n, L));
    vec3 H = normalize(L + vec3(0.0, 0.0, -1.0));
    float spec = pow(abs(dot(n, H)), 24.0) * 0.28;
    vec3 c = vCol.rgb * (0.38 + 0.62 * diff) + vec3(spec);
    gl_FragColor = vec4(c, vCol.a * uFade * uColorOn);
}
"""
    }
}
