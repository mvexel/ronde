package net.mvexel.intervalswatch.mobile.ui

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import net.mvexel.intervalswatch.shared.StepKind

/**
 * Raw hex values of brand direction B "Paper and slab" (docs/phone/design/design.md, Compose mapping table).
 * Screens should not use these directly: read [androidx.compose.material3.MaterialTheme.colorScheme] or
 * [RondeTheme.colors], which pick the light or dark value.
 */
object RondePalette {
    // Step kinds: only on black (ring discs, slab) or as chart/route colours.
    val Work = Color(0xFFFFC53D) // also Ronde Amber, the brand
    val Recovery = Color(0xFF3DC8FF)
    val Warmup = Color(0xFF96969C) // warm-up and cool-down
    val RingDisc = Color(0xFF111214) // the launcher icon's disc
    val Tick = Color(0xFFFFFFFF)

    // Light ("paper")
    val Paper = Color(0xFFF7F5F0)
    val PaperSurface = Color(0xFFFFFFFF)
    val PaperSurface2 = Color(0xFFEFECE5)
    val PaperLine = Color(0xFFE4E1DA)
    val PaperInk = Color(0xFF15161A)
    val PaperInk2 = Color(0xFF4A4E57)
    val PaperInk3 = Color(0xFF6B6F78)
    val AmberDeep = Color(0xFF7A5200)
    val PaceFastDeep = Color(0xFFC4103D) // = signal deep
    val PaceSlowDeep = Color(0xFF3D5BE0) // new in direction B
    val PaperTintWork = Color(0xFFFFF0CD)
    val PaperTintEasy = Color(0xFFE0F6FF)
    val PaperTintWu = Color(0xFFF2F2F3)

    // Dark (the watch palette on #0E0F12)
    val Night = Color(0xFF0E0F12)
    val NightSurface = Color(0xFF1A1C20)
    val NightSurface2 = Color(0xFF222429)
    val NightLine = Color(0xFF2B2D31)
    val NightNav = Color(0xFF16171A)
    val NightInk = Color(0xFFFFFFFF)
    val NightInk2 = Color(0xFFC4C8CF)
    val NightInk3 = Color(0xFF8E939B)
    val PaceFast = Color(0xFFFF5160) // = signal
    val PaceSlow = Color(0xFF5586FF)
    val NightTintWork = Color(0xFF3F3725)
    val NightTintEasy = Color(0xFF1F323D)
    val NightTintWu = Color(0xFF24262A)

    // Slab: the black "piece of the watch face" card.
    val SlabLight = Color(0xFF15161A)
    val SlabDark = Color(0xFF000000)

    // Onboarding plate (direction C, allowed on onboarding only).
    val PlateInk2 = Color(0xFF4D3800)

    // Map style (design.md `m_land`, `m_minor`, `m_park`, `m_water`); the MapLibre JSON styles in assets/map match.
    val MapLandLight = Color(0xFFEEEBE4)
    val MapMinorLight = Color(0xFFFFFFFF)
    val MapParkLight = Color(0xFFDDE4D5)
    val MapWaterLight = Color(0xFFC8D6DF)
    val MapLandDark = Color(0xFF141518)
    val MapMinorDark = Color(0xFF1F2125)
    val MapParkDark = Color(0xFF121A15)
    val MapWaterDark = Color(0xFF0B1820)

    // HR zone ramps (summary only, always with Z1-Z5 labels).
    val HrZonesDark = listOf(Color(0xFF009F68), Color(0xFF00BB7B), Color(0xFF00D98F), Color(0xFF00F7A4), Color(0xFFB4FFD6))
    val HrZonesLight = listOf(Color(0xFF9ADBC0), Color(0xFF4FC596), Color(0xFF00A66B), Color(0xFF007A4E), Color(0xFF004D31))
}

