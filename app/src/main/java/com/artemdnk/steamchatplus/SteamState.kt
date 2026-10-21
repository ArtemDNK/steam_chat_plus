package com.artemdnk.steamchatplus

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import org.json.JSONArray
import org.json.JSONObject

/** Persona states as sent by inject.js. */
object PersonaState {
    const val OFFLINE = 0
    const val ONLINE = 1
    const val IN_GAME = 2
    const val AWAY = 3
}

data class Person(
    val id: Long,
    val name: String,
    val avatar: String,
    val state: Int,
    val game: String,
    val rich: String,
    val lastSeen: Long
)

data class ChatSummary(
    val id: Long,
    val name: String,
    val avatar: String,
    val state: Int,
    val game: String,
    val last: String,
    val mine: Boolean,
    val time: Long,
    val unread: Int,
    val voice: Boolean,
    val typing: Boolean
)

data class Room(
    val id: String,
    val name: String,
    val unread: Int,
    val voiceMembers: List<Long>,
    val active: Boolean,
    val allowed: Boolean
)

data class Group(
    val id: String,
    val name: String,
    val avatar: String,
    val online: Int,
    val total: Int,
    val unread: Boolean,
    val time: Long,
    val last: String,
    val defaultRoom: String,
    val textRooms: List<Room>,
    val voiceRooms: List<Room>
)

data class VoiceState(
    val active: Boolean,
    val partner: Long,
    val groupId: String,
    val roomId: String,
    val title: String,
    val subtitle: String,
    val micMuted: Boolean,
    val outputMuted: Boolean,
    val connecting: Boolean,
    val members: List<Person>
) {
    companion object {
        val NONE = VoiceState(false, 0, "", "", "", "", false, false, false, emptyList())
    }
}

data class Message(
    val key: String,
    val author: Long,
    val time: Long,
    val text: String,
    val images: List<String>,
    val mine: Boolean,
    val system: Boolean,
    val pending: Boolean,
    val failed: Boolean,
    val deleted: Boolean
)

data class Conversation(
    val key: String,
    val kind: String,
    val id: String,
    val groupId: String,
    val roomId: String,
    val title: String,
    val subtitle: String,
    val avatar: String,
    val state: Int,
    val voice: Boolean,
    val more: Boolean,
    val loading: Boolean,
    val authors: Map<Long, Person>,
    val items: List<Message>
)

/** What the page currently is: the chat app, still loading, or something else (login). */
enum class PageStatus { LOADING, READY, WEB }

/**
 * Mirror of Steam's web-chat stores, fed by inject.js through [PageBridge].
 * All updates arrive on the main thread.
 */
object SteamState {
    val status = MutableLiveData(PageStatus.LOADING)
    val self = MutableLiveData<Person?>(null)
    val friends = MutableLiveData<List<Person>>(emptyList())
    val chats = MutableLiveData<List<ChatSummary>>(emptyList())
    val groups = MutableLiveData<List<Group>>(emptyList())
    val voice = MutableLiveData(VoiceState.NONE)
    val incoming = MutableLiveData<List<Person>>(emptyList())
    private val _conversation = MutableLiveData<Conversation?>(null)
    val conversation: LiveData<Conversation?> = _conversation
    val error = MutableLiveData<String?>(null)

    /** Key of the conversation the UI has open; stale page payloads for other chats are ignored. */
    var openKey: String? = null
        private set

    private val incomingListeners = LinkedHashSet<(List<Person>) -> Unit>()
    fun addIncomingListener(l: (List<Person>) -> Unit) { incomingListeners += l }

    fun onApi(k: String, v: Any?) {
        when (k) {
            "status" -> {
                val st = when (v as? String) {
                    "ready" -> PageStatus.READY
                    "web" -> PageStatus.WEB
                    else -> PageStatus.LOADING
                }
                val was = status.value
                status.value = st
                // Page reloaded under an open conversation: open it again in the new document.
                if (st == PageStatus.READY && was != PageStatus.READY) reopen()
            }
            "self" -> self.value = (v as? JSONObject)?.let(::person)
            "friends" -> friends.value = (v as? JSONArray).objects().map(::person)
            "chats" -> chats.value = (v as? JSONArray).objects().map(::chat)
            "groups" -> groups.value = (v as? JSONArray).objects().map(::group)
            "voice" -> voice.value = (v as? JSONObject)?.let(::voiceState) ?: VoiceState.NONE
            "incoming" -> {
                val list = (v as? JSONArray).objects().map(::person)
                incoming.value = list
                incomingListeners.toList().forEach { it(list) }
            }
            "messages" -> {
                val c = (v as? JSONObject)?.let(::conversation) ?: return
                if (c.key == openKey) _conversation.value = c
            }
            "error" -> error.value = v?.toString()
        }
    }

    /** New page document: everything will be resent. */
    fun onPageReset() {
        status.value = PageStatus.LOADING
    }

    // ---------- commands ----------

    fun openFriend(accountId: Long) = open("f$accountId", JSONObject().put("kind", "friend").put("id", accountId.toString()))

    fun openRoom(groupId: String, roomId: String) =
        open("r$groupId/$roomId", JSONObject().put("kind", "room").put("groupId", groupId).put("roomId", roomId))

    private fun open(key: String, args: JSONObject) {
        openKey = key
        _conversation.value = null
        PageBridge.api("open", args.put("key", key))
    }

