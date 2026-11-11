# Paced-run screen: build notes (branch `paced-run-v5`)

Built from `docs/paced-run/design/b-band/v5/v5-design.md` (in the owner's main checkout, not on this branch). **Nothing here is field-tested.** Every threshold is a constant from the spec; the noise assumptions behind them come from `judge-sim.py` and were never measured. The first outdoor run (log below) is what decides them.

## Where things are
| Piece | File (wear module unless noted) |
|---|---|
| Judge (pure Kotlin) | `JudgeEngine.kt` (`JudgeEngine`, `Grid`, `PaceRange`), tests `JudgeEngineTest.kt` |
| Ruler maths | `RulerMath.kt` (tested in `JudgeEngineTest`) |
| Per-second CSV log | `JudgeLog.kt`, tests `JudgeLogTest.kt`; fed from `ExerciseService.judgeTick` |
| Wash (gate + timeline) | `Wash.kt`, tests `WashTest.kt` |
| Page | `PacedStepPage.kt`, `PacedModel.kt` (pure text/verdict helpers), `PacedAmbient.kt` |
| Haptics | `Haptics.kt` unchanged; `judgeBuzzPattern` in `PacedModel.kt` |
| Settings | shared `ScreenSettings.cueRow`, `colourBand`, `PacedRunDefaults.FIRST_RUN_DEFAULTS`; phone `WatchScreensScreen.kt`; watch `SettingsEditor.kt` + `MainActivity.kt` |
| Renders | `PacedScreenshotTest.kt` (384, 456 px and font scale 1.3), images land in `wear/src/test/snapshots/images` (git-ignored) |

## The log (first outdoor run)
- One line per second while a workout is active (paused and ambient included): `files/recordings/judge-<date>.csv` in the app files dir (same folder as the FIT files; the recording store only lists `.fit`). Header is the first line; the file is appended to if you run twice in a day. Flushed every 10 s, and on workout end.
- Pull it (debug build): `adb shell run-as net.mvexel.intervalswatch cat files/recordings/judge-2026-11-12.csv > judge.csv`.
- Fields exactly as spec section 7 (26 columns). `gps_acc_m` is always empty: Health Services 1.1.0-alpha05 `LocationData` carries no accuracy. `state` is never `REST` (the rest result is a page mode, not a judge state). `raw_speed_mps` is the speed Health Services reported for that second (latest sample no older than 2.5 s).
- The log is also the answer to "is the judge too jumpy": count `OUT` episodes on a steady 10-min run, plot `raw_speed_mps` against `display_pace_s_km` and `judge_mean_s_km`, and read `sigma_hat` for the real noise.

## Commands run (Java 21 at `~/.cache/claude-rooms/jdk/jdk-21.0.13+9`, SDK from `local.properties`, offline)
```
export JAVA_HOME=/home/mvexel/.cache/claude-rooms/jdk/jdk-21.0.13+9
./gradlew --offline :shared:test :mobile:testDebugUnitTest :wear:testDebugUnitTest :mobile:assembleDebug :wear:assembleDebug   # BUILD SUCCESSFUL
./gradlew --offline :wear:assembleRelease                                                                                       # BUILD SUCCESSFUL (R8 on)
./gradlew --offline :wear:recordPaparazziDebug --tests '*Paced*'                                                                # renders written
```
`recordPaparazziDebug` for the whole wear suite was not re-run end to end (only the paced classes).

## Deviations and simplifications (spec ambiguous, simpler option picked)
1. **Pace window default 30 s** (spec section 7) instead of the earlier 20 s (T34). Only changes the default of a setting nobody has set; a stored 20 stays 20. The fallback for an unknown value is now 30 too (`DEFAULT_PACE_WINDOW`).
2. **Which steps get the paced page**: a pace target on a Run/Walk/Other step that does not end on a lap press, plus the rest result screen. Power / HR / cadence targets, Rides, open runs and lap-ended steps keep the old page, the old pill and the old `RangeGauge`; I did not remove them because the spec does not cover them. The old 10 s / 30 s range-alert rule still buzzes for those steps; for pace steps only the judge buzzes.
3. **User slots (flanks, bottom edge) and Big number are ignored on the paced page**: row 1 is time left / avg and the bottom is HR, as drawn. No `Z3` zone: the app has no HR zones (LTHR is parsed but not carried into `Workout`). HR is heart plus number only.
4. **One-sided targets**: the data model has no one-sided target, so `PaceRange.fromSpeeds` treats a low edge of ~0 or a high edge of 50 m/s or more as "no edge". Nothing produces such a target yet; it is drawn and judged (tests + render) but untested on real data. Cap = fast edge only (`SLOWER 5:30`), floor = slow edge only (`FASTER 6:00`).
5. **Grid**: `g = clamp(width/4, 2.5, 5)`; a snapped width under one step is widened by moving the slow edge. A 2.5 grid shows half seconds rounded to whole seconds in the digits.
6. **Unknown step length** (distance step: estimated as distance times the range centre pace; lap step: treated as 1200 s so it uses the 60 s window). Lap steps do not use the paced page anyway.
7. **Judge catch-up**: the service steps the judge once per wall second; if a tick stalls it back-fills up to 30 s from the raw speed series, but does not buzz for back-filled seconds. Raw speed for a second is the latest Health Services sample (max 2.5 s old), so batches delivered late while the screen is off arrive after the second was judged.
8. **Buzz budget**: "at most 2 per 5 min, then silent until OUT has ended 30 s" implemented literally (lock after 2 buzzes inside 300 s; unlocked by 30 s of no OUT). "Re-arm after 5 s IN" = 5 s without a candidate after OUT ended.
9. **Anti-strobe (10 s between onsets) is applied to the wash only**, not to OUT itself (cue row, stretch and buzz follow the judge clock). Chronic step-down (60 s) and off (5 min) are wash only too.
10. **Narrow ranges**: fast path is 10 s/km below 10 s/km width (spec text); the sim never exercised it. Buzz is off below 0.10 m/s width. `sigma_hat` uses the current display speed (floored at 1 m/s) as the scale, not the sim's fixed 3.45 m/s: slow walkers will see a large `sigma_hat` for the same speed noise and may sit in WEAK more.
11. **WEAK** freezes the judge's grid value (the marker stays on its last slot); when WEAK ends the normal 3 s hold applies before it moves.
12. **REST mode** is built minimally: only for a rest step that follows a rep with a pace band, first 20 s, from the rep's last band and average (`lastRep` in the session). Hero is the rest time left, row 1 `rep` / `next target` (centre of the next step's range), row 4 `ON PACE` or the cue row, no wash. Rest steps with their own pace target use the normal paced page.
13. **SHORT mode** (step under 60 s): hero time left, row 1 `pace` (after 15 s) / `avg`, grey near stretch only, no hue, no wash, no buzz. Only for timed steps.
14. **NEXT** (last 10 s): `» REST 2:00` (double chevron, label up to 7 characters, length) in the next step's kind colour; the cue row replaces it while OUT.
15. **Step label**: curved, padding 7 dp to approximate baseline radius 76; I did not measure it against the design PNG. Label rule (11 characters, drop counter, ellipsis) is tested.
16. **Haptics kept as they were** (EASE = one 350 ms pulse, PUSH = flutter of five 60 ms pulses; they differ in kind, not count). The brand ruling said "three short = push", but the GO step change is already three 150 ms pulses and `HapticsTest` pins the current patterns, so I did not change them. Decide after feeling them.
17. **Wash timeline**: implemented as a pure `WashAnimator` on a caller clock (300 ms ease-out in, 400 ms ease-in-out colour cross-fade, 600 ms ease-in out; scale from `ANIMATOR_DURATION_SCALE`, 0 = instant). The first fade after the page appears (wake from ambient, back from Controls) takes 150 ms. In Paparazzi/inspection mode the wash is static.
18. **Cue / range text swap** is sequential (onset 75 + 75 ms, recovery 150 out, then 150 in) via two alpha animations; the stretch does not "retract" separately, it follows the marker glide.
19. **Ambient**: built per spec section 8 (11.3-11.5 % lit pixels in the 384 and 456 px renders, measured with a script on the Paparazzi PNGs, not an automated test). **Not done**: disabling anti-aliasing on low-bit ambient devices (strokes are 2 dp or more); the one-sided ambient range text is a smaller 26 sp.
20. **LARGE mode** (font scale at least 1.15) is built (no flanks, labels, HR; hero 56 sp; ruler y 118; row 4 y 158; cue digits 30 sp) and rendered at 1.3, but not looked at against a design PNG (none exists).
21. `FIRST_RUN_DEFAULTS` is `false` (release default). Flip it in `ScreenSettings.kt` (`PacedRunDefaults`) for the owner's first outdoor build so the colour band is ON; or just switch it on in the watch or phone settings.
22. Tokens `BAND`, `PACE_*`, `ALERT_*`, `AMBIENT_*` were added to shared `Tokens`; `Rb` was not extended (the paced page reads `Tokens` directly where the new colours are needed).

