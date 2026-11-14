package de.sleeptracker.app.ui.stats

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import de.sleeptracker.analysis.Insight
import de.sleeptracker.analysis.NightProfile
import de.sleeptracker.analysis.SleepStager
import de.sleeptracker.app.R
import de.sleeptracker.app.data.repo.SessionDetails
import de.sleeptracker.app.ui.Fmt
import de.sleeptracker.app.ui.components.ChartDescription
import de.sleeptracker.app.ui.components.ChartLegend
import de.sleeptracker.app.ui.components.ChartPanel
import de.sleeptracker.app.ui.components.LayerChips
import de.sleeptracker.app.ui.components.LayerColors
import de.sleeptracker.app.ui.components.LegendItem
import de.sleeptracker.app.ui.components.LegendKind
import de.sleeptracker.app.ui.components.MetricRow
import de.sleeptracker.app.ui.components.NightLayer
import de.sleeptracker.app.ui.components.NightOverviewChart
import de.sleeptracker.app.ui.components.NightOverviewData
import de.sleeptracker.app.ui.components.PhaseNight
import de.sleeptracker.app.ui.components.PhaseStackChart
import de.sleeptracker.app.ui.components.ProfileChart
import de.sleeptracker.app.ui.components.SectionCard
import de.sleeptracker.app.ui.components.StageRun
import de.sleeptracker.app.ui.components.rememberChartZoom
import de.sleeptracker.app.ui.components.stageLegend
import de.sleeptracker.app.ui.durationText
import de.sleeptracker.app.ui.journal.luxText
import de.sleeptracker.app.ui.stageName
import de.sleeptracker.app.ui.theme.DataColors
import de.sleeptracker.audio.SoundEpisodes
import de.sleeptracker.core.SleepStage
import de.sleeptracker.core.SoundEvent
import kotlin.math.abs

@Composable
private fun layerName(l: NightLayer) = stringResource(
    when (l) {
        NightLayer.PHASES -> R.string.layer_phases
        NightLayer.NOISE -> R.string.layer_noise
        NightLayer.LIGHT -> R.string.layer_light
        NightLayer.MOVEMENT -> R.string.layer_movement
        NightLayer.SOUNDS -> R.string.layer_sounds
    },
)

