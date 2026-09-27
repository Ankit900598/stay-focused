package com.phonecodex.app.data

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.phonecodex.app.domain.model.StudyWorldSettings
import com.phonecodex.app.domain.promise.BlockedSurfaceCodec
import com.phonecodex.app.domain.scope.StoredScopeCodec

class StudyWorldSettingsStore(private val prefs: SharedPreferences) {

    constructor(context: Context) : this(
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    )

    fun getSettings(): StudyWorldSettings {
        val typedScope = readTypedScope()
        val maxVideoRaw = prefs.getInt(KEY_MAX_VIDEO_LENGTH_BLOCK_MINUTES, SENTINEL_NONE)
        val minVideoRaw = prefs.getInt(KEY_MIN_VIDEO_LENGTH_BLOCK_MINUTES, SENTINEL_NONE)
        val shortQuotaRaw = prefs.getInt(KEY_SHORT_FORM_DAILY_QUOTA, SENTINEL_NONE)
        return StudyWorldSettings(
            durationMinutes = prefs.getInt(
                KEY_DURATION_MINUTES,
                DEFAULT_DURATION_MINUTES
            ),
            lockAttemptThreshold = prefs.getInt(
                KEY_LOCK_ATTEMPT_THRESHOLD,
                DEFAULT_LOCK_ATTEMPT_THRESHOLD
            ),
            blockAttemptCooldownMinutes = prefs.getInt(
                KEY_BLOCK_ATTEMPT_COOLDOWN_MINUTES,
                DEFAULT_BLOCK_ATTEMPT_COOLDOWN_MINUTES
            ),
            maxVideoLengthBlockMinutes = maxVideoRaw.takeIf { it != SENTINEL_NONE },
            minVideoLengthBlockMinutes = minVideoRaw.takeIf { it != SENTINEL_NONE },
            shortFormDailyQuotaLimit = shortQuotaRaw.takeIf { it != SENTINEL_NONE },
            allowLongEducationalVideos = prefs.getBoolean(KEY_ALLOW_LONG_EDU, true),
            timeWindowKind = prefs.getString(KEY_TIME_WINDOW_KIND, null),
            enforcementScopePackages = prefs.getStringSet(KEY_ENFORCEMENT_SCOPE, emptySet())
                ?.filter { it.isNotBlank() }
                ?.distinct()
                .orEmpty(),
            enforcementContentBrands = prefs.getStringSet(KEY_ENFORCEMENT_CONTENT_BRANDS, emptySet())
                ?.filter { it.isNotBlank() }
                ?.distinct()
                .orEmpty(),
            enforcementScopeKind = prefs.getString(KEY_ENFORCEMENT_SCOPE_KIND, null)
                ?.trim()
                ?.takeIf { it.isNotEmpty() },
            enforcementScope = typedScope.scope,
            typedScopeCorruptDetail = typedScope.corruptDetail,
            blockedSurfaces = BlockedSurfaceCodec.decode(prefs.getStringSet(KEY_BLOCKED_SURFACES, emptySet())),
            compilerPromptVersion = prefs.getString(KEY_COMPILER_PROMPT_VERSION, null)
                ?.trim()
                ?.takeIf { it.isNotEmpty() },
            visionExperimentEnabled = if (prefs.contains(KEY_VISION_EXPERIMENT_ENABLED)) {
                prefs.getBoolean(KEY_VISION_EXPERIMENT_ENABLED, false)
            } else {
                false
            }
        )
    }

    /**
     * Write every clock in one commit so Start cannot race leftover 30 / null media rules.
     * Individual setters still exist for advanced UI tweaks.
     */
    fun replaceSettings(settings: StudyWorldSettings) {
        prefs.edit()
            .putInt(KEY_DURATION_MINUTES, settings.durationMinutes.coerceAtLeast(1))
            .putInt(KEY_LOCK_ATTEMPT_THRESHOLD, settings.lockAttemptThreshold)
            .putInt(KEY_BLOCK_ATTEMPT_COOLDOWN_MINUTES, settings.blockAttemptCooldownMinutes)
            .putInt(
                KEY_MAX_VIDEO_LENGTH_BLOCK_MINUTES,
                settings.maxVideoLengthBlockMinutes ?: SENTINEL_NONE
            )
            .putInt(
                KEY_MIN_VIDEO_LENGTH_BLOCK_MINUTES,
                settings.minVideoLengthBlockMinutes ?: SENTINEL_NONE
            )
            .putInt(
                KEY_SHORT_FORM_DAILY_QUOTA,
                settings.shortFormDailyQuotaLimit ?: SENTINEL_NONE
            )
            .putBoolean(KEY_ALLOW_LONG_EDU, settings.allowLongEducationalVideos)
            .putString(KEY_TIME_WINDOW_KIND, settings.timeWindowKind)
            .putStringSet(
                KEY_ENFORCEMENT_SCOPE,
                settings.enforcementScopePackages.map { it.trim() }.filter { it.isNotEmpty() }.toSet()
            )
            .putStringSet(
                KEY_ENFORCEMENT_CONTENT_BRANDS,
                settings.enforcementContentBrands.map { it.trim() }.filter { it.isNotEmpty() }.toSet()
            )
            .putString(KEY_ENFORCEMENT_SCOPE_KIND, settings.enforcementScopeKind)
            // Same commit as the legacy fields: typed scope and its fallback mirror never diverge.
            .putString(KEY_ENFORCEMENT_SCOPE_JSON, StoredScopeCodec.encode(settings.enforcementScope))
            .putStringSet(KEY_BLOCKED_SURFACES, BlockedSurfaceCodec.encode(settings.blockedSurfaces))
            .putString(KEY_COMPILER_PROMPT_VERSION, settings.compilerPromptVersion)
            // Vision experiment is a debug flag, not a promise clock. Do not wipe it here.
            .commit()
    }

