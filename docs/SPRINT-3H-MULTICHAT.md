# Sprint 3H — Multi-chat debug scaffold

Tree: /workspace/phonecodex-p0auth/tree (not merged to main; no gradle/UI changes)

## Files (new)
- app/src/main/java/com/phonecodex/app/domain/multichat/ChatThread.kt — ChatThread, MultiChatGate, ChatNamespace, ChatThreadStore interface, InMemoryChatThreadStore
- app/src/test/java/com/phonecodex/app/domain/multichat/ChatThreadStoreTest.kt

## Toggle
`MultiChatGate.isEnabled(BuildConfig.DEBUG, prefs.getBoolean("debug_multi_chat_enabled", false))`
→ OFF by default everywhere; release always false (requires isDebug). Not wired into any UI yet.
To enable later: set prefs `debug_multi_chat_enabled=true` on a debug build and call the gate.

## Isolation
Per-thread settings use prefix `chat_<id>_` (ChatNamespace). deleteThread removes only that prefix and clears active if it was the deleted thread.

## UI
Skipped (no HomeScreen changes). Next: Advanced Controls row "Multi-chat (debug)" showing listThreads().size when gate true; SharedPreferences-backed store impl.

## Test (laptop; no Java on box, not run)
./gradlew :app:testDebugUnitTest --tests "com.phonecodex.app.domain.multichat.*"
