package com.phonecodex.app.data

import android.content.Context
import com.phonecodex.app.domain.enforcement.AppClass
import com.phonecodex.app.domain.enforcement.AppDossier
import org.json.JSONObject

class AppDossierStore(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(
        PREFS_NAME,
        Context.MODE_PRIVATE
    )

    fun get(packageName: String): AppDossier? {
        val raw = prefs.getString(key(packageName), null) ?: return null
        return decode(raw)
    }

    fun put(dossier: AppDossier) {
        prefs.edit().putString(key(dossier.packageName), encode(dossier)).apply()
    }

    fun merged(packageName: String, screenText: String): AppDossier =
        AppDossier.withLiveScreen(get(packageName), packageName, screenText)

    fun recordUserClaim(packageName: String, claimed: AppClass, screenText: String = "") {
        val current = merged(packageName, screenText)
        put(current.withUserClaim(claimed))
    }

    fun shouldResearch(packageName: String, nowMillis: Long): Boolean {
        val stored = get(packageName) ?: return true
        if (stored.counselClass == null) return true
        return nowMillis - stored.researchedAtMillis >= RESEARCH_TTL_MS
    }

    private fun key(packageName: String): String = "dossier.$packageName"

    private fun encode(dossier: AppDossier): String {
        return JSONObject()
            .put("packageName", dossier.packageName)
            .put("localClass", dossier.localClass.name)
            .put("playClass", dossier.playClass?.name ?: JSONObject.NULL)
            .put("counselClass", dossier.counselClass?.name ?: JSONObject.NULL)
            .put("visionClass", dossier.visionClass?.name ?: JSONObject.NULL)
            .put("userClaimedClass", dossier.userClaimedClass?.name ?: JSONObject.NULL)
            .put("confidence", dossier.confidence)
            .put("summary", dossier.summary)
            .put("source", dossier.source)
            .put("researchedAtMillis", dossier.researchedAtMillis)
            .toString()
    }

    private fun decode(raw: String): AppDossier? {
        return try {
            val json = JSONObject(raw)
            AppDossier(
                packageName = json.getString("packageName"),
                localClass = parseClass(json.optString("localClass")) ?: AppClass.UNKNOWN,
                playClass = parseClass(json.optString("playClass")),
                counselClass = parseClass(json.optString("counselClass")),
                visionClass = parseClass(json.optString("visionClass")),
                userClaimedClass = parseClass(json.optString("userClaimedClass")),
                confidence = json.optDouble("confidence", 0.0),
                summary = json.optString("summary"),
                source = json.optString("source", AppDossier.SOURCE_LOCAL),
                researchedAtMillis = json.optLong("researchedAtMillis")
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun parseClass(raw: String?): AppClass? {
        if (raw.isNullOrBlank() || raw == "null") return null
        return try {
            AppClass.valueOf(raw)
        } catch (_: Exception) {
            null
        }
    }

    companion object {
        private const val PREFS_NAME = "phonecodex_app_dossier"
        const val RESEARCH_TTL_MS = 24L * 60L * 60L * 1000L
    }
}
