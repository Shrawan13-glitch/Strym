package com.strym.app.capture

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import java.util.concurrent.atomic.AtomicBoolean

private const val TAG = "StrymVideoEncoder"
private const val MIME_AVC = "video/avc"
private const val I_FRAME_INTERVAL_S = 2
private const val KEY_CSD_0 = "csd-0"
private const val KEY_CSD_1 = "csd-1"

/** First [n] bytes of [this] as hex, for diagnosing malformed encoder output. */
private fun ByteArray.hexPrefix(n: Int): String =
    take(minOf(n, size)).joinToString(" ") { "%02X".format(it.toInt() and 0xFF) }

class VideoEncoderException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * H.264 encoder fed directly by the camera through its input surface
 * (zero-copy: no ImageProxy, no GL round-trip).
 *
 * Runs MediaCodec in async mode on a dedicated thread. Output buffers are
 * converted AVCC → Annex B (unless the encoder already emits Annex B) and
 * handed to [Listener.onFrame] on that thread, so pushing into the core never
 * touches the camera or main threads. Low latency by construction: CBR, 2 s
 * IDR interval, no B-frames.
 */
class VideoEncoder(private val listener: Listener, private val clock: SessionClock) {

    interface Listener {
        fun onCodecConfig(avcDecoderConfig: ByteArray)

        fun onFrame(ptsMs: Long, isKeyframe: Boolean, annexB: ByteArray)

        fun onError(message: String)
    }

    data class Config(
        val width: Int,
        val height: Int,
        val framerate: Float,
        val bitrateBps: Int,
    )

    private var thread: HandlerThread? = null
    private var codec: MediaCodec? = null
    private var surface: Surface? = null
    private var pts: VideoPts? = null
    private var working = ByteArray(0)
    private val codecConfigSent = AtomicBoolean(false)

    @Volatile
    private var broken = false

    val running: Boolean
        get() = codec != null

    /** The surface the camera renders into. Valid between [start] and [stop]. */
    fun inputSurface(): Surface = surface ?: throw VideoEncoderException("encoder not started")

    /**
     * Create, configure, and start the encoder. [codecName] comes from
     * [EncoderCapabilities]; pass null to probe. Throws
     * [VideoEncoderException] when the device cannot encode H.264.
     */
    fun start(config: Config, codecName: String? = null) {
        if (codec != null) return
        val format = buildFormat(config, highProfile = false)
        val name = codecName
            ?: MediaCodecList(MediaCodecList.REGULAR_CODECS).findEncoderForFormat(format)
            ?: throw VideoEncoderException("No H.264 encoder available on this device")
        val worker = HandlerThread("stry-encoder").also { it.start() }
        val handler = Handler(worker.looper)
        try {
            var created = MediaCodec.createByCodecName(name)
            created.setCallback(callback, handler)
            try {
                created.configure(buildFormat(config, highProfile = true), null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            } catch (rejected: RuntimeException) {
                Log.w(TAG, "High profile rejected ($rejected); configuring without a profile")
                created.release()
                created = MediaCodec.createByCodecName(name)
                created.setCallback(callback, handler)
                created.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            }
            val input = created.createInputSurface()
            created.start()
            codec = created
            surface = input
            thread = worker
            pts = VideoPts(clock)
            codecConfigSent.set(false)
            broken = false
        } catch (e: RuntimeException) {
            worker.quitSafely()
            throw VideoEncoderException("Could not start the H.264 encoder: ${e.message}", e)
        }
    }

    /** Ask the encoder for an IDR now (viewer resync after a reconnect). */
    fun requestKeyframe() {
        val current = codec ?: return
        try {
            current.setParameters(Bundle().apply {
                putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0)
            })
        } catch (e: RuntimeException) {
            Log.w(TAG, "keyframe request failed", e)
        }
    }

    /** Idempotent teardown; safe from any thread. */
    fun stop() {
        broken = true
        codec?.let {
            runCatching { it.stop() }
            runCatching { it.release() }
        }
        codec = null
        surface?.release()
        surface = null
        thread?.quitSafely()
        thread = null
        pts?.reset()
        pts = null
    }

