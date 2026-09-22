package com.phonecodex.app.accessibility

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.phonecodex.app.domain.ProductBrand
import com.phonecodex.app.domain.enforcement.SessionEnforcementCopy
import com.phonecodex.app.domain.model.DecisionType
import com.phonecodex.app.domain.model.StrictnessLevel

/**
 * View-based WindowManager overlay for WARN / BLOCK / LOCK.
 *
 * Product voice: "You promised this. I’m helping you keep it."
 * Anatomy: mode chip → promise → evidence → strictness → attempts/cooldown → actions.
 * Technical metadata stays out of this surface (Decision Inspector only).
 */
object CommitmentOverlayCopy {

    private val PACKAGE_NAME_REGEX =
        Regex("""\b[a-z][a-z0-9_]*(?:\.[a-z][a-z0-9_]*){2,}\b""", RegexOption.IGNORE_CASE)
    private val TECHNICAL_META_REGEX = Regex(
        pattern = """(?i)\b(?:confidence|source|deployment|reasonCategory|riskLevel)\s*[:=]\s*\S+"""
    )
    private val GUARDRAIL_TECH_REGEX = Regex(
        pattern = """(?i)permanent\s*guardrail\s*:?\s*.*"""
    )
    private val MATCHED_SIGNAL_TECH_REGEX = Regex(
        pattern = """(?i)matched\s*:\s*(?:strong|weak)\s*:.*"""
    )
    private val UNDER_STRICTNESS_REGEX = Regex(
        pattern = """(?i)\s+under\s+(?:SOFT|SMART|STRICT|LOCKED)\b"""
    )

    fun overlayKind(decision: String): OverlayKind {
        return when (decision.uppercase()) {
            DecisionType.WARN.name, DecisionType.ASK.name -> OverlayKind.WARN
            DecisionType.LOCK.name -> OverlayKind.LOCK
            else -> OverlayKind.BLOCK
        }
    }

    fun decisionBadge(kind: OverlayKind): String = when (kind) {
        OverlayKind.WARN -> "WARN"
        OverlayKind.BLOCK -> "BLOCK"
        OverlayKind.LOCK -> "LOCK"
    }

    fun headline(kind: OverlayKind, reasonCode: String? = null, reason: String? = null): String {
        if (SessionEnforcementCopy.isQuotaReminder(reasonCode)) {
            val remaining = reason?.trim().orEmpty()
            if (remaining.isNotEmpty()) return remaining
        }
        return when (kind) {
            OverlayKind.WARN -> "Drift from your promise"
            OverlayKind.BLOCK -> "Outside your promise"
            OverlayKind.LOCK -> "Your commitment is locked"
        }
    }

    fun companionLine(kind: OverlayKind, reasonCode: String? = null): String {
        if (SessionEnforcementCopy.isQuotaReminder(reasonCode)) {
            return SessionEnforcementCopy.QUOTA_REMINDER_COMPANION
        }
        return when (kind) {
            OverlayKind.WARN -> "You promised this. I’m helping you keep it."
            OverlayKind.BLOCK -> "You promised this. I’m helping you keep it."
            OverlayKind.LOCK -> ProductBrand.LOCK_COMPANION
        }
    }

    fun promiseLine(goal: String?): String? {
        val cleaned = goal?.trim().orEmpty()
        if (cleaned.isEmpty()) return null
        return truncateWords(cleaned, maxWords = 12)
    }

    /** Human evidence for the overlay — never raw tech metadata. */
    fun evidenceLine(decision: String, reason: String): String {
        val cleaned = sanitizeEvidence(reason)
        if (cleaned.isNotEmpty()) return cleaned
        return when (overlayKind(decision)) {
            OverlayKind.WARN -> "This might pull you off your commitment."
            OverlayKind.BLOCK -> "This doesn’t match your current commitment."
            OverlayKind.LOCK -> "Repeated attempts or a strict commitment paused this path."
        }
    }

    /** @deprecated Prefer [evidenceLine]. Kept for call-site clarity during migration. */
    fun shortReason(decision: String, reason: String): String = evidenceLine(decision, reason)

    fun sanitizeEvidence(raw: String): String {
        var text = raw.trim()
        if (text.isEmpty()) return ""
        text = PACKAGE_NAME_REGEX.replace(text, "")
        text = TECHNICAL_META_REGEX.replace(text, "")
        text = GUARDRAIL_TECH_REGEX.replace(text, "")
        text = MATCHED_SIGNAL_TECH_REGEX.replace(text, "")
        text = UNDER_STRICTNESS_REGEX.replace(text, "")
        text = text
            .replace(Regex("""\s{2,}"""), " ")
            .replace(Regex("""\s+([,.;:])"""), "$1")
            .trim()
            .trimStart('-', '—', ':', ',')
            .trimEnd('-', '—', ':', ',')
            .trim()
        return text
    }

