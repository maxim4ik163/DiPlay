package com.shilapi.xcertplay.media

import android.os.Debug
import android.os.Process
import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.PI
import kotlin.math.sin
import org.concentus.OpusApplication
import org.concentus.OpusSignal

/**
 * Test build only. Once per app process, a few seconds after the first software-encoded microphone
 * stream ends, encodes three seconds of synthetic speech at several complexities and reports the
 * cost on this head unit. Thread CPU time is reported next to wall time, so a call or Siri request
 * that starts meanwhile only inflates the wall numbers.
 */
internal object SoftwareOpusBench {
    private val started = AtomicBoolean(false)

    fun runOnce(bitrate: Int, report: (String) -> Unit) {
        if (!started.compareAndSet(false, true)) return
        Thread({ run(bitrate, report) }, "carplay-mic-bench").apply {
            isDaemon = true
            start()
        }
    }

    private fun run(bitrate: Int, report: (String) -> Unit) {
        try {
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
            Thread.sleep(START_DELAY_MILLIS)
            val frames = speechFrames(WARMUP_FRAMES + MEASURED_FRAMES)
            val output = ByteArray(1_275)
            val cores = Runtime.getRuntime().availableProcessors()
            for (complexity in COMPLEXITIES) {
                val encoder = org.concentus.OpusEncoder(48_000, 1, OpusApplication.OPUS_APPLICATION_VOIP).apply {
                    setBitrate(bitrate)
                    setComplexity(complexity)
                    setSignalType(OpusSignal.OPUS_SIGNAL_VOICE)
                }
                for (index in 0 until WARMUP_FRAMES) encoder.encode(frames[index], 0, FRAME, output, 0, output.size)
                var wallSum = 0L
                var wallMax = 0L
                val cpuStart = cpuNanos()
                for (index in WARMUP_FRAMES until frames.size) {
                    val start = System.nanoTime()
                    encoder.encode(frames[index], 0, FRAME, output, 0, output.size)
                    val wall = System.nanoTime() - start
                    wallSum += wall
                    wallMax = maxOf(wallMax, wall)
                }
                val cpuEnd = cpuNanos()
                val cpuAverage = if (cpuStart >= 0 && cpuEnd >= cpuStart) {
                    ((cpuEnd - cpuStart) / MEASURED_FRAMES / 1_000).toString()
                } else {
                    "unknown"
                }
                val message = "Microphone: bench encoder=software complexity=$complexity bitrate=$bitrate " +
                    "frames=$MEASURED_FRAMES wallAvgUs=${wallSum / MEASURED_FRAMES / 1_000} " +
                    "wallMaxUs=${wallMax / 1_000} cpuAvgUs=$cpuAverage cores=$cores"
                Log.i(TAG, message)
                runCatching { report(message) }
            }
        } catch (error: Throwable) {
            Log.w(TAG, "software Opus bench failed", error)
        }
    }

    private fun cpuNanos(): Long = try {
        Debug.threadCpuTimeNanos()
    } catch (_: Throwable) {
        -1L
    }

    /** Voiced syllables: a gliding pitch with falling harmonics, a 4 Hz envelope and a little noise. */
    private fun speechFrames(count: Int): List<ShortArray> {
        val random = java.util.Random(7)
        var phase = 0.0
        return List(count) { frameIndex ->
            ShortArray(FRAME) { i ->
                val t = (frameIndex * FRAME + i) / 48_000.0
                val pitch = 140 + 50 * sin(2 * PI * 0.7 * t)
                phase += 2 * PI * pitch / 48_000.0
                var voiced = 0.0
                for (harmonic in 1..12) voiced += sin(harmonic * phase) / harmonic
                val envelope = 0.5 + 0.5 * sin(2 * PI * 4 * t)
                val sample = 6_000 * envelope * voiced + 300 * random.nextGaussian()
                sample.coerceIn(-32_768.0, 32_767.0).toInt().toShort()
            }
        }
    }

    private const val TAG = "xcertplay-usb"
    private const val FRAME = 960
    private const val WARMUP_FRAMES = 25
    private const val MEASURED_FRAMES = 150
    private const val START_DELAY_MILLIS = 3_000L
    private val COMPLEXITIES = intArrayOf(0, 1, 2, 3, 5, 7, 10)
}