/**
 * The Ronde colours that are not [androidx.compose.material3.ColorScheme] slots. Read them with
 * `RondeTheme.colors`. Names follow design.md's "Compose mapping" table (`pace.fast` -> [paceFast],
 * `tint.work` -> [tintWork], ...), and the Activity Detail screen reads its chart, route and slab colours from here.
 *
 * | Token          | design.md        | Light                  | Dark                       | Use |
 * |----------------|------------------|------------------------|----------------------------|-----|
 * | [work]         | work             | `#FFC53D`              | `#FFC53D`                  | Work arcs, route; only on black or as a chart colour |
 * | [recovery]     | recovery         | `#3DC8FF`              | `#3DC8FF`                  | Easy/rest arcs, route |
 * | [warmup]       | warmup           | `#96969C`              | `#96969C`                  | Warm-up and cool-down arcs, route |
 * | [ringDisc]     | (icon disc)      | `#111214`              | `#111214`                  | Disc behind a ring |
 * | [tick]         | (white tick)     | `#FFFFFF`              | `#FFFFFF`                  | The tick at 12 o'clock |
 * | [brandText]    | amber text       | `#7A5200`              | `#FFC53D`                  | "Today", brand words on the ground (= colorScheme.tertiary) |
 * | [paceFast]     | pace.fast        | `#C4103D` (deep)       | `#FF5160`                  | "▼ fast" text and out-of-range line (= colorScheme.error) |
 * | [paceSlow]     | pace.slow        | `#3D5BE0` (deep, new)  | `#5586FF`                  | "▲ slow" text and line |
 * | [paceFastDeep] | pace.fast deep   | `#C4103D`              | `#C4103D`                  | Fixed deep value, for paper-only artwork |
 * | [paceSlowDeep] | pace.slow.deep   | `#3D5BE0`              | `#3D5BE0`                  | Fixed deep value |
 * | [tintWork]     | tint.work        | `#FFF0CD`              | `#3F3725`                  | Chart shading behind work steps |
 * | [tintEasy]     | tint.easy        | `#E0F6FF`              | `#1F323D`                  | Chart shading behind easy steps |
 * | [tintWu]       | tint.wu          | `#F2F2F3`              | `#24262A`                  | Chart shading behind warm-up/cool-down |
 * | [band]         | band             | ink @ 16 %             | ink @ 22 %                 | Target range band in charts |
 * | [bandEdge]     | band edge        | ink @ 55 %             | ink @ 60 %                 | Dashed top and bottom edge of the band |
 * | [grid]         | (gridlines)      | ink3 @ 35 %            | ink3 @ 35 %                | Chart gridlines |
 * | [ink3]         | ink3             | `#6B6F78`              | `#8E939B`                  | Captions, axis labels (= colorScheme.outline) |
 * | [hrZones]      | hrZone1..5       | `#9ADBC0`..`#004D31`   | `#009F68`..`#B4FFD6`       | HR zone ladder, Z1 first |
 * | [slab]         | slab             | `#15161A`              | `#000000`                  | Watch slab, detail hero, badge |
 * | [slabEdge]     | slab edge        | none                   | `#2B2D31`                  | 1 dp edge around the slab in dark mode |
 * | [onSlab]       | ink on slab      | `#FFFFFF`              | `#FFFFFF`                  | Titles and numbers on the slab |
 * | [onSlab2]      | ink2 on slab     | `#C4C8CF`              | `#C4C8CF`                  | Secondary text on the slab (10.8:1) |
 * | [onSlab3]      | ink3 on slab     | `#8E939B`              | `#8E939B`                  | Captions on the slab (6.0:1) |
 * | [badge]        | Ronde chip       | `#15161A`              | `#000000`                  | "Ronde" chip ground |
 * | [onBadge]      | chip text        | `#FFC53D`              | `#FFC53D`                  | "Ronde" chip text (11.5:1 / 13.3:1) |
 * | [badgeEdge]    | chip edge        | none                   | `#2B2D31`                  | Chip edge in dark mode |
 * | [mapLand]      | m_land           | `#EEEBE4`              | `#141518`                  | Map ground, and the route card before tiles load |
 * | [mapMinor]     | m_minor          | `#FFFFFF`              | `#1F2125`                  | Minor roads (also the offline route sketch's grid) |
 * | [mapPark]      | m_park           | `#DDE4D5`              | `#121A15`                  | Parks |
 * | [mapWater]     | m_water          | `#C8D6DF`              | `#0B1820`                  | Water |
 * | [casing]       | casing           | `#15161A`              | `#000000`                  | Route casing, mandatory on the light map (15.2:1) |
 * | [routeNeutral] | (no step)        | `#4A4E57` (ink2)       | `#FFFFFF`                  | Route stretches outside any step |
 * | [navBar]       | NavigationBar    | `#EFECE5`              | `#16171A`                  | Bottom bar ground |
 * | [plate]        | plate (C)        | `#FFC53D`              | `#FFC53D`                  | Amber band: onboarding/login only |
 * | [plateInk]     | plate ink        | `#15161A`              | `#000000`                  | Text on the plate (11.5:1 / 13.3:1) |
 * | [plateInk2]    | plate ink2       | `#4D3800`              | `#4D3800`                  | Secondary text on the plate (7.1:1) |
 */