    private fun readTypedScope(): StoredScopeCodec.Decoded {
        val decoded = StoredScopeCodec.readRecord(prefs.contains(KEY_ENFORCEMENT_SCOPE_JSON)) {
            prefs.getString(KEY_ENFORCEMENT_SCOPE_JSON, null)
        }
        // getSettings() runs per Accessibility event; log each distinct corruption once.
        if (decoded.corrupt && decoded.corruptDetail != lastLoggedCorruptDetail) {
            Log.w(
                "PhoneCodexScope",
                "${StoredScopeCodec.CORRUPT_REASON} detail=${decoded.corruptDetail} " +
                    "commitment unverified; legacy scope fields NOT substituted"
            )
        }
        lastLoggedCorruptDetail = if (decoded.corrupt) decoded.corruptDetail else null
        return decoded
    }

    fun setDurationMinutes(minutes: Int) {
        prefs.edit()
            .putInt(KEY_DURATION_MINUTES, minutes)
            .apply()
    }

    fun setLockAttemptThreshold(threshold: Int) {
        prefs.edit()
            .putInt(KEY_LOCK_ATTEMPT_THRESHOLD, threshold)
            .apply()
    }

    fun setBlockAttemptCooldownMinutes(minutes: Int) {
        prefs.edit()
            .putInt(KEY_BLOCK_ATTEMPT_COOLDOWN_MINUTES, minutes)
            .apply()
    }

    fun setMaxVideoLengthBlockMinutes(minutes: Int?) {
        prefs.edit()
            .putInt(KEY_MAX_VIDEO_LENGTH_BLOCK_MINUTES, minutes ?: SENTINEL_NONE)
            .apply()
    }

    fun setMinVideoLengthBlockMinutes(minutes: Int?) {
        prefs.edit()
            .putInt(KEY_MIN_VIDEO_LENGTH_BLOCK_MINUTES, minutes ?: SENTINEL_NONE)
            .apply()
    }

    fun setShortFormDailyQuotaLimit(limit: Int?) {
        prefs.edit()
            .putInt(KEY_SHORT_FORM_DAILY_QUOTA, limit ?: SENTINEL_NONE)
            .apply()
    }

    fun setAllowLongEducationalVideos(allow: Boolean) {
        prefs.edit()
            .putBoolean(KEY_ALLOW_LONG_EDU, allow)
            .apply()
    }

    fun setTimeWindowKind(kind: String?) {
        prefs.edit()
            .putString(KEY_TIME_WINDOW_KIND, kind)
            .apply()
    }

    fun setEnforcementScopePackages(packages: List<String>) {
        prefs.edit()
            .putStringSet(
                KEY_ENFORCEMENT_SCOPE,
                packages.map { it.trim() }.filter { it.isNotEmpty() }.toSet()
            )
            .apply()
    }

    fun setVisionExperimentEnabled(enabled: Boolean) {
        prefs.edit()
            .putBoolean(KEY_VISION_EXPERIMENT_ENABLED, enabled)
            .apply()
    }

    companion object {
        @Volatile
        private var lastLoggedCorruptDetail: String? = null
        private const val PREFS_NAME = "phonecodex_study_world_settings"
        private const val KEY_DURATION_MINUTES = "duration_minutes"
        private const val KEY_LOCK_ATTEMPT_THRESHOLD = "lock_attempt_threshold"
        private const val KEY_BLOCK_ATTEMPT_COOLDOWN_MINUTES = "block_attempt_cooldown_minutes"
        private const val KEY_MAX_VIDEO_LENGTH_BLOCK_MINUTES = "max_video_length_block_minutes"
        private const val KEY_MIN_VIDEO_LENGTH_BLOCK_MINUTES = "min_video_length_block_minutes"
        private const val KEY_SHORT_FORM_DAILY_QUOTA = "short_form_daily_quota"
        private const val KEY_ALLOW_LONG_EDU = "allow_long_educational"
        private const val KEY_TIME_WINDOW_KIND = "time_window_kind"
        private const val KEY_ENFORCEMENT_SCOPE = "enforcement_scope_packages"
        private const val KEY_ENFORCEMENT_CONTENT_BRANDS = "enforcement_content_brands"
        private const val KEY_ENFORCEMENT_SCOPE_KIND = "enforcement_scope_kind"
        private const val KEY_ENFORCEMENT_SCOPE_JSON = "enforcement_scope_v1_json"
        private const val KEY_VISION_EXPERIMENT_ENABLED = "vision_experiment_enabled"
        private const val KEY_BLOCKED_SURFACES = "blocked_surfaces_v1"
        private const val KEY_COMPILER_PROMPT_VERSION = "compiler_prompt_version"
        private const val SENTINEL_NONE = -1

        const val DEFAULT_DURATION_MINUTES = 30
        const val DEFAULT_LOCK_ATTEMPT_THRESHOLD = 10
        const val DEFAULT_BLOCK_ATTEMPT_COOLDOWN_MINUTES = 2
        const val TIME_WINDOW_CALENDAR_DAY = "calendar_day"
        const val TIME_WINDOW_SESSION_FIXED = "session_fixed"
    }
}
