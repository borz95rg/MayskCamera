package com.maysk.camera.camera.gl

import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import android.util.Size
import android.view.Surface
import androidx.camera.core.CameraEffect
import androidx.camera.core.SurfaceOutput
import androidx.camera.core.SurfaceProcessor
import androidx.camera.core.SurfaceRequest
import com.maysk.camera.camera.Filters
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.Executor

/** Параметры обработки кадра. Иммутабельный снимок — безопасно менять из UI-потока. */
data class EffectParams(
    val colorMatrix: FloatArray = Filters.ORIGINAL.matrix,
    val vignette: Float = 0f,
    val fade: Float = 0f,
    val beauty: Float = 0f,
    val denoise: Float = 0f,
    val faceLight: Float = 0f,
    val sharpen: Float = 0f,
    val gain: Float = 1f,
    val lift: Float = 0f,
    val log: Float = 0f,
    val effect: Int = 0,
    /** Тональная кривая фильтра (768 байт: R,G,B) и её сила 0..1. */
    val lut: ByteArray? = null,
    val lutMix: Float = 0f,
    val grain: Float = 0f,
    val leak: Float = 0f,
    val skinTone: Float = 0f,
    val warmSat: Float = 0f,
    /** Портрет: центр лица (x, y в координатах кадра, y вверх), радиус, сила размытия фона. */
    val focus: FloatArray = floatArrayOf(0.5f, 0.5f, 0.2f, 0f),
    /** Рамка, рисуемая прямо в кадр (превью и запись видео). */
    val frame: com.maysk.camera.camera.FrameSpec? = null,
    /** Временное шумоподавление (ночное видео), 0..0.85. */
    val temporal: Float = 0f,
)

/**
 * OpenGL ES 2.0 процессор кадров для CameraX ([SurfaceProcessor]).
 * Принимает кадры камеры, обрабатывает их шейдером и отдаёт в превью и запись видео —
 * поэтому фильтры, AI-красота и эффекты попадают прямо в записанный ролик.
 */
class GlEffectProcessor : SurfaceProcessor {

    @Volatile
    var params: EffectParams = EffectParams()

    /** Гироскопная стабилизация (null/выкл — кадр без изменений). */
    @Volatile
    var stabilizer: com.maysk.camera.camera.GyroStabilizer? = null

    private val flipV = FloatArray(16)
    private val upToTex = FloatArray(16)
    private val texToUp = FloatArray(16)
    private val stabM = FloatArray(16)
    private val tmpA = FloatArray(16)
    private val tmpB = FloatArray(16)
    private val finalM = FloatArray(16)

    private val thread = HandlerThread("MayskGL").apply { start() }
    private val handler = Handler(thread.looper)
    val executor: Executor = Executor { r -> handler.post(r) }

    private var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var context: EGLContext = EGL14.EGL_NO_CONTEXT
    private var config: EGLConfig? = null
    private var pbuffer: EGLSurface = EGL14.EGL_NO_SURFACE
    private var glReady = false

    private var program = 0
    private var aPosition = 0
    private var aTexCoord = 0
    private val uniforms = HashMap<String, Int>()

    private var surfaceTexture: SurfaceTexture? = null
    private var ovTex = 0
    /** OES texture owned by the current CameraX input surface; used by temporal NR. */
    @Volatile private var cameraTextureId = 0
    private var ovKey: Any? = null
    private val previewM = FloatArray(16)
    private val previewInv = FloatArray(16)
    private val ovM = FloatArray(16)
    private val identity = FloatArray(16).also { android.opengl.Matrix.setIdentityM(it, 0) }

