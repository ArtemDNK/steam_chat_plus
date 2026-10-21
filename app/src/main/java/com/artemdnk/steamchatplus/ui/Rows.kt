package com.artemdnk.steamchatplus.ui

import android.content.Context
import android.text.format.DateFormat
import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.artemdnk.steamchatplus.Person
import com.artemdnk.steamchatplus.PersonaState
import com.artemdnk.steamchatplus.R
import com.artemdnk.steamchatplus.VoiceState
import com.google.android.material.imageview.ShapeableImageView
import java.util.Date

/** Where a tap on a row leads. */
sealed class Target {
    data class FriendChat(val accountId: Long) : Target()
    data class GroupRooms(val groupId: String) : Target()
    data class TextRoom(val groupId: String, val roomId: String) : Target()
    data class VoiceRoom(val groupId: String, val roomId: String) : Target()
    data class CallFriend(val accountId: Long) : Target()
    data object None : Target()
}

sealed class Row(val key: String) {
    data class Header(val title: String) : Row("h:$title")
    data class Empty(val text: String) : Row("e:$text")
    data class Search(val query: String) : Row("search")
    data class Contact(
        val id: String,
        val avatar: String?,
        val glyph: String? = null,
        val title: String,
        val subtitle: String,
        val subtitleColor: Int = R.color.text_secondary,
        val trailing: String = "",
        val badge: Int = 0,
        val dot: Boolean = false,
        val presence: Int? = null,
        val target: Target
    ) : Row("c:$id")
    data class Call(val voice: VoiceState, val speaker: Boolean) : Row("call")
    data class Incoming(val person: Person) : Row("in:${person.id}")
}

interface RowActions {
    fun onTarget(target: Target) {}
    fun onSearch(query: String) {}
    fun onCall(action: CallAction) {}
    fun onIncoming(person: Person, accept: Boolean) {}
}

enum class CallAction { MIC, DEAFEN, SPEAKER, HANGUP }

class RowAdapter(private val actions: RowActions) : ListAdapter<Row, RecyclerView.ViewHolder>(Diff) {

    private object Diff : DiffUtil.ItemCallback<Row>() {
        override fun areItemsTheSame(a: Row, b: Row) = a.key == b.key
        override fun areContentsTheSame(a: Row, b: Row) = a == b
    }

