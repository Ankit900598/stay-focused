package com.phonecodex.app.domain.scope

import com.phonecodex.app.domain.diagnostics.BackendProbeKind
import com.phonecodex.app.domain.enforcement.EnforcementReasonCodes
import com.phonecodex.app.domain.enforcement.PromiseEnforcementCoordinator
import com.phonecodex.app.domain.enforcement.PromiseEvalInput
import com.phonecodex.app.domain.enforcement.SurfaceDetector
import com.phonecodex.app.domain.enforcement.trace.EnforcementTrace
import com.phonecodex.app.domain.enforcement.trace.EnforcementTraceEvaluator
import com.phonecodex.app.domain.enforcement.trace.TraceDecision
import com.phonecodex.app.domain.model.AppRuleBehavior
import com.phonecodex.app.domain.model.DecisionType
import com.phonecodex.app.domain.model.SessionStatus
import com.phonecodex.app.domain.model.StudyWorldSettings
import com.phonecodex.app.domain.promise.ConfirmedPromiseBinder
import com.phonecodex.app.domain.protection.ProtectionReliabilityGate
import com.phonecodex.app.domain.protection.ProtectionReliabilityInput
import com.phonecodex.app.domain.protection.ProtectionReliabilityLevel
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Three persisted typed-scope states. Only an ABSENT record may use the legacy mirror;
 * a CORRUPT record must never silently become the (possibly narrower) legacy scope.
 */
class TypedScopePersistenceStateTest {

    private val chrome = ScopeTraceScreens.CHROME
    private val firefox = ScopeTraceScreens.FIREFOX
    private val shortPlayer = "m.youtube.com/watch?v=abc123xyz Hide player controls 1:02 / 12:40"
    private val goal = "Do not allow videos shorter than 30 minutes in browsers for the next 1 hour."

    private val browserVideo: EnforcementScope = ScopeCanonicalizer.canonicalize(
        EnforcementScope(
            version = EnforcementScope.CURRENT_VERSION,
            kind = ScopeKind.BROWSER_VIDEO,
            identity = ScopeIdentity.VIDEO_ANY,
            include = ScopeKindPresets.expand(ScopeKind.BROWSER_VIDEO).include,
            confidence = 0.9,
            evidenceSpans = listOf("browsers")
        )
    )

    /** Legacy mirror written alongside the v1 scope for the same promise: Chrome only. */
    private fun chromeMirror(decoded: StoredScopeCodec.Decoded? = null) = StudyWorldSettings(
        durationMinutes = 60,
        lockAttemptThreshold = 3,
        blockAttemptCooldownMinutes = 2,
        minVideoLengthBlockMinutes = 30,
        enforcementScopePackages = listOf(chrome),
        enforcementScopeKind = "chrome_browser",
        enforcementScope = decoded?.scope,
        typedScopeCorruptDetail = decoded?.corruptDetail
    )

    private fun coordinator(pkg: String, settings: StudyWorldSettings, text: String = shortPlayer) =
        PromiseEnforcementCoordinator.evaluate(
            PromiseEvalInput(
                hasActiveSession = true,
                goal = goal,
                settings = settings,
                appRule = AppRuleBehavior.AI_DECIDE,
                packageName = pkg,
                screenText = text
            )
        )

    private fun trace(pkg: String, settings: StudyWorldSettings, status: SessionStatus, text: String = shortPlayer) =
        EnforcementTraceEvaluator.evaluate(
            EnforcementTrace(
                id = "typed_scope_state_$pkg",
                packageName = pkg,
                screenText = text,
                goal = goal,
                settings = settings,
                sessionStatus = status,
                expectedDecisions = setOf(TraceDecision.ALLOW)
            )
        )

    // --- state 1: legacy record, no typed-scope key ---

