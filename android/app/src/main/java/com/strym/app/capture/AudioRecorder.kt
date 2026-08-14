package com.strym.app.capture

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaRecorder
import android.os.SystemClock
import android.util.Log
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicBoolean

private const val TAG = "StrymAudio"
private const val MIME_AAC = "audio/mp4a-latm"
private const val KEY_CSD_0 = "csd-0"
private const val SAMPLE_RATE_HZ = 48_000
private const val BITRATE_BPS = 128_000
private const val DEQUEUE_TIMEOUT_US = 10_000L
private const val MAX_INPUT_SIZE = 8_192
private const val AUDIO_HOLDBACK_MS = 200L

private data class HeldAudio(val dueMs: Long, val aac: ByteArray)

/**
 * Microphone → AAC encoder → session, the audio half of [MediaIngest].
 *
 * [AudioRecord] (48 kHz PCM16, stereo with a mono fallback — phone mics are
 * mono but the FLV tag the core emits is stereo) feeds a byte-buffer
 * MediaCodec AAC-LC encoder. Each read is stamped with the monotonic clock at
 * capture time, then the encoder's output is delayed by [AUDIO_HOLDBACK_MS] so
 * audio lands on the wire alongside video — video's pipeline (~130 ms) runs
 * far behind audio's (~20 ms), which is what let audio's dts race ahead and
 * trip the core's rebase. Both tracks rebase onto the same
 * [SessionClock.originMs]. Raw AAC frames go out (no ADTS — the core owns the
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

    private var pts: StreamPts? = null
    private val held = ArrayDeque<HeldAudio>()
    private val configSent = AtomicBoolean(false)
    private var audioLog = 0

    /** Start capture. Fatal failures are reported via [onError] immediately. */
    fun start(ingest: MediaIngest, onError: (String) -> Unit, clock: SessionClock) {
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
        pts = StreamPts(clock)
        held.clear()
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
        pts = null
        held.clear()
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
        // The mic delivers coarse PCM chunks (its buffer is many AAC frames
        // long), so feed the encoder exactly one AAC frame per queue to keep
        // output spacing uniform (chunk-boundary stamping was heard as rhythmic
        // gaps, the "cracking" voice). Each frame is stamped with the wall
        // clock at capture time, mapped from the read's wall timestamp by
        // sample offset, so audio tracks the wall at 1.0x instead of a sample
        // counter that drifts behind the camera clock and trips rebases.
        val bytesPerSample = record.channelCount * 2
        val frameBytes = 1024 * bytesPerSample
        val frameSamples = 1024
        val pending = ByteArray(frameBytes * 4)
        var pendingBytes = 0
        var totalSamplesFed = 0L
        var pendingStartSample = 0L
        val info = MediaCodec.BufferInfo()
        while (running) {
            val read = record.read(pending, pendingBytes, pending.size - pendingBytes)
            when {
                read > 0 -> {
                    // A blocking read returns samples captured up to when it
                    // returns, so take the wall stamp *after* the read; a stamp
                    // taken before it makes every frame early by the read
                    // duration, letting audio dts lag the video high-water and
                    // trip the core's rebase on every burst.
                    val nowUs = SystemClock.elapsedRealtimeNanos() / 1_000L
                    if (pendingBytes == 0) pendingStartSample = totalSamplesFed
                    totalSamplesFed += read / bytesPerSample
                    pendingBytes += read
                    while (pendingBytes >= frameBytes) {
                        val inIndex = codec.dequeueInputBuffer(DEQUEUE_TIMEOUT_US)
                        if (inIndex < 0) break
                        val buffer = codec.getInputBuffer(inIndex)
                        if (buffer != null) {
                            buffer.put(pending, 0, frameBytes)
                            val endSample = pendingStartSample + frameSamples
                            val backSamples = totalSamplesFed - endSample
                            val endWallUs = nowUs - backSamples * 1_000_000L / SAMPLE_RATE_HZ
                            codec.queueInputBuffer(inIndex, 0, frameBytes, endWallUs, 0)
                            pendingStartSample = endSample
                            System.arraycopy(pending, frameBytes, pending, 0, pendingBytes - frameBytes)
                            pendingBytes -= frameBytes
                        }
                    }
                }
                read == 0 -> {
                    // No samples yet (blocking read returned 0): try again next
                    // pass. Queuing a zero-length buffer without EOS is invalid
                    // for AAC and can corrupt the encoder output.
                }
                else -> return report("microphone read failed ($read)")
            }
            drainOutput(codec, info)
        }
    }

    private fun drainOutput(codec: MediaCodec, info: MediaCodec.BufferInfo) {
        while (true) {
            val outIndex = codec.dequeueOutputBuffer(info, 0)
            when {
                outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> emitConfig(codec.outputFormat)
                outIndex < 0 -> {
                    flushHeld()
                    return
                }
                else -> {
                    if (info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                        val buffer = codec.getOutputBuffer(outIndex)
                        if (buffer != null) {
                            buffer.position(info.offset)
                            buffer.limit(info.offset + info.size)
                            val aac = ByteArray(info.size)
                            buffer.get(aac)
                            hold(info.presentationTimeUs, aac)
                        }
                    }
                    codec.releaseOutputBuffer(outIndex, false)
                }
            }
        }
    }

    private fun hold(rawUs: Long, aac: ByteArray) {
        val captureMs = if (rawUs > 0) rawUs / 1_000L
        else SystemClock.elapsedRealtimeNanos() / 1_000_000L
        held.addLast(HeldAudio(captureMs + AUDIO_HOLDBACK_MS, aac))
        flushHeld()
    }

    private fun flushHeld() {
        val nowMs = SystemClock.elapsedRealtimeNanos() / 1_000_000L
        val pts = pts ?: return
        while (true) {
            val head = held.peekFirst() ?: return
            if (head.dueMs > nowMs) return
            held.removeFirst()
            val dts = pts.next(head.dueMs)
            if (audioLog++ % 25 == 0) {
                Log.d(TAG, "AUDIO dts=$dts due=${head.dueMs} wall=$nowMs")
            }
            ingest?.pushAudio(dts, head.aac)
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
