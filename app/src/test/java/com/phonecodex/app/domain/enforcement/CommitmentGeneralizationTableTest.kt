package com.phonecodex.app.domain.enforcement

import com.phonecodex.app.domain.model.AppRuleBehavior
import com.phonecodex.app.domain.model.FocusSession
import com.phonecodex.app.domain.model.PermanentGuardrail
import com.phonecodex.app.domain.model.SessionStatus
import com.phonecodex.app.domain.model.StrictnessLevel
import com.phonecodex.app.domain.model.StudyWorldSettings
import com.phonecodex.app.domain.promise.ConfirmedPromiseBinder
import com.phonecodex.app.domain.promise.FocusPromiseParser
import com.phonecodex.app.domain.safeapps.SafeAppsCatalog
import com.phonecodex.app.domain.session.SessionExpiryPolicy
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Table-driven generalization across promise classes. Every row records expected and actual
 * separately; mismatches are counted as false allows and false blocks, never as one accuracy
 * number. Unknown evidence must WAIT or clarify instead of guessing.
 *
 * `supported` rows must match. `probe` rows measure messy language the local parser may not
 * handle yet; they are reported, not asserted, so the report stays honest.
 */
class CommitmentGeneralizationTableTest {

    private enum class Outcome { ALLOW, BLOCK, WAIT, REFUSE_AT_CONFIRM }

    private data class Row(
        val cls: String,
        val id: String,
        val expected: Outcome,
        val probe: Boolean = false,
        val actual: () -> Pair<Outcome, String>
    )

    private val yt = VideoPlatformRegistry.YOUTUBE
    private val chrome = VideoPlatformRegistry.CHROME
    private val ig = VideoPlatformRegistry.INSTAGRAM

    private val min40Goal = "allow only YouTube videos longer than 40 minutes"
    private val min40 = StudyWorldSettings(
        durationMinutes = 60,
        lockAttemptThreshold = 3,
        blockAttemptCooldownMinutes = 2,
        minVideoLengthBlockMinutes = 40,
        enforcementContentBrands = listOf(EnforcementScopeLaw.BRAND_YOUTUBE),
        enforcementScopeKind = EnforcementScopeLaw.SCOPE_YOUTUBE_CONTENT
    )
    private val appOnly40 = min40.copy(
        enforcementContentBrands = emptyList(),
        enforcementScopePackages = listOf(yt),
        enforcementScopeKind = EnforcementScopeLaw.SCOPE_YOUTUBE_APP_ONLY
    )
    private val adultGuardrail = PermanentGuardrail("porn_guardrail", "No adult content", true, emptySet(), emptySet(), 1L, null)

    private val ytShortsPlayer = "Shorts Like Dislike Comment Share Remix Subscribe #shorts"
    private val ytHome = "YouTube Home Search Shorts Subscriptions You Explore recommended for you 0:45 1:12"
    private val igReels = "Reels Like Comment Share Remix Follow Original audio"
    private val igDms = "Messages Chats Requests Search Send message Active now"
    private val igFeed = "Home Stories Your story Suggested for you Like Comment Share Save"

    private fun verdict(
        pkg: String,
        text: String,
        goal: String,
        settings: StudyWorldSettings,
        session: SessionStatus? = SessionStatus.ACTIVE,
        rule: AppRuleBehavior? = AppRuleBehavior.AI_DECIDE,
        guardrails: List<PermanentGuardrail> = emptyList(),
        quotaUsed: Int = 0
    ): Pair<Outcome, String> {
        val law = CommitmentPolicyEvaluator.evaluate(
            CommitmentSnapshot(
                packageName = pkg,
                screenText = text,
                sessionStatus = session,
                goal = goal,
                settings = settings,
                storedAppRule = rule,
                isSafePackage = SafeAppsCatalog.isDefaultPackage(pkg),
                guardrails = guardrails
            ),
            ShortFormQuotaGate(ledger = FixedLedger(quotaUsed))
        )
        val outcome = when (law.verdict) {
            LawVerdict.BLOCK -> Outcome.BLOCK
            LawVerdict.WAIT -> Outcome.WAIT
            LawVerdict.ALLOW, LawVerdict.CONTINUE, LawVerdict.IGNORE, LawVerdict.WARN -> Outcome.ALLOW
        }
        return outcome to "${law.verdict}/${law.reasonCode}"
    }

