package com.strym.app.capture

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaRecorder
import android.os.SystemClock
import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean

private const val TAG = "StrymAudio"
private const val MIME_AAC = "audio/mp4a-latm"
private const val KEY_CSD_0 = "csd-0"
private const val SAMPLE_RATE_HZ = 48_000
private const val BITRATE_BPS = 128_000
private const val DEQUEUE_TIMEOUT_US = 10_000L
private const val MAX_INPUT_SIZE = 8_192

/**
 * Microphone → AAC encoder → session, the audio half of [MediaIngest].
 *
 * [AudioRecord] (48 kHz PCM16, stereo with a mono fallback — phone mics are
 * mono but the FLV tag the core emits is stereo) feeds a byte-buffer
 * MediaCodec AAC-LC encoder. Each read is stamped with the monotonic clock at
 * capture time, then [StreamPts] rebases the encoder's output to its own
 * origin — the same first-frame base the video track uses, so the core's
 * first-packet normalization sees a small constant skew instead of a
 * session-clock offset. Raw AAC frames go out (no ADTS — the core owns the
 * FLV header); the ASC from csd-0 is handed over once via
 * `configure_codecs(None, Some(asc))`.
 */
class AudioRecorder {

    @Volatile
    private var running = false

    private var thread: Thread? = null
    private var record: AudioRecord? = null
    private var codec: MediaCodec? = null
    private var ingest: MediaIngest? = null
    private var onError: ((String) -> Unit)? = null

    private val pts = StreamPts()
    private val configSent = AtomicBoolean(false)

    /** Start capture. Fatal failures are reported via [onError] immediately. */
    fun start(ingest: MediaIngest, onError: (String) -> Unit) {
        if (running) return
        this.ingest = ingest
        this.onError = onError
        val recorder = createAudioRecord()
        if (recorder == null) {
            report("Could not access the microphone")
            return
        }
        val encoder = createEncoder(recorder.channelCount)
        if (encoder == null) {
            recorder.release()
            report("No AAC encoder available on this device")
            return
        }
        record = recorder
        codec = encoder
        configSent.set(false)
        pts.reset()
        running = true
        thread = Thread({ encodeLoop() }, "stry-audio").also { it.start() }
    }

    /** Stop capture and release the recorder + codec. Idempotent, any thread. */
    fun stop() {
        if (!running && thread == null) return
        running = false
        runCatching { record?.stop() } // unblocks the pending read()
        thread?.join(1_000)
        thread = null
        record?.release()
        record = null
        codec?.stop()
        codec?.release()
        codec = null
        ingest = null
        onError = null
    }

    private fun encodeLoop() {
        val codec = codec ?: return
        val record = record ?: return
        try {
            codec.start()
        } catch (e: RuntimeException) {
            return report("Could not start the audio encoder: ${e.message}")
        }
        try {
            record.startRecording()
        } catch (e: RuntimeException) {
            return report("Could not start the microphone: ${e.message}")
        }
        val info = MediaCodec.BufferInfo()
        while (running) {
            val inIndex = codec.dequeueInputBuffer(DEQUEUE_TIMEOUT_US)
            if (inIndex >= 0) {
                val buffer = codec.getInputBuffer(inIndex)
                if (buffer != null) {
                    val read = record.read(buffer, buffer.remaining())
                    val nowUs = SystemClock.elapsedRealtimeNanos() / 1_000L
                    when {
                        read > 0 -> codec.queueInputBuffer(inIndex, 0, read, nowUs, 0)
                        read == 0 -> {
                            // No samples yet (blocking read returned 0): skip the
                            // queue and reuse the slot next pass. Queuing a
                            // zero-length buffer without EOS is invalid for AAC
                            // and can corrupt the encoder output.
                        }
                        else -> return report("microphone read failed ($read)")
                    }
                }
            }
            drainOutput(codec, info)
        }
    }

    private fun drainOutput(codec: MediaCodec, info: MediaCodec.BufferInfo) {
        while (true) {
            val outIndex = codec.dequeueOutputBuffer(info, 0)
            when {
                outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> emitConfig(codec.outputFormat)
                outIndex < 0 -> return
                else -> {
                    if (info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                        val buffer = codec.getOutputBuffer(outIndex)
                        if (buffer != null) {
                            buffer.position(info.offset)
                            buffer.limit(info.offset + info.size)
                            val aac = ByteArray(info.size)
                            buffer.get(aac)
                            ingest?.pushAudio(pts.next(info.presentationTimeUs / 1_000L), aac)
                        }
                    }
                    codec.releaseOutputBuffer(outIndex, false)
                }
            }
        }
    }

    private fun emitConfig(format: MediaFormat) {
        if (!configSent.compareAndSet(false, true)) return
        val csd = runCatching { format.getByteBuffer(KEY_CSD_0) }.getOrNull()
        if (csd == null) {
            configSent.set(false)
            return
        }
        val asc = ByteArray(csd.remaining())
        csd.get(asc)
        ingest?.configureCodecs(null, asc)
    }

    private fun createAudioRecord(): AudioRecord? {
        for (channelConfig in intArrayOf(AudioFormat.CHANNEL_IN_STEREO, AudioFormat.CHANNEL_IN_MONO)) {
            val minBuffer = AudioRecord.getMinBufferSize(
                SAMPLE_RATE_HZ,
                channelConfig,
                AudioFormat.ENCODING_PCM_16BIT,
            )
            if (minBuffer <= 0) continue
            val bufferBytes = (minBuffer * 4).coerceAtLeast(MAX_INPUT_SIZE)
            val rec = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE_HZ,
                channelConfig,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferBytes,
            )
            if (rec.state == AudioRecord.STATE_INITIALIZED) return rec
            rec.release()
        }
        return null
    }

    private fun createEncoder(channelCount: Int): MediaCodec? {
        val full = MediaFormat.createAudioFormat(MIME_AAC, SAMPLE_RATE_HZ, channelCount).apply {
            setInteger(MediaFormat.KEY_BIT_RATE, BITRATE_BPS)
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, MAX_INPUT_SIZE)
        }
        val first = runCatching { MediaCodec.createEncoderByType(MIME_AAC) }.getOrNull() ?: return null
        try {
            first.configure(full, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            return first
        } catch (rejected: RuntimeException) {
            Log.w(TAG, "AAC profile config rejected ($rejected); retrying minimal")
            first.release()
        }
        val minimal = MediaFormat.createAudioFormat(MIME_AAC, SAMPLE_RATE_HZ, channelCount).apply {
            setInteger(MediaFormat.KEY_BIT_RATE, BITRATE_BPS)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, MAX_INPUT_SIZE)
        }
        val fallback = runCatching { MediaCodec.createEncoderByType(MIME_AAC) }.getOrNull() ?: return null
        return try {
            fallback.configure(minimal, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            fallback
        } catch (e: RuntimeException) {
            fallback.release()
            null
        }
    }

    private fun report(message: String) {
        Log.e(TAG, message)
        onError?.invoke(message)
    }
}
