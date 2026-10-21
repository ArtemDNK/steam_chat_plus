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
import android.view.MotionEvent
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
import androidx.fragment.app.Fragment
import com.artemdnk.steamchatplus.databinding.ActivityMainBinding
import com.artemdnk.steamchatplus.ui.CallsFragment
import com.artemdnk.steamchatplus.ui.ChatsFragment
import com.artemdnk.steamchatplus.ui.ConversationFragment
import com.artemdnk.steamchatplus.ui.FriendsFragment
import com.artemdnk.steamchatplus.ui.GroupFragment
import com.artemdnk.steamchatplus.ui.GroupsFragment
import com.artemdnk.steamchatplus.ui.Navigator
import com.artemdnk.steamchatplus.ui.Target
import com.google.android.material.snackbar.Snackbar
import kotlin.math.max

class MainActivity : AppCompatActivity(), WebHolder.Host, Navigator {

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

    /** User asked to see Steam's own web page. */
    private var userWeb = false
    /** Page took too long to become the chat app: show it as is. */
    private var loadingTimedOut = false
    private val loadingTimeout = Runnable {
        if (SteamState.status.value == PageStatus.LOADING) {
            loadingTimedOut = true
            renderMode()
        }
    }

    private val tabs = linkedMapOf(
        R.id.tab_chats to "chats",
        R.id.tab_groups to "groups",
        R.id.tab_friends to "friends",
        R.id.tab_calls to "calls"
    )
    private var currentTab = R.id.tab_chats

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

        currentTab = savedInstanceState?.getInt(STATE_TAB) ?: R.id.tab_chats
        userWeb = savedInstanceState?.getBoolean(STATE_WEB) ?: false
        setupTabs(savedInstanceState == null)

        b.btnMute.setOnClickListener { CallManager.toggleMute() }
        b.btnSpeaker.setOnClickListener { CallManager.toggleSpeaker() }
        b.btnHangup.setOnClickListener { SteamState.hangup() }
        b.callBar.setOnClickListener { showTab(R.id.tab_calls) }
        CallManager.addListener(callListener)

        supportFragmentManager.addOnBackStackChangedListener { renderMode() }

        onBackPressedDispatcher.addCallback(this) {
            val wv = WebHolder.webView
            when {
                supportFragmentManager.backStackEntryCount > 0 -> supportFragmentManager.popBackStack()
                userWeb && SteamState.status.value == PageStatus.READY -> { userWeb = false; renderMode() }
                isWebShown() && wv != null && wv.canGoBack() -> wv.goBack()
                currentTab != R.id.tab_chats -> showTab(R.id.tab_chats)
                else -> moveTaskToBack(true)
            }
        }

        SteamState.status.observe(this) { st ->
            b.root.removeCallbacks(loadingTimeout)
            if (st == PageStatus.LOADING) b.root.postDelayed(loadingTimeout, 20_000)
            else loadingTimedOut = false
            renderMode()
        }
        SteamState.self.observe(this) { renderTitle() }
        SteamState.chats.observe(this) { chats ->
            val unread = chats.sumOf { it.unread }
            b.bottomNav.getOrCreateBadge(R.id.tab_chats).apply {
                isVisible = unread > 0
                number = unread
            }
        }
        SteamState.groups.observe(this) { groups ->
            b.bottomNav.getOrCreateBadge(R.id.tab_groups).apply {
                isVisible = groups.any { it.unread }
                clearNumber()
            }
        }
        SteamState.incoming.observe(this) { renderCallsBadge() }
        SteamState.voice.observe(this) {
            renderCallsBadge()
            renderCall()
        }
        SteamState.error.observe(this) { msg ->
            if (!msg.isNullOrBlank() && BuildConfig.DEBUG) snack(msg)
        }

        if (savedInstanceState == null && Prefs.notifications(this)) requestNotificationPermission()