    /**
     * Promise text through the real local parser + binder, with its session rule for [pkg].
     * Models a confirmed contract (the offline preview additionally requires reconnect for any
     * content-scoped promise; that gate is covered by the confirmation tests).
     */
    private fun promised(promise: String, pkg: String, text: String): Pair<Outcome, String> {
        val draft = FocusPromiseParser().parse(promise)
        if (draft.needsClarification || draft.legacyContractGap != null) {
            return Outcome.REFUSE_AT_CONFIRM to "clarify=${draft.needsClarification} gap=${draft.legacyContractGap}"
        }
        val rule = draft.suggestedAppRules.firstOrNull { it.packageName == pkg }?.behavior
        return verdict(pkg, text, promise, ConfirmedPromiseBinder.bind(draft), rule = rule)
    }

    private fun clock(expected: Boolean, actual: Boolean, label: String): Pair<Outcome, String> =
        (if (actual == expected) Outcome.ALLOW else Outcome.BLOCK) to "$label=$actual"

    private fun rows(): List<Row> = listOf(
        // Video: short vs long, Home is not playback, no invented duration.
        Row("video", "yt_player_12m_under_min40", Outcome.BLOCK) {
            verdict(yt, "Hide player controls Time duration 12 minutes Subscribe", min40Goal, min40)
        },
        Row("video", "yt_player_1h05_over_min40", Outcome.ALLOW) {
            verdict(yt, "Hide player controls Time duration 1 hour 5 minutes Subscribe", min40Goal, min40)
        },
        Row("video", "yt_home_is_not_playback", Outcome.ALLOW) { verdict(yt, ytHome, min40Goal, min40) },
        Row("video", "yt_shorts_player_under_min40", Outcome.BLOCK) { verdict(yt, ytShortsPlayer, min40Goal, min40) },
        Row("video", "yt_player_without_clock_waits", Outcome.WAIT) {
            verdict(yt, "Hide player controls Subscribe Like Share Comments", min40Goal, min40)
        },
        // Browser.
        Row("browser", "chrome_yt_shorts_under_min40", Outcome.BLOCK) {
            verdict(chrome, "m.youtube.com/shorts Back Search Like Share #motivation", min40Goal, min40)
        },
        Row("browser", "chrome_yt_watch_31m33_under_min40", Outcome.BLOCK) {
            verdict(chrome, "Time elapsed 1 secondTime duration 31 minutes, 33 seconds", min40Goal, min40)
        },
        Row("browser", "chrome_article_is_not_video", Outcome.ALLOW) {
            verdict(chrome, "en.wikipedia.org Linear algebra From Wikipedia, the free encyclopedia", min40Goal, min40)
        },
        Row("browser", "chrome_adult_guardrail_no_session", Outcome.BLOCK) {
            verdict(chrome, "pornhub.com free porn videos", min40Goal, min40, session = null, guardrails = listOf(adultGuardrail))
        },
        // Social: only supported pairs (short-form surface vs rest of app).
        Row("social", "ig_reels_blocked_under_reels_promise", Outcome.BLOCK) {
            promised("Block Instagram reels for 1 hour", ig, igReels)
        },
        Row("social", "ig_dms_allowed_under_reels_promise", Outcome.ALLOW) {
            promised("Block Instagram reels for 1 hour", ig, igDms)
        },
        Row("social", "ig_feed_allowed_under_reels_promise", Outcome.ALLOW) {
            promised("Block Instagram reels for 1 hour", ig, igFeed)
        },
        Row("social", "ig_feed_but_allow_dms_refused_offline", Outcome.REFUSE_AT_CONFIRM) {
            promised("Block Instagram feed but allow DMs for 1 hour", ig, igDms)
        },
        // Scope / identity.
        Row("scope", "app_only_scope_leaves_chrome_alone", Outcome.ALLOW) {
            verdict(chrome, "Time elapsed 1 secondTime duration 12 minutes", "YouTube app only: only allow videos longer than 40 min", appOnly40)
        },
        Row("scope", "app_only_scope_enforces_in_app", Outcome.BLOCK) {
            verdict(yt, "Hide player controls Time duration 12 minutes Subscribe", "YouTube app only: only allow videos longer than 40 min", appOnly40)
        },
        Row("scope", "corrupt_typed_scope_never_uses_legacy_mirror", Outcome.ALLOW) {
            val (o, why) = verdict(
                chrome,
                "m.youtube.com/watch?v=abc Hide player controls 1:02 / 12:40",
                min40Goal,
                min40.copy(typedScopeCorruptDetail = "bad_json")
            )
            (if (why.endsWith(EnforcementReasonCodes.SCOPE_PERSISTENCE_CORRUPT)) o else Outcome.BLOCK) to why
        },
        Row("scope", "shorts_only_unknown_screen_waits", Outcome.WAIT) {
            val settings = ConfirmedPromiseBinder.bind(FocusPromiseParser().parse("block youtube shorts for 1 hour"))
            verdict(yt, "Loading", "block youtube shorts for 1 hour", settings)
        },
        // Time semantics.
        Row("time", "session_deadline_minus_1ms_still_running", Outcome.ALLOW) {
            clock(false, SessionExpiryPolicy.isExpired(session(deadline = 60_000L), 59_999L), "expired")
        },
        Row("time", "session_deadline_exact_ends", Outcome.ALLOW) {
            clock(true, SessionExpiryPolicy.isExpired(session(deadline = 60_000L), 60_000L), "expired")
        },
        Row("time", "daily_quota_runs_to_local_midnight", Outcome.ALLOW) {
            val zone = ZoneId.of("Asia/Kolkata")
            val now = LocalDateTime.of(2026, 9, 27, 23, 30).atZone(zone).toInstant().toEpochMilli()
            val midnight = LocalDateTime.of(2026, 9, 28, 0, 0).atZone(zone).toInstant().toEpochMilli()
            val s = session(deadline = now + 30 * 60_000L - 1).copy(startTimeMillis = now)
            clock(true, SessionExpiryPolicy.effectiveDeadlineMillis(s, now, 10, zone) == midnight, "endsAtMidnight")
        },
        Row("time", "quota_counter_resets_at_midnight", Outcome.ALLOW) {
            val zone = ZoneId.of("Asia/Kolkata")
            val gate = ShortFormQuotaGate(zoneId = zone)
            val before = LocalDateTime.of(2026, 9, 27, 23, 59, 59).atZone(zone).toInstant().toEpochMilli()
            clock(true, gate.todayKey(before) != gate.todayKey(before + 1_000L), "newDayKey")
        },
        // Quota.
        Row("quota", "shorts_quota_used_up_blocks", Outcome.BLOCK) {
            val goal = "I want to watch at most 10 shorts today"
            verdict(yt, ytShortsPlayer, goal, quota10(), quotaUsed = 10)
        },
        Row("quota", "shorts_quota_remaining_allows", Outcome.ALLOW) {
            val goal = "I want to watch at most 10 shorts today"
            verdict(yt, ytShortsPlayer, goal, quota10(), quotaUsed = 3)
        },
        // Safety / lifecycle.
        Row("safety", "dialer_blocked_rule_locked_session", Outcome.ALLOW) {
            verdict("com.android.dialer", "Phone Keypad Recents Contacts", "block everything", min40, session = SessionStatus.LOCKED, rule = AppRuleBehavior.BLOCK)
        },
        Row("safety", "emergency_app_always_allowed", Outcome.ALLOW) {
            verdict("com.android.emergency", "Emergency information", "block everything", min40, rule = AppRuleBehavior.BLOCK)
        },
        Row("safety", "locked_session_leaves_whatsapp_usable", Outcome.ALLOW) {
            verdict("com.whatsapp", "WhatsApp Chats Updates Calls", min40Goal, min40, session = SessionStatus.LOCKED)
        },
        Row("safety", "no_session_enforces_nothing", Outcome.ALLOW) {
            verdict(yt, "Hide player controls Time duration 12 minutes", min40Goal, min40, session = null)
        },
        // Messy English / Hinglish / negation through the real local parser.
        Row("messy", "negation_dont_block_youtube_just_shorts_home", Outcome.ALLOW, probe = true) {
            promised("don't block youtube, just shorts for 1 hour", yt, ytHome)
        },
        Row("messy", "negation_dont_block_youtube_just_shorts_player", Outcome.BLOCK, probe = true) {
            promised("don't block youtube, just shorts for 1 hour", yt, ytShortsPlayer)
        },
        Row("messy", "hinglish_shorts_band_karo", Outcome.BLOCK, probe = true) {
            promised("youtube shorts band karo 1 ghante ke liye", yt, ytShortsPlayer)
        },
        Row("messy", "slang_no_reels_pls", Outcome.BLOCK, probe = true) {
            promised("no reels for 2 hrs pls", ig, igReels)
        },
        Row("messy", "hinglish_min40_long_video_allowed", Outcome.ALLOW, probe = true) {
            promised("sirf 40 min se lamba youtube video chalne do 1 ghante", yt, "Hide player controls Time duration 1 hour 5 minutes Subscribe")
        },
        Row("messy", "hinglish_min40_short_video_blocked", Outcome.BLOCK, probe = true) {
            promised("sirf 40 min se lamba youtube video chalne do 1 ghante", yt, "Hide player controls Time duration 12 minutes Subscribe")
        }
    )

