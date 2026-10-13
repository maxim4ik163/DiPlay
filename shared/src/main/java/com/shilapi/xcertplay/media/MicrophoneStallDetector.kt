package com.shilapi.xcertplay.media

/**
 * Notices a recorder that keeps running but delivers far less audio than real time.
 *
 * On a BOS Mini A1 (MediaTek, Android 9) two wired calls in a row got one 32 ms read every
 * ~600 ms, all silence, while later calls in the same session captured normally. Read errors
 * never appeared, so the restart on read failure did not run and the far end heard nothing.
 */
internal class MicrophoneStallDetector(
    sampleRate: Int,
    channels: Int,
    private val windowNanos: Long = 1_000_000_000L,
    /** A window that captured less than this share of its wall time counts as stalled. */
    private val minimumShare: Double = 0.5,
) {
    private val bytesPerSecond = sampleRate.toLong().coerceAtLeast(1) * channels.coerceIn(1, 2) * 2
    private var windowStart = -1L
    private var windowBytes = 0L

    data class Stall(val wallMillis: Long, val capturedMillis: Long)

    /** Call after every read with the bytes it returned; returns a stall once per stalled window. */
    fun onRead(bytes: Int, nowNanos: Long): Stall? {
        if (windowStart < 0) {
            windowStart = nowNanos
            windowBytes = 0
        }
        if (bytes > 0) windowBytes += bytes
        val wall = nowNanos - windowStart
        if (wall < windowNanos) return null
        val capturedNanos = windowBytes * 1_000_000_000L / bytesPerSecond
        windowStart = nowNanos
        windowBytes = 0
        return if (capturedNanos < wall * minimumShare) Stall(wall / 1_000_000, capturedNanos / 1_000_000) else null
    }

    /** Start a fresh window, for example after the recorder was restarted. */
    fun reset() {
        windowStart = -1L
        windowBytes = 0
    }
}
