package com.strym.app.capture

/**
 * The slice of the streaming session the capture pipelines feed: the H.264
 * camera path ([CameraStreamer]) and the AAC audio path ([AudioRecorder]).
 * Implemented by the [com.strym.app.session.StreamController], which forwards
 * into the core. All calls are no-ops without a live session and never block —
 * the core copies into its bounded buffer and returns.
 */
interface MediaIngest {
    /** Hand over codec configuration records (SPS/PPS, ASC) for the muxer. */
    fun configureCodecs(avcDecoderConfig: ByteArray?, audioSpecificConfig: ByteArray?)

    fun pushVideo(ptsMs: Long, isKeyframe: Boolean, annexB: ByteArray)

    fun pushAudio(ptsMs: Long, aac: ByteArray)
}