    private fun quota10() = StudyWorldSettings(
        durationMinutes = 1440,
        lockAttemptThreshold = 3,
        blockAttemptCooldownMinutes = 2,
        shortFormDailyQuotaLimit = 10,
        timeWindowKind = "calendar_day"
    )

    private fun session(deadline: Long) = FocusSession(
        id = "s",
        worldId = "study",
        goal = "g",
        startTimeMillis = 0L,
        deadlineMillis = deadline,
        status = SessionStatus.ACTIVE,
        attemptCount = 0,
        strictness = StrictnessLevel.STRICT
    )

    private class FixedLedger(private val used: Int) : ShortFormQuotaGate.Ledger {
        override fun load(dayKey: String) =
            ShortFormQuotaGate.LedgerRead.Counted(used, (1..used).map { "seed$it" }.toSet())
        override fun save(dayKey: String, count: Int, signatures: Set<String>) = Unit
    }

    /** Expected is ALLOW (or confirm refusal expected to allow nothing): a BLOCK here is a false block. */
    private fun isFalseBlock(expected: Outcome, actual: Outcome) =
        actual == Outcome.BLOCK && (expected == Outcome.ALLOW || expected == Outcome.WAIT)

    /** Expected BLOCK/WAIT/refusal but the promise let it through. */
    private fun isFalseAllow(expected: Outcome, actual: Outcome) =
        actual == Outcome.ALLOW && expected != Outcome.ALLOW