    fun strictnessCue(strictness: StrictnessLevel?): String? {
        return when (strictness) {
            StrictnessLevel.SOFT -> "SOFT"
            StrictnessLevel.SMART -> "SMART"
            StrictnessLevel.STRICT -> "STRICT"
            StrictnessLevel.LOCKED -> "LOCKED"
            null -> null
        }
    }

    fun attemptLabel(attemptCount: Int?): String? {
        if (attemptCount == null || attemptCount <= 0) return null
        return if (attemptCount == 1) {
            "1 attempt"
        } else {
            "$attemptCount attempts"
        }
    }

    fun remainingLabel(kind: OverlayKind, remainingMillis: Long?): String? {
        if (remainingMillis == null) return null
        val formatted = formatRemaining(remainingMillis) ?: return null
        return when (kind) {
            OverlayKind.LOCK -> "Lock holds · $formatted"
            else -> formatted
        }
    }

    fun formatRemaining(remainingMillis: Long): String? {
        if (remainingMillis <= 0L) return null
        val totalMinutes = (remainingMillis / 60_000L).toInt()
        val hours = totalMinutes / 60
        val minutes = totalMinutes % 60
        return when {
            hours > 0 -> "${hours}h ${minutes}m left"
            totalMinutes > 0 -> "${totalMinutes}m left"
            else -> "Under a minute left"
        }
    }

    /** Continue is only for WARN under SOFT/SMART — never BLOCK/LOCK or STRICT/LOCKED. */
    fun allowsContinue(
        kind: OverlayKind,
        strictness: StrictnessLevel?,
        forceContinue: Boolean = false
    ): Boolean {
        if (kind != OverlayKind.WARN) return false
        if (forceContinue) return true
        return strictness == StrictnessLevel.SOFT || strictness == StrictnessLevel.SMART
    }

    fun primaryActionLabel(kind: OverlayKind, reasonCode: String? = null): String {
        if (SessionEnforcementCopy.isQuotaReminder(reasonCode)) {
            return SessionEnforcementCopy.QUOTA_KEEP_WATCHING
        }
        return when (kind) {
            OverlayKind.WARN -> "Return to safe path"
            OverlayKind.BLOCK -> "Leave this screen"
            OverlayKind.LOCK -> "Back to safe screen"
        }
    }

    fun continueActionLabel(strictness: StrictnessLevel?): String {
        return when (strictness) {
            StrictnessLevel.SMART -> "Continue · counts as a strike"
            else -> "Continue carefully"
        }
    }

    private fun truncateWords(text: String, maxWords: Int): String {
        val words = text.split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (words.size <= maxWords) return text
        return words.take(maxWords).joinToString(" ") + "…"
    }
}

enum class OverlayKind {
    WARN,
    BLOCK,
    LOCK
}

data class CommitmentOverlayModel(
    val kind: OverlayKind,
    val reason: String,
    val promiseGoal: String? = null,
    val attemptCount: Int? = null,
    val remainingMillis: Long? = null,
    val strictness: StrictnessLevel? = null,
    val reasonCode: String? = null,
    val hidePromiseLine: Boolean = false,
    val forceContinue: Boolean = false
)

data class CommitmentOverlayActions(
    val onBackToSafe: () -> Unit,
    val onGoBack: (() -> Unit)? = null,
    val onOpenPhoneCodex: (() -> Unit)? = null,
    val onContinue: (() -> Unit)? = null
)

class CommitmentOverlayBuilder(private val context: Context) {

