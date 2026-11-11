package net.mvexel.intervalswatch.mobile.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import net.mvexel.intervalswatch.mobile.WorkoutRingDisc
import net.mvexel.intervalswatch.shared.Samples
import net.mvexel.intervalswatch.shared.WorkoutStep

/** Section label in small caps. */
@Composable
fun Overline(text: String) =
    Text(text.uppercase(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))

/** A tappable card row ending in a chevron. */
@Composable
fun NavRow(label: String, onClick: () -> Unit) {
    Card(Modifier.fillMaxWidth().clickable(onClick = onClick), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            Text("›", style = MaterialTheme.typography.titleMedium)
        }
    }
}

/** Surface card for a list of rows. */
@Composable
fun SurfaceCard(content: @Composable ColumnScope.() -> Unit) {
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(content = content)
    }
}

/** "Ronde / for intervals.icu" with the first workout's ring. */
@Composable
fun BrandHeader(steps: List<WorkoutStep>?) {
    Row(Modifier.padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        WorkoutRingDisc(steps ?: Samples.workouts.first().steps, size = 40.dp)
        Spacer(Modifier.width(12.dp))
        Column {
            Text("Ronde", style = MaterialTheme.typography.headlineSmall)
            Text("for intervals.icu", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Title row of a pushed screen. */
@Composable
fun BackHeader(title: String, onBack: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = onBack) { Text("‹ Back") }
        Text(title, style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
fun EmptyText(text: String) =
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