    /** Re-sends the open request (after a page reload). */
    fun reopen() {
        val key = openKey ?: return
        when {
            key.startsWith("f") -> openFriend(key.drop(1).toLongOrNull() ?: return)
            key.startsWith("r") -> {
                val parts = key.drop(1).split("/", limit = 2)
                if (parts.size == 2) openRoom(parts[0], parts[1])
            }
        }
    }

    fun close() {
        openKey = null
        _conversation.value = null
        PageBridge.api("close")
    }

    fun send(text: String) = PageBridge.api("send", JSONObject().put("text", text))
    fun typing() = PageBridge.api("typing")
    fun loadMore() = PageBridge.api("more")
    fun callFriend(accountId: Long) = PageBridge.api("callFriend", JSONObject().put("id", accountId.toString()))
    fun joinRoom(groupId: String, roomId: String) =
        PageBridge.api("joinRoom", JSONObject().put("groupId", groupId).put("roomId", roomId))
    fun hangup() = PageBridge.api("hangup")
    fun toggleDeafen() = PageBridge.api("deafen")
    fun accept(accountId: Long) = PageBridge.api("accept", JSONObject().put("id", accountId.toString()))
    fun reject(accountId: Long) = PageBridge.api("reject", JSONObject().put("id", accountId.toString()))
    fun resync() = PageBridge.api("resync")

    private var lastActivity = 0L
    /** Keeps Steam from marking the user "Away" while they use the native UI. */
    fun userActivity() {
        val now = System.currentTimeMillis()
        if (now - lastActivity < 30_000) return
        lastActivity = now
        PageBridge.api("activity")
    }

    fun personById(id: Long): Person? =
        friends.value?.firstOrNull { it.id == id } ?: self.value?.takeIf { it.id == id }

    // ---------- parsing ----------

    private fun JSONArray?.objects(): List<JSONObject> =
        if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }

    private fun JSONArray?.longs(): List<Long> =
        if (this == null) emptyList() else (0 until length()).map { optLong(it) }

    private fun JSONArray?.strings(): List<String> =
        if (this == null) emptyList() else (0 until length()).mapNotNull { optString(it).takeIf(String::isNotBlank) }

    private fun person(o: JSONObject) = Person(
        id = o.optLong("id"),
        name = o.optString("name"),
        avatar = o.optString("avatar"),
        state = o.optInt("state"),
        game = o.optString("game"),
        rich = o.optString("rich"),
        lastSeen = o.optLong("seen")
    )

    private fun chat(o: JSONObject) = ChatSummary(
        id = o.optLong("id"),
        name = o.optString("name"),
        avatar = o.optString("avatar"),
        state = o.optInt("state"),
        game = o.optString("game"),
        last = o.optString("last"),
        mine = o.optBoolean("mine"),
        time = o.optLong("time"),
        unread = o.optInt("unread"),
        voice = o.optBoolean("voice"),
        typing = o.optBoolean("typing")
    )

    private fun room(o: JSONObject) = Room(
        id = o.optString("id"),
        name = o.optString("name"),
        unread = o.optInt("unread"),
        voiceMembers = o.optJSONArray("voice").longs(),
        active = o.optBoolean("active"),
        allowed = o.optBoolean("allowed", true)
    )

    private fun group(o: JSONObject) = Group(
        id = o.optString("id"),
        name = o.optString("name"),
        avatar = o.optString("avatar"),
        online = o.optInt("online"),
        total = o.optInt("total"),
        unread = o.optBoolean("unread"),
        time = o.optLong("time"),
        last = o.optString("last"),
        defaultRoom = o.optString("defaultRoom"),
        textRooms = o.optJSONArray("text").objects().map(::room),
        voiceRooms = o.optJSONArray("voiceRooms").objects().map(::room)
    )

    private fun voiceState(o: JSONObject) = VoiceState(
        active = o.optBoolean("active"),
        partner = o.optLong("partner"),
        groupId = o.optString("groupId"),
        roomId = o.optString("roomId"),
        title = o.optString("title"),
        subtitle = o.optString("subtitle"),
        micMuted = o.optBoolean("mic"),
        outputMuted = o.optBoolean("out"),
        connecting = o.optBoolean("connecting"),
        members = o.optJSONArray("members").objects().map(::person)
    )

    private fun conversation(o: JSONObject): Conversation {
        val authors = HashMap<Long, Person>()
        o.optJSONObject("authors")?.let { a ->
            a.keys().forEach { k -> a.optJSONObject(k)?.let { authors[k.toLongOrNull() ?: 0L] = person(it) } }
        }
        return Conversation(
            key = o.optString("key"),
            kind = o.optString("kind"),
            id = o.optString("id"),
            groupId = o.optString("groupId"),
            roomId = o.optString("roomId"),
            title = o.optString("title"),
            subtitle = o.optString("subtitle"),
            avatar = o.optString("avatar"),
            state = o.optInt("state"),
            voice = o.optBoolean("voice"),
            more = o.optBoolean("more"),
            loading = o.optBoolean("loading"),
            authors = authors,
            items = o.optJSONArray("items").objects().map {
                Message(
                    key = it.optString("k"),
                    author = it.optLong("a"),
                    time = it.optLong("t"),
                    text = it.optString("x"),
                    images = it.optJSONArray("i").strings(),
                    mine = it.optBoolean("me"),
                    system = it.optBoolean("s"),
                    pending = it.optBoolean("p"),
                    failed = it.optBoolean("f"),
                    deleted = it.optBoolean("d")
                )
            }
        )
    }
}
