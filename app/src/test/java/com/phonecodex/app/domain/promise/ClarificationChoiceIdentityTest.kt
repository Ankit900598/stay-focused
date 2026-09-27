package com.phonecodex.app.domain.promise

import com.phonecodex.app.domain.enforcement.EnforcementScopeLaw
import com.phonecodex.app.domain.model.FocusPromise
import com.phonecodex.app.domain.model.StudyWorldSettings
import com.phonecodex.app.ui.home.PromiseUnderstanding
import com.phonecodex.app.ui.home.UnderstandingSource
import com.phonecodex.app.ui.home.buildPromiseUnderstanding
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phone evidence 2026-09-26: tapping "All video apps and browsers" sometimes
 * enforced Chrome only. The shown scope, DTO scope, stored session scope and
 * enforcement scope must all be the option the user tapped.
 *
 * Fixtures are the backend's real output:
 * `node backend/smoke_clarification_choice_identity.js --write-fixtures`
 */
class ClarificationChoiceIdentityTest {

    private data class Stack(
        val draft: FocusPromise,
        val ui: PromiseUnderstanding,
        val settings: StudyWorldSettings
    )

    private fun stack(fixture: String, selectedId: String = "A"): Stack {
        val json = javaClass.classLoader!!
            .getResource("clarification-choice/$fixture.json")!!
            .readText()
        val response = CompiledPromiseResponseParser.parse(json)
        assertNotNull("fixture $fixture must parse", response)
        val draft = CompiledPromiseMapper.toFocusPromise(response!!)
        val ui = buildPromiseUnderstanding(
            draft = draft,
            source = UnderstandingSource.PROMISE_COMPILER,
            selectedClarificationOptionId = selectedId
        )
        return Stack(draft, ui, ConfirmedPromiseBinder.bind(draft))
    }

    private fun enforces(settings: StudyWorldSettings, pkg: String, text: String = ""): Boolean =
        EnforcementScopeLaw.matches(pkg, text, settings)

    @Test
    fun allVideoChoice_isShownStoredAndEnforcedAcrossVideoAppsAndBrowsers() {
        val s = stack("all_video")
        assertTrue(s.ui.canStart)
        assertEquals("Major video apps and browser video", s.ui.appliesToLabel)
        assertTrue(s.ui.cautions.any { it == "Using your choice: All video apps and browsers" })
        assertFalse(s.ui.cautions.any { it.contains("Browser video only") })
        assertEquals("all_video_apps", s.settings.enforcementScopeKind)
        for (pkg in listOf(YOUTUBE, NEWPIPE, VANCED, CHROME)) {
            assertTrue("draft scope has $pkg", pkg in s.draft.scopePackages)
            assertTrue("stored scope has $pkg", pkg in s.settings.enforcementScopePackages)
            assertTrue("enforced on $pkg", enforces(s.settings, pkg))
        }
        assertEquals(30, s.settings.minVideoLengthBlockMinutes)
        assertNull(s.settings.maxVideoLengthBlockMinutes)
    }

    @Test
    fun chromeChoice_staysBrowserOnly() {
        val s = stack("chrome_only", selectedId = "B")
        assertTrue(s.ui.canStart)
        assertEquals("browser video", s.ui.appliesToLabel)
        assertEquals(listOf(CHROME), s.settings.enforcementScopePackages)
        assertTrue(enforces(s.settings, CHROME))
        assertFalse(enforces(s.settings, YOUTUBE))
        assertFalse(enforces(s.settings, NEWPIPE))
    }

    @Test
    fun youtubeAppChoice_staysOfficialAppOnly() {
        val s = stack("youtube_app_only", selectedId = "C")
        assertTrue(s.ui.canStart)
        assertEquals(EnforcementScopeLaw.SCOPE_YOUTUBE_APP_ONLY, s.settings.enforcementScopeKind)
        assertEquals(listOf(YOUTUBE), s.settings.enforcementScopePackages)
        assertTrue(enforces(s.settings, YOUTUBE))
        assertFalse(enforces(s.settings, NEWPIPE))
        assertFalse(enforces(s.settings, CHROME, "m.youtube.com/watch?v=dQw4w9WgXcQ"))
    }

    @Test
    fun youtubeContentChoice_followsYouTubeAnywhere() {
        val s = stack("youtube_content")
        assertTrue(s.ui.canStart)
        assertEquals(EnforcementScopeLaw.SCOPE_YOUTUBE_CONTENT, s.settings.enforcementScopeKind)
        assertTrue(EnforcementScopeLaw.BRAND_YOUTUBE in s.settings.enforcementContentBrands)
        assertTrue(enforces(s.settings, YOUTUBE))
        assertTrue(enforces(s.settings, NEWPIPE))
        assertTrue(enforces(s.settings, CHROME, "m.youtube.com/watch?v=dQw4w9WgXcQ"))
        assertFalse(enforces(s.settings, CHROME, "news.example.com article"))
    }

    @Test
    fun shortFormChoice_coversShortVideoAppsAndBrowser() {
        val s = stack("short_form_all")
        assertTrue(s.ui.canStart)
        for (pkg in listOf(YOUTUBE, INSTAGRAM, CHROME)) {
            assertTrue("enforced on $pkg", enforces(s.settings, pkg))
        }
    }

    @Test
    fun unverifiedPick_keepsStartDisabledEvenForConcreteClockPolicy() {
        val s = stack("min30_no_proof")
        assertTrue(s.draft.choiceVerificationFailed)
        assertTrue(s.draft.needsClarification)
        assertFalse(s.ui.canStart)
        assertTrue(s.ui.clarificationOptions.isNotEmpty())
        assertFalse(s.ui.cautions.any { it.startsWith("Using your choice") })
    }

    @Test
    fun unverifiedCompoundPick_isNotUnlockedByLocalCompoundLaw() {
        val s = stack("compound_no_proof")
        assertTrue(s.draft.choiceVerificationFailed)
        assertFalse(s.ui.canStart)
    }

    @Test
    fun ghostClarifyClearer_neverOverridesBackendFailClosed() {
        val draft = stack("min30_no_proof").draft.copy(clarificationOptions = emptyList())
        val cleared = PromiseClarificationLaw.clearGhostClarification(draft)
        assertTrue(cleared.clarificationRequired)
        assertFalse(cleared.canStartCommitment == true)
    }

    private companion object {
        const val YOUTUBE = "com.google.android.youtube"
        const val NEWPIPE = "org.schabi.newpipe"
        const val VANCED = "com.vanced.android.youtube"
        const val CHROME = "com.android.chrome"
        const val INSTAGRAM = "com.instagram.android"
    }
}
