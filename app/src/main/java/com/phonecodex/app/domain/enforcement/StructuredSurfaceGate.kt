package com.phonecodex.app.domain.enforcement

import com.phonecodex.app.domain.model.StudyWorldSettings
import com.phonecodex.app.domain.promise.BlockedSurfaceCodec

enum class StructuredSurfaceAction {
    NONE,
    BLOCK,
    ALLOW_OUTSIDE_SCOPE,
    WAIT_FOR_EVIDENCE
}

/**
 * Confirmed narrow surface rules ([StudyWorldSettings.blockedSurfaces]). A matching active
 * short-form player is blocked; known other surfaces are outside the promise, and unknown
 * surfaces wait for evidence so later AI counsel cannot broaden the rule. Callers preserve
 * permanent guardrails, explicit package-wide BLOCKs, and deterministic content rules first.
 */
object StructuredSurfaceGate {

    fun evaluate(
        packageName: String,
        settings: StudyWorldSettings,
        detection: SurfaceDetection
    ): StructuredSurfaceAction {
        if (!BlockedSurfaceCodec.blocks(
                settings.blockedSurfaces,
                packageName,
                BlockedSurfaceCodec.SHORT_FORM
            )
        ) {
            return StructuredSurfaceAction.NONE
        }

        return when {
            detection.isShortFormPlay -> StructuredSurfaceAction.BLOCK
            detection.surface == SurfaceDetectionResult.UNKNOWN ->
                StructuredSurfaceAction.WAIT_FOR_EVIDENCE
            else -> StructuredSurfaceAction.ALLOW_OUTSIDE_SCOPE
        }
    }

    fun blocks(
        packageName: String,
        settings: StudyWorldSettings,
        detection: SurfaceDetection
    ): Boolean = evaluate(packageName, settings, detection) == StructuredSurfaceAction.BLOCK
}
