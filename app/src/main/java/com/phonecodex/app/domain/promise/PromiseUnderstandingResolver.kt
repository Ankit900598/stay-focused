package com.phonecodex.app.domain.promise

import com.phonecodex.app.domain.model.FocusPromise

/**
 * Network-first compile with local parser fallback.
 * Pure helper so confirmation wiring stays unit-testable without Android UI.
 */
object PromiseUnderstandingResolver {

    fun resolve(
        text: String,
        compiledResult: Result<CompiledPromiseResponse>,
        parser: FocusPromiseParser = FocusPromiseParser(),
        selectedClarificationOptionId: String? = null,
        namedPackageHint: String? = null,
        fallbackDurationMinutes: Int = FocusPromiseParser.DEFAULT_DURATION_MINUTES
    ): ResolvedPromiseUnderstanding {
        val parseText = CompoundIntentLaw.effectivePromiseText(text, selectedClarificationOptionId)
        val fallbackDuration = fallbackDurationMinutes.coerceAtLeast(1)
        val local = parser.parse(parseText, namedPackageHint, fallbackDuration)
        return compiledResult.fold(
            onSuccess = { compiled ->
                val mapped = CompiledPromiseMapper.toFocusPromise(compiled, fallbackRawText = text)
                val compilerUsedDefaultDuration = compiled.timeWindowKind == "session_fixed_default"
                val useSavedFallback = compilerUsedDefaultDuration && !local.sessionDurationWasExplicit
                val sessionDurationMinutes = when {
                    local.sessionDurationWasExplicit -> local.sessionDurationMinutes
                    useSavedFallback -> fallbackDuration
                    else -> reconcileSessionDurationMinutes(
                        compiledMinutes = mapped.sessionDurationMinutes,
                        localMinutes = local.sessionDurationMinutes
                    )
                }
                val durationWarnings = if (compilerUsedDefaultDuration) {
                    mapped.warnings.filterNot(::isCompilerDefaultDurationWarning)
                } else {
                    mapped.warnings
                }.toMutableList()
                when {
                    useSavedFallback -> durationWarnings +=
                        "No session time was stated; I used your saved default of $fallbackDuration minutes."
                    compilerUsedDefaultDuration && local.sessionDurationWasExplicit &&
                        local.sessionDurationMinutes != mapped.sessionDurationMinutes -> {
                        durationWarnings +=
                            "I used the explicit ${local.sessionDurationMinutes}-minute session time in your promise instead of the compiler's default."
                    }
                }
                ResolvedPromiseUnderstanding(
                    draft = PromiseClarificationLaw.apply(
                        mapped.copy(
                            sessionDurationMinutes = sessionDurationMinutes,
                            sessionDurationWasExplicit = local.sessionDurationWasExplicit,
                            userFacingTime = if (compilerUsedDefaultDuration) null else mapped.userFacingTime,
                            warnings = durationWarnings.distinct()
                        ),
                        selectedClarificationOptionId
                    ),
                    fromCompiler = true
                )
            },
            onFailure = {
                ResolvedPromiseUnderstanding(
                    draft = PromiseClarificationLaw.apply(
                        local.copy(
                            rawText = text,
                            sessionDurationMinutes = local.sessionDurationMinutes,
                            warnings = (local.warnings + CompiledPromiseMapper.LOCAL_FALLBACK_CAUTION)
                                .distinct()
                        ),
                        selectedClarificationOptionId
                    ),
                    fromCompiler = false
                )
            }
        )
    }

    /**
     * Older compiler responses omit the duration-origin field; preserve the historical
     * 30-minute default contract only when local parsing has a different duration.
     */
    internal fun reconcileSessionDurationMinutes(
        compiledMinutes: Int,
        localMinutes: Int
    ): Int {
        if (compiledMinutes == localMinutes) return compiledMinutes.coerceAtLeast(1)
        if (compiledMinutes != FocusPromiseParser.DEFAULT_DURATION_MINUTES) {
            return compiledMinutes.coerceAtLeast(1)
        }
        if (localMinutes <= 0 || localMinutes == FocusPromiseParser.DEFAULT_DURATION_MINUTES) {
            return compiledMinutes.coerceAtLeast(1)
        }
        return localMinutes
    }

    private fun isCompilerDefaultDurationWarning(warning: String): Boolean =
        warning.contains("session duration was unclear", ignoreCase = true) ||
            warning.contains("defaulted to 60 minutes", ignoreCase = true)
}

data class ResolvedPromiseUnderstanding(
    val draft: FocusPromise,
    val fromCompiler: Boolean
)
