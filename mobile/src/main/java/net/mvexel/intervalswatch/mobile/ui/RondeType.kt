package net.mvexel.intervalswatch.mobile.ui

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import net.mvexel.intervalswatch.mobile.R

/**
 * Roboto Flex at `wdth 84, wght 680` (direction A's condensed numeral), bundled as a 44 KB static subset
 * (res/font/roboto_flex_condensed.ttf, SIL OFL 1.1; third_party/roboto-flex). Digits are tabular by default.
 * Characters outside the subset fall back to the system font.
 */
val RondeCondensed = FontFamily(Font(R.font.roboto_flex_condensed, FontWeight.Bold))

/**
 * Numeral and title styles (sizes from design.md: tab title 28, detail title 30, hero verdict 44, headline
 * numbers 26). No colours: pass them at the call site. Read through [RondeTheme.type] or directly.
 *
 * | Style          | Face                   | Size | Use |
 * |----------------|------------------------|------|-----|
 * | [tabTitle]     | condensed              | 28   | "Plan", "History" (also `headlineSmall`) |
 * | [detailTitle]  | condensed              | 30   | Detail screen title (also `headlineLarge`) |
 * | [hero]         | condensed              | 44   | Result hero "3 of 5" |
 * | [numberL]      | condensed              | 26   | Headline numbers (distance, time, pace tiles) |
 * | [numberM]      | condensed              | 20   | Mid-size numbers |
 * | [ringNumber]   | condensed              | 15   | Watch-order number inside a ring disc |
 * | [unit]         | system Medium          | 13   | Unit after a number ("km", "/km") |
 * | [stat]         | system, tnum           | 14   | Stat lines in rows ("8.70 km  45:00") |
 * | [axis]         | system Medium, tnum    | 10.5 | Chart axis labels |
 */
object RondeType {
    private const val TNUM = "tnum"

    val tabTitle = TextStyle(fontFamily = RondeCondensed, fontWeight = FontWeight.Bold, fontSize = 28.sp, lineHeight = 34.sp, letterSpacing = (-0.01).em)
    val detailTitle = TextStyle(fontFamily = RondeCondensed, fontWeight = FontWeight.Bold, fontSize = 30.sp, lineHeight = 34.sp, letterSpacing = (-0.01).em)
    val hero = TextStyle(fontFamily = RondeCondensed, fontWeight = FontWeight.Bold, fontSize = 44.sp, lineHeight = 46.sp)
    val numberL = TextStyle(fontFamily = RondeCondensed, fontWeight = FontWeight.Bold, fontSize = 26.sp, lineHeight = 30.sp)
    val numberM = TextStyle(fontFamily = RondeCondensed, fontWeight = FontWeight.Bold, fontSize = 20.sp, lineHeight = 24.sp)
    val ringNumber = TextStyle(fontFamily = RondeCondensed, fontWeight = FontWeight.Bold, fontSize = 15.sp, lineHeight = 15.sp)
    val unit = TextStyle(fontWeight = FontWeight.Medium, fontSize = 13.sp, lineHeight = 16.sp)
    val stat = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontFeatureSettings = TNUM)
    val axis = TextStyle(fontWeight = FontWeight.Medium, fontSize = 10.5.sp, lineHeight = 12.sp, fontFeatureSettings = TNUM)
}
