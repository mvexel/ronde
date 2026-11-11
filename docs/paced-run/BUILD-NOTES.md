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
1. **Paced-run pace window default 30 s** (spec section 7) is its own setting, `SportSettings.pacedWindowSec` (phone: "Paced-run pace window", watch: "Paced-run window"). The older `paceWindowSec` keeps its stored default of 20 s and still drives the old power / HR / cadence / ride alert and `Session.speed`; `encodeDefaults = false` means a stored explicit 20 stays 20 (an earlier revision of this branch changed that default to 30 and silently moved both; reverted, with a test).
2. **Which steps get the paced page** (one predicate, `isPacedStep(sport, step)` in `PacedModel.kt`, used by the page, the ambient page and the service's judge, so they cannot disagree): a pace target with at least one real edge on a Run/Walk/Other step that does not end on a lap press, plus the rest result screen. A lap-ended pace step is neither judged nor buzzed by the judge: it keeps the old page, and the old range alert. Power / HR / cadence targets, Rides, open runs and lap-ended steps keep the old page, the old pill and the old `RangeGauge`; I did not remove them because the spec does not cover them. The old 10 s / 30 s range-alert rule still buzzes for those steps; for pace steps only the judge buzzes.
3. **User slots (flanks, bottom edge) and Big number are ignored on the paced page**: row 1 is time left / avg and the bottom is HR, as drawn. No `Z3` zone: the app has no HR zones (LTHR is parsed but not carried into `Workout`). HR is heart plus number only.
4. **One-sided targets**: the data model has no one-sided target, so `PaceRange.fromSpeeds` treats a low edge of ~0 or a high edge of 50 m/s or more as "no edge". Nothing produces such a target yet; it is drawn and judged (tests + render) but untested on real data. Cap = fast edge only (`SLOWER 5:30`), floor = slow edge only (`FASTER 6:00`).
5. **Grid**: `g = clamp(width/4, 2.5, 5)`; a snapped width under one step is widened by moving the slow edge. A 2.5 grid shows half seconds rounded to whole seconds in the digits.
6. **Step length** for the judge window and for SHORT mode comes from one function, `judgeStepLenSec`: the timed length, or for a distance step its distance times the range centre pace (a 150 m rep at 4:40-5:00 is an estimated 43 s: SHORT on the page and no judgement). Lap steps have none and are not paced (see 2).
7. **Judge lag** (`ExerciseService.JUDGE_LAG_SEC` = 3 s, the same as `PaceSmoother`'s hold): the service judges second *s* once the wall clock reaches *s* + 3. Raw speed per second comes from `SecondSeries`, keyed by each Health Services point's own timestamp (several points in a second are averaged; a second with no point stays empty, nothing is carried forward), so a batch that arrives within 3 s lands in its true seconds and the CSV `raw_speed_mps` is the true per-second speed. A batch later than 3 s (screen off) still reaches the judge as gaps (treated like the sim's 3 % missing samples). The screen therefore shows the judged state about 3 s late, and a buzz fires about 3 s after the judged event. A step change takes effect at its own second; HR and pause state in the log are read for the judged second. Back-filled seconds after a stalled tick (at most 30 s) are judged but never buzz and never spend the budget. While the judge output still belongs to the previous step the page ignores it (`JudgeOut.stepIdx`), so for up to about 3 s after a step starts the hero shows `--:--`.
8. **Buzz budget** (`BuzzGovernor`): "at most 2 per 5 min, then silent until OUT has ended 30 s" literally (lock after 2 buzzes inside 300 s, unlocked by 30 s without OUT). "Re-arm after 5 s IN" = armed again once OUT has been off for 5 s, counted from the end of OUT (so 7 s after the last candidate second), tested; a flicker back into OUT sooner than that never buzzes.
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
19. **Ambient**: built per spec section 8. Lit pixels (any channel above 20, inside the disc) are asserted below 15 % by `PacedAmbientLitPixelTest` (Paparazzi through a capturing snapshot handler, reflection for the image): 11.5 / 11.4 / 10.7 / 10.2 % at 384 px (two-sided, burn-in shifted and paused, narrow range, one-sided cap), 11.3 / 11.2 / 10.7 / 10.5 % at 456 px. **Not done**: disabling anti-aliasing on low-bit ambient devices (strokes are 2 dp or more); the one-sided ambient range text is a smaller 26 sp.
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
- Judge sim parity: `JudgeEngineTest.steadyPaceFalseOutRateMatchesTheSimOrder` runs the sim's noise model (port of `judge-sim.py`, Kotlin RNG, so a different random series) over 24 seeds per cell and bounds the result at 2x the 24-seed python numbers (the shipped 4-seed table is too noisy to bound: one 20 min seed ranges 0-1.1 % false OUT at low noise and 0-8 % at mid). Kotlin results: low false OUT 0.16 % / 0.11 % / 0.02 % / 0.00 % for 120 / 240 / 600 / 1200 s steps (python 0.10 / 0.03 / 0.00 / 0.00), mid 3.6 / 2.5 / 1.6 / 1.4 % (python 2.8 / 2.0 / 1.2 / 1.1), mid false buzzes per 20 min 0.17 / 0.25 / 0.38 / 0.63 (python 1.17 / 0.79 / 0.33 / 0.33), high WEAK 95-97 %. The low-noise 240 s row is about 3x the python rate in relative terms (0.11 vs 0.03 %, about 1.3 s per 20 min); the low-noise bound has a floor of 0.2 %.

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

## Judge fixes after review
- Sigma history, WEAK counters and the marker slot are cleared at each step start and resume, and sigma is measured only over seconds inside the step (and at the mean speed of those seconds). Before this a walking rest followed by a run read as WEAK a few seconds into the work step (test `aWalkingRestFollowedByWorkDoesNotTripWeakAtTheBoundary` failed on the old code). Consequence: no sigma (so margin 5, no WEAK) for the first 10 s of a step.
- Dwell: OUT starts on the 5th consecutive candidate second (first candidate second plus 4 s). That is what `judge-sim.py` does (`cand_run >= D`) and the sim tables assume; the spec sentence "held 5 s" could also mean 6 samples. Kept the sim's semantics; the test comment says so.

## Known issues (deferred)
- The wash gate reads the settings snapshot taken at workout start, the page reads the live settings. They differ only if the phone changes Colour band mid-run; the page requires both.
- Health Services 1.1.0-alpha05 location data has no accuracy: `gps_acc_m` is empty.
- Late batches (more than 3 s) still appear as gaps (see 7).
- Low-bit ambient anti-aliasing is not disabled.
- The CSV is pruned to the newest 30 files at each workout start; there is no size cap on a single file (about 100 bytes per second).
