# Night Tracker – offline sleep tracker & smart alarm

A native Android app (Kotlin, Jetpack Compose) that tracks your sleep with the
phone's accelerometer and microphone, estimates sleep phases, scores each
night, keeps a journal with statistics, and wakes you in a light-sleep moment
inside a chosen window. **It works completely offline**: no internet
permission, no account, no cloud, no analytics.

The refined build prompt this project was built from is in
[`docs/PROMPT.md`](docs/PROMPT.md).

## Build

Requirements: JDK 17+, Android SDK (compileSdk 35). Open the project in Android
Studio, or:

```bash
./gradlew :sleepanalysis:test :audio:test   # algorithm unit tests (plain JVM, no SDK needed)
./gradlew :app:assembleDebug                # APK in app/build/outputs/apk/debug/
```

Without an Android SDK configured, Gradle builds only the pure-Kotlin modules.
CI (`.github/workflows/build.yml`) runs the tests, builds the APK and checks that
the APK requests no network permission.

## Architecture

```
:core           Domain models (epochs, stages, sound events, modes) – no Android
:audio          FFT, feature extraction, adaptive noise floor, sound-event
                detection & classification, WAV encoding – no Android
:importer       Sleep Cycle CSV/XLSX reader and parser – no Android
:sleepanalysis  Motion processing, epoch aggregation, sleep staging, session
                summary, sleep score, smart-alarm decider, snooze logic,
                statistics – no Android
:app            Android: Room + DataStore + Keystore crypto, tracking service,
                alarm system, Compose UI, export/backup
```

`:app` is organised by feature (`data/`, `tracking/`, `alarm/`, `maintenance/`,
`ui/sleep`, `ui/journal`, `ui/stats`, `ui/settings`, `ui/alarm`) using MVVM
with a small manual DI container (`AppContainer`). All algorithms live in JVM
modules, so they are unit tested without a device.

### Overnight data flow

```
Accelerometer ─▶ MotionProcessor ─┐
                                  ├─▶ EpochAggregator (30 s epochs) ─▶ Room (flush every 5 min)
Microphone ─▶ SoundEventDetector ─┘                │
                 │                                 └─▶ SmartAlarmDecider (inside window)
                 └─▶ events + encrypted excerpts                │
                                                                ▼
Morning: SessionAnalyzer (staging, summary) + SleepScoreCalculator ─▶ report
```

Ambient light (lux) is aggregated into the same epochs. Raw sensor samples and raw audio are never stored – only 30-second aggregates
and short, encrypted excerpts of detected events.

## Algorithms (all local)

* **Motion** – gravity removed with a slow low-pass filter on the acceleration
  magnitude; movement = deviation above an adaptive noise baseline (works on a
  mattress or a bedside table), with refractory period and burst chunking.
* **Sound** – 16 kHz mono PCM, 64 ms frames, Hann window + FFT. Features: RMS
  level, zero-crossing rate, spectral centroid, spectral flatness, low-band
  (< 400 Hz) and speech-band (300–3400 Hz) energy ratios. An adaptive noise
  floor follows quiet levels quickly and loud ones slowly; events start 6–14 dB
  above the floor (sensitivity setting). Each event is classified by a
  transparent rule model: *snoring* (low-frequency, gradual onset,
  periodicity raises confidence), *cough* (short, explosive, broadband), *sleep
  talking* (voiced, speech band, syllabic modulation – never transcribed),
  *environmental noise* (door/bang, traffic, TV/media, household, other).
* **Sleep staging** – Cole–Kripke-style weighted activity window (4 min back,
  2 min ahead) + Webster rescoring for wake/sleep; sleep onset = first 10 min of
  continuous sleep; sleep epochs are split into deep / light / REM by ranking
  smoothed stillness within ~90-minute ultradian cycles (deep biased to early
  cycles, REM to late cycle ends). Every epoch carries a confidence value.
* **Smart alarm** – inside the window, every 30 s: wake early on a movement
  burst or when the current estimate is awake/light with enough confidence and
  recent activity for 3 consecutive epochs; thresholds relax toward the
  deadline; poor signal never triggers early.
* **Score (1–100)** – duration vs. goal 30 %, efficiency 25 %, interruptions
  20 %, phase balance 15 %, bedtime regularity 10 % (weights adjustable).
