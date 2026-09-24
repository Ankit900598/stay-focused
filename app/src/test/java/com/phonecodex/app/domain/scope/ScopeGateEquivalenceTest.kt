package com.phonecodex.app.domain.scope

import com.phonecodex.app.domain.enforcement.EnforcementScopeLaw
import com.phonecodex.app.domain.enforcement.SurfaceDetector
import com.phonecodex.app.domain.enforcement.trace.PhoneSurfaceTraceCatalog
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 2A: the whole point of [LegacyScopeAdapter] + [ScopeMatcher] is to be a
 * behaviour-preserving refactor. For every real trace in
 * [PhoneSurfaceTraceCatalog], the new scope-in verdict
 * ([ScopeMatcher.match] on [LegacyScopeAdapter.from]) must agree with the old
 * scope-in verdict ([EnforcementScopeLaw.matches]).
 *
 * "Agree" means:
 *   old.matches(pkg, text, settings) == true   <=>   new match is InScope OR Exempt
 *   old.matches(pkg, text, settings) == false  <=>   new match is OutOfScope
 *
 * Unresolved never fires here -- the adapter is total on the three legacy fields.
 *
 * This is the equivalence oracle. If it goes red, the refactor changed behaviour
 * on at least one already-shipped trace and Phase 2A must stop before Phase 2B.
 */
class ScopeGateEquivalenceTest {

    @Test
    fun legacyAdapter_matches_oldEnforcementScopeLaw_forEveryCatalogTrace() {
        val traces = PhoneSurfaceTraceCatalog.all()
        val disagreements = mutableListOf<String>()
        for (trace in traces) {
            val detection = SurfaceDetector.detect(trace.packageName, trace.screenText)
            val scope = LegacyScopeAdapter.from(trace.settings)
            val newMatch = ScopeMatcher.match(
                trace.packageName,
                trace.screenText,
                detection,
                scope
            )
            val newIsInScope = newMatch is ScopeMatch.InScope || newMatch is ScopeMatch.Exempt
            val oldMatches = EnforcementScopeLaw.matches(
                packageName = trace.packageName,
                screenText = trace.screenText,
                scopePackages = trace.settings.enforcementScopePackages,
                contentBrands = trace.settings.enforcementContentBrands
            )
            if (newIsInScope != oldMatches) {
                disagreements += "id=${trace.id} pkg=${trace.packageName} " +
                    "goal=${trace.goal.take(80)} " +
                    "oldMatches=$oldMatches newMatch=$newMatch " +
                    "scopeKind=${scope.kind} " +
                    "legacyPkgs=${trace.settings.enforcementScopePackages} " +
                    "legacyBrands=${trace.settings.enforcementContentBrands} " +
                    "legacyKind=${trace.settings.enforcementScopeKind}"
            }
        }
        assertTrue(disagreements.joinToString("\n"), disagreements.isEmpty())
    }

    @Test
    fun legacyEmptyScope_matchesEverythingUnderBothOldAndNewLaw() {
        val emptyScope = LegacyScopeAdapter.from(
            packages = emptyList(),
            brands = emptyList(),
            scopeKind = null
        )
        val samples = listOf(
            "com.google.android.youtube" to "Time elapsed 00:01",
            "com.android.chrome" to "Google Search",
            "org.schabi.newpipe" to "",
            "com.random.unknown.clone" to "Play Pause",
            "com.whatsapp" to "Chats Calls Status"
        )
        for ((pkg, text) in samples) {
            val newMatch = ScopeMatcher.match(
                pkg,
                text,
                SurfaceDetector.detect(pkg, text),
                emptyScope
            )
            val oldMatches = EnforcementScopeLaw.matches(
                packageName = pkg,
                screenText = text,
                scopePackages = emptyList(),
                contentBrands = emptyList()
            )
            assertTrue("old should match for $pkg (unrestricted)", oldMatches)
            assertTrue("new should be InScope for $pkg, got $newMatch", newMatch is ScopeMatch.InScope)
        }
    }
}
