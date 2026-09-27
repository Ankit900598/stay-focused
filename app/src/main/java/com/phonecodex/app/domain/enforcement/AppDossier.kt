package com.phonecodex.app.domain.enforcement

/**
 * Shared-per-package knowledge. Counsel fields never override emergency/utility.
 * [userClaimedClass] is stored for later crowd stats and must not unlock.
 */
data class AppDossier(
    val packageName: String,
    val localClass: AppClass,
    val playClass: AppClass? = null,
    val counselClass: AppClass? = null,
    val visionClass: AppClass? = null,
    val userClaimedClass: AppClass? = null,
    val confidence: Double = 0.0,
    val summary: String = "",
    val source: String = SOURCE_LOCAL,
    val researchedAtMillis: Long = 0L
) {
    fun withCounsel(
        counselClass: AppClass,
        confidence: Double,
        summary: String,
        source: String,
        nowMillis: Long
    ): AppDossier = copy(
        counselClass = counselClass,
        confidence = confidence,
        summary = summary,
        source = source,
        researchedAtMillis = nowMillis
    )

    fun withVision(visionClass: AppClass): AppDossier = copy(visionClass = visionClass)

    fun withUserClaim(userClaimedClass: AppClass): AppDossier =
        copy(userClaimedClass = userClaimedClass)

    companion object {
        const val SOURCE_LOCAL = "local"
        const val SOURCE_PLAY = "play"
        const val SOURCE_AZURE_WEB = "azure_web"
        const val SOURCE_VISION = "vision"
        const val SOURCE_USER = "user"

        fun localOnly(packageName: String, screenText: String): AppDossier {
            val local = AppClassResolver.resolve(packageName, screenText)
            val play = if (PlayListingCategoryParser.isPlayStorePackage(packageName)) {
                PlayListingCategoryParser.infer(screenText)
            } else {
                null
            }
            return AppDossier(
                packageName = packageName,
                localClass = local,
                playClass = play,
                source = if (play != null) SOURCE_PLAY else SOURCE_LOCAL
            )
        }

        /**
         * Persisted counsel / vision / user fields plus classes read from the current screen.
         * A record for a different package is ignored rather than applied.
         */
        fun withLiveScreen(stored: AppDossier?, packageName: String, screenText: String): AppDossier {
            val local = localOnly(packageName, screenText)
            if (stored == null || stored.packageName != packageName) return local
            return stored.copy(
                localClass = local.localClass,
                playClass = local.playClass ?: stored.playClass
            )
        }
    }
}
