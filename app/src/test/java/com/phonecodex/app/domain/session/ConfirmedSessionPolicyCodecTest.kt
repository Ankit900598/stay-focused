package com.phonecodex.app.domain.session

import com.phonecodex.app.domain.model.AppRule
import com.phonecodex.app.domain.model.AppRuleBehavior
import com.phonecodex.app.domain.model.ConfirmedSessionPolicy
import com.phonecodex.app.domain.model.ContentRule
import com.phonecodex.app.domain.model.ContentRuleAction
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.nio.charset.StandardCharsets
import java.util.Base64

class ConfirmedSessionPolicyCodecTest {

    @Test
    fun malformedRuleInvalidatesTheWholePolicyInsteadOfKeepingPartialAuthority() {
        val encoded = ConfirmedSessionPolicyCodec.encode(
            ConfirmedSessionPolicy(
                appRules = listOf(AppRule("com.google.android.youtube", "YouTube", AppRuleBehavior.AI_DECIDE)),
                contentRules = listOf(
                    ContentRule(
                        appLabel = null,
                        packageName = null,
                        surface = "any",
                        contentType = "adult_sexual",
                        operator = null,
                        value = null,
                        unit = null,
                        action = ContentRuleAction.BLOCK
                    )
                )
            )
        )!!

        val malformed = encoded.replace("|BLOCK", "|UNKNOWN")

        assertNull(ConfirmedSessionPolicyCodec.decode(malformed))
    }

    @Test
    fun invalidPackageFieldDoesNotDecodeAsWildcard() {
        val encoded = ConfirmedSessionPolicyCodec.encode(
            ConfirmedSessionPolicy(
                appRules = emptyList(),
                contentRules = listOf(
                    ContentRule(
                        appLabel = null,
                        packageName = "com.google.android.youtube",
                        surface = "shorts",
                        contentType = "short_form_video",
                        operator = null,
                        value = null,
                        unit = null,
                        action = ContentRuleAction.BLOCK
                    )
                )
            )
        )!!
        val lines = encoded.split('\n').toMutableList()
        val fields = lines[1].split('|').toMutableList()
        fields[2] = Base64.getUrlEncoder().withoutPadding().encodeToString(
            "invalid".toByteArray(StandardCharsets.UTF_8)
        )
        lines[1] = fields.joinToString("|")

        assertNull(ConfirmedSessionPolicyCodec.decode(lines.joinToString("\n")))
    }

    @Test
    fun oversizedPolicyCannotBePersistedForClassifierAuthority() {
        val tooManyRules = ConfirmedSessionPolicy(
            appRules = (0..128).map {
                AppRule("com.example.app$it", "App $it", AppRuleBehavior.AI_DECIDE)
            },
            contentRules = emptyList()
        )

        assertNull(ConfirmedSessionPolicyCodec.encode(tooManyRules))
        assertNotNull(ConfirmedSessionPolicyCodec.encode(ConfirmedSessionPolicy(emptyList(), emptyList())))
    }
}
