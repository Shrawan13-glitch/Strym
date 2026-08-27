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
        val out = ByteArray(11 + sps.size + pps.size)
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
     * across csd-0/csd-1, and on some devices as raw NALs without start-codes
     * or as AVCC-like length-prefixed blobs. Returns null when either unit is
     * missing.
     */
    fun fromCsd(csd0: ByteBuffer?, csd1: ByteBuffer?): ByteArray? {
        val merged = merge(csd0, csd1) ?: return null
        var sps: ByteArray? = null
        var pps: ByteArray? = null
        // Primary path: scan as Annex B (now handles 3/4-byte and raw fallback).
        NalUnit.forEachAnnexBNal(merged, merged.size) { offset, length ->
            when (NalUnit.type(merged[offset])) {
                NalUnit.TYPE_SPS -> if (sps == null) sps = merged.copyOfRange(offset, offset + length)
                NalUnit.TYPE_PPS -> if (pps == null) pps = merged.copyOfRange(offset, offset + length)
            }
        }
        // Secondary: if no SPS/PPS found but buffer looks like AVCC (length-prefixed),
        // try to reinterpret as AVCC (some encoders deliver csd as AVCC).
        if (sps == null || pps == null) {
            val asAvcc = tryParseAvccCsd(merged)
            if (asAvcc != null) {
                val (avccSps, avccPps) = asAvcc
                if (sps == null) sps = avccSps
                if (pps == null) pps = avccPps
            }
        }
        // Tertiary: raw fallback — merged without start-codes but first byte is SPS/PPS header.
        // forEach already handles raw single NAL, but merged may contain 2 raw NALs concatenated
        // without prefixes (rare). Try splitting on plausible NAL boundaries.
        if (sps == null || pps == null) {
            val rawSplit = trySplitRawNals(merged)
            for (nal in rawSplit) {
                when (NalUnit.type(nal[0])) {
                    NalUnit.TYPE_SPS -> if (sps == null) sps = nal
                    NalUnit.TYPE_PPS -> if (pps == null) pps = nal
                }
            }
        }
        val foundSps = sps ?: return null
        val foundPps = pps ?: return null
        // Validate SPS/PPS plausibility (header forbidden bit, length)
        if (foundSps.isEmpty() || foundPps.isEmpty()) return null
        if (foundSps[0].toInt() and 0x80 != 0 || foundPps[0].toInt() and 0x80 != 0) return null
        return build(foundSps, foundPps)
    }

    private fun tryParseAvccCsd(data: ByteArray): Pair<ByteArray, ByteArray>? {
        var pos = 0
        var sps: ByteArray? = null
        var pps: ByteArray? = null
        while (pos + 4 <= data.size) {
            val len = ((data[pos].toInt() and 0xFF) shl 24) or
                ((data[pos + 1].toInt() and 0xFF) shl 16) or
                ((data[pos + 2].toInt() and 0xFF) shl 8) or
                (data[pos + 3].toInt() and 0xFF)
            if (len <= 0 || pos + 4 + len > data.size) break
            val nal = data.copyOfRange(pos + 4, pos + 4 + len)
            when (NalUnit.type(nal[0])) {
                NalUnit.TYPE_SPS -> if (sps == null) sps = nal
                NalUnit.TYPE_PPS -> if (pps == null) pps = nal
            }
            pos += 4 + len
            if (sps != null && pps != null) return sps to pps
        }
        return null
    }

    private fun trySplitRawNals(data: ByteArray): List<ByteArray> {
        // Heuristic for concatenated raw NALs without prefixes: walk and split where
        // next byte looks like a NAL header with valid type and forbidden bit 0.
        // Only used when Annex B scan found nothing.
        if (data.size < 4) return emptyList()
        // If data starts with 00 00 etc., it was Annex B — already handled.
        if (data[0] == 0.toByte() && data[1] == 0.toByte()) return emptyList()
        val nals = mutableListOf<ByteArray>()
        var start = 0
        var i = 1
        while (i < data.size) {
            val header = data[i].toInt() and 0xFF
            val plausible = header and 0x80 == 0 && (header and 0x1F) in 1..23
            // Look for NAL boundary: previous NAL likely ended, next starts with SPS/PPS/IDR header.
            // Split when we see SPS/PPS header after at least a few bytes.
            if (plausible && (header and 0x1F == NalUnit.TYPE_SPS || header and 0x1F == NalUnit.TYPE_PPS) && i - start > 8) {
                nals.add(data.copyOfRange(start, i))
                start = i
            }
            i++
        }
        if (start < data.size) nals.add(data.copyOfRange(start, data.size))
        return nals
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
