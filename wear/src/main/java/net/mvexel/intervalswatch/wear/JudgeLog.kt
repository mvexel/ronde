package net.mvexel.intervalswatch.wear

import net.mvexel.intervalswatch.shared.CueRow
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.time.Instant
import java.time.ZoneId
import java.util.Locale

/**
 * The per-second judge log (v5-design.md section 7, "Log line"): one CSV line per second while a workout is active
 * (also paused and ambient) so the first outdoor run yields real GPS speed noise. Empty field = no value.
 * Lives in the same directory as the recording store's FIT files (files/recordings/judge-<date>.csv, pull with
 * `adb shell run-as net.mvexel.intervalswatch cat files/recordings/judge-<date>.csv`); the store only lists `.fit` files.
 */
internal object JudgeLog {
    const val HEADER = "t_ms,step_idx,step_age_s,step_len_s,raw_speed_mps,gps_acc_m,display_pace_s_km,shown_s_km," +
        "judge_mean_s_km,judge_win_s,sigma_hat,margin_m,candidate,fastpath,dwell_s,state,severity,near,wash_on," +
        "alert_age_s,buzz,buzz_5min,hr_bpm,ambient,cue_row,wash_setting"

    data class Row(
        val tMs: Long,
        val stepIdx: Int,
        val stepLenSec: Double?,
        val rawSpeed: Double?,
        /** Health Services 1.1.0-alpha05 location data carries no accuracy: always empty for now. */
        val gpsAccM: Double? = null,
        val judge: JudgeOut,
        val washOn: Boolean,
        val hr: Int?,
        val ambient: Boolean,
        val cueRow: CueRow,
        val washSetting: Boolean,
    )

    private fun d1(v: Double?) = v?.let { String.format(Locale.ROOT, "%.1f", it) } ?: ""
    private fun d2(v: Double?) = v?.let { String.format(Locale.ROOT, "%.2f", it) } ?: ""
    private fun whole(v: Double?) = v?.let { if (it == Math.rint(it)) it.toLong().toString() else String.format(Locale.ROOT, "%.1f", it) } ?: ""
    private fun b(v: Boolean) = if (v) "1" else "0"

    fun line(r: Row): String {
        val j = r.judge
        return listOf(
            r.tMs.toString(), r.stepIdx.toString(), j.stepAgeSec.toLong().coerceAtLeast(0).toString(),
            r.stepLenSec?.let { it.toLong().toString() } ?: "",
            d2(r.rawSpeed), whole(r.gpsAccM), d1(j.displaySKm), whole(j.shownSKm),
            d1(j.judgeMeanSKm), j.judgeWinSec?.toString() ?: "", d1(j.sigmaHat), whole(j.marginSKm),
            j.candidate.code.toString(), b(j.fastPath), j.dwellSec.toString(), j.state.name,
            j.severity.toString(), b(j.near), b(r.washOn), j.alertAgeSec.toString(),
            b(j.buzz != Side.NONE), j.buzzCount5Min.toString(), r.hr?.toString() ?: "",
            b(r.ambient), if (r.cueRow == CueRow.NUMBER) "N" else "W", b(r.washSetting),
        ).joinToString(",")
    }

    fun fileName(epochMs: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        "judge-${Instant.ofEpochMilli(epochMs).atZone(zone).toLocalDate()}.csv"
}

/** Keeps the newest [keep] judge CSVs in [dir] (by name, which sorts by date) and deletes the rest; returns how many it deleted. */
internal fun pruneJudgeLogs(dir: File, keep: Int = 30): Int {
    val files = dir.listFiles { f -> f.name.startsWith("judge-") && f.name.endsWith(".csv") }?.sortedBy { it.name }.orEmpty()
    return files.dropLast(keep).count { runCatching { it.delete() }.getOrDefault(false) }
}

/**
 * Appends [JudgeLog] lines to a day file, flushing at most every [flushEveryMs] (10 s) so a crash loses little and the flash is spared.
 * Never throws: on any I/O failure the log disables itself ([disabled]) and the workout carries on without it.
 */
internal class JudgeLogWriter(
    private val dir: File,
    private val flushEveryMs: Long = 10_000L,
) {
    private var writer: BufferedWriter? = null
    private var name: String? = null
    private var lastFlush = 0L

    @Volatile var disabled = false
        private set

    @Synchronized fun append(row: JudgeLog.Row) {
        if (disabled) return
        try {
            val fileName = JudgeLog.fileName(row.tMs)
            if (writer == null || name != fileName) {
                close()
                dir.mkdirs()
                val f = File(dir, fileName)
                val fresh = !f.exists() || f.length() == 0L
                val w = BufferedWriter(FileWriter(f, true))
                // A failing header write must not leak the open file.
                try { if (fresh) { w.write(JudgeLog.HEADER); w.newLine() } } catch (t: Throwable) { runCatching { w.close() }; throw t }
                writer = w; name = fileName; lastFlush = row.tMs
            }
            writer!!.apply { write(JudgeLog.line(row)); newLine() }
            if (row.tMs - lastFlush >= flushEveryMs) { writer!!.flush(); lastFlush = row.tMs }
        } catch (t: Throwable) {
            disabled = true
            close()
        }
    }

    @Synchronized fun close() {
        runCatching { writer?.flush(); writer?.close() }
        writer = null; name = null
    }
}
