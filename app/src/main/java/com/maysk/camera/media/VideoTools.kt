package com.maysk.camera.media

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import com.maysk.camera.ai.ImageOps
import com.maysk.camera.data.AudioMode
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** Кодирует последовательность кадров в MP4 (H.264). Используется для таймлапса. */
class TimelapseEncoder(private val out: File, private val fps: Int = 30, bitrate: Int = 12_000_000) {
    private val w = 1280
    private val h = 960
    private val codec: MediaCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
    private val muxer = MediaMuxer(out.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
    private var track = -1
    private var started = false
    private var frames = 0L
    private var orientationSet = false
    private val info = MediaCodec.BufferInfo()

    init {
        val f = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, w, h).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }
        codec.configure(f, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()
    }

    val frameCount: Long get() = frames

    fun addFrame(src: Bitmap) {
        var bmp = src
        if (bmp.height > bmp.width) {
            if (!orientationSet) { muxer.setOrientationHint(90); orientationSet = true }
            bmp = ImageOps.rotate(bmp, 270)
        }
        orientationSet = true
        val scaled = Bitmap.createScaledBitmap(bmp, w, h, true)
        val px = IntArray(w * h)
        scaled.getPixels(px, 0, w, 0, 0, w, h)
        val idx = codec.dequeueInputBuffer(100_000)
        if (idx < 0) return
        val img = codec.getInputImage(idx) ?: return
        val yP = img.planes[0]; val uP = img.planes[1]; val vP = img.planes[2]
        val yb = yP.buffer; val ub = uP.buffer; val vb = vP.buffer
        for (y in 0 until h) for (x in 0 until w) {
            val c = px[y * w + x]
            val r = (c shr 16) and 255; val g = (c shr 8) and 255; val b = c and 255
            yb.put(y * yP.rowStride + x * yP.pixelStride, (((66 * r + 129 * g + 25 * b + 128) shr 8) + 16).toByte())
            if (y % 2 == 0 && x % 2 == 0) {
                val u = ((-38 * r - 74 * g + 112 * b + 128) shr 8) + 128
                val v = ((112 * r - 94 * g - 18 * b + 128) shr 8) + 128
                ub.put((y / 2) * uP.rowStride + (x / 2) * uP.pixelStride, u.coerceIn(0, 255).toByte())
                vb.put((y / 2) * vP.rowStride + (x / 2) * vP.pixelStride, v.coerceIn(0, 255).toByte())
            }
        }
        codec.queueInputBuffer(idx, 0, w * h * 3 / 2, frames * 1_000_000L / fps, 0)
        frames++
        drain(false)
    }

    private fun drain(eos: Boolean) {
        var tries = 0
        while (true) {
            val o = codec.dequeueOutputBuffer(info, if (eos) 10_000 else 0)
            when {
                o == MediaCodec.INFO_TRY_AGAIN_LATER -> { if (!eos || ++tries > 300) return }
                o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    track = muxer.addTrack(codec.outputFormat); muxer.start(); started = true
                }
                o >= 0 -> {
                    val buf = codec.getOutputBuffer(o)
                    if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
                    if (info.size > 0 && started && buf != null) {
                        buf.position(info.offset); buf.limit(info.offset + info.size)
                        muxer.writeSampleData(track, buf, info)
                    }
                    codec.releaseOutputBuffer(o, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                }
            }
        }
    }

    fun finish(): Boolean {
        val idx = codec.dequeueInputBuffer(100_000)
        if (idx >= 0) codec.queueInputBuffer(idx, 0, 0, frames * 1_000_000L / fps, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
        drain(true)
        runCatching { codec.stop() }; codec.release()
        val ok = started && frames > 0
        runCatching { if (started) muxer.stop() }; runCatching { muxer.release() }
        return ok
    }
}

object VideoTools {

    private fun copyTrackFormatRotation(fmt: MediaFormat): Int =
        if (fmt.containsKey(MediaFormat.KEY_ROTATION)) fmt.getInteger(MediaFormat.KEY_ROTATION) else 0

