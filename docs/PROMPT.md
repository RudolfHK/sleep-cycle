# Improved build prompt — Offline Sleep Tracker & Smart Alarm

This is the refined version of the original specification that was used to build
this repository. It keeps every functional requirement of the original, but
removes ambiguity, fixes the technical stack to concrete versions, defines the
algorithms precisely enough to be unit-tested, and states what "done" means.

---

## Role and goal

You are a senior Android engineer. Build **an original, fully offline Android
sleep tracker with a smart alarm** (functionally comparable to apps such as
Sleep Cycle, but with its own name, UI and code). The app monitors sleep through
the phone's accelerometer and microphone, estimates sleep phases, scores the
night, keeps a history with statistics, and wakes the user in a light-sleep
moment inside a chosen window — **without ever skipping the alarm**.

Priorities, in order: **alarm reliability → data safety → privacy/offline →
analysis quality → UI polish.**

## Hard constraints (non-negotiable)

1. **No network.** The merged manifest must not contain `INTERNET` or
   `ACCESS_NETWORK_STATE` (strip the latter if a library adds it). No HTTP, no
   analytics, no crash reporting, no runtime model download, no cloud backup
   (`allowBackup=false`, data-extraction rules exclude everything).
2. **All data on device**: Room (SQLite) for records, DataStore for small runtime
   state, app-private storage for audio. Audio excerpts are encrypted with
   AES-256-GCM using a key held in the Android Keystore.
3. **Excluded features:** sleep sounds/music/noise, meditation, stories,
   breathing, coaching, accounts, social, subscriptions, weather, online health.
4. **Medical honesty:** every phase/score/sleep-debt figure is labelled as an
   *estimate*; REM and deep sleep are never presented as measured.

## Stack (fixed)

| Concern | Choice |
|---|---|
| Language | Kotlin 2.1, JVM target 17 |
| UI | Jetpack Compose + Material 3, light/dark/system theme |
| Architecture | MVVM + clean layering, manual DI (`AppContainer`) |
| Build | Gradle 8.11 wrapper, AGP 8.7, version catalog |
| SDK | minSdk 26, target/compileSdk 35 |
| Persistence | Room 2.6 (KSP), DataStore Preferences |
| Background | Foreground service (tracking), `AlarmManager.setAlarmClock` (alarm), `BroadcastReceiver` (alarm, boot, time/zone change), WorkManager (maintenance only) |
| Analysis | Native Kotlin DSP + transparent rule/logistic models shipped in the APK |

### Module layout

Algorithms live in **pure Kotlin/JVM modules** so they are testable without a
device; Android-specific code lives in `:app`, organised by feature package.

```
:core           domain models & enums (no Android)
:audio          FFT, feature extraction, sound-event detection & classification
:sleepanalysis  motion processing, epoching, sleep staging, smart-alarm decider,
                snooze logic, sleep score, statistics
:app            data (Room/DataStore/crypto), tracking service, alarm, UI
                (sleep, journal, statistics, settings), export/backup
```

## Functional scope

### Navigation
Bottom bar with **Sleep · Journal · Statistics · Settings**, plus full-screen
*Tracking*, *Alarm* and *Report* screens.

### Tracking modes
* **A – Smart alarm + tracking**: target time T, window W ∈ {10,15,20,30,45,60,90}
  min. May ring in [T−W, T]; **must** ring at T at the latest.
* **B – Regular alarm + tracking**: rings exactly at T.
* **C – Tracking only**: no alarm.
* Sensor source: motion, sound, or both. If a sensor/permission is missing,
  fall back to what is available and tell the user.

### Alarm (reliability rules)
* The deadline T is always scheduled with `AlarmManager.setAlarmClock` **before**
  tracking starts, independent of the analysis. Early smart wake is decided
  inside the tracking service; if the service dies, the system alarm still fires.
* One alarm instance id per night; ringing is idempotent (no duplicates).
* Rescheduled after reboot, `TIME_SET`, `TIMEZONE_CHANGED`; wall-clock target is
  re-resolved in the current zone.
* Ringing runs in its own foreground service with `USAGE_ALARM` audio, optional
  gradual volume (over 60 s), vibration, full-screen intent on the lock screen,
  and notification actions *Snooze* / *Stop*. A visible Stop button always exists.
