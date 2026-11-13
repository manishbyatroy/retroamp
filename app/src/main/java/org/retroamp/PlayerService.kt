package org.retroamp

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.net.Uri
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject

class Track(val uri: Uri, val title: String, var durationMs: Long)

/** Owns the audio engine, the playlist and everything that must outlive the activity. */
class PlayerService : Service(), AudioEngine.Listener {

    companion object {
        const val STOPPED = 0
        const val PLAYING = 1
        const val PAUSED = 2
        const val ACTION_TOGGLE = "org.retroamp.TOGGLE"
        const val ACTION_NEXT = "org.retroamp.NEXT"
        const val ACTION_PREV = "org.retroamp.PREV"
        private const val CHANNEL = "playback"
        private const val NOTIF_ID = 1
    }

    inner class LocalBinder : Binder() {
        val service: PlayerService get() = this@PlayerService
    }

    lateinit var engine: AudioEngine; private set
    val playlist = ArrayList<Track>()
    var index = -1; private set
    var state = STOPPED; private set
    var shuffle = false
    var repeat = false
    var onChange: (() -> Unit)? = null

    private val binder = LocalBinder()
    private lateinit var prefs: SharedPreferences
    private lateinit var session: MediaSession
    private lateinit var audioManager: AudioManager
    private lateinit var wakeLock: PowerManager.WakeLock
    private var resumeOnFocus = false
    private var errorStreak = 0
    private var foreground = false
    private var noisyRegistered = false

    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS -> { resumeOnFocus = false; if (state == PLAYING) pause() }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK ->
                if (state == PLAYING) { pause(); resumeOnFocus = true }
            AudioManager.AUDIOFOCUS_GAIN ->
                if (resumeOnFocus && state == PAUSED) { resumeOnFocus = false; play() }
        }
    }

    private val noisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) {
            if (state == PLAYING) pause()
        }
    }

    override fun onCreate() {
        super.onCreate()
        prefs = getSharedPreferences("retroamp", Context.MODE_PRIVATE)
        engine = AudioEngine(applicationContext)
        engine.listener = this
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        wakeLock = (getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "retroamp:playback")
        wakeLock.setReferenceCounted(false)

        session = MediaSession(this, "RetroAmp")
        session.setCallback(object : MediaSession.Callback() {
            override fun onPlay() = play()
            override fun onPause() { if (state == PLAYING) pause() }
            override fun onStop() = stop()
            override fun onSkipToNext() = next()
            override fun onSkipToPrevious() = prev()
            override fun onSeekTo(pos: Long) = seekTo(pos)
        })

        if (Build.VERSION.SDK_INT >= 26) {
            val ch = NotificationChannel(CHANNEL, "Playback", NotificationManager.IMPORTANCE_LOW)
            ch.setShowBadge(false)
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(ch)
        }
        load()
        updateSession()
    }

    override fun onBind(intent: Intent?): IBinder = binder
    override fun onRebind(intent: Intent?) {}

    override fun onUnbind(intent: Intent?): Boolean {
        save()
        if (state == STOPPED) stopSelf()
        return true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_TOGGLE -> if (state == PLAYING) pause() else play()
            ACTION_NEXT -> next()
            ACTION_PREV -> prev()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        save()
        engine.listener = null
        engine.stop()
        setNoisy(false)
        audioManager.abandonAudioFocus(focusListener)
        if (wakeLock.isHeld) wakeLock.release()
        session.release()
        super.onDestroy()
    }

    // ---------------------------------------------------------------- transport

    val positionMs: Long get() = engine.positionUs / 1000
    val durationMs: Long
        get() {
            val d = engine.durationUs / 1000
            return if (d > 0) d else current?.durationMs ?: 0L
        }
    val current: Track? get() = playlist.getOrNull(index)

    fun playIndex(i: Int) {
        if (i !in playlist.indices) return
        index = i
        val granted = audioManager.requestAudioFocus(
            focusListener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN
        ) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        if (!granted) {
            Toast.makeText(this, "Another app is holding audio focus", Toast.LENGTH_SHORT).show()
            return
        }
        resumeOnFocus = false
        engine.play(playlist[i].uri)
        state = PLAYING
        changed()
    }

    /** Play button: resumes when paused, otherwise (re)starts the current track. */
    fun play() {
        if (state == PAUSED) {
            val granted = audioManager.requestAudioFocus(
                focusListener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN
            ) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
            if (!granted) return
            engine.resume()
            state = PLAYING
            changed()
        } else if (playlist.isNotEmpty()) {
            playIndex(if (index < 0) 0 else index)
        }
    }

    /** Pause button: toggles between paused and playing. */
    fun pause() {
        when (state) {
            PLAYING -> { engine.pause(); state = PAUSED; changed() }
            PAUSED -> play()
        }
    }

    fun stop() {
        engine.stop()
        state = STOPPED
        resumeOnFocus = false
        audioManager.abandonAudioFocus(focusListener)
        changed()
    }

    fun next(auto: Boolean = false) {
        if (playlist.isEmpty()) return
        var ni: Int
        if (shuffle && playlist.size > 1) {
            do { ni = (Math.random() * playlist.size).toInt() } while (ni == index)
        } else {
            ni = index + 1
            if (ni >= playlist.size) {
                if (auto && !repeat) { stop(); return }
                ni = 0
            }
        }
        if (state == STOPPED && !auto) { index = ni; changed() } else playIndex(ni)
    }

    fun prev() {
        if (playlist.isEmpty()) return
        val ni = if (index <= 0) playlist.size - 1 else index - 1
        if (state == STOPPED) { index = ni; changed() } else playIndex(ni)
    }

    fun seekTo(ms: Long) {
        if (state == STOPPED) return
        engine.seekTo(ms * 1000)
        changed()
    }

    // ---------------------------------------------------------------- playlist

    fun add(tracks: List<Track>, replace: Boolean = false) {
        if (replace) {
            engine.stop()
            state = STOPPED
            playlist.clear()
            index = -1
        }
        playlist.addAll(tracks)
        if (index < 0 && playlist.isNotEmpty()) index = 0
        save()
        if (replace && playlist.isNotEmpty()) playIndex(0) else changed()
    }

    fun removeAt(i: Int) {
        if (i !in playlist.indices) return
        if (i == index && state != STOPPED) stop()
        playlist.removeAt(i)
        if (i < index) index--
        if (index >= playlist.size) index = playlist.size - 1
        save()
        changed()
    }

    fun clear() {
        if (state != STOPPED) stop()
        playlist.clear()
        index = -1
        save()
        changed()
    }

    // ---------------------------------------------------------------- engine callbacks

    override fun onEnded() {
        errorStreak = 0
        next(auto = true)
    }

    override fun onError(msg: String) {
        Toast.makeText(this, "Can't play ${current?.title ?: "track"}: $msg", Toast.LENGTH_SHORT).show()
        errorStreak++
        if (errorStreak >= playlist.size) { errorStreak = 0; stop() } else next(auto = true)
    }

    override fun onInfo() {
        val d = engine.durationUs / 1000
        if (d > 0) current?.durationMs = d
        changed()
    }

    // ---------------------------------------------------------------- persistence

    fun save() {
        val arr = JSONArray()
        for (t in playlist) {
            arr.put(JSONObject().put("u", t.uri.toString()).put("t", t.title).put("d", t.durationMs))
        }
        val bands = JSONArray()
        for (g in engine.eq.gains) bands.put(g.toDouble())
        prefs.edit()
            .putString("playlist", arr.toString())
            .putInt("index", index)
            .putBoolean("shuffle", shuffle)
            .putBoolean("repeat", repeat)
            .putFloat("volume", engine.volume)
            .putFloat("balance", engine.balance)
            .putBoolean("eqOn", engine.eq.enabled)
            .putFloat("preamp", engine.eq.preampDb)
            .putString("bands", bands.toString())
            .apply()
    }

    private fun load() {
        try {
            val arr = JSONArray(prefs.getString("playlist", "[]"))
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                playlist.add(Track(Uri.parse(o.getString("u")), o.getString("t"), o.optLong("d", 0)))
            }
            val bands = JSONArray(prefs.getString("bands", "[]"))
            for (i in 0 until minOf(bands.length(), Equalizer10.BANDS)) {
                engine.eq.setGain(i, bands.getDouble(i).toFloat())
            }
        } catch (_: Exception) {
            playlist.clear()
        }
        index = prefs.getInt("index", -1).coerceIn(-1, playlist.size - 1)
        if (index < 0 && playlist.isNotEmpty()) index = 0
        shuffle = prefs.getBoolean("shuffle", false)
        repeat = prefs.getBoolean("repeat", false)
        engine.volume = prefs.getFloat("volume", 0.8f).coerceIn(0f, 1f)
        engine.balance = prefs.getFloat("balance", 0f).coerceIn(-1f, 1f)
        engine.eq.enabled = prefs.getBoolean("eqOn", true)
        engine.eq.preampDb = prefs.getFloat("preamp", 0f).coerceIn(-Equalizer10.MAX_DB, Equalizer10.MAX_DB)
    }

    // ---------------------------------------------------------------- system integration

    private fun changed() {
        if (state == PLAYING) {
            if (!wakeLock.isHeld) wakeLock.acquire()
        } else if (wakeLock.isHeld) {
            wakeLock.release()
        }
        setNoisy(state == PLAYING)
        updateSession()
        updateNotification()
        onChange?.invoke()
    }

    private fun setNoisy(on: Boolean) {
        if (on == noisyRegistered) return
        if (on) {
            val f = IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
            if (Build.VERSION.SDK_INT >= 33) registerReceiver(noisyReceiver, f, Context.RECEIVER_NOT_EXPORTED)
            else registerReceiver(noisyReceiver, f)
        } else {
            unregisterReceiver(noisyReceiver)
        }
        noisyRegistered = on
    }

    private fun updateSession() {
        val st = when (state) {
            PLAYING -> PlaybackState.STATE_PLAYING
            PAUSED -> PlaybackState.STATE_PAUSED
            else -> PlaybackState.STATE_STOPPED
        }
        session.setPlaybackState(
            PlaybackState.Builder()
                .setActions(
                    PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or
                        PlaybackState.ACTION_PLAY_PAUSE or PlaybackState.ACTION_STOP or
                        PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_SKIP_TO_PREVIOUS or
                        PlaybackState.ACTION_SEEK_TO
                )
                .setState(st, positionMs, if (state == PLAYING) 1f else 0f)
                .build()
        )
        val t = current
        session.setMetadata(
            MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, t?.title ?: "RetroAmp")
                .putLong(MediaMetadata.METADATA_KEY_DURATION, durationMs)
                .build()
        )
        session.isActive = state != STOPPED
    }

    private fun action(icon: Int, title: String, action: String, req: Int): Notification.Action {
        val pi = PendingIntent.getService(
            this, req, Intent(this, PlayerService::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return Notification.Action.Builder(Icon.createWithResource(this, icon), title, pi).build()
    }

    private fun updateNotification() {
        if (state == STOPPED) {
            if (foreground) {
                stopForeground(STOP_FOREGROUND_REMOVE)
                foreground = false
            }
            return
        }
        @Suppress("DEPRECATION")
        val b = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CHANNEL) else Notification.Builder(this)
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val playing = state == PLAYING
        b.setSmallIcon(R.drawable.ic_note)
            .setContentTitle(current?.title ?: "RetroAmp")
            .setContentText(if (playing) "Playing" else "Paused")
            .setContentIntent(open)
            .setOngoing(playing)
            .setShowWhen(false)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .addAction(action(android.R.drawable.ic_media_previous, "Previous", ACTION_PREV, 1))
            .addAction(
                action(
                    if (playing) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play,
                    if (playing) "Pause" else "Play", ACTION_TOGGLE, 2
                )
            )
            .addAction(action(android.R.drawable.ic_media_next, "Next", ACTION_NEXT, 3))
            .setStyle(
                Notification.MediaStyle()
                    .setMediaSession(session.sessionToken)
                    .setShowActionsInCompactView(0, 1, 2)
            )
        val n = b.build()
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
            } else {
                startForeground(NOTIF_ID, n)
            }
            foreground = true
        } catch (_: Exception) {
            // Background start restrictions: playback continues, just without the notification.
        }
    }
}
