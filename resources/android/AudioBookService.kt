package com.blutrixx.plugins.audiobook

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.MediaPlayer
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

/**
 * Plays an audiobook page after page with the screen off, and fetches the pages from the voice server in the background.
 *
 * One foreground service does both: while it fetches (the server voices a book over minutes) the notification says how far it is; while it
 * plays the notification and the lock screen have the controls (previous page, play/pause, next page, stop), headphone buttons work through
 * the media session, it pauses for a call or another app's sound (audio focus) and when headphones are pulled out. A page that is not on the
 * phone yet is played straight from the server; one the server has not voiced yet is waited for.
 */
class AudioBookService : Service() {
    private val main = Handler(Looper.getMainLooper())
    private var player: MediaPlayer? = null
    private var session: MediaSession? = null
    private var book: JSONObject? = null
    private var pages = IntArray(0)
    private var page = 0
    @Volatile private var playing = false
    private var wantPlay = false
    private var prepared = false
    private var speed = 1f
    private var playedFrom = 0L          // elapsed time when the sound last started, for the minutes heard
    private var playedMs = 0L            // heard on this page so far
    private var waitTries = 0
    private var waiting = false
    @Volatile private var syncing = false
    @Volatile private var syncBook: String? = null
    @Volatile private var ready = 0
    @Volatile private var lastError: String? = null
    private var focus: AudioFocusRequest? = null
    private var resumeOnGain = false
    private var noisyOn = false
    private var pausedAt = 0L
    private var lastNotify = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        live = this
        ensureChannel(this)
        session = MediaSession(this, "HodieAudiobook").apply {
            setCallback(object : MediaSession.Callback() {
                override fun onPlay() = resume()
                override fun onPause() = pause()
                override fun onSkipToNext() = step(1)
                override fun onSkipToPrevious() = step(-1)
                override fun onStop() = stopAll()
            })
            isActive = true
        }
        val st = AudioBookStore.state(this)
        speed = st.optDouble("speed", 1.0).toFloat().coerceIn(0.5f, 2.5f)
        main.postDelayed(ticker, 2000)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Android wants the notification within seconds of a start: always first
        startInForeground()
        when (intent?.action) {
            ACTION_PLAY -> {
                val id = intent.getStringExtra("book") ?: return START_NOT_STICKY
                val b = AudioBookStore.book(this, id) ?: run { lastError = "This audiobook is not on the phone"; update(); return START_NOT_STICKY }
                if (intent.hasExtra("speed")) speed = intent.getFloatExtra("speed", 1f).coerceIn(0.5f, 2.5f)
                open(b, intent.getIntExtra("page", 0), intent.getIntExtra("pos", 0), true)
                startSync(id)
            }
            ACTION_SYNC -> intent.getStringExtra("book")?.let { startSync(it) }
            ACTION_TOGGLE -> if (playing) pause() else resume()
            ACTION_PAUSE -> pause()
            ACTION_RESUME -> resume()
            ACTION_NEXT -> step(1)
            ACTION_PREV -> step(-1)
            ACTION_SEEK -> { val b = book; if (b != null) open(b, intent.getIntExtra("page", page), intent.getIntExtra("pos", 0), playing || wantPlay) }
            ACTION_SPEED -> { speed = intent.getFloatExtra("speed", 1f).coerceIn(0.5f, 2.5f); applySpeed(); saveState(); update() }
            ACTION_STOP -> stopAll()
            ACTION_FORGET -> { val id = intent.getStringExtra("book"); if (id != null) forget(id) }
        }
        maybeStop()
        return START_NOT_STICKY
    }

    // ---------------------------------------------------------------- playing

    private fun open(b: JSONObject, startPage: Int, pos: Int, play: Boolean) {
        val samePage = book?.optString("id") == b.optString("id") && startPage == page && prepared
        book = b
        pages = AudioBookStore.pages(b)
        if (pages.isEmpty()) { lastError = "This book has no pages to listen to"; update(); return }
        val n = pages.firstOrNull { it >= startPage } ?: pages.first()
        wantPlay = play
        if (samePage && pos == 0 && player != null) { if (play) resume(); return }
        playPage(n, pos)
    }

    private fun playPage(n: Int, pos: Int) {
        val b = book ?: return
        endPage(false)
        releasePlayer()
        page = n; prepared = false; waiting = false
        val id = b.getString("id")
        val f = AudioBookStore.file(this, id, n)
        val p = MediaPlayer()
        p.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
        p.setWakeMode(this, PowerManager.PARTIAL_WAKE_LOCK)
        try {
            if (f.isFile && f.length() > 0) p.setDataSource(f.path)
            else p.setDataSource(this, Uri.parse(audioUrl(b, n)), mapOf("X-TTS-Key" to b.optString("key")))
        } catch (e: Exception) {
            p.release(); waitFor(n, "the page could not be opened"); return
        }
        p.setOnPreparedListener { mp ->
            if (player !== mp) return@setOnPreparedListener
            prepared = true; waitTries = 0; lastError = null
            if (pos > 0 && pos < mp.duration) mp.seekTo(pos)
            if (wantPlay) startSound() else update()
        }
        p.setOnCompletionListener { mp -> if (player === mp) pageDone() }
        p.setOnErrorListener { mp, what, extra ->
            if (player === mp) { Log.w(TAG, "Page $n: player error $what/$extra"); waitFor(n, "the page is not ready") }
            true
        }
        player = p
        saveState()
        p.prepareAsync()
        update()
    }

    /** The page could not be played yet (the server is still voicing it, or there is no connection): try again in a little while. */
    private fun waitFor(n: Int, why: String) {
        releasePlayer()
        playing = false; waiting = true; waitTries++
        if (waitTries > 40) { lastError = "Page $n could not be played: $why"; waiting = false; wantPlay = false; AppLogBridge.w('W', "page $n could not be played after waiting ($why)"); update(); return }
        lastError = null
        update()
        main.postDelayed({ if (waiting && page == n && book != null) playPage(n, 0) }, if (waitTries < 3) 5000L else 15000L)
    }

    private fun startSound() {
        val p = player ?: return
        if (!prepared) { wantPlay = true; return }
        if (!takeFocus()) { Log.w(TAG, "No audio focus"); return }
        try { applySpeed(); p.start() } catch (e: Exception) { Log.w(TAG, "Start failed: ${e.message}") ; return }
        playing = true; wantPlay = true; playedFrom = SystemClock.elapsedRealtime()
        noisy(true)
        saveState(); update()
    }

    private fun applySpeed() {
        val p = player ?: return
        if (!prepared || !playing && !wantPlay) return
        try { p.playbackParams = p.playbackParams.setSpeed(speed) } catch (e: Exception) { Log.w(TAG, "Speed not set: ${e.message}") }
    }

    fun pause() {
        val p = player
        wantPlay = false; waiting = false
        if (p != null && playing) { try { p.pause() } catch (e: Exception) { } }
        countPlayed()
        playing = false; pausedAt = SystemClock.elapsedRealtime()
        noisy(false)
        saveState(); update()
    }

    fun resume() {
        val b = book ?: run {
            // started from the notification after the service was gone: take the saved place
            val st = AudioBookStore.state(this); val id = st.optString("book")
            val sb = if (id.isNotEmpty()) AudioBookStore.book(this, id) else null
            if (sb != null) open(sb, st.optInt("page"), st.optInt("pos"), true)
            return
        }
        wantPlay = true
        if (player == null) playPage(if (page > 0) page else AudioBookStore.pages(b).firstOrNull() ?: 1, AudioBookStore.state(this).optInt("pos")) else startSound()
    }

    /** next or previous page; "previous" in the first seconds of a page goes back a page, later it starts the page again */
    fun step(d: Int) {
        if (book == null || pages.isEmpty()) return
        val i = pages.indexOf(page).coerceAtLeast(0)
        val pos = try { player?.currentPosition ?: 0 } catch (e: Exception) { 0 }
        val target = if (d < 0 && pos > 4000) page else pages[(i + d).coerceIn(0, pages.size - 1)]
        wantPlay = playing || wantPlay
        playPage(target, 0)
    }

    private fun pageDone() {
        endPage(true)
        val i = pages.indexOf(page)
        if (i < 0 || i >= pages.size - 1) {   // the end of the book
            playing = false; wantPlay = false; releasePlayer(); noisy(false)
            val st = JSONObject().put("book", book?.optString("id")).put("page", page).put("pos", 0).put("speed", speed.toDouble()).put("playing", false).put("ended", true)
            AudioBookStore.saveState(this, st)
            update(); maybeStop(); return
        }
        playPage(pages[i + 1], 0)
    }

    /** add what was heard of the page to the log the app reads */
    private fun endPage(done: Boolean) {
        val b = book ?: return
        countPlayed()
        if (page > 0 && (playedMs > 0 || done)) AudioBookStore.addHeard(this, b.getString("id"), page, playedMs, done)
        playedMs = 0
    }

    private fun countPlayed() {
        if (playing && playedFrom > 0) playedMs += SystemClock.elapsedRealtime() - playedFrom
        playedFrom = if (playing) SystemClock.elapsedRealtime() else 0
    }

    fun stopAll() {
        if (player != null) { endPage(false); saveState() }
        syncing = false
        releasePlayer(); playing = false; wantPlay = false; waiting = false; noisy(false); dropFocus()
        book = null
        stopForegroundCompat(); stopSelf()
    }

    private fun forget(id: String) {
        if (book?.optString("id") == id) { endPage(false); releasePlayer(); playing = false; book = null }
        if (syncBook == id) syncing = false
        AudioBookStore.removeBook(this, id)
        update(); maybeStop()
    }

    private fun releasePlayer() {
        val p = player ?: return
        player = null; prepared = false
        try { p.reset(); p.release() } catch (e: Exception) { }
    }

    private fun saveState() {
        val b = book ?: return
        val pos = try { if (prepared) player?.currentPosition ?: 0 else 0 } catch (e: Exception) { 0 }
        AudioBookStore.saveState(this, JSONObject().put("book", b.optString("id")).put("page", page).put("pos", pos).put("speed", speed.toDouble()).put("playing", playing))
    }

    // ---------------------------------------------------------------- audio focus, headphones

    private fun takeFocus(): Boolean {
        val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val r = focus ?: AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                .setOnAudioFocusChangeListener(focusListener, main).setWillPauseWhenDucked(true).build().also { focus = it }
            return am.requestAudioFocus(r) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        }
        @Suppress("DEPRECATION")
        return am.requestAudioFocus(focusListener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    private fun dropFocus() {
        val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) focus?.let { am.abandonAudioFocusRequest(it) } else @Suppress("DEPRECATION") am.abandonAudioFocus(focusListener)
    }

    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS -> { resumeOnGain = false; if (playing) pause() }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT, AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> { if (playing) { resumeOnGain = true; pause() } }
            AudioManager.AUDIOFOCUS_GAIN -> { if (resumeOnGain) { resumeOnGain = false; resume() } }
        }
    }

    private val noisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) { if (i.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY && playing) pause() }
    }

    private fun noisy(on: Boolean) {
        if (on == noisyOn) return
        noisyOn = on
        try { if (on) registerReceiver(noisyReceiver, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY)) else unregisterReceiver(noisyReceiver) } catch (e: Exception) { }
    }

    // ---------------------------------------------------------------- fetching from the server

    private fun audioUrl(b: JSONObject, n: Int) = b.optString("base").trimEnd('/') + "/books/" + b.optString("remote") + "/pages/" + n + "/audio"

    /** Fetch every voiced page that is not on the phone yet, the page being heard first; while the server still voices, look again every 15 s. */
    private fun startSync(id: String) {
        if (syncing && syncBook == id) return
        syncing = true; syncBook = id
        thread(name = "audiobook-sync") {
            var errors = 0
            var fetched = 0
            var stuck = 0
            AppLogBridge.w('I', "fetching the audiobook's pages started")
            while (syncing && syncBook == id) {
                val b = AudioBookStore.book(this, id) ?: break
                try {
                    val st = getJson(b.optString("base").trimEnd('/') + "/books/" + b.optString("remote"), b.optString("key"))
                    val list = st.optJSONArray("pages")
                    val done = mutableListOf<Int>()
                    if (list != null) for (i in 0 until list.length()) { val o = list.getJSONObject(i); if (o.optString("status") == "done") done.add(o.getInt("n")) }
                    ready = done.size
                    b.put("ready", done.size).put("server", st.optString("status")).put("failed", st.optInt("failed"))
                    AudioBookStore.saveBook(this, b)
                    val here = page.takeIf { book?.optString("id") == id } ?: 0
                    val missing = done.filter { !AudioBookStore.file(this, id, it).isFile }.sortedWith(compareBy({ it < here }, { it }))
                    var got = 0
                    for (n in missing) {
                        if (!syncing || syncBook != id) break
                        if (download(b, n)) { fetched++; got++ }
                        notifySoon()
                    }
                    errors = 0
                    val working = st.optString("status") == "working"
                    if (!working && got == missing.size) break                 // everything the server has is on the phone
                    stuck = if (!working && got == 0) stuck + 1 else 0
                    if (stuck > 20) { lastError = "Some pages could not be fetched"; break }
                    Thread.sleep(if (working) 15000L else 5000L)
                } catch (e: InterruptedException) {
                    break
                } catch (e: Exception) {
                    errors++
                    lastError = if (errors > 2) "No connection to the voice server" else null
                    Log.w(TAG, "Fetching failed: ${e.message}")
                    if (errors > 40) { AppLogBridge.w('W', "fetching stopped after $errors failures: ${e.javaClass.simpleName}"); break }
                    try { Thread.sleep(if (errors < 5) 10000L else 30000L) } catch (x: InterruptedException) { break }
                }
            }
            AppLogBridge.w('I', "fetching the audiobook's pages ended: $fetched fetched")
            if (syncBook == id) syncing = false
            main.post { update(); maybeStop() }
        }
    }

    private fun download(b: JSONObject, n: Int): Boolean {
        val f = AudioBookStore.file(this, b.getString("id"), n)
        f.parentFile?.mkdirs()
        val part = File(f.path + ".part")
        val c = URL(audioUrl(b, n)).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 20000; c.readTimeout = 60000
            c.setRequestProperty("X-TTS-Key", b.optString("key"))
            if (c.responseCode != 200) return false
            c.inputStream.use { inp -> part.outputStream().use { out -> inp.copyTo(out) } }
            return part.length() > 0 && part.renameTo(f)
        } finally {
            c.disconnect()
            if (part.exists()) part.delete()
        }
    }

    private fun getJson(url: String, key: String): JSONObject {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 20000; c.readTimeout = 30000
            c.setRequestProperty("X-TTS-Key", key); c.setRequestProperty("Accept", "application/json")
            if (c.responseCode == 404) { syncing = false; throw IllegalStateException("the server no longer has this book") }
            if (c.responseCode != 200) throw IllegalStateException("server answered ${c.responseCode}")
            return JSONObject(c.inputStream.bufferedReader().readText())
        } finally { c.disconnect() }
    }

    // ---------------------------------------------------------------- notification and lock screen

    private fun notifySoon() { val now = SystemClock.elapsedRealtime(); if (now - lastNotify > 2000) { lastNotify = now; main.post { update() } } }

    private fun startInForeground() {
        val n = notification()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) startForeground(NOTIFY_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK or ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            else startForeground(NOTIFY_ID, n)
        } catch (e: Exception) { Log.w(TAG, "Foreground refused: ${e.message}"); AppLogBridge.w('W', "the audiobook service could not start (${e.javaClass.simpleName})") }
    }

    private fun update() {
        val b = book
        val pos = try { if (prepared) player?.currentPosition?.toLong() ?: 0L else 0L } catch (e: Exception) { 0L }
        val dur = try { if (prepared) player?.duration?.toLong() ?: 0L else 0L } catch (e: Exception) { 0L }
        session?.setPlaybackState(PlaybackState.Builder()
            .setActions(PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE or PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_SKIP_TO_PREVIOUS or PlaybackState.ACTION_STOP)
            .setState(if (playing) PlaybackState.STATE_PLAYING else if (waiting) PlaybackState.STATE_BUFFERING else PlaybackState.STATE_PAUSED, pos, if (playing) speed else 0f)
            .build())
        if (b != null) session?.setMetadata(MediaMetadata.Builder()
            .putString(MediaMetadata.METADATA_KEY_TITLE, b.optString("title", "Audiobook"))
            .putString(MediaMetadata.METADATA_KEY_ARTIST, "Page $page of ${b.optInt("total", pages.size)}")
            .putLong(MediaMetadata.METADATA_KEY_DURATION, dur).build())
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(NOTIFY_ID, notification())
    }

    private fun notification(): Notification {
        val b = book ?: syncBook?.let { AudioBookStore.book(this, it) }
        val title = b?.optString("title")?.takeIf { it.isNotBlank() } ?: "Audiobook"
        val total = b?.optInt("total", 0) ?: 0
        val text = when {
            lastError != null -> lastError!!
            waiting -> "Waiting for page $page to be voiced"
            book != null -> "Page $page of ${if (total > 0) total else pages.size}" + if (playing) "" else " · paused"
            syncing -> "Fetching pages: $ready ready"
            else -> "Audiobook"
        }
        val nb = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) Notification.Builder(this, CHANNEL) else @Suppress("DEPRECATION") Notification.Builder(this)
        nb.setSmallIcon(if (playing) android.R.drawable.ic_media_play else android.R.drawable.ic_media_pause)
            .setContentTitle(title).setContentText(text).setOngoing(playing || syncing).setShowWhen(false)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
        packageManager.getLaunchIntentForPackage(packageName)?.let { li ->
            nb.setContentIntent(PendingIntent.getActivity(this, 1, li.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
        }
        if (book != null) {
            nb.addAction(action(android.R.drawable.ic_media_previous, "Previous page", ACTION_PREV, 2))
            nb.addAction(if (playing) action(android.R.drawable.ic_media_pause, "Pause", ACTION_PAUSE, 3) else action(android.R.drawable.ic_media_play, "Play", ACTION_RESUME, 4))
            nb.addAction(action(android.R.drawable.ic_media_next, "Next page", ACTION_NEXT, 5))
            nb.addAction(action(android.R.drawable.ic_menu_close_clear_cancel, "Stop", ACTION_STOP, 6))
            nb.setStyle(Notification.MediaStyle().setMediaSession(session?.sessionToken).setShowActionsInCompactView(0, 1, 2))
        }
        return nb.build()
    }

    @Suppress("DEPRECATION")
    private fun action(icon: Int, label: String, act: String, code: Int): Notification.Action {
        val i = Intent(this, AudioBookService::class.java).setAction(act)
        val pi = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) PendingIntent.getForegroundService(this, code, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                 else PendingIntent.getService(this, code, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return Notification.Action.Builder(icon, label, pi).build()
    }

    // ---------------------------------------------------------------- staying alive only while needed

    private val ticker = object : Runnable {
        override fun run() {
            if (playing) { saveState(); update() }
            // paused for half an hour with nothing to fetch: let go (the place is saved; the app or the notification starts again)
            if (!playing && !syncing && !waiting && pausedAt > 0 && SystemClock.elapsedRealtime() - pausedAt > 30 * 60_000L) { stopAll(); return }
            main.postDelayed(this, 2000)
        }
    }

    private fun maybeStop() {
        if (!playing && !wantPlay && !waiting && !syncing && player == null) { stopForegroundCompat(); stopSelf() }
    }

    @Suppress("DEPRECATION")
    private fun stopForegroundCompat() { if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) stopForeground(STOP_FOREGROUND_REMOVE) else stopForeground(true) }

    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        if (player != null) { endPage(false); saveState() }
        syncing = false
        releasePlayer(); noisy(false); dropFocus()
        session?.release(); session = null
        if (live === this) live = null
        super.onDestroy()
    }

    /** what the app shows: read from the running service, or from what was saved when it is not running */
    fun snapshot(): JSONObject {
        val pos = try { if (prepared) player?.currentPosition ?: 0 else 0 } catch (e: Exception) { 0 }
        val dur = try { if (prepared) player?.duration ?: 0 else 0 } catch (e: Exception) { 0 }
        return JSONObject().put("book", book?.optString("id") ?: AudioBookStore.state(this).optString("book")).put("page", page).put("pos", pos).put("dur", dur)
            .put("playing", playing).put("waiting", waiting).put("speed", speed.toDouble()).put("syncing", syncing).put("syncBook", syncBook ?: "").put("ready", ready)
            .put("error", lastError ?: "").put("running", true)
    }

    companion object {
        private const val TAG = "AudioBook"
        const val CHANNEL = "blutrixx_audiobook"
        private const val NOTIFY_ID = 7311
        const val ACTION_PLAY = "com.blutrixx.plugins.audiobook.PLAY"
        const val ACTION_SYNC = "com.blutrixx.plugins.audiobook.SYNC"
        const val ACTION_TOGGLE = "com.blutrixx.plugins.audiobook.TOGGLE"
        const val ACTION_PAUSE = "com.blutrixx.plugins.audiobook.PAUSE"
        const val ACTION_RESUME = "com.blutrixx.plugins.audiobook.RESUME"
        const val ACTION_NEXT = "com.blutrixx.plugins.audiobook.NEXT"
        const val ACTION_PREV = "com.blutrixx.plugins.audiobook.PREV"
        const val ACTION_SEEK = "com.blutrixx.plugins.audiobook.SEEK"
        const val ACTION_SPEED = "com.blutrixx.plugins.audiobook.SPEED"
        const val ACTION_STOP = "com.blutrixx.plugins.audiobook.STOP"
        const val ACTION_FORGET = "com.blutrixx.plugins.audiobook.FORGET"

        @Volatile var live: AudioBookService? = null

        fun send(ctx: Context, action: String, fill: Intent.() -> Unit = {}) {
            val i = Intent(ctx, AudioBookService::class.java).setAction(action)
            i.fill()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ctx.startForegroundService(i) else ctx.startService(i)
        }

        fun ensureChannel(ctx: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (nm.getNotificationChannel(CHANNEL) != null) return
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Audiobooks", NotificationManager.IMPORTANCE_LOW).apply {
                description = "The audiobook that is playing or being fetched"; setShowBadge(false); setSound(null, null)
            })
        }
    }
}
