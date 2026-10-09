package com.shilapi.xcertplay.media

import com.shilapi.xcertplay.airplay.AudioCodecKind
import com.shilapi.xcertplay.airplay.MicrophoneConfig
import java.net.InetAddress
import org.junit.Assert.*
import org.junit.Test

class MicrophoneCaptureStatsTest {
    private val config = MicrophoneConfig("telephony", 48_000, 1, 100, 20,
        InetAddress.getByName("198.51.100.20"), 54321, ByteArray(32) { 0x7f }, AudioCodecKind.OPUS)

    @Test fun aggregatesFiveSecondWindowsAndKeepsFinalPartialWindow() {
        var now = 0L
        val reports = mutableListOf<String>()
        val stats = MicrophoneCaptureStats(config, reports::add) { now }
        stats.started(15)
        stats.reading()
        now = 25_000_000
        stats.read(1920)
        stats.encoded(1, 0)
        stats.sent()
        stats.reading()
        now += 30_000_000
        stats.read(0)
        stats.encoded(0, 1)
        stats.sent()
        stats.sendFailed()
        stats.flush()
        assertEquals(1, reports.size)
        now = 5_000_000_000
        stats.flush(routeType = { 18 })
        val first = reports.last()
        assertTrue(first.contains("captureBytes=1920 reads=2 zeroReads=1 readErrors=0 readMaxMs=30"))
        assertTrue(first.contains("encodedFrames=1 emptyEncodedFrames=1 udpSent=2 sendErrors=1 sendGapMaxMs=30"))
        assertTrue(first.contains("routedDeviceType=18"))
        assertTrue(first.endsWith("ended=false"))
        stats.reading()
        now += 8_000_000
        stats.read(-3)
        stats.failure(MicrophoneFailureStage.READ, code = -3)
        stats.flush(ended = true)
        val final = reports.last()
        assertTrue(final.contains("captureBytes=0 reads=1 zeroReads=0 readErrors=1 readMaxMs=8"))
        assertTrue(final.contains("encodedFrames=0 emptyEncodedFrames=0 udpSent=0 sendErrors=0"))
        assertTrue(final.endsWith("ended=true"))
    }

    @Test fun frameTimingIsReportedPerWindowAndSummarizedOncePerStream() {
        var now = 0L
        val reports = mutableListOf<String>()
        val stats = MicrophoneCaptureStats(config.copy(audioType = "speechrecognition"), reports::add) { now }
        stats.threadPriority(requested = -19, actual = -19)
        repeat(250) { index ->
            stats.reading()
            now += 20_000_000
            stats.read(1920)
            stats.frame(
                encodeNs = if (index == 0) 25_000_000 else 1_000_000,
                encodeCpuNs = 900_000,
                workNs = if (index == 0) 26_000_000 else 1_500_000,
            )
            stats.flush()
        }
        assertEquals("Microphone: thread type=speechrecognition priority=-19 requested=-19", reports[0])
        val window = reports[1]
        assertTrue(window, window.contains("windowMs=5000 audioMs=5000 encodeAvgUs=1096 encodeMaxUs=25000 " +
            "encodeCpuAvgUs=900 workMaxUs=26000 overBudget=1 ended=false"))

        stats.flush(ended = true, details = "encoder=software complexity=3")
        stats.flush(ended = true, details = "encoder=software complexity=3")
        val summaries = reports.filter { it.startsWith("Microphone: summary ") }
        assertEquals(1, summaries.size)
        assertEquals("Microphone: summary type=speechrecognition source=VOICE_RECOGNITION codec=OPUS rate=48000 " +
            "channels=1 frameMs=20 durationMs=5000 audioMs=5000 frames=250 encodeAvgUs=1096 encodeP95Us=1100 " +
            "encodeMaxUs=25000 encodeCpuAvgUs=900 workAvgUs=1598 workP95Us=1600 workMaxUs=26000 overBudget=1 " +
            "priority=-19 encoder=software complexity=3", summaries.single())
        val finalWindow = reports.last { it.startsWith("Microphone: stats ") }
        assertTrue(finalWindow, finalWindow.contains("encodeAvgUs=0 encodeMaxUs=0 encodeCpuAvgUs=unknown " +
            "workMaxUs=0 overBudget=0 ended=true"))
        assertTrue(reports.all { it.length < 512 && DiagnosticSafe.matches(it) })
    }

    @Test fun unknownThreadPriorityAndCpuTimeAreReportedAsUnknown() {
        val reports = mutableListOf<String>()
        val stats = MicrophoneCaptureStats(config, reports::add) { 0 }
        stats.threadPriority(requested = -16, actual = null)
        stats.frame(encodeNs = 2_000_000, encodeCpuNs = -1, workNs = 3_000_000)
        stats.flush(ended = true)
        assertEquals("Microphone: thread type=telephony priority=unknown requested=-16", reports[0])
        assertTrue(reports[1].contains("encodeCpuAvgUs=unknown"))
        assertTrue(reports[2], reports[2].endsWith("overBudget=0 priority=unknown"))
    }