    /** Замедление видео: растягиваем временные метки кадров (звук убирается). */
    fun slowMotion(context: Context, input: Uri, output: File, factor: Float): Boolean = runCatching {
        val ex = MediaExtractor()
        ex.setDataSource(context, input, null)
        var vTrack = -1
        for (i in 0 until ex.trackCount) {
            if (ex.getTrackFormat(i).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true) { vTrack = i; break }
        }
        if (vTrack < 0) return false
        ex.selectTrack(vTrack)
        val fmt = ex.getTrackFormat(vTrack)
        val muxer = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        muxer.setOrientationHint(copyTrackFormatRotation(fmt))
        val t = muxer.addTrack(fmt)
        muxer.start()
        val buf = ByteBuffer.allocate(8 * 1024 * 1024)
        val info = MediaCodec.BufferInfo()
        while (true) {
            val size = ex.readSampleData(buf, 0)
            if (size < 0) break
            info.offset = 0; info.size = size
            info.presentationTimeUs = (ex.sampleTime * factor).toLong()
            info.flags = if (ex.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
            muxer.writeSampleData(t, buf, info)
            ex.advance()
        }
        muxer.stop(); muxer.release(); ex.release()
        true
    }.getOrDefault(false)

    // region AI Audio
    /** Биквадратный фильтр (RBJ). */
    private class Biquad(type: Int, freq: Double, sr: Int, q: Double = 0.707, gainDb: Double = 0.0) {
        private var b0 = 0.0; private var b1 = 0.0; private var b2 = 0.0; private var a1 = 0.0; private var a2 = 0.0
        private var x1 = 0.0; private var x2 = 0.0; private var y1 = 0.0; private var y2 = 0.0

        init {
            val w0 = 2 * PI * freq / sr
            val alpha = sin(w0) / (2 * q)
            val cw = cos(w0)
            val a = Math.pow(10.0, gainDb / 40)
            val n: DoubleArray = when (type) {
                0 -> doubleArrayOf((1 + cw) / 2, -(1 + cw), (1 + cw) / 2, 1 + alpha, -2 * cw, 1 - alpha) // high-pass
                1 -> doubleArrayOf((1 - cw) / 2, 1 - cw, (1 - cw) / 2, 1 + alpha, -2 * cw, 1 - alpha)    // low-pass
                else -> doubleArrayOf(1 + alpha * a, -2 * cw, 1 - alpha * a, 1 + alpha / a, -2 * cw, 1 - alpha / a) // peak
            }
            b0 = n[0] / n[3]; b1 = n[1] / n[3]; b2 = n[2] / n[3]; a1 = n[4] / n[3]; a2 = n[5] / n[3]
        }

        fun process(x: Double): Double {
            val y = b0 * x + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
            x2 = x1; x1 = x; y2 = y1; y1 = y
            return y
        }
    }

    /** Цепочка обработки звука для режима. */
    private class AudioChain(mode: AudioMode, sr: Int, private val channels: Int) {
        private val filters: List<List<Biquad>> = List(channels) {
            when (mode) {
                AudioMode.NORMAL -> emptyList()
                AudioMode.AROUND -> listOf(Biquad(0, 120.0, sr))
                AudioMode.VOICE -> listOf(Biquad(0, 110.0, sr), Biquad(0, 110.0, sr), Biquad(1, 8000.0, sr), Biquad(2, 2500.0, sr, 1.0, 3.0))
                AudioMode.STUDIO -> listOf(Biquad(0, 90.0, sr), Biquad(1, 12000.0, sr), Biquad(2, 3000.0, sr, 0.9, 2.5), Biquad(2, 250.0, sr, 1.0, -2.0))
            }
        }
        private val gateOn = mode == AudioMode.VOICE || mode == AudioMode.STUDIO
        private val gateThr = if (mode == AudioMode.STUDIO) 0.02 else 0.012
        private val compThr = if (mode == AudioMode.STUDIO) 0.18 else 0.25
        private val ratio = if (mode == AudioMode.STUDIO) 4.0 else 3.0
        private val makeup = if (mode == AudioMode.NORMAL || mode == AudioMode.AROUND) 1.0 else 1.6
        private var env = 0.0
        private var gateGain = 1.0
        private val att = exp(-1.0 / (0.005 * sr))
        private val rel = exp(-1.0 / (0.15 * sr))

        fun process(pcm: ShortArray) {
            var i = 0
            while (i < pcm.size) {
                var peak = 0.0
                val frame = DoubleArray(channels)
                for (ch in 0 until channels) {
                    var s = pcm[i + ch] / 32768.0
                    for (f in filters[ch]) s = f.process(s)
                    frame[ch] = s
                    peak = max(peak, abs(s))
                }
                env = if (peak > env) att * env + (1 - att) * peak else rel * env + (1 - rel) * peak
                var g = 1.0
                if (gateOn) {
                    val target = if (env < gateThr) 0.15 else 1.0
                    gateGain += (target - gateGain) * (if (target > gateGain) 0.01 else 0.0005)
                    g *= gateGain
                }
                if (env > compThr) g *= Math.pow(env / compThr, 1.0 / ratio - 1.0)
                g *= makeup
                for (ch in 0 until channels) {
                    val v = (frame[ch] * g).coerceIn(-0.98, 0.98)
                    pcm[i + ch] = (v * 32767).toInt().toShort()
                }
                i += channels
            }
        }
    }

    /**
     * AI Audio: убирает ветер/шум, усиливает голос. Декодирует звук ролика, обрабатывает,
     * кодирует обратно в AAC и собирает новый MP4 с исходной видеодорожкой.
     */
    fun enhanceAudio(context: Context, input: Uri, output: File, mode: AudioMode): Boolean {
        if (mode == AudioMode.NORMAL) return false
        return runCatching { enhanceAudioImpl(context, input, output, mode) }.getOrDefault(false)
    }

    private fun enhanceAudioImpl(context: Context, input: Uri, output: File, mode: AudioMode): Boolean {
        val ex = MediaExtractor().apply { setDataSource(context, input, null) }
        var aTrack = -1; var vTrack = -1
        for (i in 0 until ex.trackCount) {
            val m = ex.getTrackFormat(i).getString(MediaFormat.KEY_MIME) ?: continue
            if (m.startsWith("audio/") && aTrack < 0) aTrack = i
            if (m.startsWith("video/") && vTrack < 0) vTrack = i
        }
        if (aTrack < 0 || vTrack < 0) { ex.release(); return false }
        val aFmt = ex.getTrackFormat(aTrack)
        ex.selectTrack(aTrack)
        val decoder = MediaCodec.createDecoderByType(aFmt.getString(MediaFormat.KEY_MIME)!!)
        decoder.configure(aFmt, null, null, 0)
        decoder.start()

        var sr = aFmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        var ch = aFmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        var encoder: MediaCodec? = null
        var chain: AudioChain? = null
        val encoded = ArrayList<Pair<ByteArray, MediaCodec.BufferInfo>>()
        var encFormat: MediaFormat? = null
        var samplesQueued = 0L
        val pending = java.util.ArrayDeque<ByteArray>()
        var pendingOffset = 0

        fun ensureEncoder() {
            if (encoder != null) return
            val f = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sr, ch).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE, 160_000)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 64 * 1024)
            }
            encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC).apply {
                configure(f, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE); start()
            }
            chain = AudioChain(mode, sr, ch)
        }

        val info = MediaCodec.BufferInfo()
        val encInfo = MediaCodec.BufferInfo()
        var inputDone = false; var decodeDone = false; var encodeDone = false
        var eosSent = false
        var guard = 0

        fun drainEncoder() {
            val enc = encoder ?: return
            while (true) {
                val o = enc.dequeueOutputBuffer(encInfo, 0)
                if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) { encFormat = enc.outputFormat; continue }
                if (o < 0) return
                val b = enc.getOutputBuffer(o)
                if (encInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0 && encInfo.size > 0 && b != null) {
                    val arr = ByteArray(encInfo.size)
                    b.position(encInfo.offset); b.get(arr)
                    val bi = MediaCodec.BufferInfo().apply { set(0, encInfo.size, encInfo.presentationTimeUs, encInfo.flags) }
                    encoded += arr to bi
                }
                enc.releaseOutputBuffer(o, false)
                if (encInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) { encodeDone = true; return }
            }
        }

        fun feedEncoder() {
            val enc = encoder ?: return
            while (pending.isNotEmpty()) {
                val idx = enc.dequeueInputBuffer(5_000)
                if (idx < 0) return
                val buf = enc.getInputBuffer(idx)!!
                buf.clear()
                val head = pending.peek()!!
                val n = min(buf.remaining(), head.size - pendingOffset)
                buf.put(head, pendingOffset, n)
                val pts = samplesQueued * 1_000_000L / sr
                samplesQueued += n / (2 * ch)
                enc.queueInputBuffer(idx, 0, n, pts, 0)
                pendingOffset += n
                if (pendingOffset >= head.size) { pending.poll(); pendingOffset = 0 }
            }
            if (decodeDone && !eosSent) {
                val idx = enc.dequeueInputBuffer(5_000)
                if (idx >= 0) {
                    enc.queueInputBuffer(idx, 0, 0, samplesQueued * 1_000_000L / sr, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                    eosSent = true
                }
            }
        }

        while (!encodeDone && guard++ < 2_000_000) {
            if (!inputDone) {
                val idx = decoder.dequeueInputBuffer(5_000)
                if (idx >= 0) {
                    val buf = decoder.getInputBuffer(idx)!!
                    val n = ex.readSampleData(buf, 0)
                    if (n < 0) {
                        decoder.queueInputBuffer(idx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inputDone = true
                    } else {
                        decoder.queueInputBuffer(idx, 0, n, ex.sampleTime, 0); ex.advance()
                    }
                }
            }
            if (!decodeDone) {
                val o = decoder.dequeueOutputBuffer(info, 5_000)
                if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val of = decoder.outputFormat
                    sr = of.getInteger(MediaFormat.KEY_SAMPLE_RATE); ch = of.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                } else if (o >= 0) {
                    ensureEncoder()
                    val b = decoder.getOutputBuffer(o)
                    if (b != null && info.size > 0) {
                        b.position(info.offset); b.limit(info.offset + info.size)
                        val sb = b.slice().order(ByteOrder.nativeOrder()).asShortBuffer()
                        val pcm = ShortArray(sb.remaining()); sb.get(pcm)
                        chain!!.process(pcm)
                        val bytes = ByteBuffer.allocate(pcm.size * 2).order(ByteOrder.nativeOrder())
                        bytes.asShortBuffer().put(pcm)
                        pending.add(bytes.array())
                    }
                    decoder.releaseOutputBuffer(o, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) { decodeDone = true; ensureEncoder() }
                }
            }
            feedEncoder()
            drainEncoder()
        }
        runCatching { decoder.stop() }; decoder.release()
        encoder?.let { runCatching { it.stop() }; it.release() }
        ex.release()
        val ef = encFormat ?: return false

        // Сборка: видео (копия) + новый звук
        val vex = MediaExtractor().apply { setDataSource(context, input, null) }
        vex.selectTrack(vTrack)
        val vFmt = vex.getTrackFormat(vTrack)
        val muxer = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        muxer.setOrientationHint(copyTrackFormatRotation(vFmt))
        val vt = muxer.addTrack(vFmt)
        val at = muxer.addTrack(ef)
        muxer.start()
        val buf = ByteBuffer.allocate(8 * 1024 * 1024)
        val vi = MediaCodec.BufferInfo()
        var ai = 0
        while (true) {
            val n = vex.readSampleData(buf, 0)
            if (n < 0) break
            val t = vex.sampleTime
            // чередуем звук и видео по времени
            while (ai < encoded.size && encoded[ai].second.presentationTimeUs <= t) {
                val (arr, bi) = encoded[ai++]
                muxer.writeSampleData(at, ByteBuffer.wrap(arr), bi)
            }
            vi.set(0, n, t, if (vex.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
            muxer.writeSampleData(vt, buf, vi)
            vex.advance()
        }
        while (ai < encoded.size) {
            val (arr, bi) = encoded[ai++]
            muxer.writeSampleData(at, ByteBuffer.wrap(arr), bi)
        }
        muxer.stop(); muxer.release(); vex.release()
        return true
    }
    // endregion

    /** Склейка панорамы из кадров, снятых при движении слева направо. */
    fun stitchPanorama(frames: List<Bitmap>): Bitmap? {
        if (frames.isEmpty()) return null
        if (frames.size == 1) return frames[0]
        val h = frames[0].height
        val list = frames.map { if (it.height == h) it else Bitmap.createScaledBitmap(it, it.width * h / it.height, h, true) }
        val offsets = mutableListOf(0 to 0)
        for (i in 1 until list.size) {
            val (dx, dy) = horizontalShift(list[i - 1], list[i])
            val prev = offsets.last()
            offsets += (prev.first + dx) to (prev.second + dy)
        }
        // отбрасываем кадры без движения
        val minY = offsets.minOf { it.second }; val maxY = offsets.maxOf { it.second }
        val totalW = offsets.last().first + list.last().width
        val outH = h - (maxY - minY)
        if (totalW <= 0 || outH <= h / 2) return null
        val out = Bitmap.createBitmap(totalW, outH, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(out)
        val paint = android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG)
        for ((i, bmp) in list.withIndex()) {
            val (ox, oy) = offsets[i]
            val top = (oy - maxY).toFloat()
            if (i == 0) { canvas.drawBitmap(bmp, ox.toFloat(), top, paint); continue }
            val overlap = (offsets[i - 1].first + list[i - 1].width) - ox
            val feather = max(1, min(overlap, bmp.width / 3))
            val faded = bmp.copy(Bitmap.Config.ARGB_8888, true)
            val c = android.graphics.Canvas(faded)
            val p = android.graphics.Paint().apply {
                shader = android.graphics.LinearGradient(0f, 0f, feather.toFloat(), 0f, 0x00000000, 0xFF000000.toInt(), android.graphics.Shader.TileMode.CLAMP)
                xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.DST_IN)
            }
            c.drawRect(0f, 0f, feather.toFloat(), faded.height.toFloat(), p)
            canvas.drawBitmap(faded, ox.toFloat(), top, paint)
        }
        return out
    }

    private fun horizontalShift(a: Bitmap, b: Bitmap): Pair<Int, Int> {
        val sw = 240
        val s = sw.toFloat() / a.width
        val sh = max(8, (a.height * s).toInt())
        val pa = IntArray(sw * sh); val pb = IntArray(sw * sh)
        Bitmap.createScaledBitmap(a, sw, sh, true).getPixels(pa, 0, sw, 0, 0, sw, sh)
        Bitmap.createScaledBitmap(b, sw, sh, true).getPixels(pb, 0, sw, 0, 0, sw, sh)
        fun l(c: Int) = ((c shr 16) and 255) + ((c shr 8) and 255) * 2 + (c and 255)
        var best = Double.MAX_VALUE; var bx = sw / 3; var by = 0
        val maxDy = sh / 20
        for (dx in sw / 10 until sw * 9 / 10) for (dy in -maxDy..maxDy) {
            var sum = 0.0; var n = 0
            var y = maxDy
            while (y < sh - maxDy) {
                var x = dx
                while (x < sw) {
                    val xb = x - dx; val yb = y - dy
                    if (yb in 0 until sh) { sum += abs(l(pa[y * sw + x]) - l(pb[yb * sw + xb])); n++ }
                    x += 2
                }
                y += 2
            }
            if (n > 50) {
                val score = sum / n
                if (score < best) { best = score; bx = dx; by = dy }
            }
        }
        return Pair((bx / s).toInt(), (by / s).toInt())
    }

    @Suppress("unused")
    private fun rms(a: ShortArray): Double = sqrt(a.fold(0.0) { acc, v -> acc + v * v.toDouble() } / max(1, a.size))
}
