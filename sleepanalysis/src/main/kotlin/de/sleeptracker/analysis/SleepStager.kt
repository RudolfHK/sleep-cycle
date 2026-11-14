package de.sleeptracker.analysis

import de.sleeptracker.core.EPOCH_MILLIS
import de.sleeptracker.core.Epoch
import de.sleeptracker.core.SleepStage
import de.sleeptracker.core.StagedEpoch
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.min

/**
 * Estimates sleep stages from 30-second epochs.
 *
 * 1. **Activity** per epoch from accelerometer motion counts and/or the fraction of audio frames
 *    above the noise floor (snoring is subtracted because it indicates sleep, not movement).
 * 2. **Wake/sleep** with a Cole–Kripke style weighted window (4 min back, 2 min ahead) followed
 *    by Webster-style rescoring rules.
 * 3. **Sleep onset**: first 10 minutes of uninterrupted sleep.
 * 4. **Deep / REM / light**: sleep epochs are ranked by smoothed activity (stillness) and combined
 *    with the typical ~90 minute ultradian cycle structure (deep sleep dominates early cycles,
 *    REM late cycle ends). This is a heuristic estimate, not a polysomnographic measurement.
 */
class SleepStager(private val config: Config = Config()) {

    data class Config(
        /** Smoothed activity above this means awake. Lower = more epochs scored awake. */
        val wakeThreshold: Float = 1.2f,
        /** Single-epoch activity that is always awake. */
        val strongWakeActivity: Float = 6f,
        val onsetEpochs: Int = 20,
        val cycleMinutes: Int = 90,
        /** Minimum run lengths in epochs (30 s): deep 10 min, REM 5 min, light 3 min, awake 2 min. */
        val minDeepEpochs: Int = 20,
        val minRemEpochs: Int = 10,
        val minLightEpochs: Int = 6,
        val minAwakeEpochs: Int = 4,
        /** Half-width (epochs) of the smoothing used for the deep/light/REM split (~12 min). */
        val phaseSmoothHalf: Int = 12,
    )

    data class Result(
        val epochs: List<StagedEpoch>,
        /** Index of the first epoch of sleep onset, or −1 if no sleep was detected. */
        val onsetIndex: Int,
    )

    fun stage(epochs: List<Epoch>): Result {
        val n = epochs.size
        if (n == 0) return Result(emptyList(), -1)
        // Constant background sound (breathing, fan) is not movement: remove the night's baseline.
        val baseline = soundBaseline(epochs)
        val activity = FloatArray(n) { activityOf(epochs[it], baseline) }
        val valid = BooleanArray(n) { !activity[it].isNaN() }

        // --- 2. wake / sleep -------------------------------------------------------------
        val smoothed = FloatArray(n) { weightedWindow(activity, valid, it) }
        val awake = BooleanArray(n) { i ->
            if (!valid[i]) false else smoothed[i] > config.wakeThreshold || activity[i] >= config.strongWakeActivity
        }
        fillGaps(awake, valid)
        rescore(awake)
        // A brief "wake" inside sleep is kept only with a strong movement.
        dropBriefWake(awake, activity)

        // --- 3. onset ---------------------------------------------------------------------
        var onset = -1
        var run = 0
        for (i in 0 until n) {
            if (!awake[i]) {
                run++
                if (run >= min(config.onsetEpochs, n)) {
                    onset = i - run + 1
                    break
                }
            } else run = 0
        }
        if (onset < 0) {
            val staged = List(n) { i ->
                StagedEpoch(epochs[i].startMillis, SleepStage.AWAKE, if (valid[i]) 0.6f else 0.2f, activity.orZero(i))
            }
            return Result(staged, -1)
        }
        for (i in 0 until onset) awake[i] = true

        // --- 4. deep / REM / light -------------------------------------------------------
        val stages = Array(n) { if (awake[it]) SleepStage.AWAKE else SleepStage.LIGHT }
        val longSmooth = FloatArray(n) { movingAverage(activity, valid, it, config.phaseSmoothHalf) }
        val sleepIdx = (0 until n).filter { !awake[it] }
        val rank = FloatArray(n)
        sleepIdx.sortedWith(compareBy({ longSmooth[it] }, { it })).forEachIndexed { r, idx ->
            rank[idx] = if (sleepIdx.size > 1) r.toFloat() / (sleepIdx.size - 1) else 0.5f
        }
        val onsetMillis = epochs[onset].startMillis
        val cycleMillis = config.cycleMinutes * 60_000L
        val byCycle = sleepIdx.groupBy { ((epochs[it].startMillis - onsetMillis) / cycleMillis).toInt() }
        for ((cycle, indices) in byCycle) {
            val phase = { i: Int ->
                val t = (epochs[i].startMillis - onsetMillis).toDouble() / cycleMillis
                (t - floor(t)).toFloat()
            }
            val deepTarget = (indices.size * DEEP_FRACTION.at(cycle)).toInt()
            val remTarget = (indices.size * REM_FRACTION.at(cycle)).toInt()
            val deepCandidates = indices.filter { rank[it] < 0.75f }
                .sortedByDescending { i ->
                    val p = phase(i)
                    (1f - rank[i]) * (if (p in 0.08f..0.65f) 1f else 0.45f)
                }
            deepCandidates.take(deepTarget).forEach { stages[it] = SleepStage.DEEP }
            val remCandidates = indices.filter {
                stages[it] == SleepStage.LIGHT && epochs[it].startMillis - onsetMillis >= 60 * 60_000L
            }.sortedByDescending { i ->
                val p = phase(i)
                (if (p >= 0.6f) 1f else 0.3f) * (1f - abs(rank[i] - 0.35f))
            }
            remCandidates.take(remTarget).forEach { stages[it] = SleepStage.REM }
        }
        val lastSleep = stages.indexOfLast { it != SleepStage.AWAKE }
        if (lastSleep > onset) enforceMinRuns(stages, onset, lastSleep)

        val staged = List(n) { i ->
            val base = if (!valid[i]) 0.2f else {
                val margin = abs(smoothed[i] - config.wakeThreshold) / config.wakeThreshold
                (0.55f + 0.4f * min(margin, 1f))
            }
            val conf = when (stages[i]) {
                SleepStage.AWAKE -> base
                SleepStage.LIGHT -> base * 0.8f
                SleepStage.DEEP -> base * 0.7f
                SleepStage.REM -> base * 0.6f
            }
            StagedEpoch(epochs[i].startMillis, stages[i], conf.coerceIn(0f, 1f), activity.orZero(i))
        }
        return Result(staged, onset)
    }

