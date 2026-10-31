package com.phonecodex.app.domain.multichat

import org.junit.Assert.*
import org.junit.Test

class ChatThreadStoreTest {
    private val store = InMemoryChatThreadStore()

    @Test fun createTwoThreads_distinctIds() {
        val a = store.createThread("A"); val b = store.createThread("B")
        assertNotEquals(a.id, b.id)
        assertEquals(2, store.listThreads().size)
    }

    @Test fun setActiveAThenB_activeIsB() {
        val a = store.createThread("A"); val b = store.createThread("B")
        store.setActiveThreadId(a.id); store.setActiveThreadId(b.id)
        assertEquals(b.id, store.getActiveThreadId())
    }

    @Test fun namespacedKeys_differBetweenThreads() {
        val a = store.createThread("A"); val b = store.createThread("B")
        assertNotEquals(ChatNamespace.key(a.id, "session_key"), ChatNamespace.key(b.id, "session_key"))
        store.putThreadValue(a.id, "commitment", "60m")
        assertNull(store.getThreadValue(b.id, "commitment"))
    }

    @Test fun deleteA_keepsBNamespace() {
        val a = store.createThread("A"); val b = store.createThread("B")
        store.putThreadValue(a.id, "session_key", "sa")
        store.putThreadValue(b.id, "session_key", "sb")
        store.setActiveThreadId(a.id)
        store.deleteThread(a.id)
        assertNull(store.getThreadValue(a.id, "session_key"))
        assertEquals("sb", store.getThreadValue(b.id, "session_key"))
        assertNull(store.getActiveThreadId())
        assertEquals(listOf(b.id), store.listThreads().map { it.id })
    }

    @Test fun gate_offByDefault() {
        assertFalse(MultiChatGate.isEnabled(isDebugBuild = true, prefsFlag = false))
        assertFalse(MultiChatGate.isEnabled(isDebugBuild = false, prefsFlag = true))
        assertTrue(MultiChatGate.isEnabled(isDebugBuild = true, prefsFlag = true))
    }
}
