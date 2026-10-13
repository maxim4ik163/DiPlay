package com.shilapi.xcertplay.media

import org.junit.Assert.assertEquals
import org.junit.Test

class OpusPacketDurationTest {
    @Test fun celtSilencePacketLastsTwentyMilliseconds() {
        // The common 3-byte Opus silence packet: CELT fullband, 20 ms, one frame.
        val silence = byteArrayOf(0xf8.toByte(), 0xff.toByte(), 0xfe.toByte())
        assertEquals(20_000L, OpusPacketDuration.micros(silence))
        assertEquals(1_920, OpusPacketDuration.silenceBytes(silence, 48_000, 1))
    }

    @Test fun tocSelectsFrameSizeAndCount() {
        assertEquals(10_000L, OpusPacketDuration.micros(byteArrayOf((0 shl 3).toByte()))) // SILK 10 ms
        assertEquals(60_000L, OpusPacketDuration.micros(byteArrayOf((3 shl 3).toByte()))) // SILK 60 ms
        assertEquals(20_000L, OpusPacketDuration.micros(byteArrayOf((13 shl 3).toByte()))) // hybrid 20 ms
        assertEquals(2_500L, OpusPacketDuration.micros(byteArrayOf((16 shl 3).toByte()))) // CELT 2.5 ms
        assertEquals(40_000L, OpusPacketDuration.micros(byteArrayOf(((19 shl 3) or 1).toByte()))) // 2 x 20 ms
        assertEquals(60_000L, OpusPacketDuration.micros(byteArrayOf(((19 shl 3) or 3).toByte(), 3))) // 3 x 20 ms
    }

    @Test fun malformedPacketsFallBackOrStayBounded() {
        assertEquals(20_000L, OpusPacketDuration.micros(ByteArray(0)))
        assertEquals(20_000L, OpusPacketDuration.micros(byteArrayOf(((19 shl 3) or 3).toByte())))
        assertEquals(120_000L, OpusPacketDuration.micros(byteArrayOf(((3 shl 3) or 3).toByte(), 0x30)))
        assertEquals(3_840, OpusPacketDuration.silenceBytes(byteArrayOf(0xf8.toByte()), 48_000, 2))
    }
}
