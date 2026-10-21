package com.artemdnk.steamchatplus.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.LiveData
import androidx.lifecycle.Observer
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.artemdnk.steamchatplus.AudioRouter
import com.artemdnk.steamchatplus.CallManager
import com.artemdnk.steamchatplus.Person
import com.artemdnk.steamchatplus.PersonaState
import com.artemdnk.steamchatplus.R
import com.artemdnk.steamchatplus.SteamState

/** Implemented by MainActivity: opens conversation / group screens. */
interface Navigator {
    fun openTarget(target: Target)
}

/** A tab = a list rebuilt from SteamState whenever any of its sources changes. */
abstract class TabFragment : Fragment(), RowActions {

    protected lateinit var adapter: RowAdapter
    protected var list: RecyclerView? = null

    abstract fun sources(): List<LiveData<*>>
    abstract fun buildRows(): List<Row>

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        inflater.inflate(R.layout.fragment_list, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        adapter = RowAdapter(this)
        list = view.findViewById<RecyclerView>(R.id.list).also {
            it.layoutManager = LinearLayoutManager(requireContext())
            it.adapter = adapter
            it.itemAnimator = null
        }
        val observer = Observer<Any?> { refresh() }
        sources().forEach { src -> src.observe(viewLifecycleOwner, observer) }
        refresh()
    }

    override fun onDestroyView() {
        list = null
        super.onDestroyView()
    }

    protected fun refresh() {
        if (view == null) return
        adapter.submitList(buildRows())
    }

    override fun onTarget(target: Target) {
        SteamState.userActivity()
        (activity as? Navigator)?.openTarget(target)
    }

    override fun onCall(action: CallAction) {
        SteamState.userActivity()
        when (action) {
            CallAction.MIC -> CallManager.toggleMute()
            CallAction.DEAFEN -> SteamState.toggleDeafen()
            CallAction.SPEAKER -> CallManager.toggleSpeaker()
            CallAction.HANGUP -> SteamState.hangup()
        }
        // Speaker state lives in Android, not in the page: redraw right away.
        list?.postDelayed({ refresh() }, 150)
    }

    override fun onIncoming(person: Person, accept: Boolean) {
        if (accept) SteamState.accept(person.id) else SteamState.reject(person.id)
    }
}

// ---------------------------------------------------------------------------

class ChatsFragment : TabFragment() {
    override fun sources() = listOf(SteamState.chats)

    override fun buildRows(): List<Row> {
        val ctx = requireContext()
        val chats = SteamState.chats.value.orEmpty()
        if (chats.isEmpty()) return listOf(Row.Empty(getString(R.string.empty_chats)))
        return chats.map { c ->
            val subtitle: String
            val color: Int
            when {
                c.typing -> { subtitle = getString(R.string.typing); color = R.color.steam_accent }
                c.voice -> { subtitle = getString(R.string.in_voice_with_you); color = R.color.state_ingame }
                else -> {
                    subtitle = (if (c.mine && c.last.isNotEmpty()) getString(R.string.you_prefix) else "") + c.last
                    color = R.color.text_secondary
                }
            }
            Row.Contact(
                id = "chat${c.id}",
                avatar = c.avatar,
                title = c.name,
                subtitle = subtitle,
                subtitleColor = color,
                trailing = shortTime(ctx, c.time),
                badge = c.unread,
                presence = c.state,
                target = Target.FriendChat(c.id)
            )
        }
    }
}

// ---------------------------------------------------------------------------

class GroupsFragment : TabFragment() {
    override fun sources() = listOf(SteamState.groups)

    override fun buildRows(): List<Row> {
        val ctx = requireContext()
        val groups = SteamState.groups.value.orEmpty()
        if (groups.isEmpty()) return listOf(Row.Empty(getString(R.string.empty_groups)))
        return groups.map { g ->
            val inVoice = g.voiceRooms.sumOf { it.voiceMembers.size }
            val subtitle = when {
                inVoice > 0 -> getString(R.string.group_in_voice, inVoice)
                g.last.isNotEmpty() -> g.last
                else -> getString(R.string.group_members, g.online, g.total)
            }
            Row.Contact(
                id = "group${g.id}",
                avatar = g.avatar.ifBlank { null },
                title = g.name,
                subtitle = subtitle,
                subtitleColor = if (inVoice > 0) R.color.state_ingame else R.color.text_secondary,
                trailing = shortTime(ctx, g.time),
                dot = g.unread,
                target = Target.GroupRooms(g.id)
            )
        }
    }
}

// ---------------------------------------------------------------------------

class FriendsFragment : TabFragment() {
    private var query = ""

    override fun sources() = listOf(SteamState.friends)

    override fun onSearch(query: String) {
        this.query = query
        refresh()
    }

