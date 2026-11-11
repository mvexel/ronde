package net.mvexel.intervalswatch.shared

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FitReaderTest {

    private val rec = Recording(
        name = "Tempo",
        sport = Sport.RUN,
        indoor = false,
        startEpochMs = 1_790_000_000_000L,
        samples = listOf(
            RecSample(0.0, 0.0, 52.1, 5.1, 10.0, 3.0, 120, null, 170),
            RecSample(1.0, 3.0, 52.1001, 5.1001, 10.4, 3.2, 130, 200, 172),
            RecSample(2.0, 6.0),
            RecSample(10.0, 9.0, hr = 140, speed = 3.0),
        ),
        laps = listOf(RecLap(0.0, 2.0, 0.0, 6.0, StepKind.WARMUP), RecLap(2.0, 11.0, 6.0, 9.0, StepKind.ACTIVE)),
        pauses = listOf(RecPause(3.0, 8.0)),
        elapsedSec = 11.0,
        totalM = 9.0,
    )

    @Test fun readsBackWhatFitWriterWrote() {
        val back = FitReader.read(FitWriter.encode(rec), "Tempo")
        assertEquals(Sport.RUN, back.sport)
        assertEquals(rec.startEpochMs, back.startEpochMs)
        assertEquals(11.0, back.elapsedSec, 1e-3)
        assertEquals(9.0, back.totalM, 1e-6)
        assertEquals(4, back.samples.size)
        val s1 = back.samples[1]
        assertEquals(1.0, s1.t, 0.0)
        assertEquals(3.0, s1.distanceM, 1e-6)
        assertEquals(52.1001, s1.lat!!, 1e-6)
        assertEquals(5.1001, s1.lon!!, 1e-6)
        assertEquals(10.4, s1.altM!!, 0.2)
        assertEquals(3.2, s1.speed!!, 1e-3)
        assertEquals(130, s1.hr)
        assertEquals(200, s1.power)
        assertEquals(172, s1.cadence) // FIT stores strides/min for runs; read back as steps/min
        assertNull(back.samples[2].hr)
        assertNull(back.samples[2].lat)
        assertEquals(listOf(RecPause(3.0, 8.0)), back.pauses)
        assertEquals(2, back.laps.size)
        assertEquals(2.0, back.laps[1].startSec, 1e-3)
        assertEquals(11.0, back.laps[1].endSec, 1e-3)
        assertEquals(StepKind.WARMUP, back.laps[0].kind)
        assertEquals(6.0, back.laps[1].startM, 1e-6)
    }

    @Test fun feedsActivityStreamsAndStepBoundaries() {
        val back = FitReader.read(FitWriter.encode(rec))
        val streams = ActivityStreams.from(back)
        assertEquals(4, streams.size)
        assertEquals(listOf(TimeSpan(3.0, 8.0)), streams.pauses)
        assertEquals(listOf(TimeSpan(0.0, 2.0), TimeSpan(2.0, 11.0)), ActivityStreams.stepBoundaries(back))
    }

    @Test fun indoorRideKeepsCadenceAsIs() {
        val ride = rec.copy(sport = Sport.RIDE, indoor = true)
        val back = FitReader.read(FitWriter.encode(ride))
        assertEquals(Sport.RIDE, back.sport)
        assertEquals(true, back.indoor)
        assertEquals(172, back.samples[1].cadence)
    }
}
