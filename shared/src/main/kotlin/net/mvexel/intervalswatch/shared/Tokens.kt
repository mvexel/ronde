package net.mvexel.intervalswatch.shared

/** runbum design tokens (notes/visual-design.md §3, §4, §6) as plain ARGB values; each app wraps them in its own theme. */
object Tokens {
    // Watch (ground is always black)
    const val GROUND = 0xFF000000L
    const val SURFACE = 0xFF16171AL
    const val TRACK = 0xFF2B2D31L
    const val INK = 0xFFFFFFFFL
    const val INK2 = 0xFFC4C8CFL
    const val INK3 = 0xFF8E939BL
    const val WORK = 0xFFFFC53DL
    const val RECOVERY = 0xFF3DC8FFL
    const val WARMUP = 0xFF96969CL // warm-up and cool-down
    const val SIGNAL = 0xFFFF4066L // off target, errors

    // Phone, light ("paper"); dark reuses GROUND-ish values below
    const val PAPER_GROUND = 0xFFF7F5F0L
    const val PAPER_SURFACE = 0xFFFFFFFFL
    const val PAPER_INK = 0xFF15161AL
    const val PAPER_INK2 = 0xFF4A4E57L
    const val PAPER_LINE = 0xFFE4E1DAL
    const val WORK_DEEP = 0xFF7A5200L
    const val RECOVERY_DEEP = 0xFF00658AL
    const val SIGNAL_DEEP = 0xFFC4103DL

    // Phone, dark
    const val DARK_GROUND = 0xFF0E0F12L
    const val DARK_SURFACE = 0xFF1A1C20L
    const val DARK_LINE = 0xFF2B2D31L
}

/** Type scale in sp (§4.1 watch, §4.2 phone). Numerals are tabular ("tnum") everywhere. */
object TypeScale {
    const val WATCH_HERO = 40f
    const val WATCH_PARTNER = 24f
    const val WATCH_TARGET = 17f
    const val WATCH_FIELD = 18f
    const val WATCH_EDGE = 14f
    const val WATCH_TITLE = 15f
    const val WATCH_BODY = 14f
    const val WATCH_LABEL = 11f

    const val PHONE_DISPLAY = 26f
    const val PHONE_TITLE = 17f
    const val PHONE_BODY = 14f
    const val PHONE_SMALL = 13f
    const val PHONE_OVERLINE = 13f
}