    fun build(model: CommitmentOverlayModel, actions: CommitmentOverlayActions): View {
        val density = context.resources.displayMetrics.density
        val dp: (Int) -> Int = { value -> (value * density).toInt() }

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(
                if (model.kind == OverlayKind.WARN) Palette.SCRIM_WARN else Palette.SCRIM
            )
            setPadding(dp(24), dp(48), dp(24), dp(48))
            gravity = Gravity.CENTER
            isClickable = true
            isFocusable = true
            contentDescription = ProductBrand.overlayContentDescription(
                CommitmentOverlayCopy.decisionBadge(model.kind)
            )
        }

        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = roundedRect(Palette.CARD, dp(20).toFloat())
            setPadding(dp(28), dp(28), dp(28), dp(28))
            elevation = if (model.kind == OverlayKind.WARN) 4f * density else 8f * density
        }

        // 1. Mode chip
        val badge = TextView(context).apply {
            text = CommitmentOverlayCopy.decisionBadge(model.kind)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTextColor(badgeTextColor(model.kind))
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            lockReadableType()
            background = roundedRect(badgeFill(model.kind), dp(999).toFloat())
            setPadding(dp(12), dp(6), dp(12), dp(6))
            gravity = Gravity.CENTER
            contentDescription = "Mode ${CommitmentOverlayCopy.decisionBadge(model.kind)}"
        }
        val badgeWrap = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.START
            addView(badge)
        }
        card.addView(badgeWrap)

        // Headline (mode-specific, contract tone — not brand-as-judge)
        card.addView(
            TextView(context).apply {
                text = CommitmentOverlayCopy.headline(model.kind, model.reasonCode, model.reason)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
                setTextColor(Palette.TEXT_PRIMARY)
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                lockReadableType()
                setPadding(0, dp(18), 0, 0)
            }
        )

        // Soft product voice
        card.addView(
            TextView(context).apply {
                text = CommitmentOverlayCopy.companionLine(model.kind, model.reasonCode)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                setTextColor(Palette.TEXT_MUTED)
                lockReadableType()
                setPadding(0, dp(8), 0, 0)
                setLineSpacing(0f, 1.25f)
            }
        )

        // 2. Promise line — hidden when we cannot prove a length violation
        val promise = if (model.hidePromiseLine) {
            null
        } else {
            CommitmentOverlayCopy.promiseLine(model.promiseGoal)
        }
        if (promise != null) {
            card.addView(metaLabel("Your promise", dp, top = 18))
            card.addView(
                TextView(context).apply {
                    text = promise
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                    setTextColor(Palette.CLAY)
                    lockReadableType()
                    setPadding(0, dp(4), 0, 0)
                    setLineSpacing(0f, 1.25f)
                }
            )
        }

        // 3. Evidence / reason line — quota reminder already uses the count as headline
        if (!SessionEnforcementCopy.isQuotaReminder(model.reasonCode)) {
            card.addView(metaLabel("Why this screen", dp, top = 16))
            card.addView(
                TextView(context).apply {
                    text = model.reason
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                    setTextColor(Palette.TEXT_PRIMARY)
                    lockReadableType()
                    setPadding(0, dp(4), 0, 0)
                    setLineSpacing(0f, 1.3f)
                }
            )
        }

        // 4–5. Strictness cue + attempt / cooldown (quiet meta row)
        val metaBits = mutableListOf<String>()
        CommitmentOverlayCopy.strictnessCue(model.strictness)?.let { metaBits.add(it) }
        CommitmentOverlayCopy.attemptLabel(model.attemptCount)?.let { metaBits.add(it) }
        CommitmentOverlayCopy.remainingLabel(model.kind, model.remainingMillis)?.let {
            metaBits.add(it)
        }
        if (metaBits.isNotEmpty()) {
            card.addView(
                TextView(context).apply {
                    text = metaBits.joinToString(" · ")
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                    setTextColor(Palette.TEXT_MUTED)
                    lockReadableType()
                    setPadding(0, dp(14), 0, 0)
                }
            )
        }

        card.addView(spacer(dp(22)))

        // Actions — mode fidelity
        when (model.kind) {
            OverlayKind.WARN -> {
                val quotaReminder = SessionEnforcementCopy.isQuotaReminder(model.reasonCode)
                val primaryAction = if (quotaReminder && actions.onContinue != null) {
                    actions.onContinue
                } else {
                    actions.onBackToSafe
                }
                card.addView(
                    primaryButton(
                        CommitmentOverlayCopy.primaryActionLabel(
                            OverlayKind.WARN,
                            model.reasonCode
                        ),
                        primaryAction,
                        dp
                    )
                )
                if (!quotaReminder) {
                    actions.onGoBack?.let { goBack ->
                        card.addView(spacer(dp(10)))
                        card.addView(secondaryButton("Go back", goBack, dp))
                    }
                    if (
                        CommitmentOverlayCopy.allowsContinue(
                            model.kind,
                            model.strictness,
                            model.forceContinue
                        )
                    ) {
                        actions.onContinue?.let { continueAction ->
                            card.addView(spacer(dp(10)))
                            card.addView(
                                quietButton(
                                    CommitmentOverlayCopy.continueActionLabel(model.strictness),
                                    continueAction,
                                    dp
                                )
                            )
                        }
                    }
                }
            }
            OverlayKind.BLOCK -> {
                card.addView(
                    primaryButton(
                        CommitmentOverlayCopy.primaryActionLabel(OverlayKind.BLOCK),
                        actions.onBackToSafe,
                        dp
                    )
                )
                actions.onGoBack?.let { goBack ->
                    card.addView(spacer(dp(10)))
                    card.addView(secondaryButton("Go back", goBack, dp))
                }
                actions.onOpenPhoneCodex?.let { openApp ->
                    card.addView(spacer(dp(10)))
                    card.addView(secondaryButton(ProductBrand.OPEN_APP, openApp, dp))
                }
            }
            OverlayKind.LOCK -> {
                // No Continue — lock has no easy bypass.
                card.addView(
                    primaryButton(
                        CommitmentOverlayCopy.primaryActionLabel(OverlayKind.LOCK),
                        actions.onBackToSafe,
                        dp
                    )
                )
                actions.onOpenPhoneCodex?.let { openApp ->
                    card.addView(spacer(dp(10)))
                    card.addView(secondaryButton(ProductBrand.OPEN_APP, openApp, dp))
                }
            }
        }

        val scroll = ScrollView(context).apply {
            isFillViewport = true
            addView(
                LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER
                    addView(
                        card,
                        LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT
                        )
                    )
                }
            )
        }

        root.addView(
            scroll,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT
            )
        )
        return root
    }

    private fun metaLabel(
        text: String,
        dp: (Int) -> Int,
        top: Int = 16
    ): TextView {
        return TextView(context).apply {
            this.text = text
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTextColor(Palette.TEXT_MUTED)
            lockReadableType()
            setPadding(0, dp(top), 0, 0)
        }
    }

    private fun spacer(heightPx: Int): View {
        return View(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                heightPx
            )
        }
    }

    private fun primaryButton(label: String, onClick: () -> Unit, dp: (Int) -> Int): Button {
        return Button(context).apply {
            text = label
            isAllCaps = false
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setTextColor(Palette.ON_CLAY)
            lockReadableType()
            background = roundedRect(Palette.CLAY, dp(14).toFloat())
            setPadding(dp(16), dp(14), dp(16), dp(14))
            contentDescription = label
            setOnClickListener { onClick() }
        }
    }

    private fun secondaryButton(label: String, onClick: () -> Unit, dp: (Int) -> Int): Button {
        return Button(context).apply {
            text = label
            isAllCaps = false
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setTextColor(Palette.TEXT_PRIMARY)
            lockReadableType()
            background = roundedRect(Palette.SURFACE_RAISED, dp(14).toFloat(), Palette.OUTLINE)
            setPadding(dp(16), dp(12), dp(16), dp(12))
            contentDescription = label
            setOnClickListener { onClick() }
        }
    }

    private fun quietButton(label: String, onClick: () -> Unit, dp: (Int) -> Int): Button {
        return Button(context).apply {
            text = label
            isAllCaps = false
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTextColor(Palette.TEXT_MUTED)
            lockReadableType()
            background = roundedRect(Color.TRANSPARENT, dp(14).toFloat())
            setPadding(dp(16), dp(10), dp(16), dp(10))
            contentDescription = label
            setOnClickListener { onClick() }
        }
    }

    /**
     * Xiaomi HyperOS treats letterSpacing as extra glyph copies and zero-width spaces.
     * Tracking must stay 0 on this overlay or copy becomes "Outsiddeyourpromisee".
     */
    private fun TextView.lockReadableType() {
        letterSpacing = 0f
        includeFontPadding = false
    }

    private fun badgeFill(kind: OverlayKind): Int = when (kind) {
        OverlayKind.WARN -> Palette.CLAY_SOFT
        OverlayKind.BLOCK -> Palette.SURFACE_RAISED
        OverlayKind.LOCK -> Palette.EMBER_SOFT
    }

    private fun badgeTextColor(kind: OverlayKind): Int = when (kind) {
        OverlayKind.WARN -> Palette.CLAY
        OverlayKind.BLOCK -> Palette.TEXT_PRIMARY
        OverlayKind.LOCK -> Palette.EMBER
    }

    private fun roundedRect(
        fill: Int,
        radiusPx: Float,
        stroke: Int? = null
    ): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radiusPx
            setColor(fill)
            if (stroke != null) {
                setStroke((1.5f * context.resources.displayMetrics.density).toInt(), stroke)
            }
        }
    }

    /** Soft dark / warm clay tokens mirrored from Compose theme (View overlay). */
    private object Palette {
        val SCRIM = Color.parseColor("#F20C0C10")
        val SCRIM_WARN = Color.parseColor("#CC0C0C10")
        val CARD = Color.parseColor("#FF15151B")
        val SURFACE_RAISED = Color.parseColor("#FF1D1D25")
        val OUTLINE = Color.parseColor("#FF2B2B35")
        val TEXT_PRIMARY = Color.parseColor("#FFEDEAE3")
        val TEXT_MUTED = Color.parseColor("#FF9A968D")
        val CLAY = Color.parseColor("#FFD9825C")
        val CLAY_SOFT = Color.parseColor("#FF3A2016")
        val ON_CLAY = Color.parseColor("#FF2A140A")
        val EMBER = Color.parseColor("#FFE0685C")
        val EMBER_SOFT = Color.parseColor("#FF331614")
    }
}
