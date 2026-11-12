package de.sleeptracker.audio

import de.sleeptracker.core.SoundEvent
import de.sleeptracker.core.SoundEventType
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Detects and classifies sound events in a continuous stream of PCM frames.
 *
 * Everything runs in memory. Only when an event has been detected (and capture is enabled)
 * the samples of that event, plus a 1-second pre-roll, are returned so the caller can store
 * an excerpt. Speech is never transcribed.
 */
class SoundEventDetector(
    val sampleRate: Int = 16_000,
    val frameSize: Int = 1024,
    /** 0..1: higher = quieter events are detected. */
    sensitivity: Float = 0.5f,
    var captureExcerpts: Boolean = true,
    private val maxExcerptSeconds: Int = 10,
) {
    data class FrameResult(
        val levelDb: Float,
        /** Frame is clearly above the noise floor (used as sound activity for sleep staging). */
        val active: Boolean,
        val floorDb: Float,
        val event: DetectedEvent?,
    )

    class DetectedEvent(val event: SoundEvent, val pcm: ShortArray?)

    /** Features summarised over an event; exposed for testing. */
    data class EventFeatures(
        val durationSec: Float,
        val peakExcessDb: Float,
        val onsetSharpness: Float,
        val framesToPeak: Int,
        val lowRatio: Float,
        val speechRatio: Float,
        val centroidHz: Float,
        val flatness: Float,
        val levelSdDb: Float,
        val peaksPerSecond: Float,
    )

    private val extractor = FeatureExtractor(sampleRate, frameSize)
    private val floor = NoiseFloorTracker()
    private val onDb = 14f - 8f * sensitivity.coerceIn(0f, 1f)
    private val activeDb = 6f
    private val absoluteMinDb = -80f + 0f
    private val frameSec = frameSize.toFloat() / sampleRate
    private val hangFrames = 5
    private val maxFrames = (30f / frameSec).toInt()

    // Current event state
    private var inEvent = false
    private var eventStartMillis = 0L
    private var quietFrames = 0
    private val frames = ArrayList<FrameFeatures>()
    private val excess = ArrayList<Float>()
    private var eventFloor = 0f

    // Excerpt capture
    private val preRollFrames = max(1, (1f / frameSec).toInt())
    private val ring = ArrayDeque<ShortArray>()
    private val capture = ArrayList<ShortArray>()
    private val maxCaptureFrames = (maxExcerptSeconds / frameSec).toInt()

    private var lastSnoreLikeMillis = Long.MIN_VALUE

    /**
     * @param frame exactly [frameSize] samples.
     * @param timestampMillis wall-clock time of the first sample.
     */
    fun process(frame: ShortArray, timestampMillis: Long): FrameResult {
        require(frame.size >= frameSize)
        val f = extractor.extract(frame)
        val floorDb = if (inEvent) floor.floorDb else floor.update(f.levelDb)
        val above = f.levelDb - floorDb
        val active = above >= activeDb && f.levelDb > absoluteMinDb
        var detected: DetectedEvent? = null

        if (!inEvent) {
            if (above >= onDb && f.levelDb > absoluteMinDb) {
                inEvent = true
                eventStartMillis = timestampMillis
                eventFloor = floorDb
                quietFrames = 0
                frames.clear(); excess.clear(); capture.clear()
                if (captureExcerpts) capture.addAll(ring)
                frames += f; excess += above
                if (captureExcerpts) capture += frame.copyOf(frameSize)
            }
        } else {
            frames += f; excess += above
            if (captureExcerpts && capture.size < maxCaptureFrames + preRollFrames) capture += frame.copyOf(frameSize)
            quietFrames = if (above < activeDb) quietFrames + 1 else 0
            if (quietFrames >= hangFrames || frames.size >= maxFrames) {
                detected = finishEvent()
            }
        }
        ring.addLast(frame.copyOf(frameSize))
        while (ring.size > preRollFrames) ring.removeFirst()
        return FrameResult(f.levelDb, active, floorDb, detected)
    }

    /** Forces the end of a running event (e.g. when tracking stops). */
    fun flush(): DetectedEvent? = if (inEvent) finishEvent() else null

    private fun finishEvent(): DetectedEvent? {
        inEvent = false
        // Trim trailing quiet frames.
        val keep = frames.size - quietFrames.coerceAtMost(frames.size - 1)
        val ef = frames.subList(0, keep).toList()
        val ex = excess.subList(0, keep).toList()
        frames.clear(); excess.clear()
        if (ef.size < 2) return null
        val features = summarise(ef, ex)
        val (type, baseConfidence) = classify(features)
        var confidence = baseConfidence
        if (type == SoundEventType.SNORE) {
            val periodic = lastSnoreLikeMillis != Long.MIN_VALUE &&
                (eventStartMillis - lastSnoreLikeMillis) in 1_500L..10_000L
            confidence = if (periodic) 0.85f else 0.55f
            lastSnoreLikeMillis = eventStartMillis
        }
        val pcm = if (captureExcerpts && capture.isNotEmpty()) {
            val total = capture.sumOf { it.size }
            ShortArray(total).also { out ->
                var pos = 0
                for (c in capture) { c.copyInto(out, pos); pos += c.size }
            }
        } else null
        capture.clear()
        val event = SoundEvent(
            startMillis = eventStartMillis,
            durationMillis = (ef.size * frameSec * 1000).toLong(),
            type = type,
            confidence = confidence,
            intensityDb = features.peakExcessDb,
        )
        return DetectedEvent(event, pcm)
    }

    private fun summarise(f: List<FrameFeatures>, ex: List<Float>): EventFeatures {
        val peak = ex.max()
        val peakIdx = ex.indexOf(peak)
        val weights = ex.map { max(it, 0.1f) }
        val wSum = weights.sum()
        fun wMean(sel: (FrameFeatures) -> Float) = f.indices.sumOf { (sel(f[it]) * weights[it]).toDouble() }.toFloat() / wSum
        val levels = f.map { it.levelDb }
        val mean = levels.average()
        val sd = sqrt(levels.sumOf { (it - mean) * (it - mean) } / levels.size).toFloat()
        // Local maxima of the envelope (≥ 3 dB prominence) ~ syllables / bursts.
        var peaks = 0
        for (i in 1 until ex.size - 1) {
            if (ex[i] > ex[i - 1] && ex[i] >= ex[i + 1]) {
                val left = ex.subList(maxOf(0, i - 3), i).minOrNull() ?: ex[i]
                if (ex[i] - left >= 3f) peaks++
            }
        }
        val duration = f.size * frameSec
        return EventFeatures(
            durationSec = duration,
            peakExcessDb = peak,
            onsetSharpness = ex[0] / peak.coerceAtLeast(0.1f),
            framesToPeak = peakIdx,
            lowRatio = wMean { it.lowRatio },
            speechRatio = wMean { it.speechRatio },
            centroidHz = wMean { it.centroidHz },
            flatness = wMean { it.flatness },
            levelSdDb = sd,
            peaksPerSecond = peaks / duration,
        )
    }

    companion object {
        /**
         * Transparent rule-based classifier. Rules were designed from the acoustic properties of
         * each event class: snoring is low-frequency, gradual and periodic; coughs are short,
         * explosive and broadband; speech is mid-band, voiced (tonal) and syllabically modulated.
         */
        fun classify(e: EventFeatures): Pair<SoundEventType, Float> {
            val sharp = e.onsetSharpness >= 0.7f && e.framesToPeak <= 2
            if (e.durationSec in 0.12f..1.0f && sharp && e.peakExcessDb >= 15f &&
                e.centroidHz >= 700f && e.flatness >= 0.08f && e.lowRatio < 0.5f
            ) return SoundEventType.COUGH to (0.6f + 0.3f * ((e.flatness - 0.08f) / 0.4f).coerceIn(0f, 1f))

            if (e.durationSec <= 1.5f && sharp && e.peakExcessDb >= 18f)
                return SoundEventType.NOISE_DOOR to 0.55f

            if (e.durationSec in 0.3f..4.5f && e.lowRatio >= 0.5f && e.centroidHz < 800f && !sharp)
                return SoundEventType.SNORE to 0.55f

            val speechLike = e.speechRatio >= 0.5f && e.flatness < 0.3f && e.levelSdDb >= 2.5f &&
                e.peaksPerSecond in 1.0f..9f
            if (speechLike && e.durationSec in 0.5f..8f)
                return SoundEventType.TALK to (0.5f + 0.3f * (e.speechRatio - 0.5f).coerceIn(0f, 0.5f) * 2f)
            if (speechLike && e.durationSec > 8f) return SoundEventType.NOISE_TV to 0.5f

            if (e.durationSec >= 2f && e.lowRatio >= 0.45f && e.levelSdDb < 4f)
                return SoundEventType.NOISE_TRAFFIC to 0.45f
            if (e.durationSec in 0.3f..8f) return SoundEventType.NOISE_HOUSEHOLD to 0.4f
            return SoundEventType.NOISE_OTHER to 0.35f
        }
    }
}