@Immutable
data class RondeColors(
    val work: Color,
    val recovery: Color,
    val warmup: Color,
    val ringDisc: Color,
    val tick: Color,
    val brandText: Color,
    val paceFast: Color,
    val paceSlow: Color,
    val paceFastDeep: Color,
    val paceSlowDeep: Color,
    val tintWork: Color,
    val tintEasy: Color,
    val tintWu: Color,
    val band: Color,
    val bandEdge: Color,
    val grid: Color,
    val ink3: Color,
    val hrZones: List<Color>,
    val slab: Color,
    val slabEdge: Color?,
    val onSlab: Color,
    val onSlab2: Color,
    val onSlab3: Color,
    val badge: Color,
    val onBadge: Color,
    val badgeEdge: Color?,
    val mapLand: Color,
    val mapMinor: Color,
    val mapPark: Color,
    val mapWater: Color,
    val casing: Color,
    val routeNeutral: Color,
    val navBar: Color,
    val plate: Color,
    val plateInk: Color,
    val plateInk2: Color,
    val isDark: Boolean,
) {
    /** Ring, route and legend colour of a step kind. */
    fun kind(k: StepKind): Color = when (k) {
        StepKind.ACTIVE -> work
        StepKind.REST -> recovery
        StepKind.WARMUP, StepKind.COOLDOWN -> warmup
    }

    /** Chart shading behind a step of kind [k]. */
    fun tint(k: StepKind): Color = when (k) {
        StepKind.ACTIVE -> tintWork
        StepKind.REST -> tintEasy
        StepKind.WARMUP, StepKind.COOLDOWN -> tintWu
    }

    companion object {
        val Light = RondeColors(
            work = RondePalette.Work, recovery = RondePalette.Recovery, warmup = RondePalette.Warmup,
            ringDisc = RondePalette.RingDisc, tick = RondePalette.Tick,
            brandText = RondePalette.AmberDeep,
            paceFast = RondePalette.PaceFastDeep, paceSlow = RondePalette.PaceSlowDeep,
            paceFastDeep = RondePalette.PaceFastDeep, paceSlowDeep = RondePalette.PaceSlowDeep,
            tintWork = RondePalette.PaperTintWork, tintEasy = RondePalette.PaperTintEasy, tintWu = RondePalette.PaperTintWu,
            band = RondePalette.PaperInk.copy(alpha = 0.16f), bandEdge = RondePalette.PaperInk.copy(alpha = 0.55f), grid = RondePalette.PaperInk3.copy(alpha = 0.35f),
            ink3 = RondePalette.PaperInk3,
            hrZones = RondePalette.HrZonesLight,
            slab = RondePalette.SlabLight, slabEdge = null,
            onSlab = RondePalette.NightInk, onSlab2 = RondePalette.NightInk2, onSlab3 = RondePalette.NightInk3,
            badge = RondePalette.SlabLight, onBadge = RondePalette.Work, badgeEdge = null,
            mapLand = RondePalette.MapLandLight, mapMinor = RondePalette.MapMinorLight,
            mapPark = RondePalette.MapParkLight, mapWater = RondePalette.MapWaterLight,
            casing = RondePalette.PaperInk, routeNeutral = RondePalette.PaperInk2,
            navBar = RondePalette.PaperSurface2,
            plate = RondePalette.Work, plateInk = RondePalette.PaperInk, plateInk2 = RondePalette.PlateInk2,
            isDark = false,
        )

        val Dark = RondeColors(
            work = RondePalette.Work, recovery = RondePalette.Recovery, warmup = RondePalette.Warmup,
            ringDisc = RondePalette.RingDisc, tick = RondePalette.Tick,
            brandText = RondePalette.Work,
            paceFast = RondePalette.PaceFast, paceSlow = RondePalette.PaceSlow,
            paceFastDeep = RondePalette.PaceFastDeep, paceSlowDeep = RondePalette.PaceSlowDeep,
            tintWork = RondePalette.NightTintWork, tintEasy = RondePalette.NightTintEasy, tintWu = RondePalette.NightTintWu,
            band = RondePalette.NightInk.copy(alpha = 0.22f), bandEdge = RondePalette.NightInk.copy(alpha = 0.6f), grid = RondePalette.NightInk3.copy(alpha = 0.35f),
            ink3 = RondePalette.NightInk3,
            hrZones = RondePalette.HrZonesDark,
            slab = RondePalette.SlabDark, slabEdge = RondePalette.NightLine,
            onSlab = RondePalette.NightInk, onSlab2 = RondePalette.NightInk2, onSlab3 = RondePalette.NightInk3,
            badge = RondePalette.SlabDark, onBadge = RondePalette.Work, badgeEdge = RondePalette.NightLine,
            mapLand = RondePalette.MapLandDark, mapMinor = RondePalette.MapMinorDark,
            mapPark = RondePalette.MapParkDark, mapWater = RondePalette.MapWaterDark,
            casing = Color.Black, routeNeutral = RondePalette.NightInk,
            navBar = RondePalette.NightNav,
            plate = RondePalette.Work, plateInk = Color.Black, plateInk2 = RondePalette.PlateInk2,
            isDark = true,
        )
    }
}

val LocalRondeColors = staticCompositionLocalOf { RondeColors.Light }
