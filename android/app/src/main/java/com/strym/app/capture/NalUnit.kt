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
     * True when [data] (first [size] bytes) begins with an Annex B start code
     * — either 4-byte (`00 00 00 01`) or 3-byte (`00 00 01`). Such output is
     * already in the core's wire format and needs no AVCC conversion. A 3-byte
     * prefix is only accepted when followed by a plausible NAL header, since
     * an AVCC length of `00 00 01 xx` would otherwise alias it.
     */
    fun isAnnexB(data: ByteArray, size: Int): Boolean {
        if (size < 4 || data[0] != 0.toByte() || data[1] != 0.toByte()) return false
        if (data[2] == 0.toByte()) return data[3] == 1.toByte()
        if (data[2] != 1.toByte()) return false
        val header = data[3].toInt() and 0xFF
        return header and 0x80 == 0 && (header and 0x1F) in 1..23
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
     * are skipped.
     */
    fun forEachAnnexBNal(data: ByteArray, size: Int, onNal: (offset: Int, length: Int) -> Unit) {
        var nalStart = -1
        var i = 0
        while (i + 2 < size) {
            if (data[i] == 0.toByte() && data[i + 1] == 0.toByte() && data[i + 2] == 1.toByte()) {
                if (nalStart >= 0) {
                    var end = i
                    if (end > nalStart && data[end - 1] == 0.toByte()) end--
                    if (end > nalStart) onNal(nalStart, end - nalStart)
                }
                nalStart = i + 3
                i += 3
            } else {
                i++
            }
        }
        if (nalStart in 0 until size) onNal(nalStart, size - nalStart)
    }
}
