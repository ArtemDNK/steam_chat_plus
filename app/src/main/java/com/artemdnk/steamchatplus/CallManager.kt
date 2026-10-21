package com.artemdnk.steamchatplus

/** Single source of truth for the voice call state reported by the page. */
object CallManager {

    var active = false
        private set
    var muted = false
        private set
    /** SystemClock.elapsedRealtime() when the current call started. */
    var startedAt = 0L
        private set

    private val listeners = LinkedHashSet<() -> Unit>()

    fun addListener(l: () -> Unit) { listeners += l }
    fun removeListener(l: () -> Unit) { listeners -= l }

    fun onPageState(active: Boolean, pageMuted: Boolean) {
        val ctx = App.instance
        val was = this.active
        if (this.active == active && (!active || muted == pageMuted)) return
        this.active = active
        if (active) muted = pageMuted

        if (active && !was) {
            startedAt = android.os.SystemClock.elapsedRealtime()
            AudioRouter.onCallStart(ctx)
        }
        if (!active && was) {
            AudioRouter.onCallEnd(ctx)
            if (muted) {
                // Don't carry a hardware mute into the next call.
                muted = false
                PageBridge.setMuted(false)
            }
        }
        changed()
    }

    fun toggleMute() {
        if (!active) return
        muted = !muted
        PageBridge.setMuted(muted)
        changed()
    }

    fun toggleSpeaker() {
        if (!active) return
        val ctx = App.instance
        AudioRouter.setSpeaker(ctx, !AudioRouter.isSpeakerOn(ctx))
        changed()
    }

    private fun changed() {
        SessionService.sync(App.instance)
        listeners.toList().forEach { it() }
    }
}
