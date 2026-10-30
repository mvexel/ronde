package net.mvexel.intervalswatch.wear

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.health.services.client.ExerciseUpdateCallback
import androidx.health.services.client.HealthServices
import androidx.health.services.client.data.Availability
import androidx.health.services.client.data.DataType
import androidx.health.services.client.data.DataTypeAvailability
import androidx.health.services.client.data.ExerciseLapSummary
import androidx.health.services.client.data.ExerciseType
import androidx.health.services.client.data.ExerciseUpdate
import androidx.health.services.client.data.LocationAvailability
import androidx.health.services.client.data.WarmUpConfig
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import net.mvexel.intervalswatch.shared.Sport
import net.mvexel.intervalswatch.shared.Workout

/** Pre-start sensor state shown on the workout detail (ux D1). Grey = searching, green = ready; never blocks Start. */
data class Readiness(val gps: Boolean = false, val hr: Boolean = false)

/**
 * Warms up GPS and heart rate with Health Services' prepare call while the detail screen is open,
 * so the first minutes of an outdoor run have a fix. Without the permissions nothing is requested
 * and the dots stay grey; the permission prompt still happens at Start.
 */
class ReadinessProbe(private val ctx: Context) {
    private val client = HealthServices.getClient(ctx).exerciseClient
    private val _state = MutableStateFlow(Readiness())
    val state: StateFlow<Readiness> = _state.asStateFlow()
    private var preparing = false

    private fun has(p: String) = ContextCompat.checkSelfPermission(ctx, p) == PackageManager.PERMISSION_GRANTED

    private val callback = object : ExerciseUpdateCallback {
        override fun onExerciseUpdateReceived(update: ExerciseUpdate) = Unit
        override fun onLapSummaryReceived(lapSummary: ExerciseLapSummary) = Unit
        override fun onRegistered() = Unit
        override fun onRegistrationFailed(throwable: Throwable) = Unit
        override fun onAvailabilityChanged(dataType: DataType<*, *>, availability: Availability) {
            when {
                availability is LocationAvailability -> _state.value = _state.value.copy(
                    gps = availability == LocationAvailability.ACQUIRED_TETHERED || availability == LocationAvailability.ACQUIRED_UNTETHERED,
                )
                dataType == DataType.HEART_RATE_BPM -> _state.value = _state.value.copy(hr = availability == DataTypeAvailability.AVAILABLE)
            }
        }
    }

    suspend fun start(w: Workout) {
        val gps = !w.indoor && has(Manifest.permission.ACCESS_FINE_LOCATION)
        // Indoors there is no GPS to wait for; show it as ready so only HR is left to turn green.
        if (w.indoor) _state.value = _state.value.copy(gps = true)
        val hrPerm = if (android.os.Build.VERSION.SDK_INT >= 36) "android.permission.health.READ_HEART_RATE" else Manifest.permission.BODY_SENSORS
        if (!has(hrPerm) && !gps) return
        val type = when (w.sport) {
            Sport.RUN -> if (w.indoor) ExerciseType.RUNNING_TREADMILL else ExerciseType.RUNNING
            Sport.RIDE -> if (w.indoor) ExerciseType.BIKING_STATIONARY else ExerciseType.BIKING
            Sport.WALK -> ExerciseType.WALKING
            Sport.OTHER -> ExerciseType.WORKOUT
        }
        runCatching {
            val caps = client.getCapabilitiesAsync().await().getExerciseTypeCapabilities(type)
            val wanted = buildSet {
                if (has(hrPerm)) add(DataType.HEART_RATE_BPM)
                if (gps) add(DataType.LOCATION)
            }.filter { it in caps.supportedDataTypes }.toSet()
            client.setUpdateCallback(callback)
            client.prepareExerciseAsync(WarmUpConfig(type, wanted)).await()
            preparing = true
        }
    }

    /** Stops the warm-up unless an exercise took over (Start pressed) or the service already runs one. */
    suspend fun stop(started: Boolean) {
        if (!preparing || started) return
        preparing = false
        runCatching { client.endExerciseAsync().await() }
        runCatching { client.clearUpdateCallbackAsync(callback).await() }
    }
}
