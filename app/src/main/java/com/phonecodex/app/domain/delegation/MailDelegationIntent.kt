package com.phonecodex.app.domain.delegation

/**
 * What the user asked Strlix to do with mail while Gmail is closed. Deterministic: the
 * connector exception is created only from the user's own words and their confirmation,
 * never from a model's interpretation.
 */
data class MailDelegationRequest(
    /** The user asked Strlix to act on mail for them ("let me ask Strlix to check…"). */
    val requested: Boolean,
    /** "No Gmail in any form", "not even through Strlix": the connector is never offered. */
    val forbidsEveryForm: Boolean,
    /** Who they want checked, as they said it ("my professor"). Never used as an address. */
    val senderHint: String?,
    /** An address typed in the promise itself; still needs explicit confirmation. */
    val senderAddressInText: EmailAddress?,
    val wantsSummary: Boolean,
    val wantsReplyDraft: Boolean,
    val wantsSend: Boolean,
    /** "Tell me when an email arrives" — not supported in this version. */
    val wantsProactiveAlerts: Boolean,
    /** The enforcement part of the promise, sent to the Promise Compiler. */
    val commitmentText: String
) {
    companion object {
        fun none(text: String) = MailDelegationRequest(
            requested = false,
            forbidsEveryForm = false,
            senderHint = null,
            senderAddressInText = null,
            wantsSummary = false,
            wantsReplyDraft = false,
            wantsSend = false,
            wantsProactiveAlerts = false,
            commitmentText = text
        )
    }
}

object MailDelegationIntent {

    private val EXPLICIT_MAIL_NOUN = Regex("""\b(gmail|e-?mails?|mails?|inbox)\b""", RegexOption.IGNORE_CASE)
    private val MESSAGE_NOUN = Regex("""\bmessages?\b""", RegexOption.IGNORE_CASE)
    private val OTHER_MESSAGING_APP = Regex(
        """\b(whatsapp|instagram|facebook|messenger|linkedin|telegram|signal|discord)\b""",
        RegexOption.IGNORE_CASE
    )

    private val DELEGATION_CUE = Regex(
        """\b(?:""" +
            """(?:let\s+me\s+)?ask\s+strlix|let\s+strlix|have\s+strlix|get\s+strlix|""" +
            """strlix\s+(?:can|could|should|may|will|to)|""" +
            """(?:via|through|using|with)\s+strlix|""" +
            """(?:let|ask)\s+(?:the\s+)?(?:assistant|ai)""" +
            """)\b""",
        RegexOption.IGNORE_CASE
    )

    private val MAIL_ACTION = Regex(
        """\b(check|read|look\s+at|summari[sz]e|summary|reply|respond|answer|draft|send|tell\s+me|notify|alert)\b""",
        RegexOption.IGNORE_CASE
    )

    private val FORBID_EVERY_FORM = listOf(
        Regex("""\bin\s+(?:any|every|all)\s+(?:forms?|ways?)\b""", RegexOption.IGNORE_CASE),
        Regex("""\bnot\s+even\s+(?:through|via|with|using|by)\s+strlix\b""", RegexOption.IGNORE_CASE),
        Regex("""\b(?:do\s+not|don't|dont|never)\s+let\s+strlix\b""", RegexOption.IGNORE_CASE),
        Regex(
            """\bstrlix\s+(?:must\s+not|mustn't|should\s+not|shouldn't|cannot|can't|may\s+not|won't|will\s+not)\s+""" +
                """(?:read|check|open|access|touch|see|look)""",
            RegexOption.IGNORE_CASE
        ),
        Regex("""\bno\s+(?:gmail|e-?mail|mail)\s+(?:at\s+all|whatsoever|access)\b""", RegexOption.IGNORE_CASE),
        Regex("""\b(?:gmail|e-?mail|mail)\s+(?:completely|entirely|totally)\s+(?:off|closed|blocked)\b""", RegexOption.IGNORE_CASE),
        Regex("""\b(?:do\s+not|don't|dont|never)\s+(?:read|check|open|touch)\s+my\s+(?:gmail|e-?mails?|mails?|inbox)\b""", RegexOption.IGNORE_CASE),
        Regex("""\bno\s+(?:exceptions?|connectors?|workarounds?)\b""", RegexOption.IGNORE_CASE)
    )

    private val PROACTIVE = listOf(
        Regex("""\b(?:tell|notify|alert|ping|buzz|inform)\s+me\s+(?:when|if|as\s+soon\s+as|whenever)\b""", RegexOption.IGNORE_CASE),
        Regex("""\blet\s+me\s+know\s+(?:when|if|as\s+soon\s+as|whenever)\b""", RegexOption.IGNORE_CASE),
        Regex("""\b(?:when|whenever|as\s+soon\s+as)\s+(?:an?\s+|any\s+|new\s+)*(?:e-?mail|mail|message)s?\s+(?:arrives?|comes?\s+in|lands?|shows?\s+up)\b""", RegexOption.IGNORE_CASE)
    )

