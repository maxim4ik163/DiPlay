package com.shilapi.xcertplay.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class MicrophoneStallDetectorTest {
    private val ms = 1_000_000L

    @Test fun realTimeCaptureNeverStalls() {
        val detector = MicrophoneStallDetector(32_000, 1)
        var now = 0L
        repeat(200) {
            now += 32 * ms // 2048 bytes every 32 ms is real time at 32 kHz mono
            assertNull(detector.onRead(2_048, now))
        }
    }

    @Test fun oneShortReadEvery600MsIsReportedAsAStall() {
        // The BOS Mini A1 wired call: one 2048-byte read about every 600 ms.
        val detector = MicrophoneStallDetector(32_000, 1)
        assertNull(detector.onRead(2_048, 2_591 * ms))
        var now = 2_591 * ms
        val stalls = mutableListOf<MicrophoneStallDetector.Stall>()
        repeat(4) {
            now += 601 * ms
            detector.onRead(2_048, now)?.let(stalls::add)
        }
        val stall = stalls.single()
        assertEquals(1_202L, stall.wallMillis)
        assertEquals(96L, stall.capturedMillis)
    }

    @Test fun slowStartThatCatchesUpIsNotAStall() {
        val detector = MicrophoneStallDetector(32_000, 1)
        assertNull(detector.onRead(2_048, 0))
        assertNull(detector.onRead(2_048, 150 * ms)) // first read after start takes a while
        var now = 150 * ms
        var bytes = 0
        while (now < 1_100 * ms) {
            now += 32 * ms
            assertNull(detector.onRead(2_048, now))
            bytes += 2_048
        }
    }

    @Test fun resetStartsAFreshWindow() {
        val detector = MicrophoneStallDetector(48_000, 1)
        detector.onRead(0, 0)
        detector.reset()
        assertNull(detector.onRead(0, 900 * ms))
        assertNotNull(detector.onRead(0, 1_900 * ms))
    }
}
