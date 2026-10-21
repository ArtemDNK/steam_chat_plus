package com.artemdnk.steamchatplus.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.os.bundleOf
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.artemdnk.steamchatplus.R
import com.artemdnk.steamchatplus.SteamState
import com.google.android.material.appbar.MaterialToolbar

/** Channels of one group chat: text rooms open a conversation, voice rooms join voice. */
class GroupFragment : Fragment(), RowActions {

    private lateinit var adapter: RowAdapter
    private val groupId get() = requireArguments().getString(ARG_GROUP).orEmpty()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        inflater.inflate(R.layout.fragment_group, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        view.findViewById<MaterialToolbar>(R.id.toolbar).setNavigationOnClickListener {
            parentFragmentManager.popBackStack()
        }
        adapter = RowAdapter(this)
        view.findViewById<RecyclerView>(R.id.list).apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = this@GroupFragment.adapter
            itemAnimator = null
        }
        SteamState.groups.observe(viewLifecycleOwner) { render() }
        SteamState.voice.observe(viewLifecycleOwner) { render() }
        render()
    }

    private fun render() {
        val v = view ?: return
        val g = SteamState.groups.value.orEmpty().firstOrNull { it.id == groupId }
        val toolbar = v.findViewById<MaterialToolbar>(R.id.toolbar)
        if (g == null) {
            toolbar.title = getString(R.string.tab_groups)
            adapter.submitList(listOf(Row.Empty(getString(R.string.group_gone))))
            return
        }
        toolbar.title = g.name
        toolbar.subtitle = getString(R.string.group_members, g.online, g.total)

        val voice = SteamState.voice.value
        val friends = SteamState.friends.value.orEmpty().associateBy { it.id }
        val rows = ArrayList<Row>()
        rows += Row.Header(getString(R.string.section_text_channels))
        g.textRooms.forEach { r ->
            rows += Row.Contact(
                id = "t${r.id}",
                avatar = null,
                glyph = "#",
                title = r.name.ifBlank { getString(R.string.text_channel) },
                subtitle = if (r.id == g.defaultRoom) getString(R.string.default_channel) else "",
                badge = r.unread,
                target = Target.TextRoom(g.id, r.id)
            )
        }
        if (g.textRooms.isEmpty()) rows += Row.Empty(getString(R.string.no_channels))

        rows += Row.Header(getString(R.string.section_voice_channels))
        g.voiceRooms.forEach { r ->
            val here = voice?.active == true && voice.groupId == g.id && voice.roomId == r.id
            val names = r.voiceMembers.map { id -> friends[id]?.name ?: SteamState.personById(id)?.name ?: "…" }
            rows += Row.Contact(
                id = "v${r.id}",
                avatar = null,
                glyph = "🔊",
                title = r.name.ifBlank { getString(R.string.voice_channel) },
                subtitle = if (names.isEmpty()) getString(R.string.channel_empty) else names.joinToString(),
                subtitleColor = if (names.isEmpty()) R.color.text_secondary else R.color.state_ingame,
                trailing = if (here) getString(R.string.you_are_here) else getString(R.string.join),
                target = if (here) Target.None else Target.VoiceRoom(g.id, r.id)
            )
        }
        if (g.voiceRooms.isEmpty()) rows += Row.Empty(getString(R.string.no_voice_channels))
        adapter.submitList(rows)
    }

    override fun onTarget(target: Target) {
        SteamState.userActivity()
        (activity as? Navigator)?.openTarget(target)
    }

    companion object {
        private const val ARG_GROUP = "group"
        fun newInstance(groupId: String) = GroupFragment().apply { arguments = bundleOf(ARG_GROUP to groupId) }
    }
}
