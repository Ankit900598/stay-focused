package com.phonecodex.app.domain.scope

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SiteResolverTest {

    @Test
    fun starPatternMatchesAny() {
        assertTrue(SiteResolver.hostMatchesAny("anything here foo.com", listOf("*")))
        // Even without any host in text, "*" still matches (design: covers "any host").
        assertTrue(SiteResolver.hostMatchesAny("no url here", listOf("*")))
    }

    @Test
    fun exactHostMatchesWithOrWithoutSubdomain() {
        assertTrue(SiteResolver.hostMatchesAny("go to youtube.com/watch?v=x", listOf("youtube.com")))
        assertTrue(SiteResolver.hostMatchesAny("go to www.youtube.com/watch", listOf("youtube.com")))
        assertTrue(SiteResolver.hostMatchesAny("m.youtube.com/x", listOf("youtube.com")))
    }

    @Test
    fun youtuBeIsSeparate() {
        assertTrue(SiteResolver.hostMatchesAny("https://youtu.be/abc123", listOf("youtu.be")))
        assertFalse(SiteResolver.hostMatchesAny("https://youtu.be/abc123", listOf("nesoacademy.org")))
    }

    @Test
    fun wildcardSubdomainMatchesRootAndSub() {
        assertTrue(SiteResolver.hostMatchesAny("cdn.example.com/asset", listOf("*.example.com")))
        assertTrue(SiteResolver.hostMatchesAny("go to example.com", listOf("*.example.com")))
        assertFalse(SiteResolver.hostMatchesAny("go to notexample.com", listOf("*.example.com")))
    }

    @Test
    fun noHostInTextMeansNoMatch() {
        assertFalse(SiteResolver.hostMatchesAny("just plain words", listOf("youtube.com")))
    }

    @Test
    fun ignoresNoisyTrailingPunctuation() {
        assertTrue(SiteResolver.hostMatchesAny("(https://youtube.com/watch?v=x).", listOf("youtube.com")))
    }
}
