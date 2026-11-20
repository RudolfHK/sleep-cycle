package de.sleeptracker.app.data.repo

import de.sleeptracker.analysis.ChronotypeCalculator
import de.sleeptracker.analysis.Chronotype
import de.sleeptracker.analysis.SleepRegularityIndex
import de.sleeptracker.analysis.SriNight
import de.sleeptracker.analysis.TagImpact
import de.sleeptracker.analysis.TagImpactCalculator
import de.sleeptracker.analysis.TrendAlert
import de.sleeptracker.analysis.TrendAlertCalculator
import de.sleeptracker.app.data.db.AppDatabase
import de.sleeptracker.app.data.db.JournalTags
import de.sleeptracker.app.data.db.SleepSession
import de.sleeptracker.core.SleepStage

/** Derived insights for a set of nights (statistics period or month). */
data class InsightBundle(
    val tagImpacts: List<TagImpact> = emptyList(),
    val chronotype: Chronotype? = null,
    val sri: Float? = null,
    val sriNights: Int = 0,
)

object AnalysisInputs {
    private const val SRI_MAX_NIGHTS = 120

    suspend fun bundle(db: AppDatabase, nights: List<SleepSession>): InsightBundle {
        val journals = db.journalDao().all().associateBy { it.sessionId }
        val records = nights.map { it.toNightRecord() }
        val tagged = nights.map { n -> n.toNightRecord() to (journals[n.id]?.personalTags?.split(",")?.filter { it.isNotBlank() }?.toSet() ?: emptySet()) }
        val recent = nights.sortedBy { it.startTimestamp }.takeLast(SRI_MAX_NIGHTS)
        val sriInput = recent.map { n -> SriNight(n.zoneOffsetMinutes, asleepIntervals(db, n)) }
        return InsightBundle(
            tagImpacts = TagImpactCalculator.compute(tagged, JournalTags.ALL),
            chronotype = ChronotypeCalculator.compute(records),
            sri = SleepRegularityIndex.compute(sriInput),
            sriNights = recent.size,
        )
    }

    /** Asleep intervals: from the hypnogram for recorded nights, onset → wake for imported ones. */
    private suspend fun asleepIntervals(db: AppDatabase, n: SleepSession): List<Pair<Long, Long>> {
        val end = n.endTimestamp ?: return emptyList()
        if (!n.hasPhaseData) return listOf((n.startTimestamp + n.sleepLatencyMinutes * 60_000L) to end)
        return db.signalDao().stages(n.id).filter { it.stageType != SleepStage.AWAKE }
            .map { it.timestamp to it.timestamp + it.durationSeconds * 1000L }
    }

    /** Trend alerts over all recorded nights, relative to [nowMillis]. */
    fun trends(all: List<SleepSession>, nowMillis: Long = System.currentTimeMillis()): List<TrendAlert> =
        TrendAlertCalculator.compute(all.map { it.toNightRecord() }, nowMillis)
}
