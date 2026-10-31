package com.phonecodex.app.domain.classifier

import com.phonecodex.app.domain.model.DecisionType

data class ContentClassification(
    val decision: DecisionType,
    val confidence: Double,
    val reason: String,
    val source: String,
    val reasonCategory: String? = null,
    val wouldEscalate: Boolean = false,
    val backendMeta: ClassificationBackendMeta? = null,
    /** True only when the backend actually sent the frame to the model. */
    val usedImage: Boolean = false,
    /** Vision caption of visible UI. Counsel only. Never a duration. */
    val whatOnScreen: String? = null,
    /** True when the backend could not resolve a result that required a second opinion. */
    val verificationIncomplete: Boolean = false
)
