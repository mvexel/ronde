package net.mvexel.intervalswatch.mobile.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import net.mvexel.intervalswatch.mobile.RecordingItem
import net.mvexel.intervalswatch.mobile.RecordingLibrary
import net.mvexel.intervalswatch.mobile.UploadWorker
import net.mvexel.intervalswatch.mobile.WatchSync
import net.mvexel.intervalswatch.shared.BuzzStrength
import net.mvexel.intervalswatch.shared.ScreenSettings

/** The FIT files kept on the phone and their upload state. */
interface RecordingsSource {
    /** Bumps whenever an upload settles. */
    val changes: Flow<Int>

    /** Newest first. */
    suspend fun recent(): List<RecordingItem>
    suspend fun retry(fileName: String)
}

class LibraryRecordingsSource(context: Context) : RecordingsSource {
    private val app = context.applicationContext
    private val library = RecordingLibrary.of(app)
    override val changes: Flow<Int> = RecordingLibrary.changes
    override suspend fun recent() = withContext(Dispatchers.IO) { library.recent() }
    override suspend fun retry(fileName: String) = withContext(Dispatchers.IO) { UploadWorker.retry(app, fileName) }
}

/** The watch screen and behaviour settings, synced both ways with the watch. Implemented by ScreenSettingsStore. */
interface ScreenSettingsSource {
    val settings: StateFlow<ScreenSettings>
    suspend fun update(edit: (ScreenSettings) -> ScreenSettings)
}

/** Fire-and-forget messages to the watch. */
fun interface WatchBuzzer {
    suspend fun testBuzz(strength: BuzzStrength)
}

class DataLayerBuzzer(context: Context) : WatchBuzzer {
    private val app = context.applicationContext
    override suspend fun testBuzz(strength: BuzzStrength) = WatchSync.testBuzz(app, strength)
}