* Snooze: fixed (1/2/5/10/15/20 min, max N times) or **intelligent** (10→7→5→3→…
  min, never past T + 0 in smart mode). Optional: double-tap phone, pick-up phone,
  volume buttons → snooze.

### Smart-wake decision (testable definition)
Every 30-s epoch inside the window, compute over the last 10 min:
`activity = Σ wᵢ·movement_countᵢ` and the current estimated stage with
confidence. Ring early when **(stage ∈ {AWAKE, LIGHT} with confidence ≥ 0.6 and
activity ≥ threshold)** or a large movement burst occurs, with a 3-epoch
hysteresis. Threshold relaxes linearly as T approaches. Poor signal quality ⇒
never ring early (deadline still fires).

### Sleep staging (testable definition)
30-s epochs. Inputs: movement count & intensity (high-pass-filtered
accelerometer magnitude), sound level and sound events. Wake/sleep via a
Cole–Kripke-style weighted window on activity; asleep epochs are split into
DEEP / LIGHT / REM using smoothed activity percentiles within the night and the
~90-min ultradian cycle position (deep biased to early cycles, REM to late,
low-motion-but-not-still epochs). Each epoch gets a stage and confidence ∈ [0,1].
Sleep onset = first run of ≥ 10 min asleep. Interruption = awake run ≥ 5 min
after onset.

### Sound events
Process audio in memory (16 kHz mono PCM, 64 ms frames). Adaptive noise floor;
an event starts when level exceeds floor + 10 dB. Classify each event from
spectral features (low-band energy ratio, spectral centroid/flatness, zero
crossing rate, onset sharpness, duration, envelope periodicity) into SNORE,
COUGH, TALK, NOISE. Only excerpts (with 1 s pre-roll, max 10 s) of enabled
categories are saved, encrypted. Speech is never transcribed.

### Score (1–100)
Weighted (configurable): duration vs. goal 30 %, efficiency 25 %,
interruptions 20 %, phase balance 15 %, regularity 10 %.

### Report, journal, statistics
* Report: date, bed/wake times, time in bed, sleep time, latency, efficiency,
  score, phase durations, hypnogram, movement graph, sound-event timeline with
  playback, interruptions, stability, note + tags (stress, coffee, alcohol,
  exercise, late meal, travel, illness, unusual place), comparison with the
  previous 7-night average, delete.
* Journal: chronological list (date, score, duration, bed/wake), grouped by
  month; open any session.
* Statistics for Day/Week/Month/Year/All: averages, min/max, circular-mean
  bed/wake time and their SD, regularity, efficiency, sleep debt vs. goal
  (estimate), phase distribution, snore/cough/talk/noise frequency, charts.

### Data control
CSV and JSON export, ZIP backup (database JSON + decrypted excerpts) and
restore via the Storage Access Framework, delete session / recording / all
recordings / all data, automatic recording deletion after N days, storage
usage display.

### Robustness
* Epoch aggregates are flushed to Room every 5 min during tracking, so a killed
  process or reboot loses at most 5 min; an interrupted session is detected on
  next launch/boot and can be finalised (analysed) or discarded — never silently
  dropped.
* Only one active session at a time.
* Battery: warn if < 20 % and not charging; partial wake lock with timeout; no
  UI updates while the screen is off; batched sensor delivery.
* Graceful handling of denied/revoked permissions, missing sensors, low storage
  (stop saving excerpts), corrupted audio files (skip and report).

## Deliverables and definition of done

1. Buildable project (`./gradlew assembleDebug`) and passing unit tests
   (`./gradlew test`) for `:audio` and `:sleepanalysis`.
2. CI workflow that builds the APK and runs tests on every push.
3. README: architecture, algorithms, permissions, privacy, limitations.
4. The acceptance checklist of the original specification, with each item
   mapped to the code that implements it.

---

# Iteration 2 — environment (noise & light) and sleep habits

Original request: *"Does the app also track noise and light levels during the
night? And does it properly track habits of when going to sleep, how much time
is spent in bed and how much was slept, with local storage and nice stats?"*

Refined prompt:

> Extend the existing app (same stack, same offline/privacy rules) with
> bedroom-environment tracking and a sleep-habits view. Keep the existing data
> and add a Room migration (v1 → v2); old backups must still restore.
>
> **1. Noise level.** Per 30-s epoch, store the mean and the peak audio level
> (dBFS, from the frames already analysed in memory — no extra recording).
> Display it as *estimated dB* = dBFS + a calibration offset (default 90,
> adjustable in Settings, labelled "uncalibrated estimate"). Per night: average
> level, peak level, minutes that were "loud" (epoch mean ≥ 10 dB above the
> night's quiet baseline = 10th percentile).
>
> **2. Light level.** Use the ambient light sensor (`TYPE_LIGHT`, lux) when
> available and enabled (new setting, default on). It is an on-change sensor, so
> carry the last value into epochs without a new reading. Per epoch: mean and max
> lux. Per night: average lux, max lux, % of time dark (< 3 lx), minutes bright
> (≥ 30 lx), lux at sleep onset. Missing sensor ⇒ "not available", never zero.
>
> **3. Habits.** For every night derive: time to bed (start of tracking), time
> fell asleep (start + latency), wake-up time, time in bed, time asleep,
> efficiency. Statistics per period add: circular-mean "fell asleep" time,
> average time in bed vs. time asleep, weekday vs. weekend nights (by the
> morning's day of week; Sat/Sun mornings = weekend) for bedtime, wake-up and
> sleep duration, and *social jet lag* = difference of mid-sleep between weekend
> and weekday nights. Environment averages per period (noise, light, dark %).
>
> **4. UI.** Report: an *Environment* card with noise and light graphs over the
> night and the per-night figures. Statistics: a *Habits* card with a sleep
> schedule chart (one floating bar per night from bedtime to wake-up on an
> evening→morning time axis, with a fell-asleep marker), a time-in-bed vs.
> time-asleep paired bar chart, the weekday/weekend table and social jet lag; an
> *Environment* card with period averages. Tracking screen shows live light and
> noise. CSV export includes the new columns.
>
> **5. Tests.** Unit tests for light carry-forward, noise peak aggregation,
> environment summary, weekday/weekend split and social jet lag.

---

# Iteration 3 — import Sleep Cycle exports

Original request: *"In Sleep Cycle it is possible to export the database as
CSV — please make it possible to import such data into the new app."* (sample:
an export opened/saved in Excel as `.xlsx`).

Refined prompt:

> Add a local, offline importer for Sleep Cycle exports (Settings → Data →
> *Import from Sleep Cycle*), picked through the Storage Access Framework.
>
> **Accepted files.** `.csv` (UTF-8 with/without BOM; `;`, `,` or tab
> delimiter, auto-detected; RFC 4180 quoting; decimal comma or point) and
> `.xlsx` (a CSV re-saved by Excel/Google Sheets: shared strings, inline
> strings, booleans, numeric Excel date serials, `_xHHHH_` escapes, the
> mis-decoded BOM `ï»¿` and mojibake dashes). Parse in a pure-Kotlin module
> (`:importer`) with unit tests; no new dependencies.
>
> **Formats.** Current export: `Went to bed, Woke up, Sleep Quality, Time in bed
> (seconds), Time asleep (seconds), Asleep after (seconds), Regularity, Did snore,
> Snore time (seconds), Coughing (per hour), Steps, City, Breathing disruptions
> (per hour), Ambient noise (dB), Ambient light (lux), Alertness …, Movements per
> hour, Wake up window start, Wake up window stop`. Legacy export: `Start, End,
> Sleep quality (76%), Time in bed (h:mm), Wake up (:) :| :(), Sleep Notes
> (colon-separated), Heart rate, Activity (steps)`. Match headers by normalised
> name, ignore unknown columns, treat `—`/empty as missing.
>
> **Values.** Percent as `76%`, `0.76` or `76`. Dates as Excel serials or text
> (`yyyy-MM-dd HH:mm[:ss]`, `yy-MM-dd …`, ISO, `dd.MM.yyyy …`, `MM/dd/yyyy …`);
> wake-window dates are disambiguated by choosing the parse closest to the
> wake-up time. Wall-clock times are interpreted in the phone's time zone.
>
> **Mapping.** Each row → a completed night with `source = sleep_cycle`: bed/wake
> times, time in bed, time asleep (fallback: in bed − latency), latency,
> efficiency, Sleep Cycle quality as score, snore time (snore count estimated as
> snore seconds ÷ 4 – documented), coughs (per hour × hours asleep), movements
> per hour, breathing disruptions, regularity, noise (stored as dBFS =
> dB − current calibration so it displays as the original dB), light (all-zero
> column ⇒ not measured), smart-alarm window → smart-alarm mode. Legacy notes →
> journal tags (Coffee, Stressful day, Drank alcohol, Worked out, Ate late, …)
> + free text; legacy mood → journal mood.
>
> **Rules.** Rows that are invalid (end ≤ start, > 24 h, unparsable dates) are
> skipped and counted. Nights already in the app (start within ±5 min) are
> skipped, so importing the same file twice is harmless. Show a preview (rows
> found, new, duplicates, invalid, date range) before importing; insert in one
> transaction. Imported nights have no hypnogram/epochs: the report says so and
> statistics average sleep phases only over nights that have phase data. Room
> migration v2 → v3 for the new columns.

---

# Iteration 4 — professional dark/orange design, detailed zoomable statistics

Original request: *"Adjust the layout and design professionally into a dark theme
with orange; corners can be edgier, not as round. Stats should be more detailed,
zoomed out from the start with zoom in on demand or on click. Make the app feel
more professionally designed — layout, where buttons are, etc."*

Refined prompt:

> **Visual system.** Dark theme is the default (light remains selectable) with a
> near-black, slightly warm neutral background, two raised surface levels and a
> single orange accent (`#FF8A1F`, pressed/secondary amber) used for primary
> actions, selection and highlights only. Data colours stay distinguishable from
> the accent and from each other. Shapes are sharp: 2–8 dp corner radii (no
> pills), applied through `MaterialTheme.shapes` and to chart bars. Panels are
> flat with a 1 dp hairline outline instead of tinted cards; section titles are
> small uppercase labels; numbers use tabular figures.
>
> **Layout.** Every top-level screen starts with the same left-aligned header
> (title + context line). The primary action of a screen sits in a fixed
> bottom bar (e.g. *Start sleep tracking* on the Sleep screen), never mid-page.
> Secondary actions are icon buttons in headers or rows. Warnings collapse into
> one banner. Settings are a grouped list with section labels and dividers.
> Report: hero row (score + three key metrics) followed by detail panels.
>
> **Zoomable charts.** All time-series charts (sleep duration, score, schedule,
> time in bed vs. asleep, noise, light, hypnogram) open *zoomed out* showing the
> entire selected period. Users zoom in by pinch, double-tap, or the panel's
> +/− buttons; when zoomed, a one-finger horizontal drag pans (vertical page
> scroll is never captured). A reset button and an expand button (full-screen
> chart dialog) are always available. Tapping a bar/point shows its value; in
> monthly views a tap offers "zoom into this month".
>
> **More detailed statistics.** Default period: *All*. KPI strip with change
> vs. the previous period (sleep, score, efficiency, bedtime). Per-night duration
> bars with a 7-night rolling average and goal line; score line; goal reached
> (% of nights) and longest streak; duration distribution histogram; average
> sleep per weekday; best and worst night (tap to open); plus the existing
> habits, regularity, phases, sounds and environment sections. Calculations live
> in `:sleepanalysis` and are unit tested.

---

# Iteration 5 — fixes from a real test night

Original feedback: *"Light in the environment doesn't seem to be measured.
Detection of sleep phases doesn't work properly with light and deep sleep. The
sensitivity of the mic seems lower than in Sleep Cycle. The segments caught over
the night are too many and too short — it's too sensitive."*

Refined prompt (diagnosis → required change → how to verify):

> **1. Light not measured.** Cause: the ambient light sensor is an on-change
> sensor registered once with a 10 s batching latency; many devices stop
> delivering it while the screen is off and only send a value on registration
> or on change. Fix: register without batching, re-register every 3 minutes to
> force a fresh reading, keep carrying the last value forward, count readings
> per night and show them live on the night screen; if a sensor exists but
> delivered nothing, the report says "no readings (device stops the sensor when
> the screen is off)" instead of a generic "not measured".
>
> **2. Microphone sensitivity.** Causes: the `VOICE_RECOGNITION` source has low
> gain on many phones, a hard −80 dBFS gate drops quiet sounds in quiet rooms,
> and events must exceed the noise floor by 10 dB. Fix: prefer the `MIC` source
> (fall back to `VOICE_RECOGNITION`) with AGC/noise suppression disabled when
> available so levels stay comparable; remove the absolute gate (keep the
> relative floor); default event threshold 7 dB above the floor (sensitivity
> slider 4–11 dB).
>
> **3. Too many / too short segments.** Causes: every snore or noise burst is a
> separate row; stage runs have no minimum length; constant breathing sound
> counts as activity. Fix:
> * Sound: bridge gaps < 0.6 s inside an event, ignore events < 0.25 s, drop
>   environmental-noise events that are < 0.4 s or < 12 dB above the floor;
>   group events of the same category into **episodes** (gap ≤ 60 s for
>   snoring, ≤ 30 s otherwise) for the report list and timeline; save at most one
>   audio excerpt per category every 3 minutes.
> * Stages: remove the night's sound baseline (30th percentile) before scoring
>   activity; brief wake (< 2 min) inside sleep only with a strong movement;
>   enforce minimum run lengths (deep ≥ 10 min, REM ≥ 5 min, light ≥ 3 min,
>   awake ≥ 2 min) by merging short runs into their longer neighbour; smooth
>   over ~12 minutes for the deep/light/REM split.
>
> **4. Verify** with unit tests: a noisy synthetic night yields fewer than ~40
> stage runs with no run below its minimum and deep sleep concentrated early;
> episode grouping; event bridging and minimum duration; quiet events below the
> old −80 dBFS gate are detected.