/** Day view: everything recorded during the night on one zoomable time axis. */
@Composable
fun NightOverviewPanel(d: SessionDetails, calibration: Int) {
    val s = d.session
    val start = s.startTimestamp
    val end = s.endTimestamp ?: (start + s.timeInBedMinutes * 60_000L)
    val data = remember(d, calibration) {
        val baseline = SleepStager.soundBaseline(d.epochs)
        NightOverviewData(
            startMillis = start,
            endMillis = end,
            onsetMillis = start + s.sleepLatencyMinutes * 60_000L,
            runs = d.stages.map { StageRun(it.timestamp, it.durationSeconds * 1000L, it.stageType) },
            epochTimes = d.epochs.map { it.startMillis },
            noiseDb = d.epochs.map { if (it.hasSound && it.soundLevelDb > -100f) it.soundLevelDb + calibration else Float.NaN },
            lux = d.epochs.map { if (it.hasLight && it.lightLux >= 0f) it.lightLux else Float.NaN },
            movement = d.epochs.map { SleepStager.activityOf(it, baseline).let { a -> if (a.isNaN()) 0f else a } },
            episodes = SoundEpisodes.group(d.soundEvents.map { SoundEvent(it.timestamp, (it.durationSeconds * 1000).toLong(), it.eventType, it.confidence, it.intensityDb) }),
        )
    }
    val available = buildList {
        add(NightLayer.PHASES)
        if (data.hasNoise) add(NightLayer.NOISE)
        if (data.hasLight) add(NightLayer.LIGHT)
        if (data.hasMovement) add(NightLayer.MOVEMENT)
        if (data.episodes.isNotEmpty()) add(NightLayer.SOUNDS)
    }
    var layers by remember(d) { mutableStateOf(available.toSet()) }
    val zoom = rememberChartZoom(((end - start) / 60_000L).toInt().coerceAtLeast(1) / 5, s.id)

    ChartPanel(stringResource(R.string.night_overview), zoom) { expanded ->
        ChartDescription(stringResource(R.string.night_overview_desc))
        // Key figures of the night next to the chart.
        Row(Modifier.fillMaxWidth()) {
            Column(Modifier.weight(1f)) {
                MetricRow(stringResource(R.string.bedtime), Fmt.time(start))
                MetricRow(stringResource(R.string.fell_asleep), Fmt.time(start + s.sleepLatencyMinutes * 60_000L))
                MetricRow(stringResource(R.string.wake_time), Fmt.time(end))
            }
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                MetricRow(stringResource(R.string.asleep), durationText(s.estimatedSleepMinutes))
                MetricRow(stringResource(R.string.stage_deep), durationText(s.deepMinutes))
                MetricRow(stringResource(R.string.sleep_score), "${s.sleepQualityScore}")
            }
        }
        LayerChips(available, layers, { layerName(it) }) { l -> layers = if (l in layers) layers - l else layers + l }
        val notMeasured = stringResource(R.string.not_measured_short)
        NightOverviewChart(
            data,
            layers,
            zoom,
            readout = { t, stage, db, lux, move ->
                listOfNotNull(
                    Fmt.time(t),
                    stage?.let { stageName(it) },
                    if (data.hasNoise) db?.let { "%.0f dB".format(it) } ?: "– dB" else null,
                    if (data.hasLight) lux?.let { luxText(it) } ?: "– lx" else null,
                    move?.takeIf { it > 0f }?.let { stringResource(R.string.readout_movement, it) },
                ).joinToString("  ·  ").ifEmpty { notMeasured }
            },
            height = if (expanded) 360.dp else 220.dp,
            description = stringResource(R.string.night_overview),
        )
        val noiseRange = data.noiseDb.filter { !it.isNaN() }.let { if (it.isEmpty()) null else "%.0f–%.0f dB".format(it.min(), it.max()) }
        val luxRange = data.lux.filter { !it.isNaN() }.let { if (it.isEmpty()) null else "${luxText(it.min())}–${luxText(it.max())}" }
        ChartLegend(
            stageLegend() + listOfNotNull(
                LegendItem(MaterialTheme.colorScheme.onSurface, stringResource(R.string.legend_hypnogram), LegendKind.LINE),
                LegendItem(LayerColors.noise, noiseRange?.let { stringResource(R.string.legend_noise_range, it) } ?: stringResource(R.string.legend_no_noise), LegendKind.AREA),
                LegendItem(LayerColors.light, luxRange?.let { stringResource(R.string.legend_light_range, it) } ?: stringResource(R.string.legend_no_light), LegendKind.LINE),
                if (data.hasMovement) LegendItem(LayerColors.movement, stringResource(R.string.layer_movement)) else null,
                if (data.episodes.isNotEmpty()) LegendItem(DataColors.sound(de.sleeptracker.core.SoundEventType.SNORE), stringResource(R.string.legend_sound_episodes)) else null,
            ),
        )
        Text(stringResource(R.string.legend_markers), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Week/month/…: stacked stage minutes per night with optional noise / darkness lines. */
@Composable
fun PhaseStackPanel(st: StatsUiState) {
    if (st.nights.isEmpty()) return
    val nights = st.nights.map { n ->
        PhaseNight(
            label = shortLabelPublic(n, st.period),
            awake = n.awakeMinutes, rem = n.remMinutes, light = n.lightMinutes, deep = n.deepMinutes,
            noiseDb = if (n.hasNoiseData) n.avgNoiseDb + st.noiseCalibration else null,
            darkPercent = if (n.hasLightData && n.darkPercent >= 0f) n.darkPercent else null,
            hasPhases = n.hasPhaseData,
        ).let { if (!n.hasPhaseData) it.copy(light = n.estimatedSleepMinutes, rem = 0, deep = 0) else it }
    }
    val hasNoise = nights.any { it.noiseDb != null }
    val hasDark = nights.any { it.darkPercent != null }
    var showNoise by remember { mutableStateOf(hasNoise) }
    var showDark by remember { mutableStateOf(false) }
    val zoom = rememberChartZoom(nights.size, st.period to st.offset)
    ChartPanel(stringResource(R.string.phases_per_night), zoom) { expanded ->
        ChartDescription(stringResource(R.string.phases_per_night_desc))
        val chips = buildList { if (hasNoise) add(NightLayer.NOISE); if (hasDark) add(NightLayer.LIGHT) }
        if (chips.isNotEmpty()) {
            LayerChips(chips, buildSet { if (showNoise) add(NightLayer.NOISE); if (showDark) add(NightLayer.LIGHT) }, {
                if (it == NightLayer.NOISE) stringResource(R.string.layer_noise) else stringResource(R.string.layer_darkness)
            }) { if (it == NightLayer.NOISE) showNoise = !showNoise else showDark = !showDark }
        }
        PhaseStackChart(
            nights, showNoise && hasNoise, showDark && hasDark, zoom,
            readout = { n ->
                if (!n.hasPhases) stringResource(R.string.readout_imported_night, n.label, durationText(n.light))
                else listOfNotNull(
                    n.label,
                    "${stageName(SleepStage.DEEP)} ${durationText(n.deep)}",
                    "${stageName(SleepStage.REM)} ${durationText(n.rem)}",
                    "${stageName(SleepStage.LIGHT)} ${durationText(n.light)}",
                    "${stageName(SleepStage.AWAKE)} ${durationText(n.awake)}",
                    n.noiseDb?.let { "%.0f dB".format(it) },
                    n.darkPercent?.let { stringResource(R.string.readout_dark, it) },
                ).joinToString(" · ")
            },
            height = if (expanded) 340.dp else 180.dp,
            description = stringResource(R.string.phases_per_night),
        )
        ChartLegend(
            stageLegend() + listOfNotNull(
                if (nights.any { !it.hasPhases }) LegendItem(MaterialTheme.colorScheme.surfaceVariant, stringResource(R.string.legend_imported)) else null,
                if (hasNoise) LegendItem(LayerColors.noise, stringResource(R.string.legend_avg_noise_scaled), LegendKind.LINE) else null,
                if (hasDark) LegendItem(LayerColors.light, stringResource(R.string.legend_dark_scaled), LegendKind.LINE) else null,
            ),
        )
    }
}

/** Typical night: when each stage usually happens, with noise and light by clock time. */
@Composable
fun ProfilePanel(profile: NightProfile?, calibration: Int) {
    if (profile == null || profile.slots.isEmpty() || profile.totalNights < 2) return
    val hasNoise = profile.slots.any { it.noiseDb != null }
    val hasLight = profile.slots.any { it.lux != null }
    var showNoise by remember { mutableStateOf(hasNoise) }
    var showLight by remember { mutableStateOf(hasLight) }
    val stageInitials = listOf(SleepStage.DEEP, SleepStage.LIGHT, SleepStage.REM, SleepStage.AWAKE).map { it to stageName(it).take(1) }
    ChartPanel(stringResource(R.string.typical_night), zoom = null) { expanded ->
        ChartDescription(stringResource(R.string.typical_night_desc, profile.totalNights))
        val chips = buildList { if (hasNoise) add(NightLayer.NOISE); if (hasLight) add(NightLayer.LIGHT) }
        if (chips.isNotEmpty()) {
            LayerChips(chips, buildSet { if (showNoise) add(NightLayer.NOISE); if (showLight) add(NightLayer.LIGHT) }, { layerName(it) }) {
                if (it == NightLayer.NOISE) showNoise = !showNoise else showLight = !showLight
            }
        }
        ProfileChart(
            profile, showNoise && hasNoise, showLight && hasLight,
            readout = { slot ->
                val time = "${Fmt.minuteOfDay((slot.startMinute + 18 * 60) % 1440)}–${Fmt.minuteOfDay((slot.startMinute + profile.slotMinutes + 18 * 60) % 1440)}"
                if (slot.nightsInBed == 0) "$time · ${stringResource(R.string.readout_nobody_in_bed)}"
                else listOfNotNull(
                    time,
                    stageInitials.joinToString(" ") { (st, initial) -> "$initial ${"%.0f".format((slot.share[st] ?: 0f) * 100)}%" },
                    slot.noiseDb?.let { "%.0f dB".format(it + calibration) },
                    slot.lux?.let { luxText(it) },
                    stringResource(R.string.n_nights, slot.nightsInBed),
                ).joinToString(" · ")
            },
            height = if (expanded) 340.dp else 200.dp,
            description = stringResource(R.string.typical_night),
        )
        ChartLegend(
            stageLegend() + listOfNotNull(
                if (hasNoise) LegendItem(LayerColors.noise, stringResource(R.string.legend_avg_noise_scaled), LegendKind.LINE) else null,
                if (hasLight) LegendItem(LayerColors.light, stringResource(R.string.legend_avg_light_log), LegendKind.LINE) else null,
            ),
        )
        Text(stringResource(R.string.typical_night_height_hint), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Associations in the user's own nights, with group sizes; never presented as causes. */
@Composable
fun InsightsPanel(insights: List<Insight>, nights: Int, calibration: Int) {
    SectionCard(stringResource(R.string.insights)) {
        if (insights.isEmpty()) {
            Text(stringResource(R.string.insights_need_more, de.sleeptracker.analysis.InsightsCalculator.MIN_NIGHTS, nights), style = MaterialTheme.typography.bodySmall)
            return@SectionCard
        }
        insights.forEachIndexed { i, ins ->
            if (i > 0) HorizontalDivider(Modifier.padding(vertical = 6.dp), color = MaterialTheme.colorScheme.outlineVariant)
            InsightRow(ins, calibration)
        }
        Spacer(Modifier.height(6.dp))
        Text(stringResource(R.string.insights_caution), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun InsightRow(ins: Insight, calibration: Int) {
    val (lowLabel, highLabel, title) = when (ins.factor) {
        Insight.Factor.NOISE -> Triple(
            stringResource(R.string.ins_quieter, "%.0f dB".format((ins.threshold ?: 0f) + calibration)),
            stringResource(R.string.ins_louder), stringResource(R.string.layer_noise),
        )
        Insight.Factor.LIGHT -> Triple(stringResource(R.string.ins_darker, luxText(ins.threshold ?: 0f)), stringResource(R.string.ins_brighter), stringResource(R.string.layer_light))
        Insight.Factor.BEDTIME -> Triple(stringResource(R.string.ins_earlier_bed), stringResource(R.string.ins_later_bed), stringResource(R.string.bedtime))
        Insight.Factor.WEEKEND -> Triple(stringResource(R.string.weekdays), stringResource(R.string.weekends), stringResource(R.string.weekday_vs_weekend))
        Insight.Factor.SNORING -> Triple(stringResource(R.string.ins_no_snoring), stringResource(R.string.ins_snoring), stringResource(R.string.snoring))
    }
    val diff = ins.scoreDifference
    val better = if (abs(diff) < 3f) null else if (diff > 0) highLabel else lowLabel
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
        Box(
            Modifier.width(3.dp).fillMaxHeight().background(
                when {
                    better == null -> MaterialTheme.colorScheme.outline
                    else -> MaterialTheme.colorScheme.primary
                },
            ),
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(
                if (better == null) stringResource(R.string.ins_no_difference)
                else stringResource(R.string.ins_better, better, "%.0f".format(abs(diff))),
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                stringResource(
                    R.string.ins_detail,
                    lowLabel, "%.0f".format(ins.lowScore), ins.lowNights, "%.0f".format(ins.lowEfficiency),
                    highLabel, "%.0f".format(ins.highScore), ins.highNights, "%.0f".format(ins.highEfficiency),
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