    private fun FloatArray.orZero(i: Int) = if (this[i].isNaN()) 0f else this[i]

    private fun weightedWindow(a: FloatArray, valid: BooleanArray, i: Int): Float {
        var sum = 0f
        var wSum = 0f
        for (k in WEIGHTS.indices) {
            val j = i + k - WEIGHT_CENTER
            if (j < 0 || j >= a.size || !valid[j]) continue
            sum += WEIGHTS[k] * a[j]
            wSum += WEIGHTS[k]
        }
        return if (wSum == 0f) Float.NaN else sum / wSum * WEIGHT_GAIN
    }

    private fun movingAverage(a: FloatArray, valid: BooleanArray, i: Int, half: Int): Float {
        var sum = 0f
        var c = 0
        for (j in (i - half)..(i + half)) {
            if (j < 0 || j >= a.size || !valid[j]) continue
            sum += a[j]; c++
        }
        return if (c == 0) 0f else sum / c
    }

    /** Epochs without signal inherit the previous decision. */
    private fun fillGaps(awake: BooleanArray, valid: BooleanArray) {
        for (i in awake.indices) if (!valid[i]) awake[i] = if (i == 0) true else awake[i - 1]
    }

    /** Webster rescoring: short sleep after long wake is wake; isolated tiny sleep runs are wake. */
    private fun rescore(awake: BooleanArray) {
        val n = awake.size
        var wakeRun = 0
        var i = 0
        while (i < n) {
            if (awake[i]) {
                wakeRun++
                i++
            } else {
                if (wakeRun >= 8) {
                    // After ≥4 min awake, the next minute of "sleep" is still awake.
                    var k = 0
                    while (k < 2 && i < n && !awake[i]) { awake[i] = true; i++; k++ }
                }
                wakeRun = 0
                while (i < n && !awake[i]) i++
            }
        }
        // Sleep runs ≤ 3 min surrounded by ≥ 10 min of wake on both sides become wake.
        val runs = runs(awake)
        for (r in runs.indices) {
            val (value, start, len) = runs[r]
            if (value || len > 6) continue
            val prev = runs.getOrNull(r - 1)
            val next = runs.getOrNull(r + 1)
            if (prev != null && next != null && prev.third >= 20 && next.third >= 20) {
                for (j in start until start + len) awake[j] = true
            }
        }
    }

    private fun runs(a: BooleanArray): List<Triple<Boolean, Int, Int>> {
        val out = ArrayList<Triple<Boolean, Int, Int>>()
        var s = 0
        for (i in 1..a.size) {
            if (i == a.size || a[i] != a[s]) {
                out += Triple(a[s], s, i - s)
                s = i
            }
        }
        return out
    }

    private fun dropBriefWake(awake: BooleanArray, activity: FloatArray) {
        val first = awake.indexOfFirst { !it }
        val last = awake.indexOfLast { !it }
        if (first < 0 || last <= first) return
        var i = first
        while (i <= last) {
            if (!awake[i]) { i++; continue }
            val s = i
            while (i <= last && awake[i]) i++
            val len = i - s
            val peak = (s until i).maxOf { if (activity[it].isNaN()) 0f else activity[it] }
            if (len < config.minAwakeEpochs && peak < config.strongWakeActivity * 1.5f) {
                for (j in s until i) awake[j] = false
            }
        }
    }

