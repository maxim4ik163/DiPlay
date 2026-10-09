package com.shilapi.xcertplay.media

import com.shilapi.xcertplay.airplay.MicrophoneConfig

internal enum class MicrophoneFailureStage {
    MIN_BUFFER, ENCODER, RECORDER_CREATION, RECORDER_INITIALIZATION, SOCKET_CREATION,
    RECORDING, READ, CAPTURE, START,
}

/** Capture-thread counters only. Never retains PCM, encoded packets, keys or endpoint metadata. */
internal class MicrophoneCaptureStats(
    config: MicrophoneConfig,
    private val report: (String) -> Unit,
    private val nowNs: () -> Long = System::nanoTime,
) {
    private val type = typeAndSource(config.audioType).first
    private val format = format(config)
    private var metadata = metadata(config)
    private var windowStart = nowNs()
    private var readStart = windowStart
    private var capturedBytes = 0L
    private var reads = 0L
    private var zeroReads = 0L
    private var readErrors = 0L
    private var readMaxNs = 0L
    private var encodedFrames = 0L
    private var emptyEncodedFrames = 0L
    private var udpSent = 0L
    private var sendErrors = 0L
    private var lastSentNs: Long? = null
    private var sendGapMaxNs = 0L
    private var routedDeviceType: Int? = null
    private val bytesPerSecond = config.sampleRate.toLong().coerceAtLeast(1) * config.channels.coerceAtLeast(1) * 2
    private val frameBudgetNs = config.frameMillis.coerceAtLeast(1) * 1_000_000L
    private var frames = 0L
    private var encodeSumNs = 0L
    private var encodeMaxNs = 0L
    private var encodeCpuSumNs = 0L
    private var encodeCpuFrames = 0L
    private var workMaxNs = 0L
    private var overBudgetFrames = 0L
    private val streamStart = windowStart
    private var streamCapturedBytes = 0L
    private val streamEncode = DurationHistogram()
    private val streamWork = DurationHistogram()
    private var streamEncodeCpuSumNs = 0L
    private var streamEncodeCpuFrames = 0L
    private var streamOverBudgetFrames = 0L
    private var priority = "unknown"
    private var summarized = false

    fun started(routeType: Int?) {
        routedDeviceType = routeType
        emit("Microphone: start $metadata routedDeviceType=${routeType ?: "unknown"}")
    }

    /** Call before the capture thread starts; [metadata] is not volatile. */
    /** The capture thread's scheduling priority after it asked for [requested]; null when unreadable. */
    fun threadPriority(requested: Int, actual: Int?) {
        priority = actual?.toString() ?: "unknown"
        emit("Microphone: thread type=$type priority=$priority requested=$requested")
    }

    fun useVoiceCommunicationSource() { metadata = "type=$type source=VOICE_COMMUNICATION $format" }

    fun reading() { readStart = nowNs() }

    fun read(count: Int) {
        reads++
        readMaxNs = maxOf(readMaxNs, (nowNs() - readStart).coerceAtLeast(0))
        when {
            count > 0 -> {
                capturedBytes += count
                streamCapturedBytes += count
            }
            count == 0 -> zeroReads++
            else -> readErrors++
        }
    }

    fun encoded(frameCount: Int, emptyCount: Int) {
        encodedFrames += frameCount.coerceAtLeast(0)
        emptyEncodedFrames += emptyCount.coerceAtLeast(0)
    }

    /**
     * Timing of one microphone frame: the encoder's wall time, the encoder's thread CPU time (negative
     * when the platform cannot measure it) and the frame's whole processing from echo cancellation
     * to the last UDP send. A frame that takes longer than its own duration makes capture fall behind.
     */
    fun frame(encodeNs: Long, encodeCpuNs: Long, workNs: Long) {
        val encode = encodeNs.coerceAtLeast(0)
        val work = workNs.coerceAtLeast(0)
        frames++
        encodeSumNs += encode
        encodeMaxNs = maxOf(encodeMaxNs, encode)
        if (encodeCpuNs >= 0) {
            encodeCpuSumNs += encodeCpuNs
            encodeCpuFrames++
            streamEncodeCpuSumNs += encodeCpuNs
            streamEncodeCpuFrames++
        }
        workMaxNs = maxOf(workMaxNs, work)
        if (work > frameBudgetNs) {
            overBudgetFrames++
            streamOverBudgetFrames++
        }
        streamEncode.add(encode)
        streamWork.add(work)
    }

    fun sent() {
        val now = nowNs()
        lastSentNs?.let { sendGapMaxNs = maxOf(sendGapMaxNs, (now - it).coerceAtLeast(0)) }
        lastSentNs = now
        udpSent++
    }

    fun sendFailed() { sendErrors++ }

    fun failure(stage: MicrophoneFailureStage, error: Throwable? = null, code: Int? = null) {
        emit(failureMessage(metadata, stage, error, code))
    }

    /** [details] describes the encoder in the end-of-stream summary, such as `encoder=software complexity=3`. */
    fun flush(ended: Boolean = false, routeType: (() -> Int?)? = null, details: String = "") {
        val now = nowNs()
        if (!ended && now - windowStart < REPORT_INTERVAL_NS) return
        if (routeType != null) routedDeviceType = runCatching { routeType() }.getOrNull()
        emit("Microphone: stats $metadata routedDeviceType=${routedDeviceType ?: "unknown"} " +
            "captureBytes=$capturedBytes reads=$reads zeroReads=$zeroReads readErrors=$readErrors " +
            "readMaxMs=${readMaxNs / 1_000_000} encodedFrames=$encodedFrames " +
            "emptyEncodedFrames=$emptyEncodedFrames udpSent=$udpSent sendErrors=$sendErrors " +
            "sendGapMaxMs=${sendGapMaxNs / 1_000_000} windowMs=${(now - windowStart).coerceAtLeast(0) / 1_000_000} " +
            "audioMs=${capturedBytes * 1_000 / bytesPerSecond} encodeAvgUs=${average(encodeSumNs, frames)} " +
            "encodeMaxUs=${encodeMaxNs / 1_000} encodeCpuAvgUs=${cpuAverage(encodeCpuSumNs, encodeCpuFrames)} " +
            "workMaxUs=${workMaxNs / 1_000} overBudget=$overBudgetFrames ended=$ended")
        if (ended) summarize(now, details)
        windowStart = now
        capturedBytes = 0
        reads = 0
        zeroReads = 0
        readErrors = 0
        readMaxNs = 0
        encodedFrames = 0
        emptyEncodedFrames = 0
        udpSent = 0
        sendErrors = 0
        sendGapMaxNs = 0
        frames = 0
        encodeSumNs = 0
        encodeMaxNs = 0
        encodeCpuSumNs = 0
        encodeCpuFrames = 0
        workMaxNs = 0
        overBudgetFrames = 0
    }

    /** One line per stream that sent frames, so a call or a Siri request can be judged as a whole. */
    private fun summarize(now: Long, details: String) {
        if (summarized || streamEncode.count == 0L) return
        summarized = true
        emit("Microphone: summary $metadata durationMs=${(now - streamStart).coerceAtLeast(0) / 1_000_000} " +
            "audioMs=${streamCapturedBytes * 1_000 / bytesPerSecond} frames=${streamEncode.count} " +
            "encodeAvgUs=${streamEncode.averageMicros()} encodeP95Us=${streamEncode.percentileMicros(95)} " +
            "encodeMaxUs=${streamEncode.maxNs / 1_000} " +
            "encodeCpuAvgUs=${cpuAverage(streamEncodeCpuSumNs, streamEncodeCpuFrames)} " +
            "workAvgUs=${streamWork.averageMicros()} workP95Us=${streamWork.percentileMicros(95)} " +
            "workMaxUs=${streamWork.maxNs / 1_000} overBudget=$streamOverBudgetFrames priority=$priority" +
            (if (details.isBlank()) "" else " ${details.trim()}"))
    }

    private fun emit(message: String) { runCatching { report(message) } }

    private fun average(sumNs: Long, count: Long): Long = if (count > 0) sumNs / count / 1_000 else 0

    private fun cpuAverage(sumNs: Long, count: Long): String =
        if (count > 0) (sumNs / count / 1_000).toString() else "unknown"

    companion object {
        private const val REPORT_INTERVAL_NS = 5_000_000_000L

        private fun typeAndSource(audioType: String): Pair<String, String> = when (audioType) {
            "telephony" -> "telephony" to "VOICE_COMMUNICATION"
            "speechrecognition" -> "speechrecognition" to "VOICE_RECOGNITION"
            else -> "other" to "MIC"
        }

        private fun format(config: MicrophoneConfig): String = "codec=${config.codec.name} " +
            "rate=${config.sampleRate} channels=${config.channels} frameMs=${config.frameMillis}"

        private fun metadata(config: MicrophoneConfig): String {
            val (type, source) = typeAndSource(config.audioType)
            return "type=$type source=$source ${format(config)}"
        }

        private fun failureMessage(
            metadata: String,
            stage: MicrophoneFailureStage,
            error: Throwable?,
            code: Int?,
        ): String = "Microphone: failure $metadata stage=${stage.name} " +
            "error=${error?.javaClass?.simpleName?.replace(Regex("[^A-Za-z0-9_]"), "")?.take(64) ?: "none"} " +
            "code=${code ?: "none"}"

        fun reportStartFailure(config: MicrophoneConfig, error: Throwable, report: (String) -> Unit) {
            runCatching { report(failureMessage(metadata(config), MicrophoneFailureStage.START, error, null)) }
        }
    }
}

