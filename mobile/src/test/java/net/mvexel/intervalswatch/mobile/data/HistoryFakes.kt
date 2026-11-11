package net.mvexel.intervalswatch.mobile.data

import net.mvexel.intervalswatch.mobile.IcuActivity
import net.mvexel.intervalswatch.mobile.IcuStream
import net.mvexel.intervalswatch.mobile.data.activity.ActivityRemoteSource
import net.mvexel.intervalswatch.shared.Workout
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

class MutableClock(var instant: Instant, private val zone: ZoneId = ZoneOffset.UTC) : Clock() {
    override fun getZone(): ZoneId = zone
    override fun withZone(zone: ZoneId): Clock = MutableClock(instant, zone)
    override fun instant(): Instant = instant
    fun advanceMinutes(m: Long) { instant = instant.plusSeconds(m * 60) }
    fun advanceDays(d: Long) { instant = instant.plusSeconds(d * 86_400) }
}

fun icu(id: String, start: String, name: String? = "Run $id", analyzed: String? = "a1", streamTypes: List<String>? = null) = IcuActivity(
    id = id, name = name, type = "Run", startDateLocal = start, distance = 5000.0, movingTime = 1500, elapsedTime = 1520,
    averageSpeed = 3.3, averageHeartrate = 140.0, maxHeartrate = 160.0, averageWatts = null, trainingLoad = 30,
    externalId = null, source = "UPLOAD", oauthClientId = null, deviceName = null, pairedEventId = null,
    streamTypes = streamTypes, analyzed = analyzed, trainer = false, note = null,
)

/** intervals.icu in memory: [all] is what the server holds. Each method call is one recorded "API call". */
class FakeHistoryRemote(var all: List<IcuActivity> = emptyList()) : ActivityRemoteSource {
    val calls = mutableListOf<String>()
    var signedIn = true
    var error: Exception? = null
    val details = HashMap<String, IcuActivity>()
    val streams = HashMap<String, List<IcuStream>>()
    val routes = HashMap<String, List<Pair<Double, Double>>>()
    val planned = HashMap<Long, Workout>()

    private fun call(c: String) {
        calls += c
        error?.let { throw it }
    }

    override suspend fun list(oldest: LocalDate, newest: LocalDate?, limit: Int): List<IcuActivity>? {
        if (!signedIn) return null
        call("list $oldest..${newest ?: "now"}/$limit")
        return all.filter {
            val d = LocalDate.parse(it.startDateLocal!!.substring(0, 10))
            d >= oldest && (newest == null || d <= newest)
        }.sortedByDescending { it.startDateLocal }.take(limit)
    }

    override suspend fun activity(id: String): IcuActivity {
        call("activity $id")
        return details[id] ?: all.first { it.id == id }
    }

    override suspend fun streams(id: String, types: List<String>): List<IcuStream> {
        call("streams $id ${types.joinToString(",")}")
        return streams[id].orEmpty()
    }

    override suspend fun route(id: String): List<Pair<Double, Double>> {
        call("route $id")
        return routes[id].orEmpty()
    }

    override suspend fun planned(eventId: Long): Workout? {
        call("planned $eventId")
        return planned[eventId]
    }
}
