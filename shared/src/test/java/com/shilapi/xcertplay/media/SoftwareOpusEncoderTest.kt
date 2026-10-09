package com.shilapi.xcertplay.media

import org.concentus.OpusDecoder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class SoftwareOpusEncoderTest {
    @Test
    fun encodesEach20MsFrameAsOneDecodableOpusPacket() {
        val encoder = SoftwareOpusEncoder(bitrate = 48_000)
        val decoder = OpusDecoder(48_000, 1)
        assertTrue(encoder.available)

        repeat(10) { index ->
            val packets = encoder.encode(toneFrame(index))
            assertEquals(1, packets.size)
            val packet = packets.single()
            assertTrue(packet.isNotEmpty())
            assertEquals("one frame per packet", 0, packet[0].toInt() and 0x03)
            val pcm = ShortArray(SoftwareOpusEncoder.FRAME_SAMPLES)
            assertEquals(SoftwareOpusEncoder.FRAME_SAMPLES, decoder.decode(packet, 0, packet.size, pcm, 0, pcm.size, false))
        }
        encoder.close()
        assertTrue(encoder.encode(toneFrame(0)).isEmpty())
    }

    @Test
    fun rejectsFramesThatAreNot20Ms() {
        val encoder = SoftwareOpusEncoder(bitrate = 48_000)
        assertTrue(encoder.encode(ByteArray(SoftwareOpusEncoder.FRAME_BYTES - 2)).isEmpty())
    }

    @Test
    fun startsAtALowComplexityForHeadUnitCpus() {
        val encoder = SoftwareOpusEncoder(bitrate = 48_000)
        assertEquals(3, encoder.complexity)
        assertEquals("complexity=3", encoder.details)
        assertEquals(10, SoftwareOpusEncoder(bitrate = 48_000, complexity = 42).complexity)
    }

    @Test
    fun slowSecondsLowerTheComplexityOneStepAtATimeDownToZero() {
        val clock = FrameClock { 6_000_000 }
        val lowered = mutableListOf<Pair<Int, Long>>()
        val encoder = SoftwareOpusEncoder(
            bitrate = 48_000,
            complexity = 2,
            onComplexityLowered = { complexity, average -> lowered += complexity to average },
            nowNs = clock::now,
        )
        repeat(SoftwareOpusEncoder.GRACE_FRAMES + SoftwareOpusEncoder.WINDOW_FRAMES - 1) { encoder.encode(toneFrame(it)) }
        assertEquals(2, encoder.complexity)
        encoder.encode(toneFrame(0))
        assertEquals(1, encoder.complexity)
        repeat(SoftwareOpusEncoder.WINDOW_FRAMES * 3) { assertEquals(1, encoder.encode(toneFrame(it)).size) }
        assertEquals(0, encoder.complexity)
        assertEquals(listOf(1 to 6_000L, 0 to 6_000L), lowered)
    }

    @Test
    fun repeatedSlowFramesLowerTheComplexityButFastSecondsKeepIt() {
        var frame = 0
        val clock = FrameClock { if (frame++ % SoftwareOpusEncoder.WINDOW_FRAMES < 3) 12_000_000 else 1_000_000 }
        val encoder = SoftwareOpusEncoder(bitrate = 48_000, nowNs = clock::now)
        repeat(SoftwareOpusEncoder.GRACE_FRAMES + SoftwareOpusEncoder.WINDOW_FRAMES) { encoder.encode(toneFrame(it)) }
        assertEquals(2, encoder.complexity)

        val fast = SoftwareOpusEncoder(bitrate = 48_000, nowNs = (FrameClock { 4_000_000 })::now)
        repeat(SoftwareOpusEncoder.WINDOW_FRAMES * 4) { fast.encode(toneFrame(it)) }
        assertEquals(SoftwareOpusEncoder.DEFAULT_COMPLEXITY, fast.complexity)
    }

    @Test
    fun warmupFramesNeverLowerTheComplexity() {
        var frame = 0
        val clock = FrameClock { if (frame++ < SoftwareOpusEncoder.GRACE_FRAMES) 35_000_000 else 1_000_000 }
        val encoder = SoftwareOpusEncoder(bitrate = 48_000, nowNs = clock::now)
        repeat(SoftwareOpusEncoder.GRACE_FRAMES + SoftwareOpusEncoder.WINDOW_FRAMES * 2) { encoder.encode(toneFrame(it)) }
        assertEquals(SoftwareOpusEncoder.DEFAULT_COMPLEXITY, encoder.complexity)
    }

    @Test
    fun warmupEncodesSpeechAtTheSiriAndCallBitrates() {
        assertEquals(200, SoftwareOpusEncoder.warmUp())
    }

    @Test
    fun everyComplexityStillProducesDecodableSpeechPackets() {
        val decoder = OpusDecoder(48_000, 1)
        for (complexity in 0..10) {
            val encoder = SoftwareOpusEncoder(bitrate = 32_000, complexity = complexity)
            repeat(5) { index ->
                val packet = encoder.encode(toneFrame(index)).single()
                val pcm = ShortArray(SoftwareOpusEncoder.FRAME_SAMPLES)
                assertEquals(SoftwareOpusEncoder.FRAME_SAMPLES, decoder.decode(packet, 0, packet.size, pcm, 0, pcm.size, false))
            }
        }
    }

    @Test
    fun platformEncoderIsPreferredAndSoftwareIsTheFallback() {
        val platform = FakeEncoder(available = true)
        assertSame(platform, MicrophoneOpusEncoders.create(48_000, platform = { platform }, software = { error("unused") }))

        val missing = FakeEncoder(available = false)
        val software = FakeEncoder(available = true)
        assertSame(software, MicrophoneOpusEncoders.create(48_000, platform = { missing }, software = { software }))
        assertTrue(missing.closed)

        val brokenSoftware = FakeEncoder(available = false)
        assertNull(MicrophoneOpusEncoders.create(48_000, platform = { FakeEncoder(false) }, software = { brokenSoftware }))
        assertTrue(brokenSoftware.closed)
    }

    private fun toneFrame(index: Int): ByteArray {
        val bytes = ByteArray(SoftwareOpusEncoder.FRAME_BYTES)
        for (i in 0 until SoftwareOpusEncoder.FRAME_SAMPLES) {
            val t = (index * SoftwareOpusEncoder.FRAME_SAMPLES + i) / 48_000.0
            val sample = (8_000 * (sin(2 * PI * 220 * t) + 0.5 * sin(2 * PI * 660 * t))).toInt()
            bytes[2 * i] = sample.toByte()
            bytes[2 * i + 1] = (sample shr 8).toByte()
        }
        return bytes
    }

    private class FakeEncoder(override val available: Boolean) : MicrophoneOpusEncoder {
        override val implementation = "fake"
        var closed = false
        override fun encode(pcm: ByteArray): List<ByteArray> = emptyList()
        override fun close() {
            closed = true
        }
    }

    /** Each encode reads the clock twice; the second read advances it by that frame's encode time. */
    private class FrameClock(private val elapsedNs: () -> Long) {
        private var nowNs = 0L
        private var reads = 0

        fun now(): Long {
            if (reads++ % 2 == 1) nowNs += elapsedNs()
            return nowNs
        }
    }
}
