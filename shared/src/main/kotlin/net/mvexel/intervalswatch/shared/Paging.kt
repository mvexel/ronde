package net.mvexel.intervalswatch.shared

/** Running mean of sensor readings over the active session (Totals page, summary S2). */
class RunningMean {
    private var sum = 0.0
    private var n = 0L

    fun add(value: Double) { sum += value; n++ }
    fun clear() { sum = 0.0; n = 0 }

    /** Null until the first reading. */
    val value: Double? get() = if (n == 0L) null else sum / n
}

/** The crown-paged screens of an active workout (settings.md §3). */
enum class WatchPage { STEP, TOTALS, RING }

/** The pages the runner has turned on, Step always first. */
fun pagesFor(profile: SportSettings): List<WatchPage> =
    listOfNotNull(WatchPage.STEP, WatchPage.TOTALS.takeIf { profile.totalsPage }, WatchPage.RING.takeIf { profile.workoutRingPage })

/**
 * Turns crown rotation into page changes (settings.md §3.1). A page changes only after a deliberate turn:
 * [thresholdDetents] detents in one direction. A turn the other way, or a pause longer than [resetMs],
 * starts the count again, so a wrist bending back doesn't page by accident.
 */
class CrownPager(private val thresholdDetents: Float = 2f, private val resetMs: Long = 600) {
    private var accumulated = 0f
    private var lastMs = Long.MIN_VALUE

    /** [detents] is signed (positive = forward). Returns -1, 0 or +1: the page step to take now. */
    fun turn(detents: Float, nowMs: Long): Int {
        if (lastMs != Long.MIN_VALUE && nowMs - lastMs > resetMs) accumulated = 0f
        lastMs = nowMs
        if (accumulated != 0f && (accumulated > 0) != (detents > 0)) accumulated = 0f
        accumulated += detents
        if (kotlin.math.abs(accumulated) < thresholdDetents) return 0
        val dir = if (accumulated > 0) 1 else -1
        accumulated = 0f
        return dir
    }

    fun reset() { accumulated = 0f; lastMs = Long.MIN_VALUE }
}

/** The page after [step] pages from [current], stopping at both ends. */
fun movePage(pages: List<WatchPage>, current: WatchPage, step: Int): WatchPage {
    val i = pages.indexOf(current).coerceAtLeast(0)
    return pages[(i + step).coerceIn(0, pages.lastIndex)]
}