---

# Iteration 6 — professional visualisation of noise, light and sleep phases

Original request: *"For the daily, weekly etc. stats add better visuals for the
noise, light and sleep phases during the night (daily should be a good zoomed-out
one where noise, light and sleep phases are overlaid and so on). Better analysis
and also descriptions or legend of what is shown. This should have a
professional approach to visualising the recorded data."*

Refined prompt:

> **Principles.** One shared time axis per view; consistent colours everywhere
> (stages as before, noise = warm grey, light = yellow, movement = blue);
> every chart has a title, a one-sentence description of what it shows and how
> to read it, a legend with units and the value range, and a tap read-out.
> Charts open zoomed out; zoom and full screen work as elsewhere. Missing data
> is shown as a gap and stated in the legend ("no light data"), never as zero.
> Imported nights (no epochs) are excluded from per-minute charts with a note.
>
> **Day — "Night overview".** A single overlay chart for the selected night:
> stage colour bands in the background, the hypnogram as a stepped line, noise
> (estimated dB) as a filled area and light (lux, log scale) as a line, all on
> the same time axis, with a bedtime/fell-asleep/wake-up marker row. Under it,
> aligned compact tracks for movement and sound episodes. Layer chips switch
> phases / noise / light / movement / sounds on and off. Tap → crosshair with a
> read-out of all layers at that minute (time, stage, dB, lux, movement). The
> night's key figures sit next to it.
>
> **Week / Month / Year / All.**
> * *Phases per night*: stacked bars (deep, light, REM, awake minutes) per
>   night with optional overlay lines for average noise and dark share
>   (secondary, normalised scales stated in the legend).
> * *Typical night profile*: for each 15-minute clock-time slot (evening →
>   morning), the share of nights in each stage as a 100 % stacked area
>   ("hypnodensity"), with average noise and light per slot as overlay lines and
>   a faint "in bed" share. Answers "when am I usually in deep sleep, when is it
>   loud or bright?".
> * *Insights*: compare score (and efficiency) between quieter vs. louder
>   nights, darker vs. brighter nights, earlier vs. later bedtimes, weekdays vs.
>   weekends, nights with vs. without snoring — split at the median, with the
>   number of nights per group; shown only with ≥ 6 nights and labelled as
>   associations, not causes.
>
> **Implementation.** Aggregations (`NightProfileCalculator`,
> `InsightsCalculator`) in `:sleepanalysis` with unit tests; charts in a
> dedicated component file; profile limited to the latest 120 recorded nights
> for performance.