    private fun buildFormat(config: Config, highProfile: Boolean): MediaFormat =
        MediaFormat.createVideoFormat(MIME_AVC, config.width, config.height).apply {
            setInteger(MediaFormat.KEY_BIT_RATE, config.bitrateBps)
            setFloat(MediaFormat.KEY_FRAME_RATE, config.framerate)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, I_FRAME_INTERVAL_S)
            setInteger(
                MediaFormat.KEY_BITRATE_MODE,
                MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR,
            )
            setInteger(
                MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface,
            )
            if (Build.VERSION.SDK_INT >= 30) {
                setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0)
            }
            if (highProfile) {
                setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileHigh)
            }
        }

    private val callback = object : MediaCodec.Callback() {
        override fun onInputBufferAvailable(codec: MediaCodec, index: Int) {
            // Surface input: frames arrive from the camera, never from here.
        }

        override fun onOutputBufferAvailable(codec: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
            if (broken || codec !== this@VideoEncoder.codec) {
                runCatching { codec.releaseOutputBuffer(index, false) }
                return
            }
            try {
                if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                    emitCodecConfigFromBuffer(codec, index, info)
                    return
                }
                if (info.size <= 0) {
                    codec.releaseOutputBuffer(index, false)
                    return
                }
                val buffer = codec.getOutputBuffer(index)
                if (buffer == null) {
                    codec.releaseOutputBuffer(index, false)
                    return
                }
                if (working.size < info.size) working = ByteArray(info.size)
                buffer.position(info.offset)
                buffer.limit(info.offset + info.size)
                buffer.get(working, 0, info.size)
                codec.releaseOutputBuffer(index, false)

                if (!NalUnit.isAnnexB(working, info.size) && !NalUnit.avccToAnnexBInPlace(working, info.size)) {
                    Log.w(TAG, "dropping malformed AVCC output size=${info.size} head=${working.hexPrefix(8)}")
                    return
                }
                val tracker = pts ?: return
                val isKeyframe = info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0
                val wallMs = SystemClock.elapsedRealtimeNanos() / 1_000_000L
                listener.onFrame(tracker.next(info.presentationTimeUs, wallMs), isKeyframe, working.copyOf(info.size))
            } catch (e: RuntimeException) {
                Log.e(TAG, "encoder output handling failed size=${info.size} head=${working.hexPrefix(8)}", e)
                fail("encoder output handling failed: ${e.message}")
            }
        }

        override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) {
            emitCodecConfig(format)
        }

        override fun onError(codec: MediaCodec, e: MediaCodec.CodecException) {
            fail("encoder error: ${e.diagnosticInfo}")
        }
    }

    private fun emitCodecConfig(format: MediaFormat) {
        if (codecConfigSent.get()) return
        val config = AvcDecoderConfig.fromCsd(
            format.getByteBuffer(KEY_CSD_0),
            format.getByteBuffer(KEY_CSD_1),
        ) ?: return
        if (codecConfigSent.compareAndSet(false, true)) {
            listener.onCodecConfig(config)
        }
    }

    private fun emitCodecConfigFromBuffer(codec: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
        try {
            if (!codecConfigSent.get() && info.size > 0) {
                codec.getOutputBuffer(index)?.let { buffer ->
                    val csd = ByteArray(info.size)
                    buffer.position(info.offset)
                    buffer.limit(info.offset + info.size)
                    buffer.get(csd)
                    AvcDecoderConfig.fromCsd(java.nio.ByteBuffer.wrap(csd), null)?.let { config ->
                        if (codecConfigSent.compareAndSet(false, true)) {
                            listener.onCodecConfig(config)
                        }
                    }
                }
            }
        } catch (e: RuntimeException) {
            Log.w(TAG, "csd buffer unreadable", e)
        } finally {
            runCatching { codec.releaseOutputBuffer(index, false) }
        }
    }

    private fun fail(message: String) {
        if (broken) return
        broken = true
        Log.e(TAG, message)
        listener.onError(message)
    }
}
