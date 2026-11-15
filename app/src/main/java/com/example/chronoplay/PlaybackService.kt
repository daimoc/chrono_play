package com.example.chronoplay

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.net.Uri
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class PlaybackState(
    val isPlaying: Boolean = false,
    val remainingMs: Long = 0L,
    val error: String? = null,
)

/**
 * Plays a single audio file in the foreground and stops it once the
 * requested duration has elapsed (or when the user presses Stop).
 */
class PlaybackService : Service() {

    companion object {
        private const val ACTION_START = "com.example.chronoplay.action.START"
        private const val ACTION_STOP = "com.example.chronoplay.action.STOP"
        private const val EXTRA_DURATION_MS = "duration_ms"
        private const val EXTRA_LOOP = "loop"
        private const val EXTRA_START_AT_MS = "start_at_ms"
        private const val EXTRA_SAVE_POSITION = "save_position"
        private const val CHANNEL_ID = "playback"
        private const val NOTIFICATION_ID = 1
        private const val TICK_MS = 100L
        private const val FADE_OUT_MS = 5_000L
        private const val DUCK_VOLUME = 0.2f
        /** A saved position this close to the end restarts the file instead. */
        private const val RESUME_END_MARGIN_MS = 1_000

        private val _state = MutableStateFlow(PlaybackState())
        val state: StateFlow<PlaybackState> = _state.asStateFlow()

        /**
         * Plays [uri] for [durationMs], starting at [startAtMs]. With [savePosition], where it
         * stops is stored in [ResumePositions] for the next play.
         */
        fun start(
            context: Context,
            uri: Uri,
            durationMs: Long,
            loop: Boolean,
            startAtMs: Long = 0L,
            savePosition: Boolean = false,
        ) {
            val intent = Intent(context, PlaybackService::class.java)
                .setAction(ACTION_START)
                .setData(uri)
                .putExtra(EXTRA_DURATION_MS, durationMs)
                .putExtra(EXTRA_LOOP, loop)
                .putExtra(EXTRA_START_AT_MS, startAtMs)
                .putExtra(EXTRA_SAVE_POSITION, savePosition)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            context.startService(Intent(context, PlaybackService::class.java).setAction(ACTION_STOP))
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private var player: MediaPlayer? = null
    private var endAtElapsed = 0L
    private var duckVolume = 1f
    private var pausedByFocusLoss = false
    private var currentUri: Uri? = null
    private var savePosition = false
    private var prepared = false

    private val positions by lazy { ResumePositions(getSharedPreferences(PREFS, MODE_PRIVATE)) }

    private val audioManager by lazy { getSystemService(AudioManager::class.java) }
    private val audioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
        .build()
    private val focusRequest by lazy {
        AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(audioAttributes)
            .setOnAudioFocusChangeListener(::onAudioFocusChange, handler)
            .build()
    }

    private val tick = object : Runnable {
        override fun run() {
            val remaining = endAtElapsed - SystemClock.elapsedRealtime()
            if (remaining <= 0) {
                stopPlayback()
            } else {
                _state.value = _state.value.copy(remainingMs = remaining)
                applyVolume(remaining)
                handler.postDelayed(this, TICK_MS)
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.channel_name),
            NotificationManager.IMPORTANCE_LOW,
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    // ServiceCompat ignores the foreground service type below API 29.
    @SuppressLint("InlinedApi")
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val uri = intent.data
                val durationMs = intent.getLongExtra(EXTRA_DURATION_MS, 0L)
                // startForeground must be called quickly after startForegroundService.
                ServiceCompat.startForeground(
                    this,
                    NOTIFICATION_ID,
                    buildNotification(durationMs),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,
                )
                if (uri == null || durationMs <= 0) {
                    stopPlayback()
                } else {
                    startPlayback(
                        uri,
                        durationMs,
                        intent.getBooleanExtra(EXTRA_LOOP, true),
                        intent.getLongExtra(EXTRA_START_AT_MS, 0L),
                        intent.getBooleanExtra(EXTRA_SAVE_POSITION, false),
                    )
                }
            }
            ACTION_STOP -> stopPlayback()
        }
        return START_NOT_STICKY
    }

    private fun startPlayback(uri: Uri, durationMs: Long, loop: Boolean, startAtMs: Long, save: Boolean) {
        rememberPosition()
        releasePlayer()
        val mp = MediaPlayer()
        player = mp
        currentUri = uri
        savePosition = save
        try {
            mp.setAudioAttributes(audioAttributes)
            mp.setWakeMode(this, PowerManager.PARTIAL_WAKE_LOCK)
            mp.setDataSource(this, uri)
            mp.isLooping = loop
            mp.setOnPreparedListener {
                if (audioManager.requestAudioFocus(focusRequest) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                    stopPlayback(getString(R.string.error_audio_focus))
                    return@setOnPreparedListener
                }
                duckVolume = 1f
                pausedByFocusLoss = false
                prepared = true
                if (startAtMs > 0 && startAtMs < it.duration - RESUME_END_MARGIN_MS) {
                    it.seekTo(startAtMs.toInt())
                }
                it.start()
                endAtElapsed = SystemClock.elapsedRealtime() + durationMs
                _state.value = PlaybackState(isPlaying = true, remainingMs = durationMs)
                updateNotification(durationMs)
                handler.post(tick)
            }
            mp.setOnCompletionListener { if (!it.isLooping) stopPlayback(finished = true) }
            mp.setOnErrorListener { _, _, _ ->
                stopPlayback(getString(R.string.error_playback))
                true
            }
            mp.prepareAsync()
        } catch (e: Exception) {
            stopPlayback(getString(R.string.error_playback))
        }
    }

    /** Volume is the duck level, scaled down linearly during the last seconds. */
    private fun applyVolume(remainingMs: Long) {
        val fade = (remainingMs.toFloat() / FADE_OUT_MS).coerceIn(0f, 1f)
        val volume = duckVolume * fade
        player?.setVolume(volume, volume)
    }

    private fun onAudioFocusChange(change: Int) {
        val mp = player ?: return
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS -> stopPlayback()
            // e.g. a phone call: pause, but keep the timer running.
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> if (mp.isPlaying) {
                mp.pause()
                pausedByFocusLoss = true
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> duckVolume = DUCK_VOLUME
            AudioManager.AUDIOFOCUS_GAIN -> {
                duckVolume = 1f
                if (pausedByFocusLoss) {
                    pausedByFocusLoss = false
                    mp.start()
                }
            }
        }
        applyVolume(endAtElapsed - SystemClock.elapsedRealtime())
    }

    /** [finished] means the file played to its end, so the next play starts over. */
    private fun stopPlayback(error: String? = null, finished: Boolean = false) {
        handler.removeCallbacks(tick)
        if (error == null) rememberPosition(finished)
        releasePlayer()
        audioManager.abandonAudioFocusRequest(focusRequest)
        _state.value = PlaybackState(error = error)
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    /** Saves where the current file stopped, if asked to and it actually started playing. */
    private fun rememberPosition(finished: Boolean = false) {
        val uri = currentUri ?: return
        if (!savePosition || !prepared) return
        val position = if (finished) 0L else runCatching { player?.currentPosition?.toLong() }.getOrNull()
        position?.let { positions.set(uri, it) }
    }

    private fun releasePlayer() {
        player?.release()
        player = null
        prepared = false
    }

    override fun onDestroy() {
        handler.removeCallbacks(tick)
        rememberPosition()
        releasePlayer()
        audioManager.abandonAudioFocusRequest(focusRequest)
        if (_state.value.isPlaying) _state.value = PlaybackState()
        super.onDestroy()
    }

    private fun updateNotification(remainingMs: Long) {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildNotification(remainingMs))
    }

    private fun buildNotification(remainingMs: Long): Notification {
        val openApp = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this, 1,
            Intent(this, PlaybackService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        // A count-down chronometer lets the system render the remaining time
        // without us re-posting the notification every second.
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.notification_title))
            .setUsesChronometer(true)
            .setChronometerCountDown(true)
            .setWhen(System.currentTimeMillis() + remainingMs)
            .setShowWhen(true)
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(openApp)
            .addAction(0, getString(R.string.stop), stop)
            .build()
    }
}
