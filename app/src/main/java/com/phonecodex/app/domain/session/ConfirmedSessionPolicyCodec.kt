package com.phonecodex.app.domain.session

import com.phonecodex.app.domain.model.AppRule
import com.phonecodex.app.domain.model.AppRuleBehavior
import com.phonecodex.app.domain.model.ConfirmedSessionPolicy
import com.phonecodex.app.domain.model.ContentRule
import com.phonecodex.app.domain.model.ContentRuleAction
import java.nio.charset.StandardCharsets
import java.util.Base64

object ConfirmedSessionPolicyCodec {

    private data class DecodedNullableField(val valid: Boolean, val value: String?)

    private const val VERSION = "confirmed-session-policy-v1"
    private const val NULL = "~"
    private const val MAX_RECORD_CHARS = 32_768
    private const val MAX_RULES = 128
    private const val MAX_FIELD_CHARS = 256
    private val PACKAGE_NAME = Regex("^[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)+$")
    private val OPERATORS = setOf("gt", "gte", "lt", "lte", "eq")

    fun encode(policy: ConfirmedSessionPolicy): String? {
        if (policy.appRules.size + policy.contentRules.size > MAX_RULES) return null
        val lines = mutableListOf(VERSION)
        policy.appRules.forEach { rule ->
            if (!isValidPackage(rule.packageName) || !isValidField(rule.label)) return null
            lines += listOf(
                "A",
                encodeField(rule.packageName),
                encodeField(rule.label),
                rule.behavior.name
            ).joinToString("|")
        }
        policy.contentRules.forEach { rule ->
            if (!isValidNullableField(rule.appLabel) ||
                !isValidNullablePackage(rule.packageName) ||
                !isValidField(rule.surface) ||
                !isValidField(rule.contentType) ||
                !isValidNullableField(rule.operator) ||
                !isValidNullableField(rule.unit) ||
                rule.operator != null && rule.operator !in OPERATORS ||
                rule.value != null && rule.value < 0
            ) return null
            lines += listOf(
                "C",
                encodeNullableField(rule.appLabel),
                encodeNullableField(rule.packageName),
                encodeField(rule.surface),
                encodeField(rule.contentType),
                encodeNullableField(rule.operator),
                rule.value?.toString() ?: NULL,
                encodeNullableField(rule.unit),
                rule.action.name
            ).joinToString("|")
        }
        return lines.joinToString("\n").takeIf { it.length <= MAX_RECORD_CHARS }
    }

    fun decode(raw: String?): ConfirmedSessionPolicy? {
        if (raw == null || raw.length > MAX_RECORD_CHARS) return null
        val lines = raw.split('\n')
        if (lines.firstOrNull() != VERSION || lines.size - 1 > MAX_RULES) return null
        val apps = mutableListOf<AppRule>()
        val content = mutableListOf<ContentRule>()
        for (line in lines.drop(1)) {
            val fields = line.split('|')
            when (fields.firstOrNull()) {
                "A" -> {
                    if (fields.size != 4) return null
                    val packageName = decodeField(fields[1]) ?: return null
                    val label = decodeField(fields[2]) ?: return null
                    if (!isValidPackage(packageName) || !isValidField(label)) return null
                    val behavior = runCatching { AppRuleBehavior.valueOf(fields[3]) }.getOrNull()
                        ?: return null
                    apps += AppRule(packageName, label, behavior)
                }
                "C" -> {
                    if (fields.size != 9) return null
                    val appLabelField = decodeNullableField(fields[1])
                    val packageField = decodeNullableField(fields[2])
                    if (!appLabelField.valid || !packageField.valid) return null
                    val appLabel = appLabelField.value
                    val packageName = packageField.value
                    val surface = decodeField(fields[3]) ?: return null
                    val contentType = decodeField(fields[4]) ?: return null
                    val operatorField = decodeNullableField(fields[5])
                    if (!operatorField.valid) return null
                    val operator = operatorField.value
                    val value = if (fields[6] == NULL) null else fields[6].toIntOrNull() ?: return null
                    val unitField = decodeNullableField(fields[7])
                    if (!unitField.valid) return null
                    val unit = unitField.value
                    if (!isValidNullableField(appLabel) ||
                        !isValidNullablePackage(packageName) ||
                        !isValidField(surface) ||
                        !isValidField(contentType) ||
                        !isValidNullableField(operator) ||
                        operator != null && operator !in OPERATORS ||
                        value != null && value < 0 ||
                        !isValidNullableField(unit)
                    ) return null
                    val action = runCatching { ContentRuleAction.valueOf(fields[8]) }.getOrNull()
                        ?: return null
                    content += ContentRule(
                        appLabel = appLabel,
                        packageName = packageName,
                        surface = surface,
                        contentType = contentType,
                        operator = operator,
                        value = value,
                        unit = unit,
                        action = action
                    )
                }
                else -> return null
            }
        }
        return ConfirmedSessionPolicy(apps, content)
    }

    private fun encodeField(value: String): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(value.toByteArray(StandardCharsets.UTF_8))

    private fun encodeNullableField(value: String?): String = value?.let(::encodeField) ?: NULL

    private fun decodeRawField(value: String): String? = runCatching {
        String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8)
            .takeIf { it.length <= MAX_FIELD_CHARS }
    }.getOrNull()

    private fun decodeField(value: String): String? =
        decodeRawField(value)?.takeIf(::isValidField)

    private fun decodeNullableField(value: String): DecodedNullableField =
        if (value == NULL) {
            DecodedNullableField(valid = true, value = null)
        } else {
            val decoded = decodeRawField(value)
            DecodedNullableField(valid = decoded != null, value = decoded)
        }

    private fun isValidPackage(value: String): Boolean =
        value.length <= MAX_FIELD_CHARS && PACKAGE_NAME.matches(value)

    private fun isValidNullablePackage(value: String?): Boolean =
        value == null || isValidPackage(value)

    private fun isValidField(value: String): Boolean =
        value.isNotBlank() && value.length <= MAX_FIELD_CHARS

    private fun isValidNullableField(value: String?): Boolean =
        value == null || value.length <= MAX_FIELD_CHARS
}
