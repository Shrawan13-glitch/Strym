package com.strym.app.capture

/**
 * H.264 NAL-unit helpers for the MediaCodec output format.
 *
 * Encoders usually emit AVCC (4-byte big-endian length, then the unit), but
 * some output Annex B directly; the core consumes Annex B (start codes). Both
 * AVCC prefixes are 4 bytes wide, so the conversion is an in-place rewrite
 * with no reallocation.
 */
object NalUnit {
    const val LENGTH_PREFIX_SIZE = 4

    const val TYPE_SPS = 7
    const val TYPE_PPS = 8

    /** NAL unit type: the low 5 bits of the header byte. */
    fun type(headerByte: Byte): Int = headerByte.toInt() and 0x1F

    /**
     * True when [data] (first [size] bytes) is Annex B — either 4-byte
     * (`00 00 00 01`) or 3-byte (`00 00 01`). Defensively disambiguates
     * AVCC lengths `00 00 01 xx` (256-511) which alias a 3-byte start-code:
     * if the whole buffer parses as valid AVCC (each 4-byte BE length
     * followed by a plausible NAL header and exact consumption) we treat it
     * as AVCC, not Annex B.
     */
    fun isAnnexB(data: ByteArray, size: Int): Boolean {
        if (size < 4 || data[0] != 0.toByte() || data[1] != 0.toByte()) return false
        val isFourByte = data[2] == 0.toByte() && data[3] == 1.toByte()
        val isThreeByte = data[2] == 1.toByte() && run {
            val header = data[3].toInt() and 0xFF
            header and 0x80 == 0 && (header and 0x1F) in 1..23
        }
        if (!isFourByte && !isThreeByte) return false
        // 4-byte prefix never aliases AVCC length 1 (rare), keep as AnnexB.
        if (isFourByte) return true
        // 3-byte: if the buffer is also valid AVCC, prefer AVCC to avoid
        // misclassifying 00 00 01 xx SPS/PPS lengths as start-codes.
        return !isValidAvcc(data, size)
    }

    /** Returns true if the whole [size] parses as 4-byte BE length-prefixed NALs. */
    private fun isValidAvcc(data: ByteArray, size: Int): Boolean {
        var pos = 0
        var count = 0
        while (pos < size) {
            if (pos + 4 > size) return false
            val len = ((data[pos].toInt() and 0xFF) shl 24) or
                ((data[pos + 1].toInt() and 0xFF) shl 16) or
                ((data[pos + 2].toInt() and 0xFF) shl 8) or
                (data[pos + 3].toInt() and 0xFF)
            if (len <= 0 || pos + 4 + len > size) return false
            if (len < 1) return false
            val header = data[pos + 4].toInt() and 0xFF
            // NAL header must have forbidden_zero_bit 0 and type 1..23 (or 24+ for newer extensions)
            // but for AVC we restrict to 1..23 to reduce alias false positives.
            if (header and 0x80 != 0) return false
            val type = header and 0x1F
            if (type !in 1..23 && type !in 24..31) return false
            pos += 4 + len
            count++
            if (count > 64) return false // sanity: frames never have that many NALs
        }
        return pos == size && count > 0
    }

    /**
     * Rewrite AVCC [data] (the first [size] bytes) in place to Annex B,
     * replacing every length prefix with a `00 00 00 01` start code. Returns
     * false when the input is malformed: a zero or negative length, a
     * truncated length field, or a length running past the end of the data.
     */
    fun avccToAnnexBInPlace(data: ByteArray, size: Int): Boolean {
        var pos = 0
        while (pos < size) {
            if (pos + LENGTH_PREFIX_SIZE > size) return false
            val len = ((data[pos].toInt() and 0xFF) shl 24) or
                ((data[pos + 1].toInt() and 0xFF) shl 16) or
                ((data[pos + 2].toInt() and 0xFF) shl 8) or
                (data[pos + 3].toInt() and 0xFF)
            // A length with bit 31 set is not a valid AVCC size (NALs are far
            // smaller than 2 GiB); it would drive the cursor negative below.
            if (len <= 0 || pos + LENGTH_PREFIX_SIZE + len > size) return false
            data[pos] = 0
            data[pos + 1] = 0
            data[pos + 2] = 0
            data[pos + 3] = 1
            pos += LENGTH_PREFIX_SIZE + len
        }
        return true
    }

    /**
     * Walk the NAL units of an Annex B packet, invoking [onNal] with the
     * offset (just past the start code) and length of each unit. Handles both
     * 3-byte (`00 00 01`) and 4-byte (`00 00 00 01`) start codes; empty units
     * are skipped. Also handles raw NALs with no start-code (some MediaCodec
     * csd buffers) by treating the whole buffer as one NAL when no code is
     * found but the first byte is a plausible header.
     */
    fun forEachAnnexBNal(data: ByteArray, size: Int, onNal: (offset: Int, length: Int) -> Unit) {
        var nalStart = -1
        var i = 0
        var foundStartCode = false
        while (i + 2 < size) {
            val isThree = data[i] == 0.toByte() && data[i + 1] == 0.toByte() && data[i + 2] == 1.toByte()
            val isFour = i + 3 < size && data[i] == 0.toByte() && data[i + 1] == 0.toByte() && data[i + 2] == 0.toByte() && data[i + 3] == 1.toByte()
            if (isFour) {
                foundStartCode = true
                if (nalStart >= 0) {
                    var end = i
                    while (end > nalStart && data[end - 1] == 0.toByte()) end--
                    if (end > nalStart) onNal(nalStart, end - nalStart)
                }
                nalStart = i + 4
                i += 4
                continue
            }
            if (isThree) {
                // Prefer 4-byte detection first, but 3-byte still valid if not preceded by an extra 0.
                // Avoid double-counting 00 00 00 01 as 00 00 01 at i+1.
                val precededByZero = i > 0 && data[i - 1] == 0.toByte()
                if (precededByZero) {
                    i++
                    continue
                }
                foundStartCode = true
                if (nalStart >= 0) {
                    var end = i
                    while (end > nalStart && data[end - 1] == 0.toByte()) end--
                    if (end > nalStart) onNal(nalStart, end - nalStart)
                }
                nalStart = i + 3
                i += 3
                continue
            }
            i++
        }
        if (nalStart >= 0 && nalStart < size) {
            var end = size
            while (end > nalStart && data[end - 1] == 0.toByte()) end--
            if (end > nalStart) onNal(nalStart, end - nalStart)
        } else if (!foundStartCode && size > 0) {
            // No start-code but plausible raw NAL (e.g. csd-0 = 0x67... without prefix)
            val header = data[0].toInt() and 0xFF
            if (header and 0x80 == 0 && (header and 0x1F) in 1..23) {
                onNal(0, size)
            }
        }
    }
}
