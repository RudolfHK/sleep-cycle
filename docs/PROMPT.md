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
