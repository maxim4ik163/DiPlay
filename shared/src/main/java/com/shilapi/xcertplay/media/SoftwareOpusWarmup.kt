package com.shilapi.xcertplay.media

import android.os.Process
import android.os.SystemClock
import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Runs the software Opus encoder before the first call or Siri request needs it. A cold encoder on a
 * MediaTek Android 9 head unit took 494 ms for its first frame and 35 ms per 20 ms frame for the rest
 * of its first second, so the first Siri request after launch lost more than a second of speech.
 * Starts once per process, at background priority, and only when the platform has no MediaCodec Opus
 * encoder that would be used instead.
 */
internal object SoftwareOpusWarmup {
    private val started = AtomicBoolean(false)

    fun startOnce(report: (String) -> Unit) {
        if (!started.compareAndSet(false, true)) return
        Thread({ run(report) }, "carplay-mic-warmup").apply {
            isDaemon = true
            start()
        }
    }

    private fun run(report: (String) -> Unit) {
        try {
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
            if (runCatching { OpusEncoderSupport.platformAvailable() }.getOrDefault(false)) return
            val start = SystemClock.elapsedRealtime()
            val frames = SoftwareOpusEncoder.warmUp()
            val message = "Microphone: warmup encoder=software frames=$frames " +
                "durationMs=${SystemClock.elapsedRealtime() - start}"
            Log.i(TAG, message)
            runCatching { report(message) }
        } catch (error: Exception) {
            Log.w(TAG, "software Opus warmup failed", error)
        } catch (error: LinkageError) {
            Log.w(TAG, "software Opus warmup unavailable", error)
        }
    }

    private const val TAG = "xcertplay-usb"
}
