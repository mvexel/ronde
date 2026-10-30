package net.mvexel.intervalswatch.shared

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ParserTest {

    private val ride = """
    [{
      "id": 46696127,
      "start_date_local": "2026-11-02T07:00:00",
      "type": "Ride",
      "category": "WORKOUT",
      "name": "4x8m VO2",
      "workout_doc": {
        "duration": 3120, "ftp": 280, "target": "POWER",
        "steps": [
          {"duration": 600, "warmup": true, "ramp": true,
           "power": {"start": 35, "end": 55, "units": "%ftp"},
           "_power": {"value": 126, "start": 98, "end": 154}},
          {"reps": 4, "text": "4x", "steps": [
            {"duration": 480, "power": {"value": 110, "units": "%ftp"},
             "_power": {"value": 308, "start": 300, "end": 316},
             "cadence": {"start": 90, "end": 100, "units": "rpm"}},
            {"duration": 240, "intensity": "rest", "power": {"value": 50, "units": "%ftp"}}
          ]},
          {"duration": 600, "cooldown": true, "power": {"value": 50, "units": "%ftp"}}
        ]
      }
    },
    {"id": 2, "category": "NOTE", "name": "note"}]
    """.trimIndent()

    private val run = """
    [{
      "id": 7, "type": "Run", "category": "WORKOUT", "name": "Tempo",
      "start_date_local": "2026-11-03T06:30:00",
      "workout_doc": {
        "threshold_pace": 4.0, "pace_units": "MINS_KM", "target": "PACE",
        "steps": [
          {"distance": 2000, "pace": {"value": 75, "units": "%pace"}},
          {"duration": 1200, "pace": {"start": 330, "end": 300, "units": "secs/km"}, "hr": {"value": 165, "units": "bpm"}},
          {"until_lap_press": true, "duration": 600, "text": "Easy until lap"}
        ]
      }
    }]
    """.trimIndent()

    @Test
    fun flattensRepeatsAndUsesResolvedValues() {
        val ws = IntervalsParser.parseEvents(ride)
        assertEquals(1, ws.size)
        val w = ws[0]
        assertEquals(Sport.RIDE, w.sport)
        assertEquals(1 + 4 * 2 + 1, w.steps.size)

        val warm = w.steps[0]
        assertEquals(StepKind.WARMUP, warm.kind)
        val wt = warm.primaryTarget!!
        assertTrue(wt.isRamp)
        assertEquals(98.0, wt.rampFrom!!, 0.01)
        assertEquals(154.0, wt.rampTo!!, 0.01)

        val work = w.steps[1]
        assertEquals("1/4", work.repeat)
        assertEquals(Metric.POWER, work.primary)
        assertEquals(300.0, work.primaryTarget!!.low, 0.01)
        assertNotNull(work.target(Metric.CADENCE))

        // Rest without _power: converted from %ftp with tolerance.
        val rest = w.steps[2]
        assertEquals(StepKind.REST, rest.kind)
        assertEquals(140.0, (rest.primaryTarget!!.low + rest.primaryTarget!!.high) / 2, 0.01)
        assertEquals("4/4", w.steps[8].repeat)
    }

    @Test
    fun stridesRecoveryByPaceIsRecovery() {
        // Real intervals.icu data (owner, A07): 4x 20s at 5:30-5:00/km, 1m at 11:00-9:00/km. No text, no intensity.
        val strides = """
        [{
          "id": 9, "type": "Run", "category": "WORKOUT", "name": "Strides",
          "start_date_local": "2026-11-04T06:30:00",
          "workout_doc": {
            "threshold_pace": 4.0, "pace_units": "MINS_KM", "target": "PACE",
            "steps": [
              {"reps": 4, "steps": [
                {"duration": 20, "pace": {"start": 330, "end": 300, "units": "secs/km"}, "_pace": {"start": 3.03, "end": 3.33}},
                {"duration": 60, "pace": {"start": 660, "end": 540, "units": "secs/km"}, "_pace": {"start": 1.515, "end": 1.852}}
              ]}
            ]
          }
        }]
        """.trimIndent()
        val w = IntervalsParser.parseEvents(strides).single()
        assertEquals(8, w.steps.size)
        assertEquals(StepKind.ACTIVE, w.steps[0].kind)
        assertEquals("Work", w.steps[0].label)
        assertEquals("1/4", w.steps[0].repeat)
        assertEquals(StepKind.REST, w.steps[1].kind)
        assertEquals("Recovery", w.steps[1].label)
        assertEquals("1/4", w.steps[1].repeat)
        assertEquals("4/4", w.steps[7].repeat)
        assertEquals("Recovery", w.steps[7].label)
    }

    @Test
    fun powerRecoveryInRepeatIsRecovery() {
        val doc = """
        [{"id": 11, "type": "Ride", "category": "WORKOUT", "start_date_local": "2026-11-04T07:00:00",
          "workout_doc": {"ftp": 280, "target": "POWER", "steps": [
            {"reps": 4, "steps": [
              {"duration": 480, "power": {"value": 110, "units": "%ftp"}},
              {"duration": 240, "power": {"value": 50, "units": "%ftp"}}
            ]}
          ]}}]
        """.trimIndent()
        val w = IntervalsParser.parseEvents(doc).single()
        assertEquals(StepKind.ACTIVE, w.steps[0].kind)
        assertEquals("Work", w.steps[0].label)
        assertEquals(StepKind.REST, w.steps[1].kind)
        assertEquals("Recovery", w.steps[1].label)
        assertEquals("4/4", w.steps[7].repeat)
    }

    @Test
    fun singleStepBlockIsUnchanged() {
        val doc = """
        [{"id": 12, "type": "Run", "category": "WORKOUT", "start_date_local": "2026-11-04T06:30:00",
          "workout_doc": {"threshold_pace": 4.0, "pace_units": "MINS_KM", "target": "PACE", "steps": [
            {"reps": 3, "steps": [
              {"duration": 60, "pace": {"value": 60, "units": "%pace"}}
            ]}
          ]}}]
        """.trimIndent()
        val w = IntervalsParser.parseEvents(doc).single()
        assertEquals(3, w.steps.size)
        assertTrue(w.steps.all { it.kind == StepKind.ACTIVE && it.label == "Work" })
    }

    @Test
    fun restStepKeepsItsOwnText() {
        val doc = """
        [{"id": 13, "type": "Run", "category": "WORKOUT", "start_date_local": "2026-11-04T06:30:00",
          "workout_doc": {"steps": [{"duration": 60, "intensity": "recovery", "text": "Work"}]}}]
        """.trimIndent()
        val s = IntervalsParser.parseEvents(doc).single().steps.single()
        assertEquals(StepKind.REST, s.kind)
        assertEquals("Work", s.label)
    }

    @Test
    fun recoveryTextWithoutIntensityIsRest() {
        val doc = """
        [{"id": 10, "type": "Run", "category": "WORKOUT", "start_date_local": "2026-11-04T06:30:00",
          "workout_doc": {"steps": [{"duration": 90, "text": "Recover"}]}}]
        """.trimIndent()
        val s = IntervalsParser.parseEvents(doc).single().steps.single()
        assertEquals(StepKind.REST, s.kind)
        assertEquals("Recover", s.label)
    }

    @Test
    fun runPaceDistanceAndLap() {
        val w = IntervalsParser.parseEvents(run).single()
        assertEquals(Sport.RUN, w.sport)
        val s0 = w.steps[0]
        assertEquals(2000.0, s0.distanceM!!, 0.01)
        assertNull(s0.durationSec)
        assertEquals(3.0, (s0.primaryTarget!!.low + s0.primaryTarget!!.high) / 2, 0.01)

        val s1 = w.steps[1]
        assertEquals(Metric.PACE, s1.primary)
        assertEquals(1000.0 / 330, s1.primaryTarget!!.low, 0.001)
        assertEquals(1000.0 / 300, s1.primaryTarget!!.high, 0.001)
        assertNotNull(s1.target(Metric.HEART_RATE))

        assertTrue(w.steps[2].endsOnLap)
        assertEquals("4:10", Format.pace(4.0, PaceUnit.KM))
    }

    @Test
    fun engineAdvancesWithCarryOver() {
        val w = IntervalsParser.parseEvents(run).single()
        val e = WorkoutEngine(w)
        e.start()
        assertEquals(0, e.update(300.0, 1999.0))
        assertEquals(1, e.update(301.0, 2003.0))
        assertEquals(1, e.index)
        // step 1 is 1200 s, started at 301
        assertEquals(0, e.update(1500.0, 5000.0))
        assertEquals(1, e.update(1502.0, 5000.0))
        assertEquals(2, e.index)
        // lap-ended step never ends by itself
        assertEquals(0, e.update(99999.0, 99999.0))
        e.lap(100000.0, 100000.0)
        assertTrue(e.finished)
    }

    @Test
    fun rampWindowMoves() {
        val t = Target(Metric.POWER, 100.0, 200.0, rampFrom = 100.0, rampTo = 200.0)
        assertEquals(0, t.compare(150.0, 0.5))
        assertEquals(-1, t.compare(150.0, 1.0))
    }

    @Test
    fun bundleRoundTrip() {
        val ws = IntervalsParser.parseEvents(ride)
        val b = Sync.decode(Sync.encode(WorkoutBundle(1L, ws)))
        assertEquals(ws, b.workouts)
    }

    @Test fun cadenceTargetRangeComparesStepsPerMinute() {
        val t = Target(Metric.CADENCE, 170.0, 180.0)
        assertEquals(-1, t.compare(160.0, 0.0))
        assertEquals(0, t.compare(175.0, 0.0))
        assertEquals(1, t.compare(190.0, 0.0))
    }

    @Test
    fun walkParsesAsWalkWithPaceAndCadenceLabel() {
        val w = Samples.walk
        assertEquals(Sport.WALK, w.sport)
        assertEquals(Sport.WALK, IntervalsParser.sportOf("Walk"))
        assertEquals(Sport.RUN, IntervalsParser.sportOf("TrailRun"))
        assertEquals(Metric.PACE, w.steps.first().primary)
        assertEquals("spm", Format.unitLabel(Metric.CADENCE, w.paceUnit, w.sport))
    }
}