---

# Iteration 7 — stop-tracking bug and far fewer, meaningful sound events

Original feedback: *"There still seem to be some bugs (after stopping tracking I
just got infinite loading, and others). Events during the night are too
sensitive: Sleep Cycle recorded maybe 10–20, this app 600 in one night. Only real
moments or louder things should be captured."*

Refined prompt:

> **1. Stop tracking must always finish.** Root causes found: the stop and the
> whole-night analysis run in the tracking screen's coroutine on the main thread
> (blocking thread join, CPU-heavy staging); a stop from the notification runs in
> the service's own scope, which the service cancels while shutting down, so the
> night is never analysed; the screen shows a bare spinner forever on any
> failure; while analysing, the Sleep screen offers "resume / discard" for the
> same night. Fix: run stop + analysis once, in the application scope, off the
> main thread, with time limits for each shutdown step; publish a stop state
> (stopping → done / failed) that the tracking screen observes and renders as
> "Saving and analysing your night…"; on failure keep the night (interrupted,
> recoverable) and show an error with a way back; never show the interrupted
> card for a night that is being finalised; stop the service cleanly when a
> stop arrives while it is not running.
>
> **2. Sound events like Sleep Cycle (≈10–30 per night).** Store *episodes*, not
> individual sounds: an online accumulator groups detected sounds per category
> (snoring gap ≤ 60 s, others ≤ 30 s) and stores one event per episode with its
> sound count, total sound time, peak and the loudest excerpt. Episodes are kept
> only when they are real moments:
> * snoring — ≥ 3 snores or ≥ 10 s of snoring;
> * cough — peak ≥ 18 dB above background, confidence ≥ 0.6;
> * talking — ≥ 1.5 s of speech, peak ≥ 12 dB;
> * environmental noise — peak ≥ 22 dB, or ≥ 3 s long and ≥ 16 dB.
> The sound-sensitivity setting shifts all thresholds by ±6 dB; the default is
> tuned for Sleep Cycle-like counts. The detector's start threshold rises to
> 9 dB by default. Snore/cough counts keep the number of individual sounds
> (new `eventCount` column, migration v5). Existing nights are consolidated once
> into episodes with the same rules (recordings of discarded events are deleted).
>
> **3. Other robustness fixes** found while auditing: analysis runs on a
> background dispatcher; recompute heavy statistics only when nights change;
> the notification stop action no longer leaks a service.
>
> **Tests:** accumulator turns hundreds of periodic snores into a handful of
> episodes, rejects faint noise, keeps a loud door bang and a real cough;
> consolidation of stored events; a simulated noisy night stays below 40 events.