## Not verified on a real watch
- Anything about timing and feel: the 5 s dwell, buzz cadence, 3 s hold, 250 ms glide, 150 / 300 / 400 / 600 ms fades.
- Text baselines and sizes with the real system font on a real 384 / 456 px watch (renders use the Paparazzi layoutlib font).
- Whether the OS honours `ANIMATOR_DURATION_SCALE` for the Compose marker glide and the cue fade (the wash reads it explicitly).
- Burn-in offset (random walk +/-3 dp) and that ambient updates arrive about once a minute.
- Haptic patterns, and that buzzes keep firing with the screen off (the judge runs in the service on a partial wake lock, same as the older alert).
- GPS speed noise itself: all `sigma_hat`, WEAK and margin thresholds are from the sim.
- Wash at 100 klux and with polarised lenses (spec "still weak" 5).
- Judge sim parity: `JudgeEngineTest.steadyPaceFalseOutRateMatchesTheSimOrder` runs the sim's noise model (port of `judge-sim.py`, Kotlin RNG, different random series) and gets false OUT of 0.0-0.3 % (low), 2.0-3.9 % (mid), WEAK 97 % (high); sim: 0.0-0.1 %, 1.2-2.6 %, 98 %. The test bounds are loose on purpose. False buzzes per 20 min at mid: 0 to 0.5 here.

