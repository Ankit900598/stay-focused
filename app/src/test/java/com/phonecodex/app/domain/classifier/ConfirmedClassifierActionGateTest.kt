package com.phonecodex.app.domain.classifier

import com.phonecodex.app.domain.enforcement.SurfaceDetectionResult
import com.phonecodex.app.domain.model.AppRule
import com.phonecodex.app.domain.model.AppRuleBehavior
import com.phonecodex.app.domain.model.ConfirmedSessionPolicy
import com.phonecodex.app.domain.model.ContentRule
import com.phonecodex.app.domain.model.ContentRuleAction
import com.phonecodex.app.domain.model.DecisionType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class ConfirmedClassifierActionGateTest {

    @Test
    fun networkBlockWithoutConfirmedPolicy_becomesWarn() {
        val result = evaluate(classification("gaming"), policy = null)

        assertEquals(ClassifierBlockAuthority.NO_MATCHING_CONFIRMED_RULE, result.authority)
        assertEquals(DecisionType.WARN, result.classification.decision)
        assertEquals(ConfirmedClassifierActionGate.UNAUTHORIZED_BLOCK_REASON, result.classification.reason)
    }

    @Test
    fun matchingConfirmedAiDecideAppRule_authorizesBlockOnlyForThatPackage() {
        val policy = ConfirmedSessionPolicy(
            appRules = listOf(AppRule(YOUTUBE, "YouTube", AppRuleBehavior.AI_DECIDE)),
            contentRules = emptyList()
        )

        val allowed = evaluate(classification("strict_monk_mode"), YOUTUBE, policy)
        val wrongPackage = evaluate(classification("strict_monk_mode"), CHROME, policy)

        assertEquals(ClassifierBlockAuthority.CONFIRMED_APP_RULE, allowed.authority)
        assertEquals(DecisionType.BLOCK, allowed.classification.decision)
        assertEquals(ClassifierBlockAuthority.NO_MATCHING_CONFIRMED_RULE, wrongPackage.authority)
        assertEquals(DecisionType.WARN, wrongPackage.classification.decision)
    }

    @Test
    fun userAllowAndWarnAppRules_neverAuthorizeClassifierBlock() {
        for (behavior in listOf(AppRuleBehavior.ALLOW, AppRuleBehavior.WARN, AppRuleBehavior.BLOCK)) {
            val policy = ConfirmedSessionPolicy(
                appRules = listOf(AppRule(YOUTUBE, "YouTube", behavior)),
                contentRules = emptyList()
            )

            val result = evaluate(classification("gaming"), YOUTUBE, policy)

            assertEquals("$behavior", ClassifierBlockAuthority.NO_MATCHING_CONFIRMED_RULE, result.authority)
            assertEquals("$behavior", DecisionType.WARN, result.classification.decision)
        }
    }

    @Test
    fun explicitAdultBlockRule_matchesOnlyAdultClassifierCategory() {
        val rule = contentRule(type = "adult_sexual", action = ContentRuleAction.BLOCK)
        val policy = ConfirmedSessionPolicy(emptyList(), listOf(rule))

        val match = evaluate(classification("adult_content"), CHROME, policy)
        val mismatch = evaluate(classification("shopping"), CHROME, policy)

        assertEquals(ClassifierBlockAuthority.CONFIRMED_CONTENT_RULE, match.authority)
        assertEquals(DecisionType.BLOCK, match.classification.decision)
        assertEquals(ClassifierBlockAuthority.NO_MATCHING_CONFIRMED_RULE, mismatch.authority)
        assertEquals(DecisionType.WARN, mismatch.classification.decision)
    }

    @Test
    fun contentRuleActionAllowOrWarn_doesNotAuthorizeHardBlock() {
        for (action in listOf(ContentRuleAction.ALLOW, ContentRuleAction.WARN)) {
            val policy = ConfirmedSessionPolicy(
                emptyList(),
                listOf(contentRule(type = "adult_sexual", action = action))
            )

            val result = evaluate(classification("adult_content"), CHROME, policy)

            assertEquals("$action", ClassifierBlockAuthority.NO_MATCHING_CONFIRMED_RULE, result.authority)
            assertEquals("$action", DecisionType.WARN, result.classification.decision)
        }
    }

    @Test
    fun explicitContentAllowOverridesBroaderAiDecideAppRule() {
        val policy = ConfirmedSessionPolicy(
            appRules = listOf(AppRule(YOUTUBE, "YouTube", AppRuleBehavior.AI_DECIDE)),
            contentRules = listOf(contentRule(type = "adult_sexual", action = ContentRuleAction.ALLOW))
        )

        val result = evaluate(classification("adult_content"), YOUTUBE, policy)

        assertEquals(ClassifierBlockAuthority.NO_MATCHING_CONFIRMED_RULE, result.authority)
        assertEquals(DecisionType.WARN, result.classification.decision)
    }

    @Test
    fun shortFormBlock_requiresShortFormSurfaceAndDoesNotTreatQuotaAsStandingBan() {
        val ban = ConfirmedSessionPolicy(
            emptyList(),
            listOf(contentRule(type = "short_form_video", surface = "shorts"))
        )
        val quota = ConfirmedSessionPolicy(
            emptyList(),
            listOf(contentRule(type = "short_form_video", surface = "shorts", unit = "count"))
        )

        val short = evaluate(
            classification("short_form_disallowed"), YOUTUBE, ban,
            SurfaceDetectionResult.SHORT_FORM_PLAYER
        )
        val long = evaluate(
            classification("short_form_disallowed"), YOUTUBE, ban,
            SurfaceDetectionResult.LONG_FORM_PLAYER
        )
        val quotaMatch = evaluate(
            classification("short_form_disallowed"), YOUTUBE, quota,
            SurfaceDetectionResult.SHORT_FORM_PLAYER
        )

        assertEquals(ClassifierBlockAuthority.CONFIRMED_CONTENT_RULE, short.authority)
        assertEquals(DecisionType.BLOCK, short.classification.decision)
        assertEquals(ClassifierBlockAuthority.NO_MATCHING_CONFIRMED_RULE, long.authority)
        assertEquals(DecisionType.WARN, long.classification.decision)
        assertEquals(ClassifierBlockAuthority.NO_MATCHING_CONFIRMED_RULE, quotaMatch.authority)
    }

    @Test
    fun narrowContentRule_preventsBroadAppAiDecideFromBlockingUnrelatedCategory() {
        val policy = ConfirmedSessionPolicy(
            appRules = listOf(AppRule(YOUTUBE, "YouTube", AppRuleBehavior.AI_DECIDE)),
            contentRules = listOf(contentRule(type = "short_form_video", surface = "shorts"))
        )

        val intendedShort = evaluate(
            classification("short_form_disallowed"),
            YOUTUBE,
            policy,
            SurfaceDetectionResult.SHORT_FORM_PLAYER
        )
        val unrelatedLongVideo = evaluate(
            classification("strict_monk_mode"),
            YOUTUBE,
            policy,
            SurfaceDetectionResult.LONG_FORM_PLAYER
        )

        assertEquals(ClassifierBlockAuthority.CONFIRMED_CONTENT_RULE, intendedShort.authority)
        assertEquals(DecisionType.BLOCK, intendedShort.classification.decision)
        assertEquals(ClassifierBlockAuthority.NO_MATCHING_CONFIRMED_RULE, unrelatedLongVideo.authority)
        assertEquals(DecisionType.WARN, unrelatedLongVideo.classification.decision)
    }

    @Test
    fun contentPolicyForDifferentApp_doesNotNarrowConfirmedAiDecideAppRule() {
        val policy = ConfirmedSessionPolicy(
            appRules = listOf(AppRule(YOUTUBE, "YouTube", AppRuleBehavior.AI_DECIDE)),
            contentRules = listOf(contentRule(type = "short_form_video", packageName = CHROME, surface = "shorts"))
        )

        val result = evaluate(classification("gaming"), YOUTUBE, policy)

        assertEquals(ClassifierBlockAuthority.CONFIRMED_APP_RULE, result.authority)
        assertEquals(DecisionType.BLOCK, result.classification.decision)
    }

    @Test
    fun labelOnlyContentRule_authorizesOnlyTheConfirmedMatchingApp() {
        val policy = ConfirmedSessionPolicy(
            appRules = listOf(AppRule(YOUTUBE, "YouTube", AppRuleBehavior.AI_DECIDE)),
            contentRules = listOf(
                contentRule(
                    type = "short_form_video",
                    appLabel = "YouTube",
                    surface = "shorts"
                )
            )
        )

        val youtubeShort = evaluate(
            classification("short_form_disallowed"),
            YOUTUBE,
            policy,
            SurfaceDetectionResult.SHORT_FORM_PLAYER
        )
        val chromeShort = evaluate(
            classification("short_form_disallowed"),
            CHROME,
            policy,
            SurfaceDetectionResult.SHORT_FORM_PLAYER
        )

        assertEquals(ClassifierBlockAuthority.CONFIRMED_CONTENT_RULE, youtubeShort.authority)
        assertEquals(DecisionType.BLOCK, youtubeShort.classification.decision)
        assertEquals(ClassifierBlockAuthority.NO_MATCHING_CONFIRMED_RULE, chromeShort.authority)
        assertEquals(DecisionType.WARN, chromeShort.classification.decision)
    }

    @Test
    fun socialFeedRule_requiresMatchingDetectedFeed() {
        val policy = ConfirmedSessionPolicy(
            emptyList(),
            listOf(contentRule(type = "social_feed", packageName = INSTAGRAM, surface = "feed"))
        )

        val feed = evaluate(
            classification("social_feed"), INSTAGRAM, policy,
            SurfaceDetectionResult.SOCIAL_FEED
        )
        val messages = evaluate(
            classification("social_feed"), INSTAGRAM, policy,
            SurfaceDetectionResult.SOCIAL_DM
        )

        assertEquals(ClassifierBlockAuthority.CONFIRMED_CONTENT_RULE, feed.authority)
        assertEquals(ClassifierBlockAuthority.NO_MATCHING_CONFIRMED_RULE, messages.authority)
    }

    @Test
    fun classifierBlock_requiresConfirmedRuleSurfaceToMatchDetectedSurface() {
        val mismatchedShorts = ConfirmedSessionPolicy(
            appRules = listOf(AppRule(YOUTUBE, "YouTube", AppRuleBehavior.AI_DECIDE)),
            contentRules = listOf(
                contentRule(type = "short_form_video", packageName = YOUTUBE, surface = "feed")
            )
        )
        val mismatchedFeed = ConfirmedSessionPolicy(
            emptyList(),
            listOf(contentRule(type = "social_feed", packageName = INSTAGRAM, surface = "messages"))
        )
        val mismatchedAdult = ConfirmedSessionPolicy(
            emptyList(),
            listOf(contentRule(type = "adult_sexual", packageName = YOUTUBE, surface = "messages"))
        )
        val matchingAdultMessages = ConfirmedSessionPolicy(
            emptyList(),
            listOf(contentRule(type = "adult_sexual", packageName = YOUTUBE, surface = "messages"))
        )
        val unsupportedSurface = ConfirmedSessionPolicy(
            emptyList(),
            listOf(contentRule(type = "adult_sexual", packageName = YOUTUBE, surface = "playlist"))
        )

        val shorts = evaluate(
            classification("short_form_disallowed"), YOUTUBE, mismatchedShorts,
            SurfaceDetectionResult.SHORT_FORM_PLAYER
        )
        val feed = evaluate(
            classification("social_feed"), INSTAGRAM, mismatchedFeed,
            SurfaceDetectionResult.SOCIAL_FEED
        )
        val adultVideo = evaluate(
            classification("adult_content"), YOUTUBE, mismatchedAdult,
            SurfaceDetectionResult.LONG_FORM_PLAYER
        )
        val adultMessages = evaluate(
            classification("adult_content"), YOUTUBE, matchingAdultMessages,
            SurfaceDetectionResult.MESSAGING_THREAD
        )
        val unsupported = evaluate(
            classification("adult_content"), YOUTUBE, unsupportedSurface,
            SurfaceDetectionResult.CHANNEL
        )

        for (result in listOf(shorts, feed, adultVideo, unsupported)) {
            assertEquals(ClassifierBlockAuthority.NO_MATCHING_CONFIRMED_RULE, result.authority)
            assertEquals(DecisionType.WARN, result.classification.decision)
        }
        assertEquals(ClassifierBlockAuthority.CONFIRMED_CONTENT_RULE, adultMessages.authority)
        assertEquals(DecisionType.BLOCK, adultMessages.classification.decision)
    }

    @Test
    fun visionAdultSignal_requiresExplicitAdultRuleOrAiDecideApp() {
        val explicitRule = ConfirmedSessionPolicy(
            emptyList(), listOf(contentRule(type = "adult_sexual"))
        )
        val noRule = ConfirmedSessionPolicy(emptyList(), emptyList())

        val permitted = evaluate(classification("adult_signal", usedImage = true), CHROME, explicitRule)
        val refused = evaluate(classification("adult_signal", usedImage = true), CHROME, noRule)

        assertEquals(ClassifierBlockAuthority.CONFIRMED_CONTENT_RULE, permitted.authority)
        assertEquals(DecisionType.WARN, refused.classification.decision)
    }

    @Test
    fun modelLock_isAlwaysDowngraded_evenWithConfirmedAiDecideRule() {
        val policy = ConfirmedSessionPolicy(
            listOf(AppRule(YOUTUBE, "YouTube", AppRuleBehavior.AI_DECIDE)),
            emptyList()
        )

        val result = evaluate(classification("gaming", decision = DecisionType.LOCK), YOUTUBE, policy)

        assertEquals(ClassifierBlockAuthority.MODEL_CANNOT_LOCK, result.authority)
        assertEquals(DecisionType.WARN, result.classification.decision)
        assertEquals(ConfirmedClassifierActionGate.MODEL_LOCK_REASON, result.classification.reason)
    }

    @Test
    fun incompleteClassification_cannotUseConfirmedRule() {
        val policy = ConfirmedSessionPolicy(
            listOf(AppRule(YOUTUBE, "YouTube", AppRuleBehavior.AI_DECIDE)),
            emptyList()
        )

        val result = evaluate(classification("gaming", incomplete = true), YOUTUBE, policy)

        assertEquals(ClassifierBlockAuthority.NO_MATCHING_CONFIRMED_RULE, result.authority)
        assertEquals(DecisionType.WARN, result.classification.decision)
    }

    @Test
    fun nonBlockDecision_isReturnedUnchanged() {
        val original = classification("adult_content", decision = DecisionType.WARN)
        val result = evaluate(original, packageName = CHROME, policy = null)

        assertEquals(ClassifierBlockAuthority.NOT_A_BLOCK, result.authority)
        assertSame(original, result.classification)
    }

    private fun evaluate(
        classification: ContentClassification,
        packageName: String = CHROME,
        policy: ConfirmedSessionPolicy?,
        surface: SurfaceDetectionResult = SurfaceDetectionResult.ACTIVE_VIDEO_PLAYER
    ) = ConfirmedClassifierActionGate.evaluate(classification, packageName, surface, policy)

    private fun classification(
        category: String,
        decision: DecisionType = DecisionType.BLOCK,
        usedImage: Boolean = false,
        incomplete: Boolean = false
    ) = ContentClassification(
        decision = decision,
        confidence = 0.95,
        reason = "model reason",
        source = AiConfidenceGate.NETWORK_AI_SOURCE,
        reasonCategory = category,
        usedImage = usedImage,
        verificationIncomplete = incomplete
    )

    private fun contentRule(
        type: String,
        action: ContentRuleAction = ContentRuleAction.BLOCK,
        packageName: String? = null,
        surface: String = "any",
        unit: String? = null,
        appLabel: String? = null
    ) = ContentRule(
        appLabel = appLabel,
        packageName = packageName,
        surface = surface,
        contentType = type,
        operator = null,
        value = null,
        unit = unit,
        action = action
    )

    private companion object {
        const val YOUTUBE = "com.google.android.youtube"
        const val CHROME = "com.android.chrome"
        const val INSTAGRAM = "com.instagram.android"
    }
}