    /** Рисует слой рамки размером с превью и загружает в текстуру (только при смене рамки/размера). */
    private fun ensureOverlay(spec: com.maysk.camera.camera.FrameSpec, pw: Int, ph: Int): Boolean {
        val k = 720f / maxOf(pw, ph).coerceAtLeast(1)
        val w = (pw * minOf(1f, k * 1.5f)).toInt().coerceAtLeast(16)
        val h = (ph * minOf(1f, k * 1.5f)).toInt().coerceAtLeast(16)
        val key = Triple(spec, w, h)
        if (key == ovKey && ovTex != 0) return true
        val bmp = runCatching { com.maysk.camera.camera.Frames.previewOverlay(w, h, spec) }.getOrNull() ?: return false
        if (ovTex == 0) {
            val t = IntArray(1); GLES20.glGenTextures(1, t, 0); ovTex = t[0]
        }
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, ovTex)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        android.opengl.GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bmp, 0)
        bmp.recycle()
        ovKey = key
        return true
    }

    private var lutTex = 0
    private var lutUploaded: ByteArray? = null

    /** Загружает LUT фильтра в текстуру 256x1 (только при смене фильтра). */
    private fun ensureLut(lut: ByteArray?) {
        if (lutTex == 0) {
            val t = IntArray(1); GLES20.glGenTextures(1, t, 0); lutTex = t[0]
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, lutTex)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        }
        if (lut == null || lut === lutUploaded) return
        val rgba = ByteBuffer.allocateDirect(256 * 4)
        for (i in 0 until 256) { rgba.put(lut[i]); rgba.put(lut[256 + i]); rgba.put(lut[512 + i]); rgba.put(-1) }
        rgba.position(0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, lutTex)
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, 256, 1, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, rgba)
        lutUploaded = lut
    }
    private var inputSize = Size(1, 1)
    private val outputs = LinkedHashMap<SurfaceOutput, EGLSurface>()

    private val texMatrix = FloatArray(16)
    private val outMatrix = FloatArray(16)
    private val colorMat = FloatArray(16)
    private val colorOffset = FloatArray(4)
    private val start = SystemClock.elapsedRealtime()

    private val vertices: FloatBuffer = floatBuffer(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f))
    private val texCoords: FloatBuffer = floatBuffer(floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f))

    init {
        handler.post { initGl() }
    }

    /** Создаёт CameraX-эффект для нужных целей (PREVIEW или PREVIEW|VIDEO_CAPTURE). */
    fun createEffect(targets: Int): CameraEffect = MayskCameraEffect(targets, executor, this)

    private class MayskCameraEffect(targets: Int, executor: Executor, processor: SurfaceProcessor) :
        CameraEffect(targets, executor, processor, { t -> Log.e("MayskGL", "effect error", t) })

    // region EGL
    private fun initGl() {
        try {
            display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            val version = IntArray(2)
            check(EGL14.eglInitialize(display, version, 0, version, 1)) { "eglInitialize failed" }
            val attribs = intArrayOf(
                EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL_RECORDABLE_ANDROID, 1,
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT or EGL14.EGL_PBUFFER_BIT,
                EGL14.EGL_NONE
            )
            val configs = arrayOfNulls<EGLConfig>(1)
            val num = IntArray(1)
            check(EGL14.eglChooseConfig(display, attribs, 0, configs, 0, 1, num, 0) && num[0] > 0) { "no EGL config" }
            config = configs[0]
            context = EGL14.eglCreateContext(
                display, config, EGL14.EGL_NO_CONTEXT,
                intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0
            )
            pbuffer = EGL14.eglCreatePbufferSurface(display, config, intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE), 0)
            EGL14.eglMakeCurrent(display, pbuffer, pbuffer, context)
            program = buildProgram(Shaders.VERTEX, Shaders.FRAGMENT)
            aPosition = GLES20.glGetAttribLocation(program, "aPosition")
            aTexCoord = GLES20.glGetAttribLocation(program, "aTexCoord")
            listOf(
                "uTexMatrix", "uTex", "uTexel", "uColor", "uOffset", "uBeauty", "uDenoise", "uFaceLight", "uSharp",
                "uGain", "uLift", "uVignette", "uFade", "uLog", "uTime", "uAspect", "uEffect", "uLut", "uLutMix", "uGrain", "uLeak", "uSkinTone", "uWarmSat", "uFocus", "uOv", "uOvOn", "uOvMatrix"
            ).forEach { uniforms[it] = GLES20.glGetUniformLocation(program, it) }
            // вариант для временного шумоподавления (может не собраться на редких GPU — тогда просто не используем)
            runCatching {
                program2D = buildProgram(Shaders.VERTEX, Shaders.FRAGMENT_2D)
                uniforms.keys.toList().forEach { uniforms2D[it] = GLES20.glGetUniformLocation(program2D, it) }
                progTemporal = buildProgram(Shaders.TEMPORAL_VERTEX, Shaders.TEMPORAL_FRAGMENT)
            }.onFailure { program2D = 0; progTemporal = 0; Log.w("MayskGL", "temporal NR unavailable", it) }
            glReady = true
        } catch (t: Throwable) {
            Log.e("MayskGL", "GL init failed", t)
        }
    }

    private fun buildProgram(vs: String, fs: String): Int {
        fun compile(type: Int, src: String): Int {
            val s = GLES20.glCreateShader(type)
            GLES20.glShaderSource(s, src)
            GLES20.glCompileShader(s)
            val ok = IntArray(1)
            GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, ok, 0)
            if (ok[0] == 0) {
                val log = GLES20.glGetShaderInfoLog(s)
                GLES20.glDeleteShader(s)
                throw IllegalStateException("Shader compile error: $log")
            }
            return s
        }
        val p = GLES20.glCreateProgram()
        GLES20.glAttachShader(p, compile(GLES20.GL_VERTEX_SHADER, vs))
        GLES20.glAttachShader(p, compile(GLES20.GL_FRAGMENT_SHADER, fs))
        GLES20.glLinkProgram(p)
        val ok = IntArray(1)
        GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, ok, 0)
        check(ok[0] != 0) { "Program link error: " + GLES20.glGetProgramInfoLog(p) }
        return p
    }
    // endregion

    override fun onInputSurface(request: SurfaceRequest) {
        if (!glReady) {
            request.willNotProvideSurface()
            return
        }
        EGL14.eglMakeCurrent(display, pbuffer, pbuffer, context)
        val tex = IntArray(1)
        GLES20.glGenTextures(1, tex, 0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, tex[0])
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

        cameraTextureId = tex[0]
        val st = SurfaceTexture(tex[0])
        st.setDefaultBufferSize(request.resolution.width, request.resolution.height)
        val surface = Surface(st)
        inputSize = request.resolution
        surfaceTexture = st
        st.setOnFrameAvailableListener({ drawFrame(it) }, handler)
        request.provideSurface(surface, executor) {
            st.setOnFrameAvailableListener(null)
            st.release()
            surface.release()
            EGL14.eglMakeCurrent(display, pbuffer, pbuffer, context)
            GLES20.glDeleteTextures(1, tex, 0)
            if (surfaceTexture === st) { surfaceTexture = null; cameraTextureId = 0 }
        }
    }

    override fun onOutputSurface(output: SurfaceOutput) {
        if (!glReady) {
            output.close()
            return
        }
        val surface = output.getSurface(executor) {
            outputs.remove(output)?.let { EGL14.eglDestroySurface(display, it) }
            output.close()
        }
        val egl = EGL14.eglCreateWindowSurface(display, config, surface, intArrayOf(EGL14.EGL_NONE), 0)
        if (egl == null || egl == EGL14.EGL_NO_SURFACE) {
            Log.e("MayskGL", "eglCreateWindowSurface failed")
            output.close()
            return
        }
        outputs[output] = egl
    }

    private fun drawFrame(st: SurfaceTexture) {
        if (st !== surfaceTexture) return
        EGL14.eglMakeCurrent(display, pbuffer, pbuffer, context)
        try {
            st.updateTexImage()
        } catch (t: Throwable) {
            return
        }
        st.getTransformMatrix(texMatrix)
        val p = params
        // ColorMatrix 4x5 (строки) -> mat4 (по столбцам) + смещение
        for (row in 0 until 4) {
            for (col in 0 until 4) colorMat[col * 4 + row] = p.colorMatrix[row * 5 + col]
            colorOffset[row] = p.colorMatrix[row * 5 + 4] / 255f
        }
        val time = (SystemClock.elapsedRealtime() - start) / 1000f

        // Стабилизация: матрица выборки в вертикальных координатах экрана (u вправо, v вниз),
        // переведённая в пространство текстуры камеры: M = T · S · T⁻¹
        val corr = stabilizer?.takeIf { it.level.software }?.correctionAt(st.timestamp)
        val stabilize = corr != null && (corr[3] > 1.0001f || corr[0] != 0f || corr[1] != 0f)
        if (stabilize && corr != null) {
            android.opengl.Matrix.setIdentityM(flipV, 0)
            flipV[5] = -1f; flipV[13] = 1f                       // v (вниз) → t (вверх)
            android.opengl.Matrix.multiplyMM(upToTex, 0, texMatrix, 0, flipV, 0)
            android.opengl.Matrix.invertM(texToUp, 0, upToTex, 0)
            android.opengl.Matrix.setIdentityM(stabM, 0)
            android.opengl.Matrix.translateM(stabM, 0, 0.5f + corr[0], 0.5f + corr[1], 0f)
            android.opengl.Matrix.rotateM(stabM, 0, corr[2], 0f, 0f, 1f)
            android.opengl.Matrix.scaleM(stabM, 0, 1f / corr[3], 1f / corr[3], 1f)
            android.opengl.Matrix.translateM(stabM, 0, -0.5f, -0.5f, 0f)
            android.opengl.Matrix.multiplyMM(tmpA, 0, upToTex, 0, stabM, 0)
            android.opengl.Matrix.multiplyMM(tmpB, 0, tmpA, 0, texToUp, 0)   // T·S·T⁻¹
        }

        // Временное шумоподавление ночного видео (в координатах кадра камеры)
        val cleanTex = if (p.temporal > 0.01f) temporalPass(p.temporal.coerceAtMost(0.55f)) else { fboFrames = 0; 0 }
        useProgram2D = cleanTex != 0

        // Рамка для видео: координаты кадра видео → координаты превью (как видит пользователь).
        // ovM = inv(M_preview) · M_video; если ориентации отличаются зеркально — отражаем, чтобы текст читался.
        var ovReady = false
        val spec = p.frame
        val previewOut = outputs.keys.firstOrNull { (it.targets and CameraEffect.PREVIEW) != 0 }
        if (spec != null && previewOut != null) {
            previewOut.updateTransformMatrix(previewM, texMatrix)
            if (android.opengl.Matrix.invertM(previewInv, 0, previewM, 0)) {
                EGL14.eglMakeCurrent(display, pbuffer, pbuffer, context)
                ovReady = ensureOverlay(spec, previewOut.size.width, previewOut.size.height)
            }
        }

        for ((out, egl) in outputs) {
            if (!EGL14.eglMakeCurrent(display, egl, egl, context)) continue
            out.updateTransformMatrix(outMatrix, texMatrix)
            val ovOn = ovReady   // рамка в том же буфере: и в превью, и в видео
            if (ovOn) {
                android.opengl.Matrix.multiplyMM(ovM, 0, previewInv, 0, outMatrix, 0)
                val det = ovM[0] * ovM[5] - ovM[1] * ovM[4]
                if (det < 0f) {
                    // отражение по горизонтали: u → 1 − u
                    for (i in 0 until 4) { val col = i * 4; ovM[col] = ovM[col + 3] - ovM[col] }
                }
            }
            val matrixForDraw = if (stabilize) {
                android.opengl.Matrix.multiplyMM(finalM, 0, tmpB, 0, outMatrix, 0)
                finalM
            } else outMatrix
            val size = out.size
            GLES20.glViewport(0, 0, size.width, size.height)
            GLES20.glUseProgram(if (useProgram2D) program2D else program)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE2)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, if (ovOn) ovTex else lutTex)
            GLES20.glUniform1i(u("uOv"), 2)
            GLES20.glUniform1f(u("uOvOn"), if (ovOn) 1f else 0f)
            GLES20.glUniformMatrix4fv(u("uOvMatrix"), 1, false, if (ovOn) ovM else identity, 0)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            if (useProgram2D) {
                GLES20.glActiveTexture(GLES20.GL_TEXTURE3)
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, cleanTex)
                GLES20.glUniform1i(u("uTex"), 3)
                GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            } else GLES20.glUniform1i(u("uTex"), 0)
            GLES20.glUniformMatrix4fv(u("uTexMatrix"), 1, false, matrixForDraw, 0)
            GLES20.glUniform2f(u("uTexel"), 1f / inputSize.width, 1f / inputSize.height)
            GLES20.glUniformMatrix4fv(u("uColor"), 1, false, colorMat, 0)
            GLES20.glUniform4fv(u("uOffset"), 1, colorOffset, 0)
            GLES20.glUniform1f(u("uBeauty"), p.beauty)
            GLES20.glUniform1f(u("uDenoise"), p.denoise)
            GLES20.glUniform1f(u("uFaceLight"), p.faceLight)
            GLES20.glUniform1f(u("uSharp"), p.sharpen)
            GLES20.glUniform1f(u("uGain"), p.gain)
            GLES20.glUniform1f(u("uLift"), p.lift)
            GLES20.glUniform1f(u("uVignette"), p.vignette)
            GLES20.glUniform1f(u("uFade"), p.fade)
            GLES20.glUniform1f(u("uLog"), p.log)
            GLES20.glUniform1f(u("uTime"), time)
            GLES20.glUniform1f(u("uAspect"), size.width.toFloat() / size.height.toFloat())
            GLES20.glUniform1i(u("uEffect"), p.effect)
            GLES20.glUniform1f(u("uGrain"), p.grain)
            GLES20.glUniform1f(u("uLeak"), p.leak)
            GLES20.glUniform1f(u("uSkinTone"), p.skinTone)
            GLES20.glUniform1f(u("uWarmSat"), p.warmSat)
            GLES20.glUniform4fv(u("uFocus"), 1, p.focus, 0)
            val lutOn = p.lut != null && p.lutMix > 0.01f
            ensureLut(p.lut)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, lutTex)
            GLES20.glUniform1i(u("uLut"), 1)
            GLES20.glUniform1f(u("uLutMix"), if (lutOn) p.lutMix else 0f)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)

            val prog = if (useProgram2D) program2D else program
            val ap = if (useProgram2D) GLES20.glGetAttribLocation(prog, "aPosition") else aPosition
            val at = if (useProgram2D) GLES20.glGetAttribLocation(prog, "aTexCoord") else aTexCoord
            GLES20.glEnableVertexAttribArray(ap)
            GLES20.glVertexAttribPointer(ap, 2, GLES20.GL_FLOAT, false, 0, vertices)
            GLES20.glEnableVertexAttribArray(at)
            GLES20.glVertexAttribPointer(at, 2, GLES20.GL_FLOAT, false, 0, texCoords)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

            EGLExt.eglPresentationTimeANDROID(display, egl, st.timestamp)
            EGL14.eglSwapBuffers(display, egl)
        }
    }

    private fun u(name: String): Int = (if (useProgram2D) uniforms2D else uniforms)[name] ?: -1

    // region временное шумоподавление
    private var program2D = 0
    private var progTemporal = 0
    private val uniforms2D = HashMap<String, Int>()
    private var useProgram2D = false
    private val fboTex = IntArray(2)
    private val fbo = IntArray(2)
    private var fboSize = Size(0, 0)
    private var fboCur = 0
    private var fboFrames = 0

    private fun ensureFbo(size: Size): Boolean {
        if (fboSize == size && fbo[0] != 0) return true
        if (fbo[0] != 0) { GLES20.glDeleteFramebuffers(2, fbo, 0); GLES20.glDeleteTextures(2, fboTex, 0) }
        GLES20.glGenTextures(2, fboTex, 0)
        GLES20.glGenFramebuffers(2, fbo, 0)
        for (i in 0 until 2) {
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, fboTex[i])
            GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, size.width, size.height, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo[i])
            GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, fboTex[i], 0)
            if (GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER) != GLES20.GL_FRAMEBUFFER_COMPLETE) {
                GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
                return false
            }
        }
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        fboSize = size; fboFrames = 0
        return true
    }

    /** Проход «кадр камеры + предыдущий результат → FBO». Возвращает текстуру с очищенным кадром или 0. */
    private fun temporalPass(strength: Float): Int {
        val sourceTexture = cameraTextureId
        if (progTemporal == 0 || program2D == 0 || sourceTexture == 0) return 0
        if (!ensureFbo(inputSize)) return 0
        val prev = fboCur; val cur = 1 - fboCur
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo[cur])
        GLES20.glViewport(0, 0, inputSize.width, inputSize.height)
        GLES20.glUseProgram(progTemporal)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, sourceTexture)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(progTemporal, "uCur"), 0)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE4)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, fboTex[prev])
        GLES20.glUniform1i(GLES20.glGetUniformLocation(progTemporal, "uPrev"), 4)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(progTemporal, "uStrength"), if (fboFrames == 0) 0f else strength)
        GLES20.glUniform2f(GLES20.glGetUniformLocation(progTemporal, "uTexel"), 1f / inputSize.width, 1f / inputSize.height)
        val ap = GLES20.glGetAttribLocation(progTemporal, "aPosition"); val at = GLES20.glGetAttribLocation(progTemporal, "aTexCoord")
        GLES20.glEnableVertexAttribArray(ap)
        GLES20.glVertexAttribPointer(ap, 2, GLES20.GL_FLOAT, false, 0, vertices)
        GLES20.glEnableVertexAttribArray(at)
        GLES20.glVertexAttribPointer(at, 2, GLES20.GL_FLOAT, false, 0, texCoords)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        fboCur = cur; fboFrames++
        return fboTex[cur]
    }
    // endregion

    fun release() {
        handler.post {
            outputs.forEach { (o, e) -> EGL14.eglDestroySurface(display, e); o.close() }
            outputs.clear()
            surfaceTexture?.release()
            if (display != EGL14.EGL_NO_DISPLAY) {
                EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                EGL14.eglDestroySurface(display, pbuffer)
                EGL14.eglDestroyContext(display, context)
                EGL14.eglTerminate(display)
            }
            thread.quitSafely()
        }
    }

    companion object {
        private const val EGL_RECORDABLE_ANDROID = 0x3142

        private fun floatBuffer(data: FloatArray): FloatBuffer =
            ByteBuffer.allocateDirect(data.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
                put(data); position(0)
            }
    }
}