* **Statistics** – circular mean/SD for bed and wake times (midnight-safe),
  regularity index, efficiency, sleep debt vs. goal, phase distribution and
  sound-event frequencies per day/week/month/year/all.

* **Bedroom environment** – per 30-s epoch the mean and peak microphone level
  (from frames already analysed in memory) and, if the phone has an ambient
  light sensor, mean/max lux (on-change sensor, last value carried forward).
  Per night: average/peak noise, loud minutes (≥ 10 dB above the night's quiet
  baseline), average/max light, % time dark (< 3 lx), bright minutes (≥ 30 lx),
  light when falling asleep. Noise is shown as *estimated dB* = dBFS + an
  adjustable calibration offset.
* **Habits** – per night: time to bed, time fell asleep (bedtime + latency),
  wake-up, time in bed, time asleep. Per period: circular means of these times,
  average time in bed vs. asleep, weekday vs. weekend nights (by the morning's
  day) and *social jet lag* (shift of mid-sleep between weekends and weekdays).
  Statistics show a sleep-schedule chart (bed → asleep → wake bar per night), a
  time-in-bed vs. asleep chart, and environment averages per period.

> All phases, scores and sleep debt are **estimates** from phone sensors and are
> labelled as such in the UI. This is not a medical device.

## Alarm reliability

* The deadline is scheduled with `AlarmManager.setAlarmClock` **before** tracking
  starts. The smart decider can only make it ring *earlier* (it re-schedules the
  same single `PendingIntent` a second ahead), so a crashed or killed analysis
  can never skip the alarm.
* One fixed `PendingIntent` request code and a per-night instance id → no
  duplicate alarms; stale broadcasts are ignored, unknown state rings anyway.
* The alarm plan is persisted in DataStore and restored after reboot, app
  update, clock change, time-zone change (wall-clock target re-resolved) and
  exact-alarm permission changes. An alarm missed while the phone was off
  rings on boot if less than an hour late.
* Ringing runs in its own `mediaPlayback` foreground service with `USAGE_ALARM`
  audio, gradual volume, vibration, fallback sounds, full-screen intent over the
  lock screen, notification Stop/Snooze actions, volume-button actions,
  double-tap and pick-up-to-snooze, and a 30-minute safety cut-off.
* Snooze: fixed (1–20 min, max N) or intelligent (10 → 7 → 5 → 3 → 2 → 1 min,
  never past the final wake-up time).

## Robustness

* Tracking runs in a `microphone|health` foreground service with a partial wake
  lock (16 h cap). Epochs are flushed to Room every 5 minutes; on unexpected
  destruction the service flushes what it has.
* After a crash, kill or reboot the session is marked **interrupted** – never
  discarded – and can be resumed, analysed, or discarded from the Sleep screen.
* If the microphone is unavailable/revoked, tracking continues with motion (and
  vice versa); missing sensors are offered alternatives before starting.
* Excerpts stop being saved below 100 MB free space; corrupted recordings are
  detected on playback.

## Privacy and data control

* Manifest removes `INTERNET`/`ACCESS_NETWORK_STATE` even if a library adds them;
  `allowBackup=false` and data-extraction rules exclude everything.
* Audio is processed in memory; only enabled event categories produce excerpts,
  stored AES-256-GCM-encrypted with a non-exportable Android Keystore key and
  played back from memory.
* Export CSV/JSON, ZIP backup and restore (Storage Access Framework – you
  choose the location), delete a night (optionally keeping its recordings),
  delete single/all recordings, automatic deletion after N days, delete all data.

## Importing from Sleep Cycle

Settings → Data → **Import from Sleep Cycle** reads a Sleep Cycle export
(`.csv`, or `.xlsx` if the CSV was opened and saved in Excel/Google Sheets).
Both the current export layout (`Went to bed; Woke up; Sleep Quality; Time in
bed (seconds); …; Ambient noise (dB); Ambient light (lux); …; Wake up window
stop`) and the legacy layout (`Start; End; Sleep quality; Time in bed; Wake up;
Sleep Notes; …`) are recognised; delimiter, encoding, decimal commas, Excel
date serials, and spreadsheet artefacts (garbled BOM, `_xHHHH_` escapes, `—`)
are handled. A preview shows how many nights are new, already present (same
bedtime ± 5 min – re-importing is harmless) or unreadable before anything is
written.