/** Durations in 100 µs buckets up to 50 ms: percentiles for a whole stream without keeping every sample. */
internal class DurationHistogram {
    private val buckets = IntArray(BUCKETS + 1)
    var count = 0L
        private set
    var maxNs = 0L
        private set
    private var sumNs = 0L

    fun add(durationNs: Long) {
        val duration = durationNs.coerceAtLeast(0)
        buckets[(duration / BUCKET_NS).coerceAtMost(BUCKETS.toLong()).toInt()]++
        count++
        sumNs += duration
        maxNs = maxOf(maxNs, duration)
    }

    fun averageMicros(): Long = if (count > 0) sumNs / count / 1_000 else 0

    /** The upper edge of the bucket that holds the [percent]th percentile, never above the maximum seen; beyond 50 ms, the maximum. */
    fun percentileMicros(percent: Int): Long {
        if (count == 0L) return 0
        val target = (count * percent.coerceIn(1, 100) + 99) / 100
        var seen = 0L
        for (index in buckets.indices) {
            seen += buckets[index]
            if (seen >= target) return if (index == BUCKETS) maxNs / 1_000 else minOf((index + 1) * BUCKET_NS, maxNs) / 1_000
        }
        return maxNs / 1_000
    }

    private companion object {
        const val BUCKET_NS = 100_000L
        const val BUCKETS = 500
    }
}