    override fun buildRows(): List<Row> {
        val ctx = requireContext()
        val all = SteamState.friends.value.orEmpty()
        val q = query.trim().lowercase()
        val friends = if (q.isEmpty()) all else all.filter { it.name.lowercase().contains(q) || it.game.lowercase().contains(q) }
        val rows = ArrayList<Row>()
        rows += Row.Search(query)
        if (all.isEmpty()) {
            rows += Row.Empty(getString(R.string.empty_friends)); return rows
        }
        fun section(title: Int, people: List<Person>) {
            if (people.isEmpty()) return
            rows += Row.Header(getString(title) + " — " + people.size)
            people.sortedBy { it.name.lowercase() }.forEach { p ->
                rows += Row.Contact(
                    id = "friend${p.id}",
                    avatar = p.avatar,
                    title = p.name,
                    subtitle = statusText(ctx, p),
                    subtitleColor = presenceColor(p.state).takeIf { p.state != PersonaState.OFFLINE } ?: R.color.text_secondary,
                    presence = p.state,
                    target = Target.FriendChat(p.id)
                )
            }
        }
        section(R.string.section_ingame, friends.filter { it.state == PersonaState.IN_GAME })
        section(R.string.section_online, friends.filter { it.state == PersonaState.ONLINE || it.state == PersonaState.AWAY })
        section(R.string.section_offline, friends.filter { it.state == PersonaState.OFFLINE })
        if (friends.isEmpty()) rows += Row.Empty(getString(R.string.nothing_found))
        return rows
    }
}

// ---------------------------------------------------------------------------

class CallsFragment : TabFragment() {
    override fun sources() = listOf(SteamState.voice, SteamState.incoming, SteamState.groups, SteamState.friends)

    override fun buildRows(): List<Row> {
        val ctx = requireContext()
        val rows = ArrayList<Row>()
        val voice = SteamState.voice.value
        if (voice != null && voice.active) rows += Row.Call(voice, AudioRouter.isSpeakerOn(ctx))

        SteamState.incoming.value.orEmpty().forEach { rows += Row.Incoming(it) }

        val friendsById = SteamState.friends.value.orEmpty().associateBy { it.id }
        val groups = SteamState.groups.value.orEmpty()

        // Voice channels of all groups, busiest first.
        val channels = groups.flatMap { g -> g.voiceRooms.map { g to it } }
            .sortedByDescending { it.second.voiceMembers.size }
        rows += Row.Header(getString(R.string.section_voice_channels))
        if (channels.isEmpty()) {
            rows += Row.Empty(getString(R.string.empty_voice_channels))
        } else {
            channels.forEach { (g, r) ->
                val names = r.voiceMembers.map { id -> friendsById[id]?.name ?: SteamState.personById(id)?.name ?: "…" }
                val here = voice?.active == true && voice.groupId == g.id && voice.roomId == r.id
                rows += Row.Contact(
                    id = "vc${g.id}/${r.id}",
                    avatar = null,
                    glyph = "🔊",
                    title = r.name.ifBlank { getString(R.string.voice_channel) },
                    subtitle = if (names.isEmpty()) g.name else "${g.name} · ${names.joinToString()}",
                    subtitleColor = if (names.isEmpty()) R.color.text_secondary else R.color.state_ingame,
                    trailing = if (here) getString(R.string.you_are_here) else if (r.voiceMembers.isNotEmpty()) r.voiceMembers.size.toString() else "",
                    target = if (here) Target.None else Target.VoiceRoom(g.id, r.id)
                )
            }
        }

        // Friends currently sitting in a voice channel we can see.
        val friendsInVoice = channels.flatMap { (g, r) -> r.voiceMembers.mapNotNull { friendsById[it] }.map { Triple(it, g, r) } }
            .distinctBy { it.first.id }
        rows += Row.Header(getString(R.string.section_friends_in_voice))
        if (friendsInVoice.isEmpty()) {
            rows += Row.Empty(getString(R.string.empty_friends_in_voice))
        } else {
            friendsInVoice.forEach { (p, g, r) ->
                rows += Row.Contact(
                    id = "fv${p.id}",
                    avatar = p.avatar,
                    title = p.name,
                    subtitle = "🔊 ${r.name} · ${g.name}",
                    subtitleColor = R.color.state_ingame,
                    presence = p.state,
                    target = Target.VoiceRoom(g.id, r.id)
                )
            }
        }

        // Quick dial: friends who are online right now.
        val online = SteamState.friends.value.orEmpty()
            .filter { it.state != PersonaState.OFFLINE }
            .sortedWith(compareBy<Person>({ it.state != PersonaState.ONLINE }, { it.name.lowercase() }))
        rows += Row.Header(getString(R.string.section_call_friend))
        if (online.isEmpty()) {
            rows += Row.Empty(getString(R.string.empty_online))
        } else {
            online.forEach { p ->
                rows += Row.Contact(
                    id = "call${p.id}",
                    avatar = p.avatar,
                    title = p.name,
                    subtitle = statusText(ctx, p),
                    subtitleColor = presenceColor(p.state),
                    trailing = "📞",
                    presence = p.state,
                    target = Target.CallFriend(p.id)
                )
            }
        }
        return rows
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }
}
