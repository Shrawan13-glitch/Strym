package com.strym.app.capture

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.nio.ByteBuffer

class AvcDecoderConfigTest {

    private val sps = byteArrayOf(0x67, 0x64, 0x00, 0x1F, 0xAC.toByte(), 0x2C.toByte())
    private val pps = byteArrayOf(0x68, 0xEB.toByte(), 0xE3.toByte())

    @Test
    fun buildMatchesCoreAvccLayout() {
        val record = AvcDecoderConfig.build(sps, pps)
        val expected = byteArrayOf(
            0x01, // configurationVersion
            0x64, // profile (sps[1])
            0x00, // compatibility (sps[2])
            0x1F, // level (sps[3])
            0xFF.toByte(), // lengthSizeMinusOne
            0xE1.toByte(), // one SPS
            0x00, sps.size.toByte(),
        ) + sps + byteArrayOf(0x01, 0x00, pps.size.toByte()) + pps
        assertArrayEquals(expected, record)
    }

    @Test
    fun buildReadsProfileFromSpsWithFallbacks() {
        val tiny = byteArrayOf(0x67)
        val record = AvcDecoderConfig.build(tiny, pps)
        assertEquals(0x64.toByte(), record[1]) // default profile
        assertEquals(0x00.toByte(), record[2])
        assertEquals(0x1F.toByte(), record[3])
    }

    @Test
    fun fromCsdExtractsBothUnits() {
        val csd = ByteBuffer.wrap(byteArrayOf(0, 0, 0, 1) + sps + byteArrayOf(0, 0, 1) + pps)
        val record = AvcDecoderConfig.fromCsd(csd, null)
        assertArrayEquals(AvcDecoderConfig.build(sps, pps), record)
    }

    @Test
    fun fromCsdHandlesSplitBuffers() {
        val head = ByteBuffer.wrap(byteArrayOf(0, 0, 0, 1) + sps)
        val tail = ByteBuffer.wrap(byteArrayOf(0, 0, 0, 1) + pps)
        val record = AvcDecoderConfig.fromCsd(head, tail)
        assertArrayEquals(AvcDecoderConfig.build(sps, pps), record)
    }

    @Test
    fun fromCsdDoesNotMutateInputPositions() {
        val csd = ByteBuffer.wrap(byteArrayOf(0, 0, 0, 1) + sps + byteArrayOf(0, 0, 1) + pps)
        val before = csd.position()
        AvcDecoderConfig.fromCsd(csd, null)
        assertEquals(before, csd.position())
    }

    @Test
    fun fromCsdRequiresSpsAndPps() {
        val spsOnly = ByteBuffer.wrap(byteArrayOf(0, 0, 0, 1) + sps)
        assertNull(AvcDecoderConfig.fromCsd(spsOnly, null))
        val ppsOnly = ByteBuffer.wrap(byteArrayOf(0, 0, 0, 1) + pps)
        assertNull(AvcDecoderConfig.fromCsd(ppsOnly, null))
        assertNull(AvcDecoderConfig.fromCsd(null, null))
    }
}
