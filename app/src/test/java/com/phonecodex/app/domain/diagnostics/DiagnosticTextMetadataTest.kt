package com.phonecodex.app.domain.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class DiagnosticTextMetadataTest {
    @Test
    fun metadataReportsOnlyPresenceAndLength() {
        val privateCaption = "Ankit opened his private message 729104"
        val metadata = diagnosticTextMetadata(privateCaption)

        assertEquals("textPresent=true textChars=${privateCaption.length}", metadata)
        assertFalse(metadata.contains(privateCaption))
    }
}
