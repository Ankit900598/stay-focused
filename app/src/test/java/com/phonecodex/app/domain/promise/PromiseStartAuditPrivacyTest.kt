package com.phonecodex.app.domain.promise

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PromiseStartAuditPrivacyTest {
    @Test
    fun formattedStartAuditOmitsPromiseText() {
        val privatePromise = "only Neso Academy and no adult content 984271"
        val audit = PromiseStartAudit(
            rawPromise = privatePromise,
            contentRules = emptyList(),
            minVideoLengthBlockMinutes = null,
            maxVideoLengthBlockMinutes = null,
            shortFormDailyQuotaLimit = null,
            enforcementScopePackages = emptyList()
        )

        val line = audit.format()

        assertTrue(line.contains("promiseChars=${privatePromise.length}"))
        assertFalse(line.contains(privatePromise))
        assertFalse(line.contains("rawPromise="))
    }
}
