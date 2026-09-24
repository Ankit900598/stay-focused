package com.phonecodex.app.domain.scope

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScopeSerializationTest {

    @Test
    fun appOnlyRoundTrips() {
        val original = buildAppOnlyYouTube()
        val json = ScopeSerialization.toJson(original).toString()
        val parsed = ScopeSerialization.fromJsonWithUserPromise(json, "YouTube app only")
        assertEquals(ScopeKind.APP_ONLY, parsed.kind)
        assertEquals(1, parsed.version)
        assertEquals(1, parsed.include.size)
        assertEquals(ScopeHost.NATIVE_APP, parsed.include.first().host)
        assertEquals(ScopeIdentity.YOUTUBE, parsed.identity)
        assertEquals(PackageMatchMode.PINNED, parsed.include.first().packageMatch)
    }

    @Test
    fun badVersionFallsToUnresolved() {
        val obj = JSONObject()
            .put("version", 2)
            .put("kind", "APP_ONLY")
        val parsed = ScopeSerialization.fromJsonWithUserPromise(obj.toString(), "foo")
        assertEquals(ScopeKind.UNRESOLVED, parsed.kind)
        assertEquals(UnresolvedReason.BAD_VERSION, parsed.unresolvedReason)
    }

    @Test
    fun missingVersionFallsToUnresolved() {
        val obj = JSONObject().put("kind", "APP_ONLY")
        val parsed = ScopeSerialization.fromJsonWithUserPromise(obj.toString(), "foo")
        assertEquals(ScopeKind.UNRESOLVED, parsed.kind)
        assertEquals(UnresolvedReason.BAD_VERSION, parsed.unresolvedReason)
    }

    @Test
    fun unknownKindFallsToUnresolvedNoCrash() {
        val obj = JSONObject()
            .put("version", 1)
            .put("kind", "YT_ONLY_LOL")
        val parsed = ScopeSerialization.fromJsonWithUserPromise(obj.toString(), "foo")
        assertEquals(ScopeKind.UNRESOLVED, parsed.kind)
        assertEquals(UnresolvedReason.UNKNOWN_KIND, parsed.unresolvedReason)
    }

    @Test
    fun packageIdInAppliesToTextIsRejected() {
        val obj = ScopeSerialization.toJson(buildAppOnlyYouTube())
        obj.put("appliesToText", "YouTube (com.google.android.youtube) only")
        val parsed = ScopeSerialization.fromJsonWithUserPromise(obj.toString(), "YouTube app only")
        assertEquals(ScopeKind.UNRESOLVED, parsed.kind)
        assertEquals(UnresolvedReason.PACKAGE_LEAK_IN_APPLIES_TO, parsed.unresolvedReason)
    }

    @Test
    fun nonVerbatimEvidenceSpanFallsToUnresolved() {
        val obj = ScopeSerialization.toJson(buildAppOnlyYouTube())
        obj.put(
            "evidenceSpans",
            org.json.JSONArray(listOf("A hallucinated span the user never wrote"))
        )
        val parsed = ScopeSerialization.fromJsonWithUserPromise(obj.toString(), "YouTube app only")
        assertEquals(ScopeKind.UNRESOLVED, parsed.kind)
        assertEquals(UnresolvedReason.MISSING_EVIDENCE, parsed.unresolvedReason)
    }

    @Test
    fun missingEvidenceForNonPinnedKindFallsToUnresolved() {
        val obj = ScopeSerialization.toJson(buildAppOnlyYouTube())
        obj.put("evidenceSpans", org.json.JSONArray())
        val parsed = ScopeSerialization.fromJsonWithUserPromise(obj.toString(), "YouTube app only")
        assertEquals(ScopeKind.UNRESOLVED, parsed.kind)
        assertEquals(UnresolvedReason.MISSING_EVIDENCE, parsed.unresolvedReason)
    }

    @Test
    fun packagePinnedTolerateMissingEvidence() {
        // PACKAGE_PINNED is the one kind where the "user named a literal app" contract
        // supplies its own evidence via the package list itself.
        val exp = ScopeKindPresets.expand(ScopeKind.PACKAGE_PINNED, packages = listOf("app.netmirror.newtv"))
        val scope = EnforcementScope(
            version = 1,
            kind = ScopeKind.PACKAGE_PINNED,
            identity = ScopeIdentity.ANY,
            include = exp.include,
            confidence = 0.85,
            appliesToText = "specific apps you named",
            evidenceSpans = emptyList()
        )
        val obj = ScopeSerialization.toJson(scope)
        val parsed = ScopeSerialization.fromJsonWithUserPromise(obj.toString(), "block NetMirror app")
        assertEquals(ScopeKind.PACKAGE_PINNED, parsed.kind)
    }

    @Test
    fun malformedSurfaceEnumFallsToUnresolved() {
        val obj = JSONObject()
            .put("version", 1)
            .put("kind", "APP_ONLY")
            .put("evidenceSpans", org.json.JSONArray(listOf("YouTube app only")))
            .put(
                "include",
                org.json.JSONArray().put(
                    JSONObject()
                        .put("id", "i1")
                        .put("host", "NATIVE_APP")
                        .put("identity", "YOUTUBE")
                        .put("surfaces", org.json.JSONArray().put("PLAYER_BOGUS"))
                        .put("packageMatch", "PINNED")
                        .put("packages", org.json.JSONArray(listOf("com.google.android.youtube")))
                )
            )
        val parsed = ScopeSerialization.fromJsonWithUserPromise(obj.toString(), "YouTube app only")
        assertEquals(ScopeKind.UNRESOLVED, parsed.kind)
        assertEquals(UnresolvedReason.MALFORMED_CLAUSE, parsed.unresolvedReason)
    }

    @Test
    fun emptyIncludeForNonUnresolvedIsMalformed() {
        val obj = JSONObject()
            .put("version", 1)
            .put("kind", "APP_ONLY")
            .put("evidenceSpans", org.json.JSONArray(listOf("YouTube app only")))
            .put("include", org.json.JSONArray())
        val parsed = ScopeSerialization.fromJsonWithUserPromise(obj.toString(), "YouTube app only")
        assertEquals(ScopeKind.UNRESOLVED, parsed.kind)
        assertEquals(UnresolvedReason.MALFORMED_CLAUSE, parsed.unresolvedReason)
    }

    @Test
    fun blankInputFallsToUnresolved() {
        val parsed = ScopeSerialization.fromJson("")
        assertEquals(ScopeKind.UNRESOLVED, parsed.kind)
        assertTrue(parsed.unresolvedReason != null)
    }

    @Test
    fun candidateScopesAreParsedRecursively() {
        // Candidate scopes share the user's ORIGINAL wording as evidence — a compiler
        // that offers 3 readings for "only YouTube" still has "only YouTube" as the
        // verbatim span pinning each candidate.
        val exp = ScopeKindPresets.expand(ScopeKind.APP_ONLY, ScopeIdentity.YOUTUBE)
        val option = EnforcementScope(
            version = 1,
            kind = ScopeKind.APP_ONLY,
            identity = ScopeIdentity.YOUTUBE,
            include = exp.include,
            exempt = exp.exempt,
            confidence = 0.7,
            appliesToText = "the YouTube app only",
            evidenceSpans = listOf("only YouTube")
        )
        val unresolved = EnforcementScope(
            version = 1,
            kind = ScopeKind.UNRESOLVED,
            identity = ScopeIdentity.YOUTUBE,
            confidence = 0.3,
            unresolvedReason = UnresolvedReason.HOST_AMBIGUOUS,
            evidenceSpans = listOf("only YouTube"),
            candidateScopes = listOf(option)
        )
        val json = ScopeSerialization.toJson(unresolved).toString()
        val parsed = ScopeSerialization.fromJsonWithUserPromise(json, "only YouTube")
        assertEquals(ScopeKind.UNRESOLVED, parsed.kind)
        assertEquals(1, parsed.candidateScopes.size)
        assertEquals(ScopeKind.APP_ONLY, parsed.candidateScopes.first().kind)
    }

    private fun buildAppOnlyYouTube(): EnforcementScope {
        val exp = ScopeKindPresets.expand(ScopeKind.APP_ONLY, ScopeIdentity.YOUTUBE)
        return EnforcementScope(
            version = 1,
            kind = ScopeKind.APP_ONLY,
            identity = ScopeIdentity.YOUTUBE,
            include = exp.include,
            exempt = exp.exempt,
            confidence = 0.92,
            appliesToText = "the YouTube app only — browser and other players stay untouched",
            evidenceSpans = listOf("YouTube app only")
        )
    }
}
