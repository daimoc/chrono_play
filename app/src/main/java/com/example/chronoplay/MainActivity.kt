package com.example.chronoplay

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.edit
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.chronoplay.ui.ChronoPlayTheme
import kotlin.math.roundToInt

private const val PREFS = "chronoplay"
private const val KEY_URI = "uri"
private const val KEY_MINUTES = "minutes"
private const val KEY_LOOP = "loop"
private const val MAX_MINUTES = 180

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            ChronoPlayTheme {
                ChronoPlayScreen()
            }
        }
    }
}

@Composable
fun ChronoPlayScreen() {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }
    val playback by PlaybackService.state.collectAsStateWithLifecycle()

    var uri by remember { mutableStateOf(prefs.getString(KEY_URI, null)?.let(Uri::parse)) }
    var minutes by remember { mutableFloatStateOf(prefs.getInt(KEY_MINUTES, 30).toFloat()) }
    var loop by remember { mutableStateOf(prefs.getBoolean(KEY_LOOP, true)) }
    val fileName = remember(uri) { uri?.let { displayName(context, it) } }

    val pickFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { picked ->
        if (picked != null) {
            // Keep read access across restarts so the last file can be replayed.
            context.contentResolver.takePersistableUriPermission(
                picked, Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
            uri = picked
            prefs.edit { putString(KEY_URI, picked.toString()) }
        }
    }

    val startPlayback = {
        uri?.let {
            val wholeMinutes = minutes.roundToInt()
            prefs.edit { putInt(KEY_MINUTES, wholeMinutes); putBoolean(KEY_LOOP, loop) }
            PlaybackService.start(context, it, wholeMinutes * 60_000L, loop)
        }
    }
    // The notification only shows the countdown; playback works even if it is denied.
    val requestNotifications =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { startPlayback() }

    Scaffold { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            OutlinedButton(onClick = { pickFile.launch(arrayOf("audio/*")) }, enabled = !playback.isPlaying) {
                Text(stringResource(R.string.choose_file))
            }
            Text(
                text = fileName ?: stringResource(R.string.no_file),
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center,
            )

            if (playback.isPlaying) {
                Text(formatRemaining(playback.remainingMs), fontSize = 56.sp)
                Button(onClick = { PlaybackService.stop(context) }) {
                    Text(stringResource(R.string.stop))
                }
            } else {
                Column(Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.stop_after, minutes.roundToInt()))
                    Slider(
                        value = minutes,
                        onValueChange = { minutes = it },
                        valueRange = 1f..MAX_MINUTES.toFloat(),
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.loop), Modifier.weight(1f))
                    Switch(checked = loop, onCheckedChange = { loop = it })
                }
                Button(
                    enabled = uri != null,
                    onClick = {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            requestNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
                        } else {
                            startPlayback()
                        }
                    },
                ) {
                    Text(stringResource(R.string.play))
                }
                playback.error?.let {
                    Text(it, color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

private fun displayName(context: Context, uri: Uri): String? =
    runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
    }.getOrNull()

private fun formatRemaining(ms: Long): String {
    val totalSeconds = (ms + 999) / 1000
    val h = totalSeconds / 3600
    val m = (totalSeconds % 3600) / 60
    val s = totalSeconds % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
}
