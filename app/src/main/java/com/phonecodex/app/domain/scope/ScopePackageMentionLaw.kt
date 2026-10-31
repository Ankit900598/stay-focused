package com.phonecodex.app.domain.scope

internal object ScopePackageMentionLaw {

    private val labelsByPackage = mapOf(
        "com.google.android.youtube" to "YouTube",
        "org.schabi.newpipe" to "NewPipe",
        "app.netmirror.newtv" to "NetMirror",
        "com.instagram.android" to "Instagram",
        "com.facebook.katana" to "Facebook",
        "com.facebook.orca" to "Messenger",
        "com.whatsapp" to "WhatsApp",
        "com.android.chrome" to "Chrome",
        "org.telegram.messenger" to "Telegram",
        "com.google.android.gm" to "Gmail",
        "com.google.android.apps.drive" to "Google Drive",
        "com.google.android.apps.docs" to "Google Docs",
        "com.zhiliaoapp.musically" to "TikTok",
        "com.twitter.android" to "X (Twitter)",
        "com.netflix.mediaclient" to "Netflix",
        "com.reddit.frontpage" to "Reddit",
        "com.discord" to "Discord",
        "com.spotify.music" to "Spotify",
        "com.snapchat.android" to "Snapchat",
        "com.android.vending" to "Play Store"
    )

    private val aliasesByPackage = mapOf(
        "com.google.android.youtube" to listOf("youtube", "you tube", "yt"),
        "org.schabi.newpipe" to listOf("newpipe"),
        "app.netmirror.newtv" to listOf("netmirror", "newtv"),
        "com.instagram.android" to listOf("instagram", "insta", "ig"),
        "com.facebook.katana" to listOf("facebook", "fb"),
        "com.facebook.orca" to listOf("messenger"),
        "com.whatsapp" to listOf("whatsapp", "whats app"),
        "com.android.chrome" to listOf("chrome"),
        "org.telegram.messenger" to listOf("telegram"),
        "com.google.android.gm" to listOf("gmail", "google mail"),
        "com.google.android.apps.drive" to listOf("google drive"),
        "com.google.android.apps.docs" to listOf("google docs"),
        "com.zhiliaoapp.musically" to listOf("tiktok", "tik tok"),
        "com.twitter.android" to listOf("twitter"),
        "com.netflix.mediaclient" to listOf("netflix"),
        "com.reddit.frontpage" to listOf("reddit"),
        "com.discord" to listOf("discord"),
        "com.spotify.music" to listOf("spotify"),
        "com.snapchat.android" to listOf("snapchat"),
        "com.android.vending" to listOf("play store", "google play")
    )

    fun wasNamedIn(packageName: String, promiseText: String): Boolean {
        val aliases = aliasesByPackage[packageName] ?: return false
        return aliases.any { alias ->
            Regex(
                "(?<![\\p{L}\\p{N}])${Regex.escape(alias)}(?![\\p{L}\\p{N}])",
                RegexOption.IGNORE_CASE
            ).containsMatchIn(promiseText)
        }
    }

    fun displayLabels(packageNames: Iterable<String>): List<String> =
        packageNames.mapNotNull(labelsByPackage::get).distinct()
}
