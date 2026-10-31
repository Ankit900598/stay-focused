package com.phonecodex.app.harness

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EmulatorTestHarnessConfigTest {

    private fun readFirstExisting(vararg candidates: String): String =
        candidates.map(::File).first { it.exists() }.readText()

    private val buildScript: String
        get() = readFirstExisting("build.gradle.kts", "app/build.gradle.kts")

    private val emulatorRunner: String
        get() = readFirstExisting(
            "../tools/phone/Invoke-StrlixEmulatorTests.ps1",
            "tools/phone/Invoke-StrlixEmulatorTests.ps1"
        )

    @Test
    fun phoneTestVariantShipsComposeTestManifestSoComponentActivityCanLaunch() {
        assertTrue(
            "phoneTest app must merge ui-test-manifest; without it every createComposeRule() test fails to launch ComponentActivity",
            buildScript.contains("\"phoneTestImplementation\"(libs.androidx.compose.ui.test.manifest)")
        )
        assertTrue(buildScript.contains("debugImplementation(libs.androidx.compose.ui.test.manifest)"))
    }

    @Test
    fun phoneTestDependencyAvoidsUngeneratedTypedAccessor() {
        val unresolvedAccessor = Regex("""(?m)^\s*phoneTestImplementation\(""")
        assertFalse(
            "Build types created in this script get no typed accessor; use the string configuration name",
            unresolvedAccessor.containsMatchIn(buildScript)
        )
    }

    @Test
    fun emulatorRunnerRefusesPhysicalDevicesBeforeAnyInstall() {
        val script = emulatorRunner
        val serialGuard = script.indexOf("-notmatch '^emulator-\\d+$'")
        val qemuGuard = script.indexOf("getprop ro.kernel.qemu")
        val bootGuard = script.indexOf("getprop sys.boot_completed")
        val firstInstall = script.indexOf(" install -r ")
        assertTrue(serialGuard >= 0 && qemuGuard >= 0 && bootGuard >= 0 && firstInstall >= 0)
        assertTrue(serialGuard < qemuGuard && qemuGuard < bootGuard && bootGuard < firstInstall)
        assertFalse("Every adb call must be pinned to the checked serial", Regex("""&\s*\${'$'}adb\s+(install|shell)""").containsMatchIn(script))
    }

    @Test
    fun emulatorRunnerInstallsCredentialFreePhoneTestAppWithDebugTestApk() {
        val script = emulatorRunner
        assertTrue(script.contains("""apk\phoneTest\app-phoneTest.apk"""))
        assertTrue(script.contains("""apk\androidTest\debug\app-debug-androidTest.apk"""))
        assertFalse("Normal Debug app can embed a local backend secret", script.contains("""apk\debug\app-debug.apk"""))
        assertTrue(script.contains(":app:assemblePhoneTest :app:assembleDebugAndroidTest"))
    }
}
