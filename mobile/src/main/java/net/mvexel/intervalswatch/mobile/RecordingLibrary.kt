package net.mvexel.intervalswatch.mobile

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import net.mvexel.intervalswatch.shared.FitToGpx
import net.mvexel.intervalswatch.shared.RecordingProtocol
import java.io.File

enum class UploadState { PENDING, UPLOADED, FAILED }

enum class ExportFormat(val ext: String, val mime: String) {
    FIT("fit", "application/octet-stream"),
    GPX("gpx", "application/gpx+xml"),
}

/** One recording kept on the phone. [startEpochSec] comes from the file name. */
data class RecordingItem(
    val fileName: String,
    val title: String,
    val startEpochSec: Long?,
    val sizeBytes: Long,
    val state: UploadState,
    val reason: String = "",
)

/**
 * The FIT files the watch sent stay in [dir] after upload. Upload state lives in a sidecar
 * `<file>.state` (`uploaded` or `failed:<reason>`); no sidecar means still pending.
 */
class RecordingLibrary(private val dir: File, private val keep: Int = MAX_KEPT) {

    private fun stateFile(fileName: String) = File(dir, "$fileName.state")

    fun file(fileName: String) = File(dir, File(fileName).name)

    fun markUploaded(fileName: String) = stateFile(fileName).writeText("uploaded")

    fun markFailed(fileName: String, reason: String) = stateFile(fileName).writeText("failed:" + reason.lineSequence().firstOrNull().orEmpty())

    /** Back to pending (the user pressed Retry). */
    fun clearState(fileName: String) { stateFile(fileName).delete() }

    fun state(fileName: String): UploadState {
        val s = runCatching { stateFile(fileName).readText() }.getOrNull() ?: return UploadState.PENDING
        return if (s.startsWith("failed")) UploadState.FAILED else UploadState.UPLOADED
    }

    /** Newest first. */
    fun recent(): List<RecordingItem> =
        dir.listFiles { f -> f.name.endsWith(".fit") }.orEmpty().map { f ->
            val state = state(f.name)
            RecordingItem(
                fileName = f.name,
                title = RecordingProtocol.displayName(f.name),
                startEpochSec = f.name.substringBefore('_').toLongOrNull(),
                sizeBytes = f.length(),
                state = state,
                reason = if (state == UploadState.FAILED) runCatching { stateFile(f.name).readText().removePrefix("failed:") }.getOrDefault("") else "",
            )
        }.sortedByDescending { it.startEpochSec ?: 0 }

    /** Drops the oldest uploaded or failed recordings beyond [keep]; pending ones are never removed. */
    fun prune() {
        recent().filter { it.state != UploadState.PENDING }.drop(keep).forEach {
            file(it.fileName).delete()
            stateFile(it.fileName).delete()
        }
    }

    /** The bytes of [fileName] in [format]; GPX is converted from the FIT on demand. */
    fun export(fileName: String, format: ExportFormat): ByteArray {
        val fit = file(fileName).readBytes()
        return when (format) {
            ExportFormat.FIT -> fit
            ExportFormat.GPX -> FitToGpx.convert(fit, RecordingProtocol.displayName(fileName)).toByteArray(Charsets.UTF_8)
        }
    }

    /** Suggested name for an export, e.g. `Tempo_run_2026-10-30.gpx`. */
    fun exportName(fileName: String, format: ExportFormat): String {
        val title = RecordingProtocol.displayName(fileName).replace(Regex("[^\\p{L}\\p{N}]+"), "_").trim('_').ifEmpty { "Ronde" }
        val day = fileName.substringBefore('_').toLongOrNull()?.let { java.time.Instant.ofEpochSecond(it).atZone(java.time.ZoneId.systemDefault()).toLocalDate() }
        return listOfNotNull(title, day?.toString()).joinToString("_") + "." + format.ext
    }

    companion object {
        const val MAX_KEPT = 30

        /** Bumped whenever an upload settles, so an open screen can refresh its list. */
        val changes = kotlinx.coroutines.flow.MutableStateFlow(0)

        /** Deletes share copies left in the cache; call before making a new one. */
        fun cleanExports(ctx: Context) {
            File(ctx.cacheDir, "exports").listFiles()?.forEach { it.delete() }
        }

        fun of(ctx: Context) = RecordingLibrary(File(ctx.filesDir, "recordings").apply { mkdirs() })

        /** An ACTION_SEND chooser for [fileName] as [format], via a FileProvider copy in the cache. */
        fun shareIntent(ctx: Context, fileName: String, format: ExportFormat): Intent {
            val lib = of(ctx)
            cleanExports(ctx)
            val out = File(File(ctx.cacheDir, "exports").apply { mkdirs() }, lib.exportName(fileName, format))
            out.writeBytes(lib.export(fileName, format))
            val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", out)
            val send = Intent(Intent.ACTION_SEND).apply {
                type = format.mime
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            return Intent.createChooser(send, null)
        }
    }
}
