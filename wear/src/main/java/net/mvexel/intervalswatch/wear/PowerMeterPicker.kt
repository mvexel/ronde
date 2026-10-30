package net.mvexel.intervalswatch.wear

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.material.ButtonDefaults
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.CompactButton
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text

/** Stateful wrapper: asks for the BLE permissions, scans, and remembers the pick. */
@Composable
internal fun PowerMeterPickerScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val store = remember { PowerMeterStore(ctx) }
    val scanner = remember { PowerMeterScanner(ctx) }
    val devices by scanner.devices.collectAsStateWithLifecycle()
    val scanning by scanner.scanning.collectAsStateWithLifecycle()
    var selected by remember { mutableStateOf(store.address) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (it.values.all { granted -> granted }) scanner.start()
    }
    DisposableEffect(Unit) {
        launcher.launch(arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT))
        onDispose { scanner.stop() }
    }
    PowerMeterPicker(
        devices = devices,
        scanning = scanning,
        selectedAddress = selected,
        selectedName = store.name,
        onPick = { store.save(it.address, it.name); selected = it.address },
        onForget = { store.forget(); selected = null },
        onRescan = scanner::start,
        onBack = onBack,
    )
}

@Composable
internal fun PowerMeterPicker(
    devices: List<FoundPowerMeter>,
    scanning: Boolean,
    selectedAddress: String?,
    selectedName: String?,
    onPick: (FoundPowerMeter) -> Unit,
    onForget: () -> Unit,
    onRescan: () -> Unit,
    onBack: () -> Unit,
) {
    WatchList {
        item { Text("Power meter", style = MaterialTheme.typography.title3) }
        if (selectedAddress != null && devices.none { it.address == selectedAddress }) {
            item {
                Chip(
                    onClick = onForget,
                    label = { Text(selectedName ?: selectedAddress, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    secondaryLabel = { Text("Selected · tap to forget") },
                    colors = ChipDefaults.primaryChipColors(),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        items(devices, key = { it.address }) { d ->
            val chosen = d.address == selectedAddress
            Chip(
                onClick = { if (chosen) onForget() else onPick(d) },
                label = { Text(d.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                secondaryLabel = if (chosen) ({ Text("Selected · tap to forget") }) else null,
                colors = if (chosen) ChipDefaults.primaryChipColors() else ChipDefaults.secondaryChipColors(),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        item {
            Text(
                if (scanning) "Scanning…" else if (devices.isEmpty()) "No power meters found" else "",
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(4.dp),
            )
        }
        if (!scanning) {
            item { CompactButton(onClick = onRescan) { Text("↻") } }
        }
        item {
            CompactButton(onClick = onBack, colors = ButtonDefaults.secondaryButtonColors()) { Text("←") }
        }
    }
}