    @Test
    fun absentKey_isLegacy_andKeepsLegacyAdapterBehavior() {
        val decoded = StoredScopeCodec.readRecord(keyPresent = false) {
            error("absent key must not be read")
        }
        assertEquals(TypedScopeRecord.ABSENT, decoded.record)
        assertNull(decoded.scope)
        assertNull(decoded.corruptDetail)

        val settings = chromeMirror(decoded)
        assertEquals(TypedScopeRecord.ABSENT, settings.typedScopeRecord)
        for (pkg in listOf(chrome, firefox)) {
            val viaSettings = ScopeGate.evaluate(pkg, shortPlayer, goal, settings)
            val viaAdapter = ScopeGate.evaluate(
                pkg, shortPlayer, SurfaceDetector.detect(pkg, shortPlayer), goal, LegacyScopeAdapter.from(settings)
            )
            assertEquals("$pkg: legacy session unchanged", viaAdapter, viaSettings)
        }
        // Old Chrome-only session keeps enforcing on Chrome exactly as before.
        assertEquals(DecisionType.BLOCK, coordinator(chrome, settings).decision)
        assertEquals(EnforcementReasonCodes.ENFORCEMENT_SCOPE_ALLOW, coordinator(firefox, settings).reasonCode)
    }

    // --- state 2: valid typed-scope record ---

    @Test
    fun validRecord_usesTypedScope_notLegacyMirror() {
        val encoded = StoredScopeCodec.encode(browserVideo)
        assertNotNull(encoded)
        val decoded = StoredScopeCodec.readRecord(keyPresent = true) { encoded }
        assertEquals(TypedScopeRecord.VALID, decoded.record)
        assertEquals(browserVideo, decoded.scope)

        val settings = chromeMirror(decoded)
        assertEquals(TypedScopeRecord.VALID, settings.typedScopeRecord)
        val onFirefox = ScopeGate.evaluate(firefox, shortPlayer, goal, settings)
        assertEquals(ScopeKind.BROWSER_VIDEO, onFirefox.scopeKind)
        assertTrue("firefox player in typed browser-video scope: ${onFirefox.match}", onFirefox.match is ScopeMatch.InScope)
        assertEquals(DecisionType.BLOCK, coordinator(firefox, settings).decision)
    }

    // --- state 3: corrupt / unreadable typed-scope record ---

    @Test
    fun corruptOrUnreadableRecords_areCorrupt_neverAbsent() {
        val cases: List<Pair<String, () -> String?>> = listOf(
            "not json" to { "{not json" },
            "empty object" to { "{}" },
            "blank" to { "   " },
            "null value under present key" to { null },
            "unenforceable" to { JSONObject().put("version", 1).put("kind", "UNRESOLVED").toString() },
            "wrong version" to { JSONObject().put("version", 7).put("kind", "BROWSER_VIDEO").toString() },
            "non-string pref" to { throw ClassCastException("java.util.HashSet cannot be cast to java.lang.String") }
        )
        for ((name, read) in cases) {
            val decoded = StoredScopeCodec.readRecord(keyPresent = true, read = read)
            assertEquals(name, TypedScopeRecord.CORRUPT, decoded.record)
            assertNull(name, decoded.scope)
            assertNotNull(name, decoded.corruptDetail)
            assertEquals(name, TypedScopeRecord.CORRUPT, chromeMirror(decoded).typedScopeRecord)
        }
    }

    @Test
    fun corruptV1BrowserVideo_withChromeOnlyMirror_doesNotSilentlyBecomeChromeOnly() {
        val decoded = StoredScopeCodec.readRecord(keyPresent = true) {
            // Truncated write of the confirmed BROWSER_VIDEO scope.
            StoredScopeCodec.encode(browserVideo)!!.take(40)
        }
        val settings = chromeMirror(decoded)
        assertEquals(TypedScopeRecord.CORRUPT, settings.typedScopeRecord)

        // What the old fallback would have done: Firefox silently out of scope.
        val legacy = LegacyScopeAdapter.from(settings)
        assertEquals(
            ScopeMatch.OutOfScope,
            ScopeMatcher.match(firefox, shortPlayer, SurfaceDetector.detect(firefox, shortPlayer), legacy)
        )

        for (pkg in listOf(firefox, chrome)) {
            val gate = ScopeGate.evaluate(pkg, shortPlayer, goal, settings)
            assertTrue("$pkg: ${gate.match}", gate.match is ScopeMatch.PersistenceCorrupt)
            assertTrue(gate.shortCircuits)
            assertFalse(gate.monkOverridesScope)
            assertNotEquals("$pkg: not the legacy scope", legacy.kind, gate.scopeKind)

            val coord = coordinator(pkg, settings)
            assertEquals(pkg, EnforcementReasonCodes.SCOPE_PERSISTENCE_CORRUPT, coord.reasonCode)
            assertNotEquals(pkg, EnforcementReasonCodes.ENFORCEMENT_SCOPE_ALLOW, coord.reasonCode)
            assertEquals(pkg, DecisionType.ALLOW, coord.decision)
        }
    }

