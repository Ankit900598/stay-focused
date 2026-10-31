package com.phonecodex.app.domain.delegation

import com.phonecodex.app.domain.model.AppRule
import com.phonecodex.app.domain.model.AppRuleBehavior
import com.phonecodex.app.domain.model.FocusPromise
import com.phonecodex.app.domain.promise.PromiseIntentRules

enum class MailHelperOffer {
    /** Nothing about mail in this promise. */
    NONE,
    /** User forbade Gmail in every form: Strlix will not read mail for them either. */
    FORBIDDEN_BY_USER,
    /** User asked for delegated help and the promise closes Gmail. */
    REQUESTED,
    /** Promise closes Gmail; the helper is available but off until the user adds it. */
    OPTIONAL,
    /** User asked for help but this promise does not close Gmail; there is nothing to relay. */
    GMAIL_NOT_CLOSED
}

object MailDelegationOfferLaw {

    const val GMAIL_PACKAGE = "com.google.android.gm"

    fun draftClosesGmail(draft: FocusPromise): Boolean =
        draft.suggestedAppRules.any { it.packageName == GMAIL_PACKAGE && it.behavior == AppRuleBehavior.BLOCK } ||
            PromiseIntentRules.explicitlyBlocksKnownPackage(draft.rawText, GMAIL_PACKAGE)

    /**
     * When the user's own words close Gmail, the draft must carry a Gmail BLOCK rule, even if
     * the compiler omitted it: the mail helper is only ever an exception to an enforced block.
     */
    fun withDeterministicGmailBlock(draft: FocusPromise): FocusPromise {
        if (!PromiseIntentRules.explicitlyBlocksKnownPackage(draft.rawText, GMAIL_PACKAGE)) return draft
        val existing = draft.suggestedAppRules.firstOrNull { it.packageName == GMAIL_PACKAGE }
        if (existing?.behavior == AppRuleBehavior.BLOCK) return draft
        val rules = draft.suggestedAppRules.filterNot { it.packageName == GMAIL_PACKAGE } +
            AppRule(GMAIL_PACKAGE, "Gmail", AppRuleBehavior.BLOCK)
        return draft.copy(suggestedAppRules = rules)
    }

    fun decide(request: MailDelegationRequest, draftClosesGmail: Boolean): MailHelperOffer = when {
        request.forbidsEveryForm -> MailHelperOffer.FORBIDDEN_BY_USER
        !draftClosesGmail -> if (request.requested) MailHelperOffer.GMAIL_NOT_CLOSED else MailHelperOffer.NONE
        request.requested -> MailHelperOffer.REQUESTED
        else -> MailHelperOffer.OPTIONAL
    }
}
