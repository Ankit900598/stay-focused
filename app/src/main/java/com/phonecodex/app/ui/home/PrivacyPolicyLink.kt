package com.phonecodex.app.ui.home

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.phonecodex.app.BuildConfig
import com.phonecodex.app.R

/**
 * Opens the public privacy policy URL configured in strings / BuildConfig.
 * Does not embed legal body text — the page (or placeholder host) owns that.
 */
object PrivacyPolicyLink {

    /** Must stay identical to [R.string.privacy_policy_url] / BuildConfig.PRIVACY_POLICY_URL. */
    const val PLACEHOLDER_URL = "https://strlix.tech/privacy"

    fun configuredUrl(): String = BuildConfig.PRIVACY_POLICY_URL.ifBlank { PLACEHOLDER_URL }

    /**
     * Pure description of the browser intent we fire. Unit tests assert this;
     * [viewIntent] turns it into an Android Intent.
     */
    data class BrowserTarget(val action: String, val url: String) {
        init {
            require(url.startsWith("https://")) { "privacy policy URL must be https" }
            require(action == Intent.ACTION_VIEW) { "privacy policy must use ACTION_VIEW" }
        }
    }

    fun browserTarget(url: String = configuredUrl()): BrowserTarget =
        BrowserTarget(action = Intent.ACTION_VIEW, url = url)

    fun viewIntent(url: String = configuredUrl()): Intent {
        val target = browserTarget(url)
        return Intent(target.action, Uri.parse(target.url)).apply {
            addCategory(Intent.CATEGORY_BROWSABLE)
        }
    }

    fun launch(context: Context) {
        val fromResources = context.getString(R.string.privacy_policy_url)
        context.startActivity(viewIntent(fromResources))
    }
}