## Manual test checklist, first outdoor run
Build debug, set Colour band ON (or flip `FIRST_RUN_DEFAULTS`), keep Cue row on Number for the first half and on Words for a second run.
1. Before: open a pace workout with a 4:40-5:00 style range, wait for the GPS fix. `GPS...` hero, avg `--`, then `settling` for 15 s with a hollow marker.
2. 10 min steady run in range: pull the CSV. Count OUT episodes (target: almost none), false buzzes (target: at most one per 20-30 min), time in WEAK, real `sigma_hat` (the sim assumes about 4.6 / 9.2 / 16.4 s/km sd of the 30 s mean).
3. Deliberate drift 5 / 10 / 15 s/km past an edge on a 10 min step and a 2 min step: note time to NEAR, to OUT (cue, stretch hue, wash on one clock) and to the first buzz; compare with the spec table (about 30-45 s to turn, 40-66 s to buzz on long steps).
4. EASE and PUSH buzz: can you tell them apart without looking, in stride? (Pulse patterns are a single long pulse vs a flutter.)
5. Number cue: read 20 glances at stride and count mistakes against Words. Does the unsigned chevron + number say what to do? Is 26 sp readable at 384 px beside the HR row?
6. Wash: noticeable in shade without looking down at s1? Gone in full sun? With polarised lenses? Any flash or pulse (there must be none)? Does it step down after 60 s of continuous OUT and turn off after 5 min?
7. Weak GPS: run under tree cover or next to a building; the marker should freeze hollow dashed, `weak GPS` in the unit slot, avg still visible, no stretch/wash/buzz; recovery after 10 s clean.
8. Step changes: SETTLING again at each step start and after Pause / Resume; no jump. Rest result: `rep` average, `next target`, ON PACE or the cue row for about 20 s. A 30 s rep: time-left hero, pace after 15 s, grey stretch only, no buzz.
9. NEXT: last 10 s shows `» REST ...`.
10. Wrist drop mid-alert: ambient shows only outlines and the hollow average tick; buzzes still fire; on wake the cue row is there at once, the wash returns at the same severity in about 150 ms, and the marker is already at the live value.
11. Pause: dashed ring, PAUSED pill, 50 % content, no marker, no buzz.
12. Settings: change Cue row and Colour band on the watch and on the phone; both sync both ways. Font scale 1.15+ selects LARGE.
13. Non-pace steps (power, HR, lap-ended) still show the old page and the old alert.
