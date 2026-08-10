package com.strym.app.capture

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NalUnitTest {

    private fun avcc(vararg nals: ByteArray): ByteArray {
        val out = ByteArray(nals.sumOf { it.size + NalUnit.LENGTH_PREFIX_SIZE })
        var pos = 0
        for (nal in nals) {
            out[pos] = (nal.size shr 24).toByte()
            out[pos + 1] = (nal.size shr 16).toByte()
            out[pos + 2] = (nal.size shr 8).toByte()
            out[pos + 3] = nal.size.toByte()
            nal.copyInto(out, pos + NalUnit.LENGTH_PREFIX_SIZE)
            pos += nal.size + NalUnit.LENGTH_PREFIX_SIZE
        }
        return out
    }

    @Test
    fun annexBRewritesLengthPrefixesInPlace() {
        val idr = byteArrayOf(0x65, 1, 2, 3)
        val data = avcc(idr)
        assertTrue(NalUnit.avccToAnnexBInPlace(data, data.size))
        assertArrayEquals(byteArrayOf(0, 0, 0, 1, 0x65, 1, 2, 3), data)
    }

    @Test
    fun annexBConvertsEveryNal() {
        val sps = byteArrayOf(0x67, 0x64, 0x00)
        val pps = byteArrayOf(0x68, 0x01)
        val idr = byteArrayOf(0x65, 0x11, 0x22, 0x33, 0x44)
        val data = avcc(sps, pps, idr)
        assertTrue(NalUnit.avccToAnnexBInPlace(data, data.size))
        assertArrayEquals(
            byteArrayOf(0, 0, 0, 1, 0x67, 0x64, 0x00, 0, 0, 0, 1, 0x68, 0x01, 0, 0, 0, 1, 0x65, 0x11, 0x22, 0x33, 0x44),
            data,
        )
    }

    @Test
    fun annexBRejectsZeroLengthUnit() {
        val data = byteArrayOf(0, 0, 0, 0)
        assertFalse(NalUnit.avccToAnnexBInPlace(data, data.size))
    }

    @Test
    fun annexBRejectsTruncatedLengthField() {
        val data = byteArrayOf(0, 0, 0)
        assertFalse(NalUnit.avccToAnnexBInPlace(data, data.size))
    }

    @Test
    fun annexBRejectsLengthOverrun() {
        val data = avcc(byteArrayOf(0x65, 1)).copyOf(6) // claims 4 payload bytes, has 2
        assertFalse(NalUnit.avccToAnnexBInPlace(data, data.size))
    }

    @Test
    fun annexBIgnoresBytesBeyondSize() {
        // A larger buffer with stale tail: only the first `size` bytes count.
        val data = avcc(byteArrayOf(0x61, 7)).copyOf(64)
        val valid = 4 + 2
        assertTrue(NalUnit.avccToAnnexBInPlace(data, valid))
        assertArrayEquals(byteArrayOf(0, 0, 0, 1, 0x61, 7), data.copyOf(valid))
    }

    @Test
    fun walkerSplitsThreeAndFourByteStartCodes() {
        val sps = byteArrayOf(0x67, 0x64, 0x00, 0x1F)
        val pps = byteArrayOf(0x68, 0x01, 0x02)
        val packet = byteArrayOf(0, 0, 0, 1) + sps + byteArrayOf(0, 0, 1) + pps
        val found = mutableListOf<Pair<Int, Int>>()
        NalUnit.forEachAnnexBNal(packet, packet.size) { offset, length -> found.add(offset to length) }
        assertEquals(2, found.size)
        val (spsOffset, spsLength) = found[0]
        assertEquals(0x67, packet[spsOffset])
        assertEquals(sps.size, spsLength)
        val (ppsOffset, ppsLength) = found[1]
        assertEquals(0x68, packet[ppsOffset])
        assertEquals(pps.size, ppsLength)
    }

    @Test
    fun walkerSkipsEmptyUnits() {
        val packet = byteArrayOf(0, 0, 0, 1, 0, 0, 0, 1, 0x65, 9)
        val found = mutableListOf<Int>()
        NalUnit.forEachAnnexBNal(packet, packet.size) { offset, _ -> found.add(offset) }
        assertEquals(1, found.size)
        assertEquals(0x65, packet[found.single()])
    }

    @Test
    fun typeMasksToFiveBits() {
        assertEquals(NalUnit.TYPE_SPS, NalUnit.type(0x67))
        assertEquals(NalUnit.TYPE_PPS, NalUnit.type(0x68))
        assertEquals(5, NalUnit.type(0x65))
        assertEquals(5, NalUnit.type((0x65 or 0x80).toByte()))
    }
}
