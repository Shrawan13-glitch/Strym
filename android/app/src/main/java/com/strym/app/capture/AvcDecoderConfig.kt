package com.strym.app.capture

import java.nio.ByteBuffer

/**
 * The ISO/IEC 14496-15 `AVCDecoderConfigurationRecord` — the H.264
 * sequence-header payload handed to the core via `configure_codecs`.
 *
 * The layout mirrors the core's own `build_avcc` byte for byte, so the
 * record always matches what the FLV muxer expects: profile and level read
 * from the SPS, 4-byte NAL lengths, exactly one SPS and one PPS.
 */
object AvcDecoderConfig {

    fun build(sps: ByteArray, pps: ByteArray): ByteArray {
        val out = ByteArray(6 + sps.size + pps.size)
        out[0] = 0x01 // configurationVersion
        out[1] = sps.getOrElse(1) { 0x64 } // AVCProfileIndication
        out[2] = sps.getOrElse(2) { 0 } // profile_compatibility
        out[3] = sps.getOrElse(3) { 0x1F } // AVCLevelIndication
        out[4] = 0xFF.toByte() // lengthSizeMinusOne = 3 -> 4-byte NAL lengths
        out[5] = 0xE1.toByte() // numOfSequenceParameterSets = 1
        var pos = 6
        out[pos] = (sps.size shr 8).toByte()
        out[pos + 1] = sps.size.toByte()
        pos += 2
        sps.copyInto(out, pos)
        pos += sps.size
        out[pos] = 0x01 // numOfPictureParameterSets
        out[pos + 1] = (pps.size shr 8).toByte()
        out[pos + 2] = pps.size.toByte()
        pos += 3
        pps.copyInto(out, pos)
        return out
    }

    /**
     * Build the record from MediaCodec's AVC csd buffers. Encoders deliver
     * SPS and PPS in Annex B form, usually both in csd-0 but sometimes split
     * across csd-0/csd-1. Returns null when either unit is missing.
     */
    fun fromCsd(csd0: ByteBuffer?, csd1: ByteBuffer?): ByteArray? {
        val merged = merge(csd0, csd1) ?: return null
        var sps: ByteArray? = null
        var pps: ByteArray? = null
        NalUnit.forEachAnnexBNal(merged, merged.size) { offset, length ->
            when (NalUnit.type(merged[offset])) {
                NalUnit.TYPE_SPS -> if (sps == null) sps = merged.copyOfRange(offset, offset + length)
                NalUnit.TYPE_PPS -> if (pps == null) pps = merged.copyOfRange(offset, offset + length)
            }
        }
        val foundSps = sps ?: return null
        val foundPps = pps ?: return null
        return build(foundSps, foundPps)
    }

    private fun merge(a: ByteBuffer?, b: ByteBuffer?): ByteArray? {
        val sizeA = a?.remaining() ?: 0
        val sizeB = b?.remaining() ?: 0
        if (sizeA + sizeB == 0) return null
        val out = ByteArray(sizeA + sizeB)
        a?.duplicate()?.get(out, 0, sizeA)
        b?.duplicate()?.get(out, sizeA, sizeB)
        return out
    }
}
