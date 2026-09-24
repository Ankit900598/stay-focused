package com.phonecodex.app.domain.promise

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 2A pre-work tests for `PromiseIntentRules.explicitlyBlocksKnownPackage`.
 *
 * The old implementation scanned an 80-character window around the app alias and
 * fired on any bare "no", "stop", "block" cue that happened to be nearby. That
 * false-blocks the connector apps we advertise ("blocked UI, not blocked life") —
 * WhatsApp and Gmail were being force-BLOCKed on goals like
 * "allow whatsapp and gmail, no shorts". See docs/scope-semantics-v1-phase2-review.md §2 F2.
 *
 * The fix: the negation cue must be syntactically bound to the alias itself.
 * A "no shorts" clause elsewhere in the same sentence must NOT block whatsapp.
 * Positive blocks ("no whatsapp", "block gmail", "except whatsapp", "whatsapp blocked")
 * must still fire.
 */
class PromiseIntentRulesAppBlockTest {

    private val whatsapp = "com.whatsapp"
    private val gmail = "com.google.android.gm"

    // ---- must NOT block (the new invariant) --------------------------------

    @Test
    fun allow_whatsapp_and_gmail_no_shorts_does_not_block_whatsapp() {
        assertFalse(
            PromiseIntentRules.explicitlyBlocksKnownPackage(
                goal = "allow whatsapp and gmail, no shorts",
                packageName = whatsapp
            )
        )
    }

    @Test
    fun no_shorts_keep_gmail_working_does_not_block_gmail() {
        assertFalse(
            PromiseIntentRules.explicitlyBlocksKnownPackage(
                goal = "no shorts, keep gmail working",
                packageName = gmail
            )
        )
    }

    @Test
    fun stop_wasting_time_gmail_is_fine_does_not_block_gmail() {
        assertFalse(
            PromiseIntentRules.explicitlyBlocksKnownPackage(
                goal = "stop wasting time, gmail is fine",
                packageName = gmail
            )
        )
    }

    @Test
    fun block_reels_but_docs_ok_does_not_block_docs() {
        assertFalse(
            PromiseIntentRules.explicitlyBlocksKnownPackage(
                goal = "block reels but docs ok",
                packageName = "com.google.android.apps.docs"
            )
        )
    }

    @Test
    fun allow_whatsapp_ok_still_does_not_block_whatsapp() {
        // regression fence for the existing "whatsapp ok" case that already passed
        assertFalse(
            PromiseIntentRules.explicitlyBlocksKnownPackage(
                goal = "whatsapp ok",
                packageName = whatsapp
            )
        )
    }

    // ---- must still block (positive cases, the good behavior) --------------

    @Test
    fun no_whatsapp_blocks_whatsapp() {
        assertTrue(
            PromiseIntentRules.explicitlyBlocksKnownPackage(
                goal = "study only, no whatsapp",
                packageName = whatsapp
            )
        )
    }

    @Test
    fun block_gmail_blocks_gmail() {
        assertTrue(
            PromiseIntentRules.explicitlyBlocksKnownPackage(
                goal = "focus mode: block gmail for 2 hours",
                packageName = gmail
            )
        )
    }

    @Test
    fun except_whatsapp_still_treated_as_scope_reference() {
        // "except whatsapp" is the explicit exclusion phrasing the old regex handled;
        // it is a scope reference, not a "block whatsapp" cue. Post-fix must remain false.
        assertFalse(
            PromiseIntentRules.explicitlyBlocksKnownPackage(
                goal = "no messengers except whatsapp",
                packageName = whatsapp
            )
        )
    }

    @Test
    fun whatsapp_is_blocked_binds_the_cue_to_the_alias() {
        assertTrue(
            PromiseIntentRules.explicitlyBlocksKnownPackage(
                goal = "whatsapp is blocked for the next hour",
                packageName = whatsapp
            )
        )
    }

    @Test
    fun dont_use_gmail_blocks_gmail() {
        assertTrue(
            PromiseIntentRules.explicitlyBlocksKnownPackage(
                goal = "don't use gmail during study",
                packageName = gmail
            )
        )
    }
}
