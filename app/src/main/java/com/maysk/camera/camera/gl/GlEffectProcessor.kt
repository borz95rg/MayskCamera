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
)

/**
 * OpenGL ES 2.0 процессор кадров для CameraX ([SurfaceProcessor]).
 * Принимает кадры камеры, обрабатывает их шейдером и отдаёт в превью и запись видео —
 * поэтому фильтры, AI-красота и эффекты попадают прямо в записанный ролик.
 */
class GlEffectProcessor : SurfaceProcessor {

    @Volatile
    var params: EffectParams = EffectParams()

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
                "uGain", "uLift", "uVignette", "uFade", "uLog", "uTime", "uAspect", "uEffect"
            ).forEach { uniforms[it] = GLES20.glGetUniformLocation(program, it) }
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
            if (surfaceTexture === st) surfaceTexture = null
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

        for ((out, egl) in outputs) {
            if (!EGL14.eglMakeCurrent(display, egl, egl, context)) continue
            out.updateTransformMatrix(outMatrix, texMatrix)
            val size = out.size
            GLES20.glViewport(0, 0, size.width, size.height)
            GLES20.glUseProgram(program)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glUniform1i(u("uTex"), 0)
            GLES20.glUniformMatrix4fv(u("uTexMatrix"), 1, false, outMatrix, 0)
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

            GLES20.glEnableVertexAttribArray(aPosition)
            GLES20.glVertexAttribPointer(aPosition, 2, GLES20.GL_FLOAT, false, 0, vertices)
            GLES20.glEnableVertexAttribArray(aTexCoord)
            GLES20.glVertexAttribPointer(aTexCoord, 2, GLES20.GL_FLOAT, false, 0, texCoords)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

            EGLExt.eglPresentationTimeANDROID(display, egl, st.timestamp)
            EGL14.eglSwapBuffers(display, egl)
        }
    }

    private fun u(name: String): Int = uniforms[name] ?: -1

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