    @Test
    fun generalizationTable_reportsFalseAllowsAndFalseBlocksSeparately() {
        val results = rows().map { row -> Triple(row, row.actual(), row.expected) }
        val report = StringBuilder("\nGENERALIZATION TABLE (expected | actual | detail)\n")
        for ((row, actual, expected) in results) {
            val mark = when {
                actual.first == expected -> "ok"
                isFalseAllow(expected, actual.first) -> "FALSE_ALLOW"
                isFalseBlock(expected, actual.first) -> "FALSE_BLOCK"
                actual.first == Outcome.REFUSE_AT_CONFIRM -> "CLARIFIED_INSTEAD"
                else -> "MISMATCH"
            }
            report.append(
                "${if (row.probe) "probe" else "     "} ${row.cls.padEnd(8)} ${row.id.padEnd(48)} " +
                    "${expected.name.padEnd(17)} ${actual.first.name.padEnd(17)} $mark ${actual.second}\n"
            )
        }
        fun count(probe: Boolean, pred: (Outcome, Outcome) -> Boolean) =
            results.count { (row, actual, expected) -> row.probe == probe && pred(expected, actual.first) }
        val supportedFalseAllows = count(false, ::isFalseAllow)
        val supportedFalseBlocks = count(false, ::isFalseBlock)
        val supportedOther = count(false) { e, a -> e != a && !isFalseAllow(e, a) && !isFalseBlock(e, a) }
        report.append(
            "supported rows=${results.count { !it.first.probe }} falseAllows=$supportedFalseAllows " +
                "falseBlocks=$supportedFalseBlocks otherMismatch=$supportedOther\n" +
                "probe rows=${results.count { it.first.probe }} falseAllows=${count(true, ::isFalseAllow)} " +
                "falseBlocks=${count(true, ::isFalseBlock)} " +
                "otherMismatch=${count(true) { e, a -> e != a && !isFalseAllow(e, a) && !isFalseBlock(e, a) }}\n"
        )
        println(report)
        assertEquals(report.toString(), 0, supportedFalseAllows)
        assertEquals(report.toString(), 0, supportedFalseBlocks)
        assertEquals(report.toString(), 0, supportedOther)
    }
}
