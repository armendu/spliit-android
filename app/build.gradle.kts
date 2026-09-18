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
        versionCode = 1
        versionName = "0.1.0"

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

    // Design-system unit tests (MonogramPalette, MoneySign, DateBucketText) plus Part 10's
    // ViewModel and URL-parsing tests — none of these need Android or a device, only a runner.
    // `make test` does not run this module (it only builds :api and :core, on purpose — see
    // CLAUDE.md); `:app:testDebugUnitTest` does.
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotlinx.coroutines.test)
    // A real HTTP server on a loopback port for the ViewModel tests to point a TrpcClient at,
    // rather than hand-rolling a fake transport — the same reasoning :api's own tests follow.
    testImplementation(libs.okhttp.mockwebserver)
    testRuntimeOnly(libs.junit.platform.launcher)
}