        handleIntent(intent)
        renderMode()
        renderCall()
        SessionService.sync(this)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(STATE_TAB, currentTab)
        outState.putBoolean(STATE_WEB, userWeb)
    }

    // ---------- tabs ----------

    private fun setupTabs(fresh: Boolean) {
        val fm = supportFragmentManager
        if (fresh) {
            val tx = fm.beginTransaction()
            tabs.forEach { (id, tag) ->
                val f = tabFragment(id)
                tx.add(R.id.nativeContainer, f, tag)
                if (id != currentTab) tx.hide(f)
            }
            tx.commitNow()
        }
        b.bottomNav.selectedItemId = currentTab
        b.bottomNav.setOnItemSelectedListener { item ->
            showTab(item.itemId); true
        }
        b.bottomNav.setOnItemReselectedListener {
            if (fm.backStackEntryCount > 0) fm.popBackStack(null, androidx.fragment.app.FragmentManager.POP_BACK_STACK_INCLUSIVE)
        }
        renderTitle()
    }

    private fun tabFragment(id: Int): Fragment = when (id) {
        R.id.tab_groups -> GroupsFragment()
        R.id.tab_friends -> FriendsFragment()
        R.id.tab_calls -> CallsFragment()
        else -> ChatsFragment()
    }

    private fun showTab(id: Int) {
        val fm = supportFragmentManager
        if (fm.backStackEntryCount > 0) fm.popBackStack(null, androidx.fragment.app.FragmentManager.POP_BACK_STACK_INCLUSIVE)
        currentTab = id
        val tx = fm.beginTransaction()
        tabs.forEach { (tid, tag) ->
            val f = fm.findFragmentByTag(tag) ?: return@forEach
            if (tid == id) tx.show(f) else tx.hide(f)
        }
        tx.commit()
        if (b.bottomNav.selectedItemId != id) b.bottomNav.selectedItemId = id
        if (userWeb) userWeb = false
        renderTitle()
        renderMode()
    }

    private fun renderTitle() {
        b.toolbar.title = getString(
            when (currentTab) {
                R.id.tab_groups -> R.string.tab_groups
                R.id.tab_friends -> R.string.tab_friends
                R.id.tab_calls -> R.string.tab_calls
                else -> R.string.tab_chats
            }
        )
        b.toolbar.subtitle = SteamState.self.value?.name
        if (isWebShown()) {
            b.toolbar.title = getString(if (userWeb) R.string.menu_web else R.string.app_name)
            b.toolbar.subtitle = null
        }
    }

    // ---------- Navigator ----------

    override fun openTarget(target: Target) {
        when (target) {
            is Target.FriendChat -> push(ConversationFragment.friend(target.accountId))
            is Target.TextRoom -> push(ConversationFragment.room(target.groupId, target.roomId))
            is Target.GroupRooms -> push(GroupFragment.newInstance(target.groupId))
            is Target.VoiceRoom -> SteamState.joinRoom(target.groupId, target.roomId)
            is Target.CallFriend -> {
                SteamState.callFriend(target.accountId)
                push(ConversationFragment.friend(target.accountId))
            }
            Target.None -> Unit
        }
    }

    private fun push(f: Fragment) {
        supportFragmentManager.beginTransaction()
            .setCustomAnimations(
                android.R.anim.slide_in_left, android.R.anim.fade_out,
                android.R.anim.fade_in, android.R.anim.slide_out_right
            )
            .add(R.id.nativeContainer, f)
            .addToBackStack(null)
            .commit()
    }

    // ---------- modes: native UI / loading / web page ----------

    private fun isWebShown(): Boolean {
        val st = SteamState.status.value
        return userWeb || st == PageStatus.WEB || (st == PageStatus.LOADING && loadingTimedOut)
    }

    private fun renderMode() {
        val st = SteamState.status.value
        val web = isWebShown()
        val loading = !web && st == PageStatus.LOADING
        val pushed = supportFragmentManager.backStackEntryCount > 0
        b.nativeContainer.isVisible = !web
        b.loadingView.isVisible = loading
        b.bottomNav.isVisible = !web && !loading && !pushed
        b.toolbar.isVisible = web || !pushed
        renderTitle()
        invalidateOptionsMenu()
    }

    private fun renderCallsBadge() {
        val incoming = SteamState.incoming.value.orEmpty().size
        val active = SteamState.voice.value?.active == true
        b.bottomNav.getOrCreateBadge(R.id.tab_calls).apply {
            isVisible = incoming > 0 || active
            if (incoming > 0) number = incoming else clearNumber()
        }
    }

    // ---------- intents ----------

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        intent ?: return
        val key = intent.getIntExtra(Notifier.EXTRA_NOTIF_KEY, -1)
        if (key != -1) {
            intent.removeExtra(Notifier.EXTRA_NOTIF_KEY)
            Notifier.onClicked(key)
            openNewestUnread()
        }
        val accept = intent.getLongExtra(Notifier.EXTRA_ACCEPT_CALL, 0L)
        if (accept != 0L) {
            intent.removeExtra(Notifier.EXTRA_ACCEPT_CALL)
            Notifier.cancelIncoming(this, accept)
            SteamState.accept(accept)
            showTab(R.id.tab_calls)
        }
        if (intent.getBooleanExtra(Notifier.EXTRA_SHOW_CALLS, false)) {
            intent.removeExtra(Notifier.EXTRA_SHOW_CALLS)
            showTab(R.id.tab_calls)
        }
    }

    /** A message notification was tapped: jump to the most recent unread conversation. */
    private fun openNewestUnread() {
        val chat = SteamState.chats.value.orEmpty().filter { it.unread > 0 }.maxByOrNull { it.time }
        if (chat == null) {
            showTab(R.id.tab_chats)
            return
        }
        showTab(R.id.tab_chats)
        b.root.post { openTarget(Target.FriendChat(chat.id)) }
    }

    // ---------- lifecycle ----------

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
        b.root.removeCallbacks(loadingTimeout)
        CallManager.removeListener(callListener)
        fileCallback?.onReceiveValue(null)
        fileCallback = null
        if (WebHolder.host === this) WebHolder.host = null
        WebHolder.detach(this)
        super.onDestroy()
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) SteamState.userActivity()
        return super.dispatchTouchEvent(ev)
    }

    // ---------- menu ----------

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main, menu)
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        val ready = SteamState.status.value == PageStatus.READY
        menu.findItem(R.id.menu_web)?.apply {
            isVisible = ready
            title = getString(if (userWeb) R.string.menu_native else R.string.menu_web)
        }
        return super.onPrepareOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        R.id.menu_web -> {
            userWeb = !userWeb
            renderMode(); true
        }
        R.id.menu_reload -> {
            WebHolder.reload(); true
        }
        R.id.menu_settings -> {
            startActivity(Intent(this, SettingsActivity::class.java)); true
        }
        else -> super.onOptionsItemSelected(item)
    }

    // ---------- call bar ----------

    private fun renderCall() {
        val active = CallManager.active || SteamState.voice.value?.active == true
        b.callBar.isVisible = active
        volumeControlStream = if (active) AudioManager.STREAM_VOICE_CALL else AudioManager.USE_DEFAULT_STREAM_TYPE
        if (active) {
            val voice = SteamState.voice.value
            b.callTitle.text = voice?.title?.takeIf { it.isNotBlank() } ?: getString(R.string.call_title)
            if (!timerRunning) {
                b.callTimer.base = if (CallManager.startedAt > 0) CallManager.startedAt else android.os.SystemClock.elapsedRealtime()
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
        b.progress.isVisible = isWebShown() && progress in 1..99
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

    companion object {
        private const val STATE_TAB = "tab"
        private const val STATE_WEB = "web"
    }
}
