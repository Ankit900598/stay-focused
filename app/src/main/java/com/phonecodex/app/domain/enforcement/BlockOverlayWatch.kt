package com.phonecodex.app.domain.enforcement

enum class OverlayWatchAction {
    RETAIN,
    AWAY_PENDING,
    CLEAR_LEFT_TARGET,
    REEVALUATE_PAGE_CHANGED
}

data class OverlayWatchVerdict(
    val action: OverlayWatchAction,
    val reason: String,
    val eventPackage: String,
    val rootPackage: String?,
    val effectivePackage: String,
    val targetPackage: String,
    val elapsedMs: Long,
    val awayMs: Long = 0L,
    val blockedPage: String? = null,
    val currentPage: String? = null
) {
    fun logLine(source: String): String =
        "OVERLAY_LIFECYCLE action=${action.name.lowercase()} reason=$reason source=$source " +
            "event=$eventPackage root=${rootPackage ?: "none"} effective=$effectivePackage " +
            "target=$targetPackage elapsedMs=$elapsedMs awayMs=$awayMs " +
            "blockedPageKind=${OverlayLifecycleGate.pageLogKind(blockedPage)} " +
            "currentPageKind=${OverlayLifecycleGate.pageLogKind(currentPage)}"
}

/**
 * Retain/clear law for one BLOCK overlay, fed by Accessibility events and a watchdog tick.
 *
 * The root window package is the truth for "what is under the overlay". Event packages
 * lie: after Home the phone reported event=chrome root=miui.home, and a covered Chrome
 * stops emitting events, so only systemui ticks arrive.
 */
class BlockOverlayWatch(
    val targetPackage: String,
    private val blockedPage: String?,
    private val shownAtMillis: Long,
    private val awayDebounceMs: Long = OverlayLifecycleGate.AWAY_DEBOUNCE_MS,
    private val pageChangeHoldMs: Long = OverlayLifecycleGate.DECISION_HOLD_MS,
    private val blockedScreenSignature: String? = null,
    private val screenProbeIntervalMs: Long = SCREEN_PROBE_INTERVAL_MS
) {
    private var awayCandidate: String? = null
    private var awaySinceMillis = 0L
    private var lastScreenProbeAtMillis = Long.MIN_VALUE

    fun shouldProbeTargetScreen(nowMillis: Long): Boolean {
        if (nowMillis - shownAtMillis < pageChangeHoldMs) return false
        return lastScreenProbeAtMillis == Long.MIN_VALUE ||
            nowMillis - lastScreenProbeAtMillis >= screenProbeIntervalMs
    }

    fun recordTargetScreenProbe(nowMillis: Long) {
        lastScreenProbeAtMillis = nowMillis
    }

    fun observe(
        eventPackage: String,
        rootPackage: String?,
        currentPage: String?,
        nowMillis: Long,
        currentScreenSignature: String? = null
    ): OverlayWatchVerdict {
        val root = rootPackage?.takeIf { it.isNotBlank() }
        val effective = root ?: eventPackage
        val elapsed = (nowMillis - shownAtMillis).coerceAtLeast(0L)

        fun verdict(action: OverlayWatchAction, reason: String, awayMs: Long = 0L) =
            OverlayWatchVerdict(
                action = action,
                reason = reason,
                eventPackage = eventPackage,
                rootPackage = root,
                effectivePackage = effective,
                targetPackage = targetPackage,
                elapsedMs = elapsed,
                awayMs = awayMs,
                blockedPage = blockedPage,
                currentPage = currentPage
            )

        if (OverlayLifecycleGate.isOwnPackage(effective) || OverlayLifecycleGate.isSystemUi(effective)) {
            resetAway()
            return verdict(OverlayWatchAction.RETAIN, "noise_root")
        }

        if (effective == targetPackage) {
            resetAway()
            if (currentPage != null && blockedPage != null && currentPage == blockedPage) {
                return verdict(OverlayWatchAction.RETAIN, "target_same_page")
            }
            if (elapsed < pageChangeHoldMs) return verdict(OverlayWatchAction.RETAIN, "page_change_hold")
            if (currentPage != null && blockedPage != null) {
                return verdict(OverlayWatchAction.REEVALUATE_PAGE_CHANGED, "target_page_changed")
            }
            if (blockedPage != null &&
                blockedScreenSignature != null &&
                currentScreenSignature != null &&
                currentScreenSignature != blockedScreenSignature
            ) {
                return verdict(OverlayWatchAction.REEVALUATE_PAGE_CHANGED, "target_screen_changed")
            }
            if (currentPage != null && blockedPage == null) {
                return verdict(OverlayWatchAction.REEVALUATE_PAGE_CHANGED, "blocked_page_became_known")
            }
            if (currentPage == null) return verdict(OverlayWatchAction.RETAIN, "target_page_unknown")
            if (blockedPage == null) return verdict(OverlayWatchAction.RETAIN, "blocked_page_unknown")
            return verdict(OverlayWatchAction.REEVALUATE_PAGE_CHANGED, "target_page_changed")
        }

        val leaveReason = if (OverlayLifecycleGate.isLauncher(effective)) {
            "left_to_launcher"
        } else {
            "left_to_other_app"
        }
        if (awayCandidate != effective) {
            awayCandidate = effective
            awaySinceMillis = nowMillis
            return verdict(OverlayWatchAction.AWAY_PENDING, leaveReason)
        }
        val awayMs = nowMillis - awaySinceMillis
        if (awayMs >= awayDebounceMs) {
            return verdict(OverlayWatchAction.CLEAR_LEFT_TARGET, leaveReason, awayMs)
        }
        return verdict(OverlayWatchAction.AWAY_PENDING, leaveReason, awayMs)
    }

    private fun resetAway() {
        awayCandidate = null
        awaySinceMillis = 0L
    }

    private companion object {
        const val SCREEN_PROBE_INTERVAL_MS = 1_500L
    }
}