    private val SENDER_HINT = Regex(
        """\bfrom\s+((?:my\s+|the\s+|our\s+)?[a-z][a-z.'\- ]{1,40}?)(?=\s*(?:,|\.|;|$|\band\b|\bbut\b|\bthen\b|\bto\b|\bso\b))""",
        RegexOption.IGNORE_CASE
    )

    private val ADDRESS_IN_TEXT = Regex("""[A-Za-z0-9._%+\-]+@[A-Za-z0-9.\-]+\.[A-Za-z]{2,}""")

    private val CLAUSE_SPLIT = Regex("""(?<=[.!?;])\s+|,?\s+\bbut\b\s+|,\s+(?=(?:and\s+)?(?:let|ask|have)\s)""", RegexOption.IGNORE_CASE)

    fun detect(rawText: String): MailDelegationRequest {
        val text = rawText.trim()
        if (text.isEmpty()) return MailDelegationRequest.none(text)

        val forbids = FORBID_EVERY_FORM.any { it.containsMatchIn(text) }
        val clauses = text.split(CLAUSE_SPLIT).map { it.trim() }.filter { it.isNotEmpty() }
        val hasExplicitMailContext = EXPLICIT_MAIL_NOUN.containsMatchIn(text)
        val competingMessagingApp = OTHER_MESSAGING_APP.containsMatchIn(text)
        val delegationClauses = clauses.filter {
            isDelegationClause(it, hasExplicitMailContext, competingMessagingApp)
        }
        val requested = delegationClauses.isNotEmpty() && !forbids

        if (delegationClauses.isEmpty()) {
            return MailDelegationRequest.none(text).copy(
                forbidsEveryForm = forbids,
                wantsProactiveAlerts = wantsProactiveMailAlerts(text)
            )
        }

        val delegationText = delegationClauses.joinToString(" ")
        val remaining = clauses.filterNot { it in delegationClauses }
            .joinToString(". ") { it.trimEnd('.', ',', ';', ' ') }
            .trim()
        val commitment = if (remaining.isBlank()) text else remaining

        return MailDelegationRequest(
            requested = requested,
            forbidsEveryForm = forbids,
            senderHint = SENDER_HINT.find(delegationText)?.groupValues?.get(1)?.trim()
                ?.takeUnless { it.contains('@') },
            senderAddressInText = ADDRESS_IN_TEXT.find(text)?.value?.let(EmailAddress::parseUserEntry),
            wantsSummary = Regex("""\bsummar""", RegexOption.IGNORE_CASE).containsMatchIn(delegationText),
            wantsReplyDraft = Regex("""\b(reply|respond|answer|draft)""", RegexOption.IGNORE_CASE).containsMatchIn(delegationText),
            wantsSend = Regex("""\bsend\b""", RegexOption.IGNORE_CASE).containsMatchIn(delegationText),
            wantsProactiveAlerts = wantsProactiveMailAlerts(text),
            commitmentText = commitment
        )
    }

    private fun wantsProactiveMailAlerts(text: String): Boolean {
        val hasExplicitMailContext = EXPLICIT_MAIL_NOUN.containsMatchIn(text)
        val hasCompetingMessagingApp = OTHER_MESSAGING_APP.containsMatchIn(text)
        val sentenceBoundaries = charArrayOf('.', ';', '!', '?')
        return PROACTIVE.any { proactive ->
            proactive.findAll(text).any { match ->
                val start = match.range.first
                val afterMatch = match.range.last + 1
                val sentenceEnd = text.indexOfAny(sentenceBoundaries, startIndex = afterMatch)
                    .let { if (it < 0) text.length else it }
                val context = text.substring(start, sentenceEnd)
                val explicitMail = EXPLICIT_MAIL_NOUN.containsMatchIn(context)
                val contextualMessages = hasExplicitMailContext &&
                    !hasCompetingMessagingApp && MESSAGE_NOUN.containsMatchIn(context)
                !OTHER_MESSAGING_APP.containsMatchIn(context) && (explicitMail || contextualMessages)
            }
        }
    }

    private fun isDelegationClause(
        clause: String,
        hasExplicitMailContext: Boolean,
        competingMessagingApp: Boolean
    ): Boolean =
        DELEGATION_CUE.containsMatchIn(clause) &&
            MAIL_ACTION.containsMatchIn(clause) &&
            (EXPLICIT_MAIL_NOUN.containsMatchIn(clause) ||
                (hasExplicitMailContext && !competingMessagingApp && MESSAGE_NOUN.containsMatchIn(clause)))
}
