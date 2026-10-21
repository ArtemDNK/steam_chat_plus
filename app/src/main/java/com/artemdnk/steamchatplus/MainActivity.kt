package com.artemdnk.steamchatplus

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Color
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import androidx.activity.SystemBarStyle
import androidx.activity.addCallback
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import com.artemdnk.steamchatplus.databinding.ActivityMainBinding
import com.google.android.material.snackbar.Snackbar
import kotlin.math.max

class MainActivity : AppCompatActivity(), WebHolder.Host {

    private lateinit var b: ActivityMainBinding

    private var micCallback: ((Boolean) -> Unit)? = null
    private val micLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        micCallback?.invoke(ok)
        micCallback = null
        if (!ok) snack(getString(R.string.mic_denied))
    }

    private val notifLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    private var fileCallback: ValueCallback<Array<Uri>>? = null
    private val fileLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val cb = fileCallback ?: return@registerForActivityResult
        fileCallback = null
        val data = r.data
        val uris: Array<Uri>? = when {
            r.resultCode != RESULT_OK || data == null -> null
            data.clipData != null -> {
                val clip = data.clipData!!
                Array(clip.itemCount) { clip.getItemAt(it).uri }
            }
            data.data != null -> arrayOf(data.data!!)
            else -> null
        }
        cb.onReceiveValue(uris)
    }

    private val callListener: () -> Unit = { renderCall() }
    private var timerRunning = false

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT)
        )
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)
        setSupportActionBar(b.toolbar)

        ViewCompat.setOnApplyWindowInsetsListener(b.root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            v.setPadding(bars.left, bars.top, bars.right, max(bars.bottom, ime.bottom))
            WindowInsetsCompat.CONSUMED
        }

        WebHolder.host = this
        WebHolder.attach(this, b.webContainer)

        b.btnMute.setOnClickListener { CallManager.toggleMute() }
        b.btnSpeaker.setOnClickListener { CallManager.toggleSpeaker() }
        CallManager.addListener(callListener)

        onBackPressedDispatcher.addCallback(this) {
            val wv = WebHolder.webView
            if (wv != null && wv.canGoBack()) wv.goBack() else moveTaskToBack(true)
        }

        if (savedInstanceState == null && Prefs.notifications(this)) requestNotificationPermission()

        handleIntent(intent)
        renderCall()
        SessionService.sync(this)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        val key = intent?.getIntExtra(Notifier.EXTRA_NOTIF_KEY, -1) ?: -1
        if (key != -1) {
            intent?.removeExtra(Notifier.EXTRA_NOTIF_KEY)
            Notifier.onClicked(key)
        }
    }

    override fun onResume() {
        super.onResume()
        PageBridge.setHidden(false)
        Notifier.cancelMessages(this)
        renderCall()
    }

    override fun onPause() {
        // Deliberately not pausing the WebView: the call and the chat socket must keep running.
        PageBridge.setHidden(true)
        super.onPause()
    }

    override fun onDestroy() {
        CallManager.removeListener(callListener)
        fileCallback?.onReceiveValue(null)
        fileCallback = null
        if (WebHolder.host === this) WebHolder.host = null
        WebHolder.detach(this)
        super.onDestroy()
    }

    // ---------- menu ----------

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        R.id.menu_home -> { WebHolder.goHome(); true }
        R.id.menu_reload -> { WebHolder.reload(); true }
        R.id.menu_settings -> { startActivity(Intent(this, SettingsActivity::class.java)); true }
        else -> super.onOptionsItemSelected(item)
    }

    // ---------- call bar ----------

    private fun renderCall() {
        val active = CallManager.active
        b.callBar.isVisible = active
        volumeControlStream = if (active) AudioManager.STREAM_VOICE_CALL else AudioManager.USE_DEFAULT_STREAM_TYPE
        if (active) {
            if (!timerRunning) {
                b.callTimer.base = CallManager.startedAt
                b.callTimer.start()
                timerRunning = true
            }
            val muted = CallManager.muted
            b.btnMute.setIconResource(if (muted) R.drawable.ic_mic_off else R.drawable.ic_mic)
            b.btnMute.contentDescription = getString(if (muted) R.string.action_unmute else R.string.action_mute)
            val speaker = AudioRouter.isSpeakerOn(this)
            b.btnSpeaker.setIconResource(if (speaker) R.drawable.ic_speaker else R.drawable.ic_earpiece)
            b.btnSpeaker.contentDescription = getString(if (speaker) R.string.action_earpiece else R.string.action_speaker)
        } else if (timerRunning) {
            b.callTimer.stop()
            timerRunning = false
        }
    }

    // ---------- WebHolder.Host ----------

    override fun requestMicPermission(onResult: (Boolean) -> Unit) {
        micCallback?.invoke(false)
        micCallback = onResult
        micLauncher.launch(Manifest.permission.RECORD_AUDIO)
    }

    override fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 && !Permissions.hasNotifications(this)) {
            notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    override fun showFileChooser(
        cb: ValueCallback<Array<Uri>>,
        params: WebChromeClient.FileChooserParams
    ): Boolean {
        fileCallback?.onReceiveValue(null)
        fileCallback = cb
        val intent = params.createIntent().apply {
            if (params.mode == WebChromeClient.FileChooserParams.MODE_OPEN_MULTIPLE) {
                putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            }
        }
        try {
            fileLauncher.launch(intent)
        } catch (_: ActivityNotFoundException) {
            fileCallback = null
            cb.onReceiveValue(null)
        }
        return true
    }

    override fun onProgress(progress: Int) {
        b.progress.isVisible = progress in 1..99
        b.progress.setProgressCompat(progress, true)
    }

    override fun onLoadError(description: String) {
        Snackbar.make(b.root, getString(R.string.load_error, description), Snackbar.LENGTH_LONG)
            .setAction(R.string.menu_reload) { WebHolder.reload() }
            .show()
    }

    override fun onWebViewLost() {
        b.webContainer.post {
            if (!isDestroyed) WebHolder.attach(this, b.webContainer)
        }
        snack(getString(R.string.renderer_restarted))
    }

    private fun snack(text: String) = Snackbar.make(b.root, text, Snackbar.LENGTH_SHORT).show()
}
