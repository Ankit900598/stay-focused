plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    // Processes app/google-services.json (git-ignored, never commit).
    alias(libs.plugins.google.services)
}

import java.util.Properties
import java.net.URI

val localProps = Properties().apply {
    val localFile = rootProject.file("local.properties")
    if (localFile.exists()) localFile.inputStream().use { load(it) }
}

fun strlixProp(key: String, env: String, fallback: String): String =
    (localProps.getProperty(key) ?: System.getenv(env) ?: fallback).trim()

fun quoted(value: String): String =
    "\"${value.replace("\\", "\\\\").replace("\"", "\\\"")}\""

val backendUrl = strlixProp(
    "strlix.backendUrl",
    "STRLIX_BACKEND_URL",
    "http://localhost:8787"
).trimEnd('/')
val backendSecret = strlixProp("strlix.backendSecret", "STRLIX_BACKEND_APP_SECRET", "")
val googleWebClientId = strlixProp("strlix.googleWebClientId", "STRLIX_GOOGLE_WEB_CLIENT_ID", "")
val mailAssistUrl = strlixProp("strlix.mailAssistUrl", "STRLIX_MAIL_ASSIST_URL", backendUrl).trimEnd('/')
// Non-prod gate for Firebase ID-token auth (Authorization: Bearer). Default OFF. Read ONLY for
// the debug build type; release and phoneTest are hard-wired false below until the owner has
// finished the Firebase checklist in PHONECODEX-P0-AUTH-SCAFFOLD.md.
val useFirebaseIdAuthDebug =
    strlixProp("strlix.useFirebaseIdAuth", "STRLIX_USE_FIREBASE_ID_AUTH", "false").equals("true", ignoreCase = true)

android {
    namespace = "com.phonecodex.app"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.phonecodex.app"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // OAuth *web* client ID (public, not a secret): audience of the Google ID token the
        // backend verifies. Empty = mail helper shows "not set up in this build".
        buildConfigField("String", "BACKEND_BASE_URL", quoted(backendUrl))
        buildConfigField("String", "BACKEND_APP_SECRET", quoted(backendSecret))
        buildConfigField("String", "GOOGLE_WEB_CLIENT_ID", quoted(googleWebClientId))
        buildConfigField("String", "MAIL_ASSIST_URL", quoted(mailAssistUrl))
        buildConfigField("boolean", "STRLIX_USE_FIREBASE_ID_AUTH", "false")
        // Placeholder until Ankit publishes the final policy page; keep in sync with strings.xml.
        buildConfigField("String", "PRIVACY_POLICY_URL", quoted("https://strlix.tech/privacy"))
    }

    buildTypes {
        debug {
            buildConfigField("boolean", "STRLIX_USE_FIREBASE_ID_AUTH", useFirebaseIdAuthDebug.toString())
        }
        release {
            optimization { enable = false }
            // Owner flips this only after the Firebase checklist; release never embeds a shared secret.
            buildConfigField("boolean", "STRLIX_USE_FIREBASE_ID_AUTH", "false")
        }
        create("phoneTest") {
            initWith(getByName("debug"))
            matchingFallbacks += listOf("debug")
            versionNameSuffix = "-phone-test"
            buildConfigField("String", "BACKEND_BASE_URL", quoted("http://localhost:8787"))
            buildConfigField("String", "BACKEND_APP_SECRET", quoted(""))
            buildConfigField("String", "GOOGLE_WEB_CLIENT_ID", quoted(""))
            buildConfigField("String", "MAIL_ASSIST_URL", quoted("http://localhost:8787"))
            buildConfigField("boolean", "STRLIX_USE_FIREBASE_ID_AUTH", "false")
        }
    }
    sourceSets {
        getByName("phoneTest").res.directories.add("src/debug/res")
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

val validateReleaseBackendTransport by tasks.registering {
    inputs.property("backendUrl", backendUrl)
    inputs.property("mailAssistUrl", mailAssistUrl)
    inputs.property("backendCredentialConfigured", backendSecret.isNotBlank())
    doLast {
        check(backendSecret.isBlank()) {
            "Release builds must not embed a shared backend credential. Configure user-bound backend authentication before shipping."
        }
        val endpointInputs = inputs.properties
        val backendUrlValue = endpointInputs["backendUrl"] as String
        val mailAssistUrlValue = endpointInputs["mailAssistUrl"] as String
        val backendUri = runCatching { URI(backendUrlValue) }.getOrNull()
        check(
            backendUri != null &&
                backendUri.scheme.equals("https", ignoreCase = true) &&
                !backendUri.host.isNullOrBlank() &&
                backendUri.rawUserInfo == null &&
                backendUri.rawQuery == null &&
                backendUri.rawFragment == null
        ) {
            "strlix.backendUrl must be an absolute HTTPS base URL without credentials, query, or fragment for release builds."
        }
        val mailUri = runCatching { URI(mailAssistUrlValue) }.getOrNull()
        check(
            mailUri != null &&
                mailUri.scheme.equals("https", ignoreCase = true) &&
                !mailUri.host.isNullOrBlank() &&
                mailUri.rawUserInfo == null &&
                mailUri.rawQuery == null &&
                mailUri.rawFragment == null
        ) {
            "strlix.mailAssistUrl must be an absolute HTTPS base URL without credentials, query, or fragment for release builds."
        }
    }
}

tasks.configureEach {
    if (name == "preReleaseBuild") dependsOn(validateReleaseBackendTransport)
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.compose.material:material-icons-extended")
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    // Delegated Gmail: Sign in with Google (identity) + AuthorizationClient (per-scope consent).
    implementation("androidx.credentials:credentials:1.5.0")
    implementation("androidx.credentials:credentials-play-services-auth:1.5.0")
    implementation("com.google.android.libraries.identity.googleid:googleid:1.1.1")
    implementation("com.google.android.gms:play-services-auth:21.3.0")
    // Firebase Auth (anonymous ID token). FirebaseBackendIdentityTokenProvider reaches the SDK by
    // reflection. Release/phoneTest auth gate (STRLIX_USE_FIREBASE_ID_AUTH) remains hard false.
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.auth)
    testImplementation(libs.junit)
    testImplementation("org.json:json:20240303")
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
    // phoneTest is created in this script, so Gradle generates no typed accessor for it.
    "phoneTestImplementation"(libs.androidx.compose.ui.test.manifest)
}
