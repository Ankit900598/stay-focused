package com.phonecodex.app.domain.scope

import com.phonecodex.app.domain.enforcement.SurfaceDetectionResult

/**
 * PURE mapping from [SurfaceDetectionResult] (20 values) to [ScopeSurfaceClass] (12 values).
 *
 * This file is the entire scope-side change to SurfaceDetector's public API. It does NOT
 * change any detection heuristic, regex, or threshold. Adding a new [SurfaceDetectionResult]
 * value must add a `when` branch here (compile-time exhaustive).
 *
 * See docs/scope-semantics-v1.md §4.2.
 */
val SurfaceDetectionResult.scopeClass: ScopeSurfaceClass
    get() = when (this) {
        SurfaceDetectionResult.SHORT_FORM_PLAYER -> ScopeSurfaceClass.PLAYER_SHORT
        SurfaceDetectionResult.LONG_FORM_PLAYER -> ScopeSurfaceClass.PLAYER_LONG
        SurfaceDetectionResult.ACTIVE_VIDEO_PLAYER -> ScopeSurfaceClass.PLAYER_ANY
        SurfaceDetectionResult.SOCIAL_FEED -> ScopeSurfaceClass.FEED
        SurfaceDetectionResult.SUBSCRIPTIONS -> ScopeSurfaceClass.FEED
        SurfaceDetectionResult.DISCOVER_NEWS -> ScopeSurfaceClass.FEED
        SurfaceDetectionResult.SOCIAL_DM -> ScopeSurfaceClass.DM_LIST
        SurfaceDetectionResult.MESSAGING_THREAD -> ScopeSurfaceClass.DM_THREAD
        SurfaceDetectionResult.SEARCH -> ScopeSurfaceClass.SEARCH
        SurfaceDetectionResult.HOME -> ScopeSurfaceClass.BROWSE
        SurfaceDetectionResult.CHANNEL -> ScopeSurfaceClass.BROWSE
        SurfaceDetectionResult.COMMENTS -> ScopeSurfaceClass.BROWSE
        SurfaceDetectionResult.INSTALL_FLOW -> ScopeSurfaceClass.INSTALL
        SurfaceDetectionResult.PAYMENT_FLOW -> ScopeSurfaceClass.PAYMENT
        SurfaceDetectionResult.PLAY_STORE -> ScopeSurfaceClass.SYSTEM
        SurfaceDetectionResult.SYSTEM_SHADE -> ScopeSurfaceClass.SYSTEM
        SurfaceDetectionResult.LAUNCHER -> ScopeSurfaceClass.SYSTEM
        SurfaceDetectionResult.BROWSER_TAB_SWITCHER -> ScopeSurfaceClass.SYSTEM
        SurfaceDetectionResult.UNKNOWN -> ScopeSurfaceClass.ANY
    }