    @Test
    fun corrupt_coordinatorAndTracePathAgree_activeAndLocked() {
        val settings = chromeMirror(StoredScopeCodec.readRecord(keyPresent = true) { "{not json" })
        for (pkg in listOf(chrome, firefox)) {
            for (status in listOf(SessionStatus.ACTIVE, SessionStatus.LOCKED)) {
                val t = trace(pkg, settings, status)
                // Both paths run the allow-only lock pre-check before ScopeGate; it may
                // allow an unrelated surface first, but nothing may block or lock.
                val allowed = setOf(
                    EnforcementReasonCodes.SCOPE_PERSISTENCE_CORRUPT,
                    EnforcementReasonCodes.SESSION_LOCK_ALLOW_UNRELATED
                )
                assertTrue("$pkg/$status: ${t.reasonCode}", t.reasonCode in allowed)
                assertEquals("$pkg/$status: no legacy lock", TraceDecision.ALLOW, t.decision)
                if (status == SessionStatus.ACTIVE) {
                    assertEquals("$pkg/$status", EnforcementReasonCodes.SCOPE_PERSISTENCE_CORRUPT, t.reasonCode)
                }
            }
        }
    }

    @Test
    fun corrupt_keepsEmergencyPath_andDoesNotBroadlyBlockUnrelatedApps() {
        val settings = chromeMirror(StoredScopeCodec.readRecord(keyPresent = true) { "{}" })
        val dialer = trace("com.android.phone", settings, SessionStatus.ACTIVE, "Emergency call Phone keypad")
        assertEquals(EnforcementReasonCodes.EMERGENCY_ALLOW, dialer.reasonCode)
        for ((pkg, text) in ScopeTraceScreens.UNRELATED_APPS) {
            assertEquals(pkg, DecisionType.ALLOW, coordinator(pkg, settings, text).decision)
        }
    }

    // --- surfaced state ---

    private fun protection(unverified: Boolean) = ProtectionReliabilityGate.evaluate(
        ProtectionReliabilityInput(
            accessibilityEnabled = true,
            accessibilityAlive = true,
            lastEventMillis = 1_000L,
            lastEventPackage = chrome,
            lastEventReason = "event",
            hasActiveCommitment = true,
            permanentGuardrailOn = true,
            backendKind = BackendProbeKind.REACHABLE,
            nowMillis = 2_000L,
            commitmentScopeUnverified = unverified
        )
    )

    @Test
    fun corrupt_homeDoesNotClaimProtected_andShowsStableReason() {
        assertEquals(ProtectionReliabilityLevel.PROTECTED, protection(unverified = false).level)

        val model = protection(unverified = true)
        assertEquals(ProtectionReliabilityLevel.NOT_PROTECTING, model.level)
        assertFalse(model.isProtected)
        assertNotEquals(ProtectionReliabilityGate.HEADLINE_PROTECTED, model.headline)
        assertTrue(model.commitmentLine.contains(StoredScopeCodec.CORRUPT_REASON))
        assertTrue(model.checkLogLine.contains(EnforcementReasonCodes.SCOPE_PERSISTENCE_CORRUPT))
        assertEquals(ProtectionReliabilityGate.SUMMARY_SCOPE_UNVERIFIED, model.summary)
    }

    @Test
    fun startReadBack_carriesCorruptionIntoHomeSettings_clocksUnchanged() {
        val bound = chromeMirror().copy(enforcementScope = browserVideo)
        val storedCorrupt = chromeMirror(StoredScopeCodec.readRecord(keyPresent = true) { "{not json" })
        val home = ConfirmedPromiseBinder.reflectStoredScopeIntegrity(bound, storedCorrupt)
        assertEquals(TypedScopeRecord.CORRUPT, home.typedScopeRecord)
        assertNull(home.enforcementScope)
        assertEquals(bound.durationMinutes, home.durationMinutes)
        assertEquals(bound.minVideoLengthBlockMinutes, home.minVideoLengthBlockMinutes)

        val storedValid = chromeMirror(StoredScopeCodec.readRecord(keyPresent = true) { StoredScopeCodec.encode(browserVideo) })
        assertEquals(bound, ConfirmedPromiseBinder.reflectStoredScopeIntegrity(bound, storedValid))
    }
}
