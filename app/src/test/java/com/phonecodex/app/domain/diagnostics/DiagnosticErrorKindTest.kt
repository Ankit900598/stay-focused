package com.phonecodex.app.domain.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class DiagnosticErrorKindTest {
    @Test
    fun diagnosticErrorKindDoesNotIncludeTheExceptionMessage() {
        val marker = "private-screen-message-987654"
        val kind = diagnosticErrorKind(IllegalStateException(marker))

        assertEquals("IllegalStateException", kind)
        assertFalse(kind.contains(marker))
    }
}
