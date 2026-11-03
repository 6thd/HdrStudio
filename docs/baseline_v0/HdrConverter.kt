package com.mojahed.hdrstudio

import android.content.Context
import android.graphics.SurfaceTexture
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
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
import android.view.Surface
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max

/**
 * SDR -> HDR10 (HEVC Main10, BT.2020, PQ/ST2084) converter.
 *
 * Pipeline:  MediaExtractor -> MediaCodec decoder -> SurfaceTexture -> GL shader
 *            (detail + colour + inverse tone mapping + PQ) -> 10-bit EGL surface
 *            -> MediaCodec HEVC Main10 encoder -> MediaMuxer (+ audio passthrough)
 */
class HdrConverter(private val context: Context) {

    class Params(
        val expansion: Float,   // 0..1  HDR highlight expansion
        val saturation: Float,  // 1.0 = unchanged
        val sharpness: Float,   // 0..1.5 local detail
        val contrast: Float,    // 0..0.5 S-curve
        val bitsPerPixel: Float // bitrate = w*h*fps*bpp
    )

    class Result(val width: Int, val height: Int, val fps: Int, val bitrate: Int, val pqSurface: Boolean, val encoder: String)

    fun convert(src: Uri, dst: File, p: Params, cancel: AtomicBoolean, onProgress: (Float) -> Unit): Result {
        val extractor = MediaExtractor()
        val audioExtractor = MediaExtractor()
        var decoder: MediaCodec? = null
        var encoder: MediaCodec? = null
        var muxer: MediaMuxer? = null
        var renderer: GlRenderer? = null
        var muxerStarted = false
        try {
            extractor.setDataSource(context, src, null)
            audioExtractor.setDataSource(context, src, null)

            var vTrack = -1
            var aTrack = -1
            for (i in 0 until extractor.trackCount) {
                val mime = extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME) ?: continue
                if (vTrack < 0 && mime.startsWith("video/")) vTrack = i
                else if (aTrack < 0 && mime.startsWith("audio/")) aTrack = i
            }
            if (vTrack < 0) throw IllegalStateException("لا يوجد مسار فيديو في الملف")

            val vFormat = extractor.getTrackFormat(vTrack)
            val vMime = vFormat.getString(MediaFormat.KEY_MIME)!!

            if (vFormat.containsKey(MediaFormat.KEY_COLOR_TRANSFER)) {
                val t = vFormat.getInteger(MediaFormat.KEY_COLOR_TRANSFER)
                if (t == MediaFormat.COLOR_TRANSFER_ST2084 || t == MediaFormat.COLOR_TRANSFER_HLG) {
                    throw IllegalStateException("هذا الفيديو HDR أصلاً، التحويل مخصص لفيديو SDR")
                }
            }

            val srcW = vFormat.getInteger(MediaFormat.KEY_WIDTH)
            val srcH = vFormat.getInteger(MediaFormat.KEY_HEIGHT)
            val rotation = if (vFormat.containsKey(MediaFormat.KEY_ROTATION)) vFormat.getInteger(MediaFormat.KEY_ROTATION) else 0
            // We keep the raw (unrotated) frame and let the container carry the rotation.
            vFormat.setInteger(MediaFormat.KEY_ROTATION, 0)
            val durationUs = if (vFormat.containsKey(MediaFormat.KEY_DURATION)) max(1L, vFormat.getLong(MediaFormat.KEY_DURATION)) else 1L
            var fps = 30
            if (vFormat.containsKey(MediaFormat.KEY_FRAME_RATE)) {
                fps = try {
                    vFormat.getInteger(MediaFormat.KEY_FRAME_RATE)
                } catch (e: Exception) {
                    vFormat.getFloat(MediaFormat.KEY_FRAME_RATE).toInt()
                }
            }
            fps = fps.coerceIn(24, 60)
            val w = srcW and 1.inv()
            val h = srcH and 1.inv()

            // ---- Encoder (HEVC Main10) ----
            val (encName, caps) = findMain10Encoder(w, h)
                ?: throw IllegalStateException("هذا الجهاز لا يوفر ترميز HEVC 10-bit بالعتاد لهذه الدقة")
            var bitrate = (w.toLong() * h * fps * p.bitsPerPixel).toLong().coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            bitrate = caps.videoCapabilities.bitrateRange.clamp(bitrate)

            val profiles = mutableListOf<Int>()
            if (caps.profileLevels.any { it.profile == MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10HDR10 }) {
                profiles += MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10HDR10
            }
            profiles += MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10

            var lastErr: Exception? = null
            for (pf in profiles) {
                val enc = MediaCodec.createByCodecName(encName)
                try {
                    enc.configure(buildEncoderFormat(w, h, fps, bitrate, pf, caps), null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                    encoder = enc
                    break
                } catch (e: Exception) {
                    lastErr = e
                    enc.release()
                }
            }
            val enc = encoder ?: throw IllegalStateException("فشل إعداد الترميز: ${lastErr?.message}")
            val inputSurface = enc.createInputSurface()
            enc.start()

            // ---- GL + Decoder ----
            renderer = GlRenderer(inputSurface, w, h, srcW, srcH, p)
            renderer.setup()

            decoder = MediaCodec.createDecoderByType(vMime)
            decoder.configure(vFormat, renderer.decoderSurface, null, 0)
            decoder.start()
            extractor.selectTrack(vTrack)

            // ---- Muxer ----
            val mux = MediaMuxer(dst.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            muxer = mux
            mux.setOrientationHint(rotation)
            val aFormat: MediaFormat? = if (aTrack >= 0) audioExtractor.getTrackFormat(aTrack) else null
            if (aTrack >= 0) audioExtractor.selectTrack(aTrack)
            var vOut = -1
            var aOut = -1
            val audioBuf = ByteBuffer.allocateDirect(1 shl 20)
            val audioInfo = MediaCodec.BufferInfo()

            fun pumpAudio(untilUs: Long) {
                if (aOut < 0) return
                while (audioExtractor.sampleTrackIndex >= 0 && audioExtractor.sampleTime <= untilUs) {
                    audioBuf.clear()
                    val n = audioExtractor.readSampleData(audioBuf, 0)
                    if (n < 0) break
                    audioInfo.set(0, n, audioExtractor.sampleTime, audioExtractor.sampleFlags)
                    mux.writeSampleData(aOut, audioBuf, audioInfo)
                    audioExtractor.advance()
                }
            }

            val info = MediaCodec.BufferInfo()
            val encInfo = MediaCodec.BufferInfo()
            var extractorDone = false
            var decoderDone = false
            var encoderDone = false

            while (!encoderDone) {
                if (cancel.get()) throw CancellationException()

                if (!extractorDone) {
                    val i = decoder.dequeueInputBuffer(2_000)
                    if (i >= 0) {
                        val buf = decoder.getInputBuffer(i)!!
                        val n = extractor.readSampleData(buf, 0)
                        if (n < 0) {
                            decoder.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            extractorDone = true
                        } else {
                            decoder.queueInputBuffer(i, 0, n, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }

                if (!decoderDone) {
                    val o = decoder.dequeueOutputBuffer(info, 2_000)
                    if (o >= 0) {
                        val render = info.size > 0
                        val eos = (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                        decoder.releaseOutputBuffer(o, render)
                        if (render) {
                            renderer.awaitNewFrame()
                            renderer.draw(info.presentationTimeUs * 1000L)
                            onProgress((info.presentationTimeUs.toFloat() / durationUs).coerceIn(0f, 1f))
                        }
                        if (eos) {
                            enc.signalEndOfInputStream()
                            decoderDone = true
                        }
                    }
                }

                while (true) {
                    val e = enc.dequeueOutputBuffer(encInfo, if (decoderDone) 10_000 else 0)
                    if (e == MediaCodec.INFO_TRY_AGAIN_LATER) break
                    if (e == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        check(!muxerStarted) { "تغيّر صيغة الترميز بعد بدء الكتابة" }
                        val of = enc.outputFormat
                        of.setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT2020)
                        of.setInteger(MediaFormat.KEY_COLOR_TRANSFER, MediaFormat.COLOR_TRANSFER_ST2084)
                        of.setInteger(MediaFormat.KEY_COLOR_RANGE, MediaFormat.COLOR_RANGE_LIMITED)
                        if (!of.containsKey(MediaFormat.KEY_HDR_STATIC_INFO)) {
                            of.setByteBuffer(MediaFormat.KEY_HDR_STATIC_INFO, staticInfo())
                        }
                        vOut = mux.addTrack(of)
                        if (aFormat != null) {
                            aOut = try { mux.addTrack(aFormat) } catch (ex: Exception) { -1 }
                        }
                        mux.start()
                        muxerStarted = true
                    } else if (e >= 0) {
                        val data = enc.getOutputBuffer(e)!!
                        if ((encInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) encInfo.size = 0
                        if (encInfo.size > 0 && muxerStarted) {
                            data.position(encInfo.offset)
                            data.limit(encInfo.offset + encInfo.size)
                            mux.writeSampleData(vOut, data, encInfo)
                            pumpAudio(encInfo.presentationTimeUs)
                        }
                        enc.releaseOutputBuffer(e, false)
                        if ((encInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                            encoderDone = true
                            break
                        }
                    }
                }
            }
            pumpAudio(Long.MAX_VALUE)
            onProgress(1f)
            val result = Result(w, h, fps, bitrate, renderer.pqSurface, encName)
            mux.stop()
            muxerStarted = false
            return result
        } finally {
            runCatching { decoder?.stop() }
            runCatching { decoder?.release() }
            runCatching { encoder?.stop() }
            runCatching { encoder?.release() }
            runCatching { renderer?.release() }
            if (muxerStarted) runCatching { muxer?.stop() }
            runCatching { muxer?.release() }
            runCatching { extractor.release() }
            runCatching { audioExtractor.release() }
        }
    }

    // ------------------------------------------------------------------------

    private fun findMain10Encoder(w: Int, h: Int): Pair<String, MediaCodecInfo.CodecCapabilities>? {
        val infos = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
            .filter { it.isEncoder && it.supportedTypes.any { t -> t.equals("video/hevc", true) } }
            .sortedBy { if (it.isHardwareAccelerated) 0 else 1 }
        for (info in infos) {
            val caps = info.getCapabilitiesForType("video/hevc")
            val ok = caps.profileLevels.any {
                it.profile == MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10 ||
                    it.profile == MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10HDR10
            }
            if (ok && caps.videoCapabilities.isSizeSupported(w, h)) return info.name to caps
        }
        return null
    }

    private fun buildEncoderFormat(
        w: Int, h: Int, fps: Int, bitrate: Int, profile: Int, caps: MediaCodecInfo.CodecCapabilities
    ): MediaFormat {
        val f = MediaFormat.createVideoFormat("video/hevc", w, h)
        f.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
        f.setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
        f.setInteger(MediaFormat.KEY_FRAME_RATE, fps)
        f.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        f.setInteger(MediaFormat.KEY_PROFILE, profile)
        f.setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT2020)
        f.setInteger(MediaFormat.KEY_COLOR_TRANSFER, MediaFormat.COLOR_TRANSFER_ST2084)
        f.setInteger(MediaFormat.KEY_COLOR_RANGE, MediaFormat.COLOR_RANGE_LIMITED)
        f.setByteBuffer(MediaFormat.KEY_HDR_STATIC_INFO, staticInfo())
        if (caps.encoderCapabilities.isBitrateModeSupported(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR)) {
            f.setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR)
        }
        return f
    }

    /** CTA-861.3 static metadata: BT.2020 primaries, D65, 1000 nit peak, 0.005 nit black. */
    private fun staticInfo(): ByteBuffer {
        val b = ByteBuffer.allocateDirect(25).order(ByteOrder.LITTLE_ENDIAN)
        b.put(0.toByte())
        b.putShort(35400.toShort()); b.putShort(14600.toShort()) // R
        b.putShort(8500.toShort()); b.putShort(39850.toShort())  // G
        b.putShort(6550.toShort()); b.putShort(2300.toShort())   // B
        b.putShort(15635.toShort()); b.putShort(16450.toShort()) // white point
        b.putShort(1000.toShort())  // max display luminance (nits)
        b.putShort(50.toShort())    // min display luminance (0.0001 nit units)
        b.putShort(1000.toShort())  // MaxCLL
        b.putShort(400.toShort())   // MaxFALL
        b.flip()
        return b
    }

    // ------------------------------------------------------------------------

    private class GlRenderer(
        private val outSurface: Surface,
        private val outW: Int,
        private val outH: Int,
        private val srcW: Int,
        private val srcH: Int,
        private val p: Params
    ) {
        private var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
        private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
        private var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE
        private var program = 0
        private var texId = 0
        private val stMatrix = FloatArray(16)
        private val lock = Object()
        private var frameAvailable = false
        private lateinit var surfaceTexture: SurfaceTexture
        private lateinit var thread: HandlerThread
        lateinit var decoderSurface: Surface
        var pqSurface = false

        private val vertices: java.nio.FloatBuffer = ByteBuffer.allocateDirect(16 * 4)
            .order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
                put(floatArrayOf(-1f, -1f, 0f, 0f, 1f, -1f, 1f, 0f, -1f, 1f, 0f, 1f, 1f, 1f, 1f, 1f))
                position(0)
            }

        fun setup() {
            display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            val ver = IntArray(2)
            check(EGL14.eglInitialize(display, ver, 0, ver, 1)) { "EGL init failed" }

            val cfgAttr = intArrayOf(
                EGL14.EGL_RED_SIZE, 10, EGL14.EGL_GREEN_SIZE, 10, EGL14.EGL_BLUE_SIZE, 10, EGL14.EGL_ALPHA_SIZE, 2,
                EGL14.EGL_RENDERABLE_TYPE, EGLExt.EGL_OPENGL_ES3_BIT_KHR,
                EGLExt.EGL_RECORDABLE_ANDROID, 1,
                EGL14.EGL_NONE
            )
            val configs = arrayOfNulls<EGLConfig>(1)
            val num = IntArray(1)
            EGL14.eglChooseConfig(display, cfgAttr, 0, configs, 0, 1, num, 0)
            check(num[0] > 0) { "الجهاز لا يدعم سطح رسم 10-bit" }

            eglContext = EGL14.eglCreateContext(
                display, configs[0], EGL14.EGL_NO_CONTEXT,
                intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE), 0
            )
            // EGL_GL_COLORSPACE_KHR (0x309D) = EGL_GL_COLORSPACE_BT2020_PQ_EXT (0x3340)
            eglSurface = EGL14.eglCreateWindowSurface(
                display, configs[0], outSurface, intArrayOf(0x309D, 0x3340, EGL14.EGL_NONE), 0
            )
            pqSurface = eglSurface != EGL14.EGL_NO_SURFACE
            if (!pqSurface) {
                eglSurface = EGL14.eglCreateWindowSurface(display, configs[0], outSurface, intArrayOf(EGL14.EGL_NONE), 0)
            }
            check(eglSurface != EGL14.EGL_NO_SURFACE) { "فشل إنشاء سطح الإخراج" }
            check(EGL14.eglMakeCurrent(display, eglSurface, eglSurface, eglContext)) { "eglMakeCurrent failed" }

            program = buildProgram(VERTEX, FRAGMENT)
            val tex = IntArray(1)
            GLES20.glGenTextures(1, tex, 0)
            texId = tex[0]
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texId)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

            thread = HandlerThread("frame-callback").also { it.start() }
            surfaceTexture = SurfaceTexture(texId)
            surfaceTexture.setDefaultBufferSize(srcW, srcH)
            surfaceTexture.setOnFrameAvailableListener({
                synchronized(lock) { frameAvailable = true; lock.notifyAll() }
            }, Handler(thread.looper))
            decoderSurface = Surface(surfaceTexture)
        }

        fun awaitNewFrame() {
            val end = System.currentTimeMillis() + 8_000
            synchronized(lock) {
                while (!frameAvailable) {
                    lock.wait(200)
                    if (System.currentTimeMillis() > end) throw RuntimeException("انتهت مهلة انتظار إطار الفيديو")
                }
                frameAvailable = false
            }
            surfaceTexture.updateTexImage()
        }

        fun draw(ptsNanos: Long) {
            surfaceTexture.getTransformMatrix(stMatrix)
            GLES20.glViewport(0, 0, outW, outH)
            GLES20.glUseProgram(program)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texId)
            GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "uTex"), 0)
            GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(program, "uST"), 1, false, stMatrix, 0)
            GLES20.glUniform2f(GLES20.glGetUniformLocation(program, "uTexel"), 1f / srcW, 1f / srcH)
            GLES20.glUniform1f(GLES20.glGetUniformLocation(program, "uExpand"), p.expansion)
            GLES20.glUniform1f(GLES20.glGetUniformLocation(program, "uSat"), p.saturation)
            GLES20.glUniform1f(GLES20.glGetUniformLocation(program, "uSharp"), p.sharpness)
            GLES20.glUniform1f(GLES20.glGetUniformLocation(program, "uContrast"), p.contrast)

            val aPos = GLES20.glGetAttribLocation(program, "aPos")
            val aTex = GLES20.glGetAttribLocation(program, "aTex")
            vertices.position(0)
            GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 16, vertices)
            GLES20.glEnableVertexAttribArray(aPos)
            vertices.position(2)
            GLES20.glVertexAttribPointer(aTex, 2, GLES20.GL_FLOAT, false, 16, vertices)
            GLES20.glEnableVertexAttribArray(aTex)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

            EGLExt.eglPresentationTimeANDROID(display, eglSurface, ptsNanos)
            EGL14.eglSwapBuffers(display, eglSurface)
        }

        fun release() {
            runCatching { if (display != EGL14.EGL_NO_DISPLAY) EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT) }
            runCatching { if (eglSurface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, eglSurface) }
            runCatching { if (eglContext != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, eglContext) }
            runCatching { if (display != EGL14.EGL_NO_DISPLAY) EGL14.eglTerminate(display) }
            runCatching { decoderSurface.release() }
            runCatching { surfaceTexture.release() }
            runCatching { thread.quitSafely() }
            runCatching { outSurface.release() }
        }

        private fun buildProgram(vs: String, fs: String): Int {
            val v = compile(GLES20.GL_VERTEX_SHADER, vs)
            val f = compile(GLES20.GL_FRAGMENT_SHADER, fs)
            val prog = GLES20.glCreateProgram()
            GLES20.glAttachShader(prog, v)
            GLES20.glAttachShader(prog, f)
            GLES20.glLinkProgram(prog)
            val ok = IntArray(1)
            GLES20.glGetProgramiv(prog, GLES20.GL_LINK_STATUS, ok, 0)
            check(ok[0] == GLES20.GL_TRUE) { "Shader link failed: " + GLES20.glGetProgramInfoLog(prog) }
            return prog
        }

        private fun compile(type: Int, src: String): Int {
            val s = GLES20.glCreateShader(type)
            GLES20.glShaderSource(s, src)
            GLES20.glCompileShader(s)
            val ok = IntArray(1)
            GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, ok, 0)
            check(ok[0] == GLES20.GL_TRUE) { "Shader compile failed: " + GLES20.glGetShaderInfoLog(s) }
            return s
        }

        companion object {
            private const val VERTEX = """
                attribute vec4 aPos;
                attribute vec4 aTex;
                uniform mat4 uST;
                varying vec2 vTex;
                void main() {
                    gl_Position = aPos;
                    vTex = (uST * aTex).xy;
                }
            """

            // SDR (BT.709, gamma ~2.2) -> HDR10 (BT.2020 primaries, PQ).
            private const val FRAGMENT = """
                #extension GL_OES_EGL_image_external : require
                precision highp float;
                varying vec2 vTex;
                uniform samplerExternalOES uTex;
                uniform vec2 uTexel;
                uniform float uExpand;
                uniform float uSat;
                uniform float uSharp;
                uniform float uContrast;

                const vec3 L709 = vec3(0.2126, 0.7152, 0.0722);
                const vec3 L2020 = vec3(0.2627, 0.6780, 0.0593);
                const float SDR_WHITE = 203.0;
                const float PEAK = 1000.0;

                vec3 pq(vec3 y) {
                    vec3 ym = pow(y, vec3(0.1593017578125));
                    return pow((0.8359375 + 18.8515625 * ym) / (1.0 + 18.6875 * ym), vec3(78.84375));
                }

                void main() {
                    vec3 c = texture2D(uTex, vTex).rgb;

                    // 1) Local detail (limited unsharp mask, avoids halos)
                    vec3 n = texture2D(uTex, vTex + vec2(0.0, uTexel.y)).rgb;
                    vec3 s = texture2D(uTex, vTex - vec2(0.0, uTexel.y)).rgb;
                    vec3 e = texture2D(uTex, vTex + vec2(uTexel.x, 0.0)).rgb;
                    vec3 w = texture2D(uTex, vTex - vec2(uTexel.x, 0.0)).rgb;
                    vec3 blur = (n + s + e + w) * 0.25;
                    c += clamp(c - blur, -0.12, 0.12) * uSharp;
                    c = clamp(c, 0.0, 1.0);

                    // 2) Contrast S-curve
                    c = mix(c, c * c * (3.0 - 2.0 * c), uContrast);

                    // 3) Vibrance-style saturation (boosts muted colours more)
                    float luma = dot(c, L709);
                    float chroma = max(c.r, max(c.g, c.b)) - min(c.r, min(c.g, c.b));
                    float k = 1.0 + (uSat - 1.0) * (1.0 - 0.6 * chroma);
                    c = clamp(mix(vec3(luma), c, k), 0.0, 1.0);

                    // 4) Linearise and convert BT.709 -> BT.2020 primaries
                    vec3 lin = pow(c, vec3(2.2));
                    mat3 M = mat3(0.6274, 0.0691, 0.0164,
                                  0.3293, 0.9195, 0.0880,
                                  0.0433, 0.0114, 0.8956);
                    lin = M * lin;

                    // 5) Inverse tone mapping: lift highlights towards PEAK nits
                    float Y = clamp(dot(lin, L2020), 0.0, 1.0);
                    float gain = 1.0 + uExpand * (PEAK / SDR_WHITE - 1.0) * Y * Y * Y;
                    vec3 nits = lin * SDR_WHITE * gain;

                    // 6) PQ (ST 2084) encode
                    gl_FragColor = vec4(pq(clamp(nits / 10000.0, 0.0, 1.0)), 1.0);
                }
            """
        }
    }
}