    @Test fun durationPercentilesUseBucketEdgesCappedAtTheMaximum() {
        val histogram = DurationHistogram()
        assertEquals(0, histogram.percentileMicros(95))
        repeat(19) { histogram.add(250_000) }
        histogram.add(80_000_000)
        assertEquals(300, histogram.percentileMicros(95))
        assertEquals(80_000, histogram.percentileMicros(100))
        assertEquals(4_237, histogram.averageMicros())
        val single = DurationHistogram().apply { add(1_234_000) }
        assertEquals(1_234, single.percentileMicros(95))
    }

    @Test fun reportingIsBoundedAndRouteQueriesRunOnlyWhenDue() {
        var now = 0L
        var reports = 0
        var routeQueries = 0
        var longest = 0
        val stats = MicrophoneCaptureStats(config, { reports++; longest = maxOf(longest, it.length) }) { now }
        repeat(100_000) {
            stats.reading()
            now += 1000
            stats.read(0)
            stats.encoded(0, 1)
            stats.flush(routeType = { routeQueries++; 15 })
        }
        assertEquals(0, reports)
        assertEquals(0, routeQueries)
        now = 5_000_000_000
        stats.flush(routeType = { routeQueries++; 15 })
        assertEquals(1, reports)
        assertEquals(1, routeQueries)
        assertTrue(longest < 512)
    }

    @Test fun callbackAndRouteFailuresDoNotEscapeOrPreventCounterReset() {
        var now = 0L
        var fail = true
        val reports = mutableListOf<String>()
        val stats = MicrophoneCaptureStats(config, {
            if (fail) throw IllegalStateException("diagnostic callback failed")
            reports.add(it)
        }) { now }
        stats.started(15)
        stats.failure(MicrophoneFailureStage.CAPTURE, IllegalStateException("not reported"))
        stats.reading()
        stats.read(1920)
        stats.sent()
        now = 5_000_000_000
        stats.flush(routeType = { throw IllegalStateException("route metadata unavailable") })
        fail = false
        stats.flush(ended = true)
        assertEquals(1, reports.size)
        assertTrue(reports.single().contains("captureBytes=0 reads=0"))
        assertTrue(reports.single().contains("udpSent=0"))
        assertTrue(reports.single().contains("routedDeviceType=unknown"))
    }

    @Test fun fallbackSourceIsReportedAfterTheRefusedSource() {
        val reports = mutableListOf<String>()
        val stats = MicrophoneCaptureStats(config.copy(audioType = "speechrecognition"), reports::add) { 0 }
        stats.failure(MicrophoneFailureStage.RECORDER_CREATION, UnsupportedOperationException())
        stats.useVoiceCommunicationSource()
        stats.started(15)
        stats.flush(ended = true)
        assertTrue(reports[0].contains("type=speechrecognition source=VOICE_RECOGNITION"))
        assertTrue(reports.drop(1).all { it.contains("type=speechrecognition source=VOICE_COMMUNICATION") })
    }

    @Test fun outputContainsOnlyAllowlistedMetadataAndNeverExceptionMessagesOrEndpoints() {
        val reports = mutableListOf<String>()
        val privateConfig = config.copy(audioType = "PRIVATE_PHONE_NAME\nsecret=value")
        val stats = MicrophoneCaptureStats(privateConfig, reports::add) { 0 }
        stats.started(15)
        stats.failure(MicrophoneFailureStage.CAPTURE, IllegalStateException("SECRET_PAYLOAD 198.51.100.20 54321"))
        stats.flush(ended = true)
        MicrophoneCaptureStats.reportStartFailure(privateConfig, SecurityException("PRIVATE_DEVICE_ADDRESS"), reports::add)
        val text = reports.joinToString("\n")
        assertTrue(text.contains("type=other source=MIC codec=OPUS"))
        assertTrue(text.contains("stage=CAPTURE error=IllegalStateException"))
        for (privateValue in listOf("PRIVATE", "secret", "SECRET", "198.51.100.20", "54321", "head=", "payload=", "key=")) {
            assertFalse(privateValue, text.contains(privateValue))
        }
        assertEquals(4, reports.size)
        assertTrue(reports.all { it.length < 512 && '\n' !in it })
    }

    private object DiagnosticSafe {
        private val unsafe = Regex("(?i)(token|pass|key=|head=|payload=|body=|hex=|name[=:])")
        fun matches(line: String) = !unsafe.containsMatchIn(line)
    }
}
