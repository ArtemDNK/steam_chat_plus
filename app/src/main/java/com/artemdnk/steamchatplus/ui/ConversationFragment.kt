package com.artemdnk.steamchatplus.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.text.format.DateFormat
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.os.bundleOf
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.artemdnk.steamchatplus.Conversation
import com.artemdnk.steamchatplus.Message
import com.artemdnk.steamchatplus.R
import com.artemdnk.steamchatplus.SteamState
import com.artemdnk.steamchatplus.WebHolder
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.imageview.ShapeableImageView
import java.util.Date

class ConversationFragment : Fragment() {

    private val kind get() = requireArguments().getString(ARG_KIND).orEmpty()
    private val friendId get() = requireArguments().getLong(ARG_FRIEND)
    private val groupId get() = requireArguments().getString(ARG_GROUP).orEmpty()
    private val roomId get() = requireArguments().getString(ARG_ROOM).orEmpty()
    private val key get() = if (kind == KIND_FRIEND) "f$friendId" else "r$groupId/$roomId"

    private lateinit var adapter: MessageAdapter
    private lateinit var layoutManager: LinearLayoutManager
    private var lastMoreRequest = 0L
    private var lastTyping = 0L
    private var firstLoad = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (SteamState.openKey != key) {
            if (kind == KIND_FRIEND) SteamState.openFriend(friendId) else SteamState.openRoom(groupId, roomId)
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        inflater.inflate(R.layout.fragment_conversation, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val toolbar = view.findViewById<MaterialToolbar>(R.id.toolbar)
        toolbar.setNavigationOnClickListener { parentFragmentManager.popBackStack() }
        toolbar.inflateMenu(R.menu.conversation)
        toolbar.setOnMenuItemClickListener {
            if (it.itemId == R.id.menu_call) { startCall(); true } else false
        }

        adapter = MessageAdapter(isGroup = kind != KIND_FRIEND)
        layoutManager = LinearLayoutManager(requireContext()).apply { stackFromEnd = true }
        val list = view.findViewById<RecyclerView>(R.id.messages)
        list.layoutManager = layoutManager
        list.adapter = adapter
        list.itemAnimator = null
        list.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                if (dy < 0 && layoutManager.findFirstVisibleItemPosition() <= 2) requestMore()
            }
        })

        val input = view.findViewById<EditText>(R.id.input)
        input.doAfterTextChanged {
            val now = System.currentTimeMillis()
            if (!it.isNullOrBlank() && now - lastTyping > 4000) {
                lastTyping = now
                SteamState.typing()
            }
        }
        view.findViewById<View>(R.id.send).setOnClickListener {
            val text = input.text.toString().trim()
            if (text.isEmpty()) return@setOnClickListener
            SteamState.send(text)
            SteamState.userActivity()
            input.setText("")
            lastTyping = 0
        }

        SteamState.conversation.observe(viewLifecycleOwner) { c -> render(c) }
    }

    private fun startCall() {
        SteamState.userActivity()
        if (kind == KIND_FRIEND) SteamState.callFriend(friendId) else SteamState.joinRoom(groupId, roomId)
    }

    private fun requestMore() {
        val c = SteamState.conversation.value ?: return
        val now = System.currentTimeMillis()
        if (!c.more || c.loading || now - lastMoreRequest < 1500) return
        lastMoreRequest = now
        SteamState.loadMore()
    }

    private fun render(c: Conversation?) {
        val v = view ?: return
        v.findViewById<View>(R.id.loading).isVisible = c == null
        if (c == null) return
        v.findViewById<TextView>(R.id.title).text = c.title
        v.findViewById<TextView>(R.id.subtitle).apply {
            val friend = if (c.kind == KIND_FRIEND) SteamState.personById(c.id.toLongOrNull() ?: 0) else null
            text = when {
                c.subtitle.isNotEmpty() -> c.subtitle
                friend != null -> statusText(context, friend)
                else -> ""
            }
            setTextColor(ContextCompat.getColor(context,
                if (c.subtitle == getString(R.string.typing)) R.color.steam_accent else R.color.text_secondary))
            isVisible = text.isNotEmpty()
        }
        v.findViewById<ShapeableImageView>(R.id.avatar).apply {
            loadAvatar(c.avatar.ifBlank { null })
            setPresence(if (c.kind == KIND_FRIEND) c.state else null)
        }

        val atBottom = layoutManager.findLastVisibleItemPosition() >= adapter.itemCount - 2
        val grew = c.items.size > adapter.itemCount
        adapter.authors = c.authors
        adapter.submitList(c.items) {
            if (firstLoad || (grew && atBottom) || c.items.lastOrNull()?.mine == true && grew) {
                firstLoad = false
                if (adapter.itemCount > 0) layoutManager.scrollToPosition(adapter.itemCount - 1)
            }
        }
    }

    override fun onDestroy() {
        if (isRemoving && SteamState.openKey == key) SteamState.close()
        super.onDestroy()
    }

    companion object {
        const val KIND_FRIEND = "friend"
        const val KIND_ROOM = "room"
        private const val ARG_KIND = "kind"
        private const val ARG_FRIEND = "friend"
        private const val ARG_GROUP = "group"
        private const val ARG_ROOM = "room"

        fun friend(accountId: Long) = ConversationFragment().apply {
            arguments = bundleOf(ARG_KIND to KIND_FRIEND, ARG_FRIEND to accountId)
        }

        fun room(groupId: String, roomId: String) = ConversationFragment().apply {
            arguments = bundleOf(ARG_KIND to KIND_ROOM, ARG_GROUP to groupId, ARG_ROOM to roomId)
        }
    }
}