Imported nights get: bed/wake/fell-asleep times, time in bed, time asleep,
efficiency, Sleep Cycle's quality as the score, snoring time (snore count
estimated as seconds ÷ 4), coughs (per hour × hours asleep), movements per
hour, breathing disruptions, Sleep Cycle regularity, average noise and light
(an all-zero light column is treated as "not measured"), the smart-alarm
window, and – from legacy files – mood and sleep notes mapped to journal tags.
Sleep Cycle exports contain no hypnogram, so imported nights show no phase
graph and statistics average sleep phases only over nights recorded here.

## Permissions

| Permission | Why |
|---|---|
| `RECORD_AUDIO` | Sound tracking (optional) |
| `FOREGROUND_SERVICE`, `_MICROPHONE`, `_HEALTH`, `_MEDIA_PLAYBACK` | Overnight tracking and ringing |
| `HIGH_SAMPLING_RATE_SENSORS` | Prerequisite of the health foreground-service type (install-time) |
| `POST_NOTIFICATIONS` | Tracking status, alarm, report notifications |
| `USE_EXACT_ALARM` (API 33+) / `SCHEDULE_EXACT_ALARM` (≤ 32) | Exact alarm |
| `USE_FULL_SCREEN_INTENT` | Alarm over the lock screen |
| `RECEIVE_BOOT_COMPLETED` | Restore alarm after reboot |
| `VIBRATE`, `WAKE_LOCK` | Alarm vibration, overnight processing |

## Known limitations

* Sleep phases from a phone are estimates; the classifier is rule-based and
  should be calibrated with real recordings (thresholds are centralised in
  `SleepStager.Config`, `SoundEventDetector.classify` and `ScoreWeights`).
* After a reboot, a microphone foreground service cannot be restarted from the
  background on Android 14+; the session is preserved and the user resumes it.
* Alarms are re-registered on `BOOT_COMPLETED`, i.e. after the first unlock.

## Acceptance checklist → implementation

| Requirement | Where |
|---|---|
| Conventional / smart alarm, configurable window | `TrackingController.start`, `AlarmScheduler`, `SmartAlarmDecider`, `SleepScreen` |
| Alarm never skipped by analysis errors | deadline scheduled first; decider only calls `triggerSoon`; errors → `WAIT` |
| Snooze / intelligent snooze | `SnoozeCalculator`, `AlarmRingService.snooze` |
| Screen off / offline / background | `setAlarmClock`, foreground services, wake locks, no network code |
| Motion, sound, combined tracking | `SleepTrackingService`, `MotionProcessor`, `SoundEventDetector` |
| Interrupted sessions | `TrackingController.recoverIfNeeded/resume/finishInterrupted`, `SystemEventReceiver` |
| Duration, latency, efficiency, phases, score, graphs | `SessionAnalyzer`, `SleepStager`, `SleepScoreCalculator`, `ReportScreen`, `Charts.kt` |
| Snoring, coughing, talking, noise; play/delete excerpts | `SoundEventDetector`, `RecordingRepository`, `ReportScreen`, `RecordingsScreen` |
| Day/week/month/year statistics, notes | `StatisticsScreen`, `StatisticsCalculator`, `ReportScreen.JournalCard` |
| Export, backup/restore, local deletion | `DataExporter`, `SettingsScreen` |
| Noise & light levels per night, environment stats | `EpochAggregator`, `EnvironmentAnalyzer`, `ReportScreen.EnvironmentCard`, `StatisticsScreen.EnvironmentStatsCard` |
| Bedtime / fell asleep / wake habits, time in bed vs. asleep, weekday vs. weekend | `StatisticsCalculator.schedule/socialJetLag`, `StatisticsScreen.HabitsCard`, `HabitCharts.kt` |
| Import Sleep Cycle exports (CSV/XLSX) | `:importer` (`TableReader`, `SleepCycleParser`), `SleepCycleImport`, `SettingsScreen` |
| No internet permission | `AndroidManifest.xml` (`tools:node="remove"`), CI permission check |