    override fun getItemViewType(position: Int) = when (getItem(position)) {
        is Row.Header -> 0
        is Row.Empty -> 1
        is Row.Search -> 2
        is Row.Contact -> 3
        is Row.Call -> 4
        is Row.Incoming -> 5
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inf = LayoutInflater.from(parent.context)
        val layout = when (viewType) {
            0 -> R.layout.item_header
            1 -> R.layout.item_empty
            2 -> R.layout.item_search
            3 -> R.layout.item_contact
            4 -> R.layout.item_call
            else -> R.layout.item_incoming
        }
        val v = inf.inflate(layout, parent, false)
        return when (viewType) {
            2 -> SearchVH(v as EditText, actions)
            else -> object : RecyclerView.ViewHolder(v) {}
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val v = holder.itemView
        when (val row = getItem(position)) {
            is Row.Header -> (v as TextView).text = row.title
            is Row.Empty -> (v as TextView).text = row.text
            is Row.Search -> (holder as SearchVH).bind(row.query)
            is Row.Contact -> bindContact(v, row)
            is Row.Call -> bindCall(v, row)
            is Row.Incoming -> {
                v.findViewById<ShapeableImageView>(R.id.avatar).loadAvatar(row.person.avatar)
                v.findViewById<TextView>(R.id.title).text = row.person.name
                v.findViewById<View>(R.id.accept).setOnClickListener { actions.onIncoming(row.person, true) }
                v.findViewById<View>(R.id.reject).setOnClickListener { actions.onIncoming(row.person, false) }
            }
        }
    }

    private fun bindContact(v: View, row: Row.Contact) {
        val ctx = v.context
        val avatar = v.findViewById<ShapeableImageView>(R.id.avatar)
        val glyph = v.findViewById<TextView>(R.id.glyph)
        if (row.glyph != null) {
            avatar.isVisible = false
            glyph.isVisible = true
            glyph.text = row.glyph
        } else {
            glyph.isVisible = false
            avatar.isVisible = true
            avatar.loadAvatar(row.avatar)
            avatar.setPresence(row.presence)
        }
        v.findViewById<TextView>(R.id.title).text = row.title
        v.findViewById<TextView>(R.id.subtitle).apply {
            text = row.subtitle
            isVisible = row.subtitle.isNotEmpty()
            setTextColor(ContextCompat.getColor(ctx, row.subtitleColor))
        }
        v.findViewById<TextView>(R.id.trailing).text = row.trailing
        v.findViewById<TextView>(R.id.badge).apply {
            isVisible = row.badge > 0 || row.dot
            text = if (row.badge > 0) (if (row.badge > 99) "99+" else row.badge.toString()) else ""
            minWidth = if (row.badge > 0) dp(ctx, 20) else dp(ctx, 10)
            layoutParams = layoutParams.apply { height = if (row.badge > 0) dp(ctx, 20) else dp(ctx, 10) }
        }
        v.setOnClickListener { actions.onTarget(row.target) }
    }

    private fun bindCall(v: View, row: Row.Call) {
        val ctx = v.context
        val voice = row.voice
        v.findViewById<TextView>(R.id.callState).text = ctx.getString(
            if (voice.connecting) R.string.call_connecting else R.string.call_in_progress
        )
        v.findViewById<TextView>(R.id.callName).text = voice.title.ifBlank { ctx.getString(R.string.call_title) }
        v.findViewById<TextView>(R.id.callSub).apply {
            text = voice.subtitle
            isVisible = voice.subtitle.isNotEmpty()
        }
        val members = v.findViewById<LinearLayout>(R.id.callMembers)
        members.removeAllViews()
        voice.members.forEach { p ->
            val line = LayoutInflater.from(ctx).inflate(R.layout.item_member, members, false)
            line.findViewById<ShapeableImageView>(R.id.avatar).loadAvatar(p.avatar)
            line.findViewById<TextView>(R.id.name).text = p.name
            members.addView(line)
        }
        members.isVisible = voice.members.isNotEmpty()

        v.findViewById<ImageView>(R.id.cMic).apply {
            setImageResource(if (voice.micMuted) R.drawable.ic_mic_off else R.drawable.ic_mic)
            setBackgroundResource(if (voice.micMuted) R.drawable.round_button_red else R.drawable.round_button_dark)
            setOnClickListener { actions.onCall(CallAction.MIC) }
        }
        v.findViewById<ImageView>(R.id.cDeafen).apply {
            setImageResource(if (voice.outputMuted) R.drawable.ic_headset_off else R.drawable.ic_stat_call)
            setBackgroundResource(if (voice.outputMuted) R.drawable.round_button_red else R.drawable.round_button_dark)
            setOnClickListener { actions.onCall(CallAction.DEAFEN) }
        }
        v.findViewById<ImageView>(R.id.cSpeaker).apply {
            setImageResource(if (row.speaker) R.drawable.ic_speaker else R.drawable.ic_earpiece)
            setOnClickListener { actions.onCall(CallAction.SPEAKER) }
        }
        v.findViewById<View>(R.id.cHangup).setOnClickListener { actions.onCall(CallAction.HANGUP) }
    }

    private class SearchVH(private val edit: EditText, actions: RowActions) : RecyclerView.ViewHolder(edit) {
        private var binding = false
        init {
            edit.doAfterTextChanged { if (!binding) actions.onSearch(it?.toString().orEmpty()) }
        }
        fun bind(query: String) {
            if (edit.text.toString() == query) return
            binding = true
            edit.setText(query)
            edit.setSelection(query.length)
            binding = false
        }
    }

    companion object {
        fun dp(ctx: Context, v: Int) = (v * ctx.resources.displayMetrics.density).toInt()
    }
}

// ---------- shared helpers ----------

fun ImageView.loadAvatar(url: String?) {
    if (url.isNullOrBlank()) {
        setImageResource(R.drawable.avatar_placeholder)
        return
    }
    load(url) {
        crossfade(true)
        placeholder(R.drawable.avatar_placeholder)
        error(R.drawable.avatar_placeholder)
    }
}

fun ShapeableImageView.setPresence(state: Int?) {
    if (state == null) {
        strokeWidth = 0f
        return
    }
    strokeWidth = 2f * resources.displayMetrics.density
    setStrokeColorResource(presenceColor(state))
}

fun presenceColor(state: Int) = when (state) {
    PersonaState.IN_GAME -> R.color.state_ingame
    PersonaState.ONLINE -> R.color.state_online
    PersonaState.AWAY -> R.color.state_away
    else -> R.color.state_offline
}

/** "В игре: Dota 2", "В сети", "Был в сети 12.10 14:30" */
fun statusText(ctx: Context, p: Person): String = when (p.state) {
    PersonaState.IN_GAME -> p.game.ifBlank { ctx.getString(R.string.state_ingame) }
    PersonaState.ONLINE -> ctx.getString(R.string.state_online)
    PersonaState.AWAY -> ctx.getString(R.string.state_away)
    else -> if (p.lastSeen > 0) ctx.getString(R.string.state_last_seen, shortTime(ctx, p.lastSeen))
            else ctx.getString(R.string.state_offline)
}

/** Unix seconds -> "14:30" today, "вчера", or "12.10". */
fun shortTime(ctx: Context, unixSeconds: Long): String {
    if (unixSeconds <= 0) return ""
    val ms = unixSeconds * 1000
    return when {
        DateUtils.isToday(ms) -> DateFormat.getTimeFormat(ctx).format(Date(ms))
        DateUtils.isToday(ms + DateUtils.DAY_IN_MILLIS) -> ctx.getString(R.string.yesterday)
        else -> android.text.format.DateFormat.format("dd.MM", ms).toString()
    }
}
