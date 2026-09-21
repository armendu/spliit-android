// The Android app: Compose M3 screens, ViewModels, DataStore, resources.
//
// Everything Android lives here. :api and :core are plain JVM libraries and stay that way; this
// module is the only one that knows what a Context is.
// AGP 9 brings its own Kotlin support and REJECTS the `org.jetbrains.kotlin.android` plugin —
// applying it is a hard error, not a warning. That is why this module's plugin list is shorter
// than :api's and :core's, which are plain JVM and still need `kotlin-jvm`.
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
    // Only for the recent-groups DataStore adapter (Part 8) — see
    // data/DataStoreRecentGroupsStore.kt for why JSON is the on-disk shape.
    alias(libs.plugins.kotlin.serialization)
}

/**
 * A signing variable that must be present and non-empty, or the build stops and says which one.
 *
 * Blank is treated as missing for the same reason as `SPLIIT_KEYSTORE_PATH` below: CI sets every
 * one of these to "" when no keystore is configured, so "present" is not the same question as
 * "has a value".
 */
private fun Project.required(name: String): String =
    requireNotNull(providers.environmentVariable(name).orNull?.ifBlank { null }) {
        "$name is empty or unset, but SPLIIT_KEYSTORE_PATH is set. Signing needs all four."
    }

android {
    namespace = "app.spliit.android"

    // Android now ships minor platform versions, and the installed platform is android-37.1 —
    // there is no bare android-37. AGP 9 splits that across two properties: the API level and
    // its minor. See CLAUDE.md, "Things that will bite you".
    compileSdk = libs.versions.compileSdk.get().toInt()
    compileSdkMinor = libs.versions.compileSdkMinor.get().toInt()

    defaultConfig {
        applicationId = "app.spliit.android"
        // The iOS bundle ID is not reused: there is no Play listing to update in place.
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        // What `connectedDebugAndroidTest` launches the instrumented suite with.
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // Both from the version catalogue, like every other version in this build — see
        // CLAUDE.md. The release workflow reads the same two lines to check the git tag agrees.
        versionCode = libs.versions.appVersionCode.get().toInt()
        versionName = libs.versions.appVersionName.get()

        // Where a link that names no server — a bare group ID — is looked up, and what the "add
        // by link" placeholder is built from. `spliit.baseUrl` is the property CLAUDE.md reserves
        // for a test-only base URL and the one `make e2e` already passes, so pointing a build at
        // a throwaway instance needs no second spelling. Settings (Part 13) makes this a stored
        // preference; until then the default below is spliit.app, exactly as it was.
        val defaultInstance = (providers.gradleProperty("spliit.baseUrl").orNull ?: "https://spliit.app/")
            .let { if (it.endsWith("/")) it else "$it/" }
        buildConfigField("String", "DEFAULT_INSTANCE_BASE_URL", "\"$defaultInstance\"")
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    // How a downloadable APK gets signed.
    //
    // Android will not install an unsigned APK, so a release build with no signing config is a
    // file nobody can use. Two ways in, and which one applies is decided by whether the
    // environment carries a keystore:
    //
    //   * **A real key**, from the four SPLIIT_KEYSTORE_* variables. The release workflow sets
    //     them from repository secrets. This is what a build anyone is expected to *upgrade*
    //     rather than reinstall has to use, because Android identifies an app by its signature:
    //     two APKs signed by different keys are different apps as far as the installer is
    //     concerned, however identical their contents.
    //
    //   * **The debug key**, when those are absent. Good enough to sideload and try, and it
    //     means `make apk` produces something installable on a fresh clone with no setup at
    //     all. Not good enough to ship from twice: the debug keystore is generated per machine
    //     (and per CI run), so consecutive releases signed this way cannot replace each other.
    //     The release workflow marks such a build clearly rather than passing it off.
    //
    // The keystore is read from a path, never checked in. `signingConfigs` is deliberately not
    // populated when the variables are missing, so a typo in one of them fails the build instead
    // of quietly falling back to a debug key on a release everyone assumed was properly signed.
    //
    // **`ifBlank` is not decoration.** An environment variable that is *set to the empty string*
    // is not an absent one: `orNull` hands back "", the branch below is taken, and `file("")`
    // fails the build with "Cannot convert '' to File". GitHub Actions cannot conditionally omit
    // a key from an `env:` block, so the release workflow sets these to "" whenever no keystore
    // secret is configured — which is the default state of a fresh fork, and which is exactly
    // how the first real release build broke. Tested locally by *unsetting* the variable, which
    // is the one case that was never going to reproduce it.
    val keystorePath = providers.environmentVariable("SPLIIT_KEYSTORE_PATH").orNull?.ifBlank { null }
    if (keystorePath != null) {
        signingConfigs.create("release") {
            storeFile = file(keystorePath)
            // `required` rather than `.get()`: the failure names the variable, which is what
            // makes a half-configured keystore diagnosable instead of a stack trace.
            storePassword = required("SPLIIT_KEYSTORE_PASSWORD")
            keyAlias = required("SPLIIT_KEY_ALIAS")
            keyPassword = required("SPLIIT_KEY_PASSWORD")
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")

            // R8 is off for 0.1.0, on purpose rather than by oversight. Compose, kotlinx
            // -serialization and the reflection-free DTOs here would each need their own keep
            // rules, and an APK that shrinks correctly in every screen is something to verify
            // with the instrumented suite on a device — not to switch on in the same change that
            // first makes releases downloadable. The APK is a few megabytes either way.
            isMinifyEnabled = false
            isShrinkResources = false
        }
    }


    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    kotlin {
        jvmToolchain(21)
    }

    // :api and :core run their tests on JUnit 6 / the JUnit Platform (see their own
    // build.gradle.kts); this module's unit tests — the design-system logic that needs no
    // Android framework class, such as MonogramPalette's hash or DateBucketText's mapping — use
    // the same runner rather than defaulting to AGP's bundled JUnit 4, so one `useJUnitPlatform`
    // convention covers all three modules instead of two.
    testOptions {
        unitTests.all { it.useJUnitPlatform() }
    }
}

dependencies {
    implementation(project(":api"))
    implementation(project(":core"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    // viewModelScope, used by Part 10's ViewModels (GroupsListViewModel and friends) to launch
    // their suspend work off the composable that owns them.
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.serialization.json)
    // DataStore's `data` is a Flow; this is what lets the store call `.first()` on it rather than
    // reaching for a transitive version of the library that happened to come along with DataStore.
    implementation(libs.kotlinx.coroutines.core)

    // The BOM decides every Compose version below it, so the artifacts are listed without one.
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    debugImplementation(libs.androidx.compose.ui.tooling)

    // Design-system unit tests (MonogramPalette, MoneySign, DateBucketText) plus the ViewModel,
    // presentation and URL-parsing suites — none of these need Android or a device, only a
    // runner. `make test` runs them alongside :api and :core.
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotlinx.coroutines.test)
    // A real HTTP server on a loopback port for the ViewModel tests to point a TrpcClient at,
    // rather than hand-rolling a fake transport — the same reasoning :api's own tests follow.
    testImplementation(libs.okhttp.mockwebserver)
    testRuntimeOnly(libs.junit.platform.launcher)

    // The instrumented suite. On a device, so JUnit4 and AndroidJUnitRunner — `useJUnitPlatform`
    // above applies to unit tests only, and Compose's test rules are JUnit4 rules regardless.
    // The BOM versions the two Compose artifacts, same as the implementation ones.
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    // Supplies the empty Activity that `createAndroidComposeRule` launches into. debug-only
    // because it contributes a manifest entry that has no business in a release APK.
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}

// The same guard :api and :core carry, pointed at AGP's task name. This module's suites ran
// nowhere at all for several parts — not in `make test`, not in CI — and nothing failed, which
// is precisely the failure mode a check on "did anything run" exists to catch.
val verifyTestsRan by tasks.registering {
    val resultsDir = layout.buildDirectory.dir("test-results/testDebugUnitTest")
    val label = project.path
    outputs.upToDateWhen { false }
    doLast {
        val reports = resultsDir.get().asFile
            .listFiles { file -> file.name.startsWith("TEST-") && file.extension == "xml" }
            .orEmpty()
        check(reports.isNotEmpty()) { "$label ran no tests at all — the suite is empty." }
    }
}

// `matching` rather than `named`: AGP registers its variant test tasks after this script is
// evaluated, so asking for the task by name here fails outright with "task not found".
tasks.matching { it.name == "testDebugUnitTest" }.configureEach { finalizedBy(verifyTestsRan) }
