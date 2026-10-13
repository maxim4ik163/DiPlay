package com.shilapi.xcertplay.media

/**
 * Playback duration of an Opus packet, read from its TOC byte (RFC 6716, section 3.1).
 * Used for the tiny silence/DTX packets the iPhone sends while the far end is quiet, so their
 * share of the call can be played as silence instead of leaving the track empty.
 */
internal object OpusPacketDuration {
    const val DEFAULT_MICROS = 20_000L
    private const val MAX_MICROS = 120_000L

    fun micros(packet: ByteArray): Long {
        if (packet.isEmpty()) return DEFAULT_MICROS
        val toc = packet[0].toInt() and 0xff
        val config = toc ushr 3
        val frameMicros = when {
            config < 12 -> longArrayOf(10_000, 20_000, 40_000, 60_000)[config % 4] // SILK
            config < 16 -> longArrayOf(10_000, 20_000)[config % 2] // hybrid
            else -> longArrayOf(2_500, 5_000, 10_000, 20_000)[config % 4] // CELT
        }
        val frames = when (toc and 0x3) {
            0 -> 1
            1, 2 -> 2
            else -> if (packet.size >= 2) (packet[1].toInt() and 0x3f) else 0
        }
        if (frames == 0) return DEFAULT_MICROS
        return (frameMicros * frames).coerceAtMost(MAX_MICROS)
    }

    /** Bytes of 16-bit PCM silence covering [packet] at [sampleRate] and [channels]. */
    fun silenceBytes(packet: ByteArray, sampleRate: Int, channels: Int): Int {
        val samples = sampleRate.toLong() * micros(packet) / 1_000_000L
        return (samples * channels.coerceIn(1, 2) * 2).toInt()
    }
}