    private fun minRun(stage: SleepStage) = when (stage) {
        SleepStage.DEEP -> config.minDeepEpochs
        SleepStage.REM -> config.minRemEpochs
        SleepStage.LIGHT -> config.minLightEpochs
        SleepStage.AWAKE -> config.minAwakeEpochs
    }

    /**
     * Merges runs shorter than their stage's minimum into the longer neighbouring run, shortest
     * first, between sleep onset and the last sleep epoch. Produces stable, readable phases.
     */
    private fun enforceMinRuns(stages: Array<SleepStage>, from: Int, to: Int) {
        repeat(stages.size) {
            data class Run(val stage: SleepStage, val start: Int, val len: Int)
            val runs = ArrayList<Run>()
            var s = from
            for (i in from + 1..to + 1) {
                if (i > to || stages[i] != stages[s]) { runs += Run(stages[s], s, i - s); s = i }
            }
            val idx = runs.indices.filter { runs[it].len < minRun(runs[it].stage) && runs.size > 1 }
                .minByOrNull { runs[it].len } ?: return
            val r = runs[idx]
            val prev = runs.getOrNull(idx - 1)
            val next = runs.getOrNull(idx + 1)
            // Prefer a sleep neighbour over wake, then the longer one.
            val target = listOfNotNull(prev, next).sortedWith(
                compareBy<Run>({ if (r.stage != SleepStage.AWAKE && it.stage == SleepStage.AWAKE) 1 else 0 }, { -it.len }),
            ).first().stage
            for (j in r.start until r.start + r.len) stages[j] = target
        }
    }

    /** Removes stage flicker shorter than 1.5 minutes among sleep stages. */
    private fun modeFilter(stages: Array<SleepStage>) {
        val copy = stages.copyOf()
        for (i in stages.indices) {
            if (copy[i] == SleepStage.AWAKE) continue
            val counts = IntArray(SleepStage.entries.size)
            for (j in (i - 2)..(i + 2)) {
                if (j < 0 || j >= copy.size || copy[j] == SleepStage.AWAKE) continue
                counts[copy[j].ordinal]++
            }
            val best = counts.indices.maxBy { counts[it] }
            if (counts[best] > counts[copy[i].ordinal]) stages[i] = SleepStage.entries[best]
        }
    }

    companion object {
        /** Weights for epoch offsets −8 … +4 (Cole–Kripke shape adapted to 30 s epochs). */
        private val WEIGHTS = floatArrayOf(0.5f, 0.5f, 0.6f, 0.6f, 0.7f, 0.8f, 1.0f, 1.3f, 4.0f, 1.3f, 1.0f, 0.8f, 0.7f)
        private const val WEIGHT_CENTER = 8
        /** Scales the weighted mean so that the centre epoch dominates like in Cole–Kripke. */
        private const val WEIGHT_GAIN = 1.6f
        private val DEEP_FRACTION = floatArrayOf(0.40f, 0.30f, 0.16f, 0.08f, 0.05f)
        private val REM_FRACTION = floatArrayOf(0.08f, 0.20f, 0.27f, 0.32f, 0.35f)

        private fun FloatArray.at(i: Int) = this[i.coerceIn(0, size - 1)]

        /**
         * Activity value of an epoch, NaN when the epoch contains no signal.
         * Motion counts are capped at 10 per epoch; sound activity above the night's baseline maps 10 % → 1 (cap 8).
         */
        /** 30th percentile of non-snore sound activity over the night (constant background). */
        fun soundBaseline(epochs: List<Epoch>): Float {
            val v = epochs.filter { it.hasSound }.map { nonSnoreSound(it) }.sorted()
            return if (v.size < 20) 0f else v[(v.size * 0.3).toInt()]
        }

        private fun nonSnoreSound(e: Epoch) = (e.soundActivity - e.snoreSeconds / (EPOCH_MILLIS / 1000f)).coerceAtLeast(0f)

        fun activityOf(e: Epoch, soundBaseline: Float = 0f): Float {
            val motion = if (e.hasMotion) min(e.motionCount.toFloat(), 10f) else Float.NaN
            val sound = if (e.hasSound) {
                val nonSnore = (nonSnoreSound(e) - soundBaseline).coerceAtLeast(0f)
                // 10 % extra active frames ≈ one movement; a rustle while turning over is movement,
                // not wake, so sound alone saturates below the "strong wake" level for long sounds only.
                min(nonSnore * 10f, 8f)
            } else Float.NaN
            return when {
                !motion.isNaN() && !sound.isNaN() -> min(motion + 0.5f * sound, 12f)
                !motion.isNaN() -> motion
                !sound.isNaN() -> sound
                else -> Float.NaN
            }
        }
    }
}
