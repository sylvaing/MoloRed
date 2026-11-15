package fr.sylvain.matv

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.dash.DashMediaSource
import androidx.media3.exoplayer.drm.DefaultDrmSessionManager
import androidx.media3.exoplayer.drm.FrameworkMediaDrm
import androidx.media3.exoplayer.drm.HttpMediaDrmCallback
import androidx.media3.ui.PlayerView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Full-screen live TV with time-shift.
 *
 * Left/Right or CH+/CH-: zap · digits: channel number · Down: channel strip ·
 * Up/OK: control panel (channel, programme, scrub bar with Left/Right, Up = back-to-live button) · Play/Pause: pause (stays paused) · Rewind/FF: ±30 s (faster when held) ·
 * Back: back to live when delayed, else channel grid · Menu: restart programme / live / grid.
 *
 * Time-shift: the live manifest (start=LIVE) keeps ~15 min; further back, or "restart",
 * reloads the same stream with start=<time> (up to 8 h) and a RESTART licence, like the web player.
 */
@OptIn(UnstableApi::class)
class PlayerActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val ui = Handler(Looper.getMainLooper())
    private lateinit var playerView: PlayerView
    private lateinit var banner: View
    private lateinit var bLogo: ImageView
    private lateinit var bNumber: TextView
    private lateinit var bName: TextView
    private lateinit var bNow: TextView
    private lateinit var bNext: TextView
    private lateinit var bProgress: ProgressBar
    private lateinit var digitsView: TextView
    private lateinit var message: TextView

    private var player: ExoPlayer? = null
    private var drmCallback: HttpMediaDrmCallback? = null
    private var index = 0
    private var playJob: Job? = null
    private var retries = 0
    private var digits = ""
    private var zapAt = 0L

    // Channel strip (Down to open, Left/Right to browse, OK to watch)
    private lateinit var strip: View
    private lateinit var stripList: RecyclerView
    private lateinit var stripTitle: TextView
    private lateinit var stripNext: TextView
    private lateinit var stripAdapter: ChannelCardAdapter
    private val hideStrip = Runnable { closeStrip() }

    // Time-shift
    private lateinit var tsOverlay: View
    private lateinit var tsChip: TextView
    private lateinit var tsState: TextView
    private lateinit var tsTitle: TextView
    private lateinit var tsOffset: TextView
    private lateinit var tsProgress: ProgressBar
    private lateinit var tsStart: TextView
    private lateinit var tsEnd: TextView
    private lateinit var tsHint: TextView
    private lateinit var pLogo: ImageView
    private lateinit var pNumber: TextView
    private lateinit var pName: TextView
    private lateinit var pNext: TextView
    private lateinit var tsLiveBtn: TextView
    private lateinit var tsBarRow: View
    /** Control panel focus: false = scrub bar, true = "back to live" button. */
    private var panelFocusLive = false
    private val window = Timeline.Window()
    /** null = live manifest (start=LIVE); else wall-clock start of the start-over manifest. */
    private var streamStartMs: Long? = null
    /** Target of a seek being composed with repeated Rewind/FF presses. */
    private var pendingSeekWall: Long? = null
    /** Last known playhead (wall clock), to resume after errors / falling out of the window. */
    private var lastWall: Long? = null
    private var seekDirection = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_player)
        playerView = findViewById(R.id.player_view)
        banner = findViewById(R.id.banner)
        bLogo = findViewById(R.id.b_logo)
        bNumber = findViewById(R.id.b_number)
        bName = findViewById(R.id.b_name)
        bNow = findViewById(R.id.b_now)
        bNext = findViewById(R.id.b_next)
        bProgress = findViewById(R.id.b_progress)
        digitsView = findViewById(R.id.digits)
        message = findViewById(R.id.message)
        playerView.useController = false
        playerView.keepScreenOn = true

        tsOverlay = findViewById(R.id.ts_overlay)
        tsChip = findViewById(R.id.ts_chip)
        tsState = findViewById(R.id.ts_state)
        tsTitle = findViewById(R.id.ts_title)
        tsOffset = findViewById(R.id.ts_offset)
        tsProgress = findViewById(R.id.ts_progress)
        tsStart = findViewById(R.id.ts_start)
        tsEnd = findViewById(R.id.ts_end)
        tsHint = findViewById(R.id.ts_hint)
        pLogo = findViewById(R.id.p_logo)
        pNumber = findViewById(R.id.p_number)
        pName = findViewById(R.id.p_name)
        pNext = findViewById(R.id.p_next)
        tsLiveBtn = findViewById(R.id.ts_live_btn)
        tsBarRow = findViewById(R.id.ts_bar_row)

        strip = findViewById(R.id.strip)
        stripList = findViewById(R.id.strip_list)
        stripTitle = findViewById(R.id.strip_title)
        stripNext = findViewById(R.id.strip_next)
        stripAdapter = ChannelCardAdapter(
            scope,
            fixedWidthPx = (230 * resources.displayMetrics.density).toInt(),
            onClick = { i -> closeStrip(); if (i != index) play(i) },
            onFocus = { i -> showStripInfo(i) },
        )
        stripList.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        stripList.adapter = stripAdapter
        stripList.itemAnimator = null

        if (Repo.channels.isEmpty()) { finish(); return }
        index = intent.getIntExtra(EXTRA_INDEX, 0).coerceIn(0, Repo.channels.lastIndex)

        // Keep the SSO token fresh while watching (licence renewals use it).
        scope.launch {
            while (isActive) {
                delay(4 * 60_000)
                Auth.validToken(this@PlayerActivity)?.let { t ->
                    val cd = withContext(Dispatchers.IO) { SfrApi.licenseCustomData(this@PlayerActivity, t, licenseType()) }
                    drmCallback?.setKeyRequestProperty("customdata", cd)
                }
                Repo.refreshEpgIfNeeded()
            }
        }
    }

    override fun onStart() {
        super.onStart()
        ui.post(tick)
        if (Repo.channels.isEmpty()) return
        val resumeAt = lastWall
        if (streamStartMs != null && resumeAt != null) load(streamStartMs, resumeAt, 0) else play(index)
    }

    override fun onStop() {
        lastWall = playheadWall() ?: lastWall
        ui.removeCallbacks(tick)
        playJob?.cancel()
        releasePlayer()
        super.onStop()
    }

    override fun onDestroy() {
        scope.cancel()
        ui.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private fun releasePlayer() {
        player?.release()
        player = null
        drmCallback = null
    }

    private fun licenseType() = if (streamStartMs != null) "RESTART" else "LIVEOTT"

    // ------------------------------------------------------------------ playback

    /** Zap to channel [i], at the live edge. */
    private fun play(i: Int) {
        index = (i + Repo.channels.size) % Repo.channels.size
        retries = 0
        zapAt = System.currentTimeMillis()
        pendingSeekWall = null
        lastWall = null
        hideTs()
        showBanner()
        load(null, null, 450) // debounce fast zapping: only the channel you stop on starts
    }

    /**
     * Loads the current channel: live manifest if [startMs] is null, else the start-over
     * manifest beginning at [startMs], positioned at [seekWall] (wall clock).
     */
    private fun load(startMs: Long?, seekWall: Long?, debounceMs: Long) {
        val ch = Repo.channels[index]
        streamStartMs = startMs
        Log.i(TAG, "load #${ch.number} ${ch.name} start=${startMs?.let { Repo.time(it) } ?: "LIVE"} seek=${seekWall?.let { Repo.time(it) }}")
        Repo.setLastChannel(this, ch.serviceId)
        message.visibility = View.GONE
        playJob?.cancel()
        playJob = scope.launch {
            delay(debounceMs)
            val token = Auth.validToken(this@PlayerActivity)
            if (token == null) {
                // Session lost and automatic login impossible: login screen, replay on return (onStart).
                startActivity(android.content.Intent(this@PlayerActivity, LoginActivity::class.java))
                return@launch
            }
            val (url, customData) = try {
                withContext(Dispatchers.IO) {
                    val raw = if (startMs != null) SfrApi.streamFrom(ch.streamUrl, startMs) else ch.streamUrl
                    SfrApi.signStream(token, raw, Repo.emeLevel) to
                        SfrApi.licenseCustomData(this@PlayerActivity, token, licenseType())
                }
            } catch (e: AuthExpiredException) {
                Auth.invalidate(this@PlayerActivity)
                if (retries++ < 2) load(startMs, seekWall, 1500)
                return@launch
            }
            val startPos = if (startMs != null) ((seekWall ?: startMs) - startMs).coerceAtLeast(0) else null
            startPlayback(url, customData, startPos)
        }
    }

    private fun startPlayback(url: String, customData: String, startPositionMs: Long?) {
        releasePlayer()
        val http = DefaultHttpDataSource.Factory()
            .setUserAgent(Sfr.USER_AGENT)
            .setDefaultRequestProperties(Sfr.COMMON_HEADERS)
            .setAllowCrossProtocolRedirects(true)
        val cb = HttpMediaDrmCallback(Sfr.LICENSE_URL, http).apply {
            setKeyRequestProperty("customdata", customData)
        }
        drmCallback = cb
        val drm = DefaultDrmSessionManager.Builder()
            .setUuidAndExoMediaDrmProvider(C.WIDEVINE_UUID, FrameworkMediaDrm.DEFAULT_PROVIDER)
            .setMultiSession(false)
            .build(cb)
        val source = DashMediaSource.Factory(http)
            .setDrmSessionManagerProvider { drm }
            .createMediaSource(
                MediaItem.Builder()
                    .setUri(url)
                    .setDrmConfiguration(MediaItem.DrmConfiguration.Builder(C.WIDEVINE_UUID).build())
                    // No speed tricks to chase the live edge: TV sound must stay natural.
                    .setLiveConfiguration(
                        MediaItem.LiveConfiguration.Builder().setMinPlaybackSpeed(1f).setMaxPlaybackSpeed(1f).build()
                    )
                    .build()
            )
        val p = ExoPlayer.Builder(this).build()
        p.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                var c: Throwable? = error
                while (c != null) {
                    if (c is androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException) {
                        Log.e(TAG, "HTTP ${c.responseCode} on ${c.dataSpec.uri.toString().substringBefore('?')}: " +
                            String(c.responseBody).take(600))
                    }
                    c = c.cause
                }
                Log.w(TAG, "player error ${error.errorCodeName}")
                if (error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
                    // Paused too long: the live window moved past us. Continue from where we were.
                    val at = lastWall ?: System.currentTimeMillis() - 60_000
                    load(at, at, 0)
                    return
                }
                val drmError = error.errorCode in 6000..6999 ||
                    error.errorCode == PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS
                if (drmError) Auth.invalidate(this@PlayerActivity)
                if (retries++ < 3) {
                    showMessage(getString(R.string.retrying, error.errorCodeName))
                    load(streamStartMs, if (streamStartMs != null) lastWall else null, 1500)
                } else {
                    showMessage(getString(R.string.play_error, error.errorCodeName))
                }
            }

            override fun onPlaybackStateChanged(state: Int) {
                Log.i(TAG, "state=$state ch=${Repo.channels[index].name} t=${System.currentTimeMillis() - zapAt}ms")
                if (state == Player.STATE_READY) { retries = 0; message.visibility = View.GONE }
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) = updateTs()
        })
        playerView.player = p
        if (startPositionMs != null) p.setMediaSource(source, startPositionMs) else p.setMediaSource(source)
        p.playWhenReady = true
        p.prepare()
        player = p
    }

    // ------------------------------------------------------------------ time-shift

    private fun win(): Timeline.Window? {
        val p = player ?: return null
        val tl = p.currentTimeline
        if (tl.isEmpty) return null
        tl.getWindow(p.currentMediaItemIndex, window)
        return if (window.windowStartTimeMs == C.TIME_UNSET) null else window
    }

    private fun playheadWall(): Long? = win()?.let { it.windowStartTimeMs + player!!.currentPosition }
    private fun windowStartWall(): Long? = win()?.windowStartTimeMs
    /** Live position (live edge minus the stream's presentation delay). */
    private fun liveWall(): Long? = win()?.let { w ->
        if (w.defaultPositionMs != C.TIME_UNSET) w.windowStartTimeMs + w.defaultPositionMs
        else w.windowStartTimeMs + w.durationMs
    }

    private fun delayMs(): Long {
        val live = liveWall() ?: return 0
        val pos = pendingSeekWall ?: playheadWall() ?: return 0
        return (live - pos).coerceAtLeast(0)
    }

    private fun isDelayed() = delayMs() > 20_000

    private fun noFastForward(ch: Channel) = if (streamStartMs != null) ch.affStartOver else ch.affTimeShift

    private fun togglePause() {
        val p = player ?: return
        val ch = Repo.channels[index]
        if (p.isPlaying) {
            if (!ch.timeShift) { toast(getString(R.string.ts_not_allowed)); return }
            lastWall = playheadWall()
            p.pause()
            showTs()
        } else {
            val at = lastWall
            val start = windowStartWall()
            if (at != null && start != null && at < start + 2000) load(at, at, 0) // fell out of the live window
            else p.play()
            showTs()
        }
    }

    /** Rewind / fast-forward: composes a target while the key is pressed/held, seeks when released. */
    private fun seekBy(deltaMs: Long) {
        val ch = Repo.channels[index]
        if (!ch.timeShift) { toast(getString(R.string.ts_not_allowed)); return }
        val base = pendingSeekWall ?: playheadWall() ?: return
        val live = liveWall() ?: return
        if (deltaMs > 0 && noFastForward(ch)) { showTs(); return }
        val earliest = System.currentTimeMillis() - MAX_BACK_MS
        pendingSeekWall = (base + deltaMs).coerceIn(earliest, live)
        seekDirection = if (deltaMs < 0) -1 else 1
        showTs()
        ui.removeCallbacks(commitSeek)
        ui.postDelayed(commitSeek, 900)
    }

    private val commitSeek = Runnable {
        val t = pendingSeekWall ?: return@Runnable
        pendingSeekWall = null
        seekDirection = 0
        seekToWall(t)
    }

    private fun seekToWall(t: Long) {
        val start = windowStartWall()
        val live = liveWall()
        lastWall = t
        when {
            live != null && t >= live - 5_000 -> goLive()
            start != null && t >= start + 3_000 -> { player?.seekTo(t - start); player?.play() }
            else -> load(t, t, 0) // before the current window: start-over manifest from t
        }
        showTs()
    }

    private fun goLive() {
        pendingSeekWall = null
        ui.removeCallbacks(commitSeek)
        val p = player
        if (p != null && streamStartMs == null) { p.seekToDefaultPosition(); p.play() }
        else load(null, null, 0) // leave the heavy start-over manifest, back to the plain live one
        lastWall = null
        showTs()
    }

    private fun restartProgramme() {
        val ch = Repo.channels[index]
        if (!ch.startOver) { toast(getString(R.string.ts_not_allowed)); return }
        val at = playheadWall() ?: System.currentTimeMillis()
        val prog = Repo.programAt(ch, at) ?: return
        val start = prog.start.coerceAtLeast(System.currentTimeMillis() - MAX_BACK_MS)
        hideBanner.run()
        load(start, start, 0)
        lastWall = start
        showTs()
    }

    private val hideTsRunnable = Runnable {
        if (player?.isPlaying != false && pendingSeekWall == null) hideTs() else armTsTimer()
    }

    private fun armTsTimer() {
        ui.removeCallbacks(hideTsRunnable)
        ui.postDelayed(hideTsRunnable, 8000)
    }

    /** Opens (or refreshes) the control panel; focus starts on the scrub bar. */
    private fun showTs() {
        if (tsOverlay.visibility != View.VISIBLE) panelFocusLive = false
        banner.visibility = View.GONE
        hideBanner.run()
        tsOverlay.visibility = View.VISIBLE
        val ch = Repo.channels[index]
        pNumber.text = ch.number.toString()
        pName.text = ch.name
        Logos.load(scope, ch.logo, pLogo)
        updateTs()
        armTsTimer()
    }

    /** Keys while the control panel is open. Returns true when consumed. */
    private fun panelKey(event: KeyEvent): Boolean {
        val down = event.action == KeyEvent.ACTION_DOWN
        when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                if (down && !panelFocusLive) {
                    val step = (30_000L * (1 + event.repeatCount / 3)).coerceAtMost(5 * 60_000L)
                    seekBy(if (event.keyCode == KeyEvent.KEYCODE_DPAD_LEFT) -step else step)
                }
                armTsTimer(); return true
            }
            KeyEvent.KEYCODE_DPAD_UP -> {
                if (down && isDelayed()) { panelFocusLive = true; updateTs() }
                armTsTimer(); return true
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                if (down) { if (panelFocusLive) { panelFocusLive = false; updateTs() } else hideTs() }
                return true
            }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                if (down && event.repeatCount == 0) {
                    when {
                        panelFocusLive -> { panelFocusLive = false; goLive() }
                        pendingSeekWall != null -> { ui.removeCallbacks(commitSeek); commitSeek.run() }
                        else -> togglePause()
                    }
                }
                armTsTimer(); return true
            }
            KeyEvent.KEYCODE_BACK -> {
                if (!down) {
                    if (pendingSeekWall != null) { pendingSeekWall = null; seekDirection = 0; ui.removeCallbacks(commitSeek); updateTs() }
                    else hideTs()
                }
                return true
            }
        }
        return false
    }

    private fun hideTs() {
        tsOverlay.visibility = View.GONE
        updateTs()
    }

    /** Refreshes the time-shift bar and the "−12 min" chip. */
    private fun updateTs() {
        val p = player
        val ch = Repo.channels.getOrNull(index) ?: return
        val delayed = isDelayed()
        val d = delayMs()
        tsChip.visibility = if (delayed && tsOverlay.visibility != View.VISIBLE && strip.visibility != View.VISIBLE) View.VISIBLE else View.GONE
        tsChip.text = if (p?.playWhenReady == false) "⏸  ${getString(R.string.ts_delay, fmtDelay(d))}" else getString(R.string.ts_delay, fmtDelay(d))
        if (tsOverlay.visibility != View.VISIBLE) return

        val pos = pendingSeekWall ?: playheadWall() ?: System.currentTimeMillis()
        val live = liveWall() ?: System.currentTimeMillis()
        tsState.text = when {
            seekDirection < 0 -> "⏪"
            seekDirection > 0 -> "⏩"
            p?.playWhenReady == false -> "⏸"
            p?.playbackState == Player.STATE_BUFFERING -> "…"
            else -> "▶"
        }
        val prog = Repo.programAt(ch, pos)
        tsTitle.text = prog?.let { "${Repo.time(it.start)} – ${Repo.time(it.end)}   ${it.title}" } ?: ""
        val next = prog?.let { Repo.programAt(ch, it.end + 1) }
        pNext.text = next?.let { getString(R.string.next, Repo.time(it.start), it.title) } ?: ""
        tsLiveBtn.alpha = if (delayed) 1f else 0.35f
        if (!delayed) panelFocusLive = false
        tsLiveBtn.setBackgroundResource(if (panelFocusLive) R.drawable.live_btn_focus_bg else R.drawable.live_btn_bg)
        if (panelFocusLive) tsBarRow.background = null else tsBarRow.setBackgroundResource(R.drawable.focus_row_bg)
        val from = prog?.start ?: (windowStartWall() ?: pos - 15 * 60_000)
        val to = prog?.end ?: live
        val span = (to - from).coerceAtLeast(1)
        tsProgress.progress = ((pos - from) * 1000 / span).toInt().coerceIn(0, 1000)
        tsProgress.secondaryProgress = ((live - from) * 1000 / span).toInt().coerceIn(0, 1000)
        tsStart.text = Repo.time(from)
        tsEnd.text = Repo.time(to)
        if (delayed) {
            tsOffset.text = getString(R.string.ts_delay, fmtDelay(d))
            tsOffset.setBackgroundResource(R.drawable.chip_bg)
            tsHint.text = getString(if (noFastForward(ch)) R.string.ts_hint_noff else R.string.ts_hint_delayed)
        } else {
            tsOffset.text = getString(R.string.ts_live)
            tsOffset.setBackgroundResource(R.drawable.chip_live_bg)
            tsHint.text = getString(R.string.ts_hint_live)
        }
    }

    private val tick = object : Runnable {
        override fun run() {
            if (player?.isPlaying == true) lastWall = playheadWall() ?: lastWall
            updateTs()
            ui.postDelayed(this, 1000)
        }
    }

    private fun fmtDelay(ms: Long): String {
        val s = ms / 1000
        return when {
            s < 60 -> "$s s"
            s < 3600 -> "${s / 60} min"
            else -> "%d h %02d".format(s / 3600, (s % 3600) / 60)
        }
    }

    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_SHORT).show()

    private fun showMenu() {
        val ch = Repo.channels[index]
        val items = ArrayList<Pair<String, () -> Unit>>()
        if (ch.startOver && Repo.programAt(ch, playheadWall() ?: System.currentTimeMillis()) != null)
            items += getString(R.string.menu_restart) to { restartProgramme() }
        if (isDelayed()) items += getString(R.string.menu_live) to { goLive() }
        items += getString(R.string.menu_grid) to { finish() }
        AlertDialog.Builder(this)
            .setTitle("${ch.number}  ${ch.name}")
            .setItems(items.map { it.first }.toTypedArray()) { _, which -> items[which].second() }
            .show()
    }

    // ------------------------------------------------------------------ overlays

    private fun showMessage(text: String) {
        message.text = text
        message.visibility = View.VISIBLE
    }

    private val hideBanner = Runnable { banner.visibility = View.GONE }

    private fun showBanner() {
        val ch = Repo.channels[index]
        tsOverlay.visibility = View.GONE
        bNumber.text = ch.number.toString()
        bName.text = ch.name
        Logos.load(scope, ch.logo, bLogo)
        val (now, next) = Repo.nowNext(ch)
        if (now != null) {
            bNow.text = "${Repo.time(now.start)} – ${Repo.time(now.end)}   ${now.title}"
            val pct = ((System.currentTimeMillis() - now.start) * 100 / (now.end - now.start).coerceAtLeast(1)).toInt()
            bProgress.progress = pct.coerceIn(0, 100)
            bProgress.visibility = View.VISIBLE
        } else {
            bNow.text = ""
            bProgress.visibility = View.GONE
        }
        bNext.text = next?.let { getString(R.string.next, Repo.time(it.start), it.title) } ?: ""
        banner.visibility = View.VISIBLE
        ui.removeCallbacks(hideBanner)
        ui.postDelayed(hideBanner, 5000)
    }

    private fun openStrip() {
        hideBanner.run()
        tsOverlay.visibility = View.GONE
        stripAdapter.playingId = Repo.channels[index].serviceId
        stripAdapter.notifyDataSetChanged()
        strip.visibility = View.VISIBLE
        val lm = stripList.layoutManager as LinearLayoutManager
        val cardW = (246 * resources.displayMetrics.density).toInt()
        lm.scrollToPositionWithOffset(index, (stripList.width - cardW) / 2)
        showStripInfo(index)
        stripList.post { stripList.findViewHolderForAdapterPosition(index)?.itemView?.requestFocus() }
        armStripTimer()
    }

    private fun closeStrip() {
        ui.removeCallbacks(hideStrip)
        strip.visibility = View.GONE
        playerView.requestFocus()
    }

    private fun armStripTimer() {
        ui.removeCallbacks(hideStrip)
        ui.postDelayed(hideStrip, 10_000)
    }

    private fun showStripInfo(i: Int) {
        val ch = Repo.channels[i]
        val (now, next) = Repo.nowNext(ch)
        stripTitle.text = if (now != null)
            "${ch.number}  ${ch.name}  ·  ${Repo.time(now.start)} – ${Repo.time(now.end)}  ${now.title}"
        else "${ch.number}  ${ch.name}"
        stripNext.text = next?.let { getString(R.string.next, Repo.time(it.start), it.title) } ?: ""
    }

    // ------------------------------------------------------------------ keys

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (strip.visibility == View.VISIBLE) {
            armStripTimer()
            when (event.keyCode) {
                KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_DPAD_UP -> {
                    if (event.action == KeyEvent.ACTION_UP) closeStrip()
                    return true
                }
                KeyEvent.KEYCODE_DPAD_DOWN -> return true
            }
            // Left/Right/OK fall through: onKeyDown ignores them while the strip is open,
            // so the framework moves the focus inside the list.
        } else if (tsOverlay.visibility == View.VISIBLE) {
            if (panelKey(event)) return true
        } else if (event.keyCode == KeyEvent.KEYCODE_BACK && isDelayed()) {
            // Back while delayed = back to live (a second Back then goes to the grid).
            if (event.action == KeyEvent.ACTION_UP) goLive()
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    private val commitDigits = Runnable {
        val n = digits.toIntOrNull()
        digits = ""
        digitsView.visibility = View.GONE
        if (n != null) {
            val i = Repo.channels.indexOfFirst { it.number == n }.takeIf { it >= 0 }
                ?: Repo.channels.indexOfFirst { it.number > n }.takeIf { it >= 0 }
            if (i != null && i != index) play(i)
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (strip.visibility == View.VISIBLE) return super.onKeyDown(keyCode, event)
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_PAGE_UP -> { play(index + 1); return true }
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_CHANNEL_DOWN, KeyEvent.KEYCODE_PAGE_DOWN -> { play(index - 1); return true }
            KeyEvent.KEYCODE_DPAD_DOWN -> { openStrip(); return true }
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> { showTs(); return true }
            KeyEvent.KEYCODE_INFO -> { if (banner.visibility == View.VISIBLE) hideBanner.run() else showBanner(); return true }
            KeyEvent.KEYCODE_MENU -> { showMenu(); return true }
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> { togglePause(); return true }
            KeyEvent.KEYCODE_MEDIA_PAUSE -> { if (player?.isPlaying == true) togglePause(); return true }
            KeyEvent.KEYCODE_MEDIA_PLAY -> { if (player?.isPlaying == false) togglePause(); return true }
            KeyEvent.KEYCODE_MEDIA_REWIND, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> {
                // 30 s per press; when held, steps grow up to 5 min.
                val step = (30_000L * (1 + event.repeatCount / 3)).coerceAtMost(5 * 60_000L)
                seekBy(if (keyCode == KeyEvent.KEYCODE_MEDIA_REWIND) -step else step)
                return true
            }
        }
        if (keyCode in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9) {
            digits = (digits + (keyCode - KeyEvent.KEYCODE_0)).takeLast(3)
            digitsView.text = digits
            digitsView.visibility = View.VISIBLE
            ui.removeCallbacks(commitDigits)
            ui.postDelayed(commitDigits, 1500)
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    companion object {
        const val EXTRA_INDEX = "index"
        private const val TAG = "MaTV"
        /** Start-over depth offered by SFR's restart API. */
        private const val MAX_BACK_MS = 8 * 3600_000L - 5 * 60_000L
    }
}
