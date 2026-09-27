package com.phonecodex.app.ui.home

import com.phonecodex.app.domain.model.AppRule
import com.phonecodex.app.domain.model.AppRuleBehavior
import com.phonecodex.app.domain.model.ContentRule
import com.phonecodex.app.domain.model.ContentRuleAction
import com.phonecodex.app.domain.model.FocusPromise
import com.phonecodex.app.domain.model.StrictnessLevel
import com.phonecodex.app.domain.promise.FocusPromiseParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Confirmation card is built from the same effective policy that Start saves. */
class ScopedAppBlockPreviewTest {

    private val yt = "com.google.android.youtube"

    private fun draft(
        appRules: List<AppRule>,
        contentRules: List<ContentRule> = emptyList(),
        blocked: List<String> = emptyList(),
        allowed: List<String> = emptyList()
    ) = FocusPromise(
        rawText = "block youtube shorts for 1 hour",
        sessionDurationMinutes = 60,
        allowedKeywords = emptyList(),
        blockedKeywords = emptyList(),
        strictness = StrictnessLevel.STRICT,
        suggestedAppRules = appRules,
        contentRules = contentRules,
        blockedSummaries = blocked,
        allowedSummaries = allowed,
        canStartCommitment = true
    )

    private val ytShorts = ContentRule(
        appLabel = "YouTube",
        packageName = yt,
        surface = "shorts",
        contentType = "short_form_video",
        operator = null,
        value = null,
        unit = null,
        action = ContentRuleAction.BLOCK
    )

    @Test
    fun oneHourStrongerConfirmation_isNotLabelledPermanent() {
        val oneHour = draft(emptyList()).copy(
            rawText = "no reels for 1 hour",
            requiresStrongerConfirmation = true,
            isPermanentCommitment = false,
            canStartCommitment = false
        )
        val ui = buildPromiseUnderstanding(oneHour, source = UnderstandingSource.PROMISE_COMPILER)
        assertNotEquals("Permanent commitment", ui.statusLabel)
        assertFalse(ui.userFacingTextBlob().contains("Permanent commitment"))
        assertFalse("checkbox still required", ui.canStart)
    }

    @Test
    fun shortsOnlyPreview_namesShorts_notAllOfYouTube() {
        val ui = buildPromiseUnderstanding(
            draft(
                appRules = listOf(AppRule(yt, "YouTube", AppRuleBehavior.AI_DECIDE)),
                contentRules = listOf(ytShorts),
                blocked = listOf("YouTube")
            ),
            source = UnderstandingSource.PROMISE_COMPILER
        )
        assertTrue(ui.blockedLabel, ui.blockedLabel.contains("YouTube Shorts"))
        assertFalse(ui.blockedLabel, ui.blockedLabel.split(", ").any { it.equals("YouTube", true) })
        assertTrue(ui.canStart)
    }

    @Test
    fun wholeAppBlockPreview_doesNotClaimLongVideosAllowed_andConflictCannotStart() {
        val ui = buildPromiseUnderstanding(
            draft(
                appRules = listOf(AppRule(yt, "YouTube", AppRuleBehavior.BLOCK)),
                contentRules = listOf(
                    ContentRule("YouTube", yt, "video", "long_form_video", null, null, null, ContentRuleAction.ALLOW)
                ),
                blocked = listOf("YouTube Shorts"),
                allowed = listOf("Long YouTube videos")
            ),
            source = UnderstandingSource.PROMISE_COMPILER
        )
        assertFalse(ui.allowedLabel, ui.allowedLabel.contains("Long YouTube videos"))
        assertFalse("contradiction keeps Start closed", ui.canStart)
    }

    @Test
    fun offlineShortsPreview_keepsStartClosedUntilScopeCanBeVerified() {
        val ui = buildPromiseUnderstanding(FocusPromiseParser().parse("block youtube shorts for 1 hour"))
        assertFalse(ui.canStart)
        assertFalse(ui.blockedLabel, ui.blockedLabel.split(", ").any { it.equals("YouTube", true) })
        assertEquals("Reconnect to verify this promise", ui.statusLabel)
        assertTrue(ui.statusDetail, ui.statusDetail.contains("Start is disabled while offline"))
    }

    @Test
    fun offlineUnscopedPreview_remainsStartable() {
        val ui = buildPromiseUnderstanding(draft(emptyList()), source = UnderstandingSource.LOCAL_PREVIEW)
        assertTrue(ui.canStart)
    }
}
