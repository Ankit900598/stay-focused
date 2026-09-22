package com.phonecodex.app.domain

/**
 * User-visible product name. Package IDs stay `com.phonecodex.app`.
 */
object ProductBrand {
    const val DISPLAY_NAME = "Strlix"
    const val OPEN_APP = "Open Strlix"
    const val LOCK_COMPANION =
        "You asked not to get an easy way out. Lock is active — wait it out or open Strlix."

    fun overlayContentDescription(badge: String): String =
        "$DISPLAY_NAME $badge overlay"
}
