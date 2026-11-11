package net.mvexel.intervalswatch.mobile

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.mvexel.intervalswatch.mobile.app.AppContainer
import net.mvexel.intervalswatch.mobile.app.AppViewModel
import net.mvexel.intervalswatch.mobile.app.RondeApp
import net.mvexel.intervalswatch.mobile.feature.watch.RecordingActions
import net.mvexel.intervalswatch.mobile.feature.watch.RecordingsViewModel
import net.mvexel.intervalswatch.mobile.ui.RondeTheme

/** CreateDocument with the MIME type chosen per call (the stock contract fixes it at construction). */
private class CreateExport : ActivityResultContracts.CreateDocument("*/*") {
    var mime = "*/*"
    override fun createIntent(context: Context, input: String) = super.createIntent(context, input).setType(mime)
}

/**
 * Hosts [RondeApp] and the things that need an Activity: the OAuth redirect, the login Custom Tab,
 * recording Share/Save, and the watch-settings listener while visible.
 */
class MainActivity : ComponentActivity() {
    private val container by lazy { AppContainer.from(this) }
    private val factory by lazy {
        viewModelFactory {
            initializer { AppViewModel(container.session) }
            initializer { RecordingsViewModel(container.recordings) }
        }
    }
    private val appVm: AppViewModel by viewModels { factory }
    private val recordingsVm: RecordingsViewModel by viewModels { factory }

    private val createExport = CreateExport()
    private var pendingSave: Pair<String, ExportFormat>? = null // restored in onCreate: the picker can outlive the activity
    private val saveLauncher = registerForActivityResult(createExport) { uri -> uri?.let(::writeExport) }

    private val saved = Bundle()

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putAll(saved)
    }

    private fun writeExport(uri: Uri) {
        val (fileName, format) = pendingSave ?: run {
            Toast.makeText(this, "Could not save", Toast.LENGTH_SHORT).show()
            return
        }
        lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    val bytes = RecordingLibrary.of(this@MainActivity).export(fileName, format)
                    contentResolver.openOutputStream(uri)!!.use { it.write(bytes) }
                }.isSuccess
            }
            Toast.makeText(this@MainActivity, if (ok) "Saved" else "Could not save", Toast.LENGTH_SHORT).show()
        }
    }

    private fun save(item: RecordingItem, format: ExportFormat) {
        pendingSave = item.fileName to format
        saved.putString(KEY_SAVE_NAME, item.fileName); saved.putString(KEY_SAVE_FORMAT, format.name)
        createExport.mime = format.mime
        saveLauncher.launch(RecordingLibrary.of(this).exportName(item.fileName, format))
    }

    private fun share(item: RecordingItem, format: ExportFormat) {
        lifecycleScope.launch {
            val intent = withContext(Dispatchers.IO) { runCatching { RecordingLibrary.shareIntent(this@MainActivity, item.fileName, format) }.getOrNull() }
            if (intent != null) startActivity(intent) else Toast.makeText(this@MainActivity, "Could not share", Toast.LENGTH_SHORT).show()
        }
    }

    private fun login() {
        lifecycleScope.launch {
            if (!launchLoginTab(this@MainActivity, appVm.beginLogin())) appVm.showStatus("No browser found to log in with")
        }
    }

    companion object {
        private const val KEY_SAVE_NAME = "save_name"
        private const val KEY_SAVE_FORMAT = "save_format"
    }

    override fun onResume() {
        super.onResume()
        recordingsVm.refresh() // uploads finish while the app is in the background
    }

    override fun onStart() {
        super.onStart()
        container.screenSettings.startListening()
        lifecycleScope.launch { container.screenSettings.load() }
    }

    override fun onStop() {
        container.screenSettings.stopListening()
        super.onStop()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        savedInstanceState?.let { b ->
            val n = b.getString(KEY_SAVE_NAME); val f = b.getString(KEY_SAVE_FORMAT)?.let { runCatching { ExportFormat.valueOf(it) }.getOrNull() }
            if (n != null && f != null) { pendingSave = n to f; createExport.mime = f.mime; saved.putAll(b) }
        }
        // After process death the redirect starts a fresh instance; the pending login is read from storage.
        if (savedInstanceState == null) appVm.handleRedirect(intent?.dataString)
        val actions = RecordingActions(onShare = ::share, onSave = ::save)
        setContent {
            RondeTheme {
                RondeApp(container, appVm, recordingsVm, actions, onLogin = ::login)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        appVm.handleRedirect(intent.dataString)
    }
}
