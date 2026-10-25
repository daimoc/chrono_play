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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
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
import androidx.compose.ui.text.style.TextOverflow
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
    val historyStore = remember { History(prefs) }
    val playback by PlaybackService.state.collectAsStateWithLifecycle()

    var uri by remember { mutableStateOf(prefs.getString(KEY_URI, null)?.let(Uri::parse)) }
    var minutes by remember { mutableFloatStateOf(prefs.getInt(KEY_MINUTES, 30).toFloat()) }
    var loop by remember { mutableStateOf(prefs.getBoolean(KEY_LOOP, true)) }
    var history by remember { mutableStateOf(historyStore.load()) }
    val fileName = remember(uri) { uri?.let { displayName(context, it) } }

    // Persisted read grants are limited per app, so give back those nothing refers to anymore.
    fun releaseIfUnused(candidate: Uri?) {
        if (candidate == null || candidate == uri || history.any { it.uri == candidate }) return
        runCatching {
            context.contentResolver.releasePersistableUriPermission(
                candidate, Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        }
    }

    val pickFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { picked ->
        if (picked != null) {
            // Keep read access across restarts so the last file can be replayed.
            context.contentResolver.takePersistableUriPermission(
                picked, Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
            val previous = uri
            uri = picked
            prefs.edit { putString(KEY_URI, picked.toString()) }
            releaseIfUnused(previous)
        }
    }

    fun startPlayback(entry: HistoryEntry) {
        val previous = uri
        uri = entry.uri
        minutes = entry.minutes.toFloat()
        loop = entry.loop
        prefs.edit {
            putString(KEY_URI, entry.uri.toString())
            putInt(KEY_MINUTES, entry.minutes)
            putBoolean(KEY_LOOP, entry.loop)
        }
        val dropped = history
        history = historyStore.add(entry)
        (dropped.map { it.uri } + previous).distinct().forEach(::releaseIfUnused)
        PlaybackService.start(context, entry.uri, entry.minutes * 60_000L, entry.loop)
    }

    var pending by remember { mutableStateOf<HistoryEntry?>(null) }
    // The notification only shows the countdown; playback works even if it is denied.
    val requestNotifications =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
            pending?.let(::startPlayback)
            pending = null
        }
    val play: (HistoryEntry) -> Unit = { entry ->
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pending = entry
            requestNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            startPlayback(entry)
        }
    }

    Scaffold { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp),
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
                        uri?.let {
                            val name = fileName ?: it.lastPathSegment ?: it.toString()
                            play(HistoryEntry(it, name, minutes.roundToInt(), loop))
                        }
                    },
                ) {
                    Text(stringResource(R.string.play))
                }
                playback.error?.let {
                    Text(it, color = MaterialTheme.colorScheme.error)
                }
                if (history.isNotEmpty()) {
                    RecentList(
                        entries = history,
                        onPlay = play,
                        onRemove = { entry ->
                            history = historyStore.remove(entry)
                            releaseIfUnused(entry.uri)
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun RecentList(
    entries: List<HistoryEntry>,
    onPlay: (HistoryEntry) -> Unit,
    onRemove: (HistoryEntry) -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        Text(
            stringResource(R.string.recent),
            style = MaterialTheme.typography.titleMedium,
        )
        entries.forEach { entry ->
            val details = stringResource(R.string.minutes, entry.minutes) +
                if (entry.loop) " · " + stringResource(R.string.looped) else ""
            ListItem(
                modifier = Modifier.clickable { onPlay(entry) },
                leadingContent = { Icon(Icons.Filled.PlayArrow, contentDescription = null) },
                headlineContent = { Text(entry.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                supportingContent = { Text(details) },
                trailingContent = {
                    IconButton(onClick = { onRemove(entry) }) {
                        Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.remove_recent))
                    }
                },
            )
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
