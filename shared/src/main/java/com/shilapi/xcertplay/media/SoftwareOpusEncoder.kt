package com.shilapi.xcertplay.media

import org.concentus.OpusApplication
import org.concentus.OpusSignal

/**
 * Opus encoder in pure Java (Concentus, a port of the Opus reference library) for head units whose
 * Android has no MediaCodec Opus encoder. Kept free of Android types so it stays testable on the JVM.
 *
 * Head-unit CPUs are much slower than the machines the encoder was measured on, so it starts at a low
 * complexity and lowers it further when a second of frames shows that encoding cannot keep up. The first
 * frames of every encoder are not judged: they include class loading and JIT warmup, not steady cost.
 */
internal class SoftwareOpusEncoder(
    bitrate: Int,
    complexity: Int = DEFAULT_COMPLEXITY,
    private val onError: (String, Throwable) -> Unit = { _, _ -> },
    /** Runs on the encoding thread with the new complexity and the slow window's average encode time. */
    private val onComplexityLowered: (complexity: Int, averageMicros: Long) -> Unit = { _, _ -> },
    private val nowNs: () -> Long = System::nanoTime,
) : MicrophoneOpusEncoder {
    override val implementation: String = "software"

    var complexity: Int = complexity.coerceIn(MIN_COMPLEXITY, MAX_COMPLEXITY)
        private set

    override val details: String get() = "complexity=$complexity"

    private var encoder: org.concentus.OpusEncoder? = try {
        org.concentus.OpusEncoder(SAMPLE_RATE, CHANNELS, OpusApplication.OPUS_APPLICATION_VOIP).apply {
            setBitrate(bitrate)
            setComplexity(this@SoftwareOpusEncoder.complexity)
            setSignalType(OpusSignal.OPUS_SIGNAL_VOICE)
        }
    } catch (error: Throwable) {
        onError("software Opus encoder unavailable", error)
        null
    }
    private val samples = ShortArray(FRAME_SAMPLES)
    private val output = ByteArray(MAX_PACKET_BYTES)
    private var failures = 0
    private var graceFrames = GRACE_FRAMES
    private var windowFrames = 0
    private var windowNs = 0L
    private var windowSlowFrames = 0

    override val available: Boolean get() = encoder != null

    override fun encode(pcm: ByteArray): List<ByteArray> {
        val encoder = encoder ?: return emptyList()
        if (pcm.size != FRAME_BYTES) return emptyList()
        val start = nowNs()
        // The byte[] overload allocates a new sample array for every frame; reuse one instead.
        for (i in samples.indices) {
            samples[i] = ((pcm[2 * i].toInt() and 0xff) or (pcm[2 * i + 1].toInt() shl 8)).toShort()
        }
        val packets = try {
            val length = encoder.encode(samples, 0, FRAME_SAMPLES, output, 0, output.size)
            if (length > 0) listOf(output.copyOf(length)) else emptyList()
        } catch (error: Exception) {
            if (failures++ < MAX_REPORTED_FAILURES) onError("software Opus encode failed", error)
            emptyList()
        }
        observe(nowNs() - start)
        return packets
    }

    /** Lowers the complexity by one step after a window of slow frames. Never raises it again. */
    private fun observe(elapsedNs: Long) {
        if (graceFrames > 0) {
            graceFrames--
            return
        }
        val elapsed = elapsedNs.coerceAtLeast(0)
        windowFrames++
        windowNs += elapsed
        if (elapsed > SLOW_FRAME_NS) windowSlowFrames++
        if (windowFrames < WINDOW_FRAMES) return
        val averageNs = windowNs / windowFrames
        val slow = averageNs > SLOW_AVERAGE_NS || windowSlowFrames >= SLOW_FRAMES_PER_WINDOW
        windowFrames = 0
        windowNs = 0
        windowSlowFrames = 0
        if (!slow || complexity <= MIN_COMPLEXITY) return
        val encoder = encoder ?: return
        try {
            encoder.setComplexity(complexity - 1)
            complexity--
            onComplexityLowered(complexity, averageNs / 1_000)
        } catch (error: Exception) {
            onError("software Opus complexity change failed", error)
        }
    }

    override fun close() {
        encoder = null
    }

    internal companion object {
        const val SAMPLE_RATE = 48_000
        const val CHANNELS = 1
        const val FRAME_SAMPLES = 960
        const val FRAME_BYTES = FRAME_SAMPLES * CHANNELS * 2

        /**
         * Low enough for slow head-unit CPUs: complexity 10 audibly stuttered on a MediaTek DiLink 4
         * head unit, and speech for a call or Siri does not need more.
         */
        const val DEFAULT_COMPLEXITY = 3
        const val MIN_COMPLEXITY = 0
        const val MAX_COMPLEXITY = 10

        /** One second of 20 ms frames. */
        const val WINDOW_FRAMES = 50

        /** Two seconds of frames that never lower the complexity. */
        const val GRACE_FRAMES = 100

        /** Encoding above a quarter of real time leaves too little for capture, echo cancellation and sending. */
        const val SLOW_AVERAGE_NS = 5_000_000L

        /** Half a frame. */
        const val SLOW_FRAME_NS = 10_000_000L
        const val SLOW_FRAMES_PER_WINDOW = 3

        private const val MAX_PACKET_BYTES = 1_275
        private const val MAX_REPORTED_FAILURES = 3

        /** Siri arrives at 64 kbit/s and calls at 96 kbit/s; the encoder takes different paths for each. */
        private val WARMUP_BITRATES = intArrayOf(64_000, 96_000)
        private const val WARMUP_FRAMES_PER_BITRATE = 100

        /**
         * Encodes a few seconds of synthetic voiced speech at each uplink bitrate so that the classes
         * are loaded and the hot methods compiled before a real stream needs them. Returns the number
         * of frames that produced a packet.
         */
        fun warmUp(): Int {
            val pcm = ByteArray(FRAME_BYTES)
            var phase = 0.0
            var encoded = 0
            for (bitrate in WARMUP_BITRATES) {
                val encoder = SoftwareOpusEncoder(bitrate)
                try {
                    for (frame in 0 until WARMUP_FRAMES_PER_BITRATE) {
                        for (i in 0 until FRAME_SAMPLES) {
                            val t = (frame * FRAME_SAMPLES + i) / SAMPLE_RATE.toDouble()
                            phase += 2 * Math.PI * (140 + 50 * Math.sin(2 * Math.PI * 0.7 * t)) / SAMPLE_RATE
                            var voiced = 0.0
                            for (harmonic in 1..8) voiced += Math.sin(harmonic * phase) / harmonic
                            val sample = (6_000 * (0.5 + 0.5 * Math.sin(2 * Math.PI * 4 * t)) * voiced).toInt()
                            pcm[2 * i] = sample.toByte()
                            pcm[2 * i + 1] = (sample shr 8).toByte()
                        }
                        if (encoder.encode(pcm).isNotEmpty()) encoded++
                    }
                } finally {
                    encoder.close()
                }
            }
            return encoded
        }
    }
}