// ---------------------------------------------------------------------------

private class MessageAdapter(private val isGroup: Boolean) :
    ListAdapter<Message, RecyclerView.ViewHolder>(object : DiffUtil.ItemCallback<Message>() {
        override fun areItemsTheSame(a: Message, b: Message) = a.key == b.key
        override fun areContentsTheSame(a: Message, b: Message) = a == b
    }) {

    var authors: Map<Long, com.artemdnk.steamchatplus.Person> = emptyMap()

    override fun getItemViewType(position: Int) = if (getItem(position).system) 1 else 0

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val layout = if (viewType == 1) R.layout.item_system else R.layout.item_message
        return object : RecyclerView.ViewHolder(LayoutInflater.from(parent.context).inflate(layout, parent, false)) {}
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val m = getItem(position)
        val v = holder.itemView
        val ctx = v.context
        if (m.system) {
            (v as TextView).text = m.text.ifBlank { ctx.getString(R.string.system_message) }
            return
        }
        val prev = if (position > 0) getItem(position - 1) else null
        val next = if (position + 1 < itemCount) getItem(position + 1) else null
        val firstInRun = prev == null || prev.author != m.author || prev.system || m.time - prev.time > 600
        val lastInRun = next == null || next.author != m.author || next.system || next.time - m.time > 600

        val row = v.findViewById<LinearLayout>(R.id.row)
        row.gravity = if (m.mine) Gravity.END else Gravity.START
        row.setPadding(row.paddingLeft, if (firstInRun) dp(ctx, 6) else dp(ctx, 1), row.paddingRight, dp(ctx, 1))

        val avatar = v.findViewById<ShapeableImageView>(R.id.avatar)
        val showAvatarColumn = isGroup && !m.mine
        avatar.visibility = when {
            !showAvatarColumn -> View.GONE
            lastInRun -> View.VISIBLE
            else -> View.INVISIBLE
        }
        if (showAvatarColumn && lastInRun) avatar.loadAvatar(authors[m.author]?.avatar)

        val bubble = v.findViewById<LinearLayout>(R.id.bubble)
        bubble.setBackgroundResource(if (m.mine) R.drawable.bubble_out else R.drawable.bubble_in)
        val maxW = (ctx.resources.displayMetrics.widthPixels * 0.78f).toInt()

        v.findViewById<TextView>(R.id.author).apply {
            isVisible = isGroup && !m.mine && firstInRun
            text = authors[m.author]?.name ?: ""
        }

        val image = v.findViewById<ImageView>(R.id.image)
        val url = m.images.firstOrNull()
        image.isVisible = url != null && !m.deleted
        if (url != null && !m.deleted) {
            image.load(url) { crossfade(true) }
            image.setOnClickListener { WebHolder.openLink(Uri.parse(url)) }
        } else {
            image.setOnClickListener(null)
        }

        val body = v.findViewById<TextView>(R.id.text)
        body.maxWidth = maxW
        when {
            m.deleted -> {
                body.text = ctx.getString(R.string.message_deleted)
                body.setTypeface(null, Typeface.ITALIC)
                body.isVisible = true
            }
            else -> {
                body.text = m.text
                body.setTypeface(null, Typeface.NORMAL)
                body.isVisible = m.text.isNotEmpty() || url == null
            }
        }
        body.setOnLongClickListener {
            val cm = ctx.getSystemService(ClipboardManager::class.java)
            cm.setPrimaryClip(ClipData.newPlainText("message", m.text))
            Toast.makeText(ctx, R.string.copied, Toast.LENGTH_SHORT).show()
            true
        }

        val time = if (m.time > 0) DateFormat.getTimeFormat(ctx).format(Date(m.time * 1000)) else ""
        v.findViewById<TextView>(R.id.meta).apply {
            text = when {
                m.failed -> ctx.getString(R.string.message_failed)
                m.pending -> "$time ⏳"
                else -> time
            }
            setTextColor(ContextCompat.getColor(ctx, if (m.failed) R.color.danger else R.color.text_secondary))
        }
    }

    private fun dp(ctx: android.content.Context, v: Int) = (v * ctx.resources.displayMetrics.density).toInt()
}
