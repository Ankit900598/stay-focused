package com.phonecodex.app.domain.multichat

import java.util.UUID

/** Debug-only multi-chat scaffold. OFF by default. */
data class ChatThread(
    val id: String,
    val title: String,
    val createdAtMillis: Long,
    val updatedAtMillis: Long,
)

object MultiChatGate {
    /** Hard switch. Release can never enable multi-chat. */
    const val MULTI_CHAT_DEBUG_ALLOWED: Boolean = true
    const val PREF_KEY_ENABLED = "debug_multi_chat_enabled"

    /** Enabled only when build is debug AND the debug prefs flag is set (default false). */
    fun isEnabled(isDebugBuild: Boolean, prefsFlag: Boolean): Boolean =
        MULTI_CHAT_DEBUG_ALLOWED && isDebugBuild && prefsFlag
}

/** Each thread's commitment/session settings live under prefix `chat_<id>_`. */
object ChatNamespace {
    fun prefix(threadId: String): String = "chat_${threadId}_"
    fun key(threadId: String, key: String): String = prefix(threadId) + key
}

interface ChatThreadStore {
    fun listThreads(): List<ChatThread>
    fun createThread(title: String): ChatThread
    fun getActiveThreadId(): String?
    fun setActiveThreadId(id: String?)
    fun deleteThread(id: String)
    /** Namespaced per-thread values (session key, commitment settings, ...). */
    fun putThreadValue(threadId: String, key: String, value: String)
    fun getThreadValue(threadId: String, key: String): String?
}

class InMemoryChatThreadStore(
    private val clock: () -> Long = System::currentTimeMillis,
    private val idGen: () -> String = { UUID.randomUUID().toString() },
) : ChatThreadStore {
    private val threads = LinkedHashMap<String, ChatThread>()
    private val values = HashMap<String, String>()
    private var active: String? = null

    override fun listThreads() = threads.values.toList()
    override fun createThread(title: String): ChatThread {
        val now = clock()
        val t = ChatThread(idGen(), title, now, now)
        threads[t.id] = t
        return t
    }
    override fun getActiveThreadId() = active
    override fun setActiveThreadId(id: String?) {
        require(id == null || threads.containsKey(id)) { "Unknown thread $id" }
        active = id
    }
    override fun deleteThread(id: String) {
        threads.remove(id)
        val p = ChatNamespace.prefix(id)
        values.keys.removeAll { it.startsWith(p) }
        if (active == id) active = null
    }
    override fun putThreadValue(threadId: String, key: String, value: String) {
        values[ChatNamespace.key(threadId, key)] = value
    }
    override fun getThreadValue(threadId: String, key: String) = values[ChatNamespace.key(threadId, key)]
}
