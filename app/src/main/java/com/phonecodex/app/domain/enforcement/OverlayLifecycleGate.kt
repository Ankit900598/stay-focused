package com.phonecodex.app.domain.enforcement

/**
 * Pure overlay retain/clear rules — no WindowManager.
 * Prevents own-package flicker and stuck overlays after leaving the target.
 */
object OverlayLifecycleGate {

    const val AWAY_DEBOUNCE_MS = 1_200L
    /** Hold the last overlay decision briefly so content thrash cannot vibrate the UI. */
    const val DECISION_HOLD_MS = 1_500L
    const val OWN_PACKAGE = "com.phonecodex.app"

    private val SYSTEM_UI_PACKAGES = setOf(
        "com.android.systemui",
        "miui.systemui.plugin"
    )

    private val LAUNCHER_PACKAGES = setOf(
        "com.miui.home",
        "com.google.android.apps.nexuslauncher",
        "com.android.launcher3",
        "com.android.launcher",
        "com.sec.android.app.launcher"
    )

    fun isOwnPackage(packageName: String): Boolean = packageName == OWN_PACKAGE

    fun isSystemUi(packageName: String): Boolean = packageName in SYSTEM_UI_PACKAGES

    fun isLauncher(packageName: String): Boolean = packageName in LAUNCHER_PACKAGES

    /**
     * Retain overlay for noise events that are not a real leave of the violation target.
     * Own-package and system UI retain. Launcher / other apps do NOT retain — they start
     * away-debounce so the overlay can clear after leaving the blocked player.
     */
    fun shouldRetainForNoise(eventPackage: String, targetPackage: String): Boolean {
        if (isOwnPackage(eventPackage)) return true
        if (isSystemUi(eventPackage)) return true
        if (eventPackage == targetPackage) return true
        return false
    }

    /**
     * PASS/ALLOW must clear a stale WARN/BLOCK overlay even when the session is
     * LOCKED. Lock is only for the forbidden surface, not the rest of the phone.
     */
    @Suppress("UNUSED_PARAMETER")
    fun shouldClearOverlayOnPassiveAllow(sessionLocked: Boolean): Boolean = true

    /**
     * @return true when away-from-target has lasted long enough to clear the overlay.
     */
    fun shouldClearAfterAway(
        candidatePackage: String,
        trackedCandidate: String?,
        awaySinceMillis: Long,
        nowMillis: Long,
        debounceMs: Long = AWAY_DEBOUNCE_MS
    ): Boolean {
        if (trackedCandidate != candidatePackage) return false
        if (awaySinceMillis <= 0L) return false
        return nowMillis - awaySinceMillis >= debounceMs
    }

    /**
     * Warning must clear when the user leaves blocked/warned content (including
     * same-package home/search ALLOW). Keeping it caused stuck WARN overlays.
     */
    fun shouldClearWarningOnAllow(): Boolean = true

    /**
     * Avoid flipping overlay show/hide on every tiny CONTENT_CHANGED within the hold window
     * when package + decision are unchanged.
     * Callers must NOT hold ALLOW/PASS — those must always clear overlays.
     */
    fun shouldHoldSameDecision(
        packageName: String,
        decision: String,
        lastPackage: String?,
        lastDecision: String?,
        lastAppliedAtMillis: Long,
        nowMillis: Long,
        holdMs: Long = DECISION_HOLD_MS
    ): Boolean {
        if (packageName != lastPackage) return false
        if (decision != lastDecision) return false
        if (lastAppliedAtMillis <= 0L) return false
        return nowMillis - lastAppliedAtMillis < holdMs
    }

    private val MEDIA_ID_REGEX = Regex(
        "(?:watch\\?(?:[^\\s]*&)?v=|shorts/|youtu\\.be/)([A-Za-z0-9_-]{6,})"
    )

    /** Stable id of the media on screen (e.g. `shorts:abc123`), or null when no URL is visible. */
    fun mediaIdentity(screenText: String): String? {
        val match = MEDIA_ID_REGEX.find(screenText) ?: return null
        val kind = if (match.value.startsWith("shorts/")) "shorts" else "video"
        return "$kind:${match.groupValues[1]}"
    }

    /**
     * Target-package events are retained under a BLOCK overlay, so a same-app navigation
     * to different media is not re-evaluated. This reports when that happened.
     */
    fun targetMediaChanged(blockedScreenText: String?, currentScreenText: String): Boolean {
        val before = blockedScreenText?.let(::mediaIdentity) ?: return false
        val now = mediaIdentity(currentScreenText) ?: return false
        return before != now
    }

    /**
     * What page is on screen: media id first, else the browser URL bar (`url:example.com`).
     * Null means unknown (fullscreen player, app without URL bar) — callers must retain.
     * Screen text alone is never parsed for hosts: page bodies mention other domains.
     */
    fun pageIdentity(urlBarText: String?, screenText: String): String? {
        val url = urlBarText?.trim()?.takeIf { it.isNotEmpty() }
        if (url != null) return mediaIdentity(url) ?: normalizeUrl(url)?.let { "url:$it" }
        return mediaIdentity(screenText)
    }

    private fun normalizeUrl(raw: String): String? {
        val cleaned = raw.lowercase()
            .substringBefore(' ')
            .removePrefix("https://")
            .removePrefix("http://")
            .removePrefix("www.")
            .substringBefore('#')
            .substringBefore('?')
            .trimEnd('/')
        return cleaned.takeIf { it.contains('.') }
    }
}