---

# Iteration 8 — smoother, calmer charts

Original request: *"Make the visualisations prettier, especially the ones with
few bars which seem kind of irregular. Better to have spline visualisations with
maybe only faint bars in the background."*

Refined prompt:

> **Diagnosis.** Bar width is 70 % of the slot, so with few values (week view,
> weekdays, histogram) bars become very wide blocks; bar edges are not snapped
> to whole pixels, so neighbouring bars render with uneven widths and gaps;
> lines are straight polylines.
>
> **Chart style (all trend charts).**
> * The value series is a **smooth monotone cubic spline** (Fritsch–Carlson:
>   passes through every value, no overshoot above/below the data, so a 0 never
>   dips below the axis) with a soft vertical gradient fill underneath.
> * Values are also shown as **faint background bars** (≈ 15 % opacity) with a
>   capped width (≤ 22 dp) and pixel-snapped edges, centred in their slot;
>   point markers on the curve when ≤ 40 points are visible.
> * Secondary series (e.g. 7-night average) as a thinner spline; reference lines
>   (goal, average) dashed with a small label; 3 horizontal grid lines with
>   y-axis labels in the chart's unit; gaps where data is missing.
> * Tap highlights the point (ring + vertical guide) and shows the read-out;
>   zoom/pan/full-screen behave as before.
> * Time in bed vs. asleep: two splines with the area between them filled
>   (the "awake in bed" band).
> * Typical-night profile: stacked areas drawn with smoothed boundaries.
> * Night-time lines (noise, light) in reports use the same spline + gradient.
> * Stacked phase bars keep bars (they encode composition) but get the width
>   cap and pixel snapping.
>
> **Implementation.** `MonotoneSpline` (pure Kotlin, unit tested: interpolates
> nodes, preserves monotonic segments, no overshoot, handles 1–2 points) and a
> reusable `TrendChart` composable used by every statistics trend.
