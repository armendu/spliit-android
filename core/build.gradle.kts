// Money, currency, split maths, form drafts, date bucketing.
//
// Kotlin/JVM ONLY, like :api, and for the same reason. It also deliberately does NOT depend on
// :api: the arithmetic here is about amounts and participants, not about wire formats, and
// keeping the two independent is what stops a server model from becoming the shape the maths
// is written against. Where both are needed, :app is where they meet.
//
// No main dependencies at all beyond the Kotlin stdlib. minSdk 26 gives us java.time natively
// and java.util.Currency carries the minor-unit table, so there is nothing left to add.
plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(21)

    // Every public declaration in these modules is about to become someone else's API: :app
    // consumes both, and Parts 1-8 grow the real surface. Explicit API mode makes each `public`
    // a decision rather than a default, and requires a declared return type on it — far cheaper
    // to adopt now, while the surface is empty, than to retrofit over a finished module.
    explicitApi()
}

dependencies {
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()

    // Already the Gradle 9 default; pinned so a future change to that default cannot quietly
    // take the check away. It covers the misconfiguration case — test classes present but the
    // engine discovers nothing in them, e.g. the wrong JUnit version or a missing annotation.
    failOnNoDiscoveredTests = true
}

// What `failOnNoDiscoveredTests` cannot see: with no test sources at all the task is NO-SOURCE
// and never runs, so an empty suite is BUILD SUCCESSFUL — the same green as a suite that passed.
// Verified, not assumed: deleting this module's only test file leaves the build passing without
// the check below. The first wiring test was written to guard exactly this and could not, since
// a platform that discovers nothing does not discover the guard either.
val verifyTestsRan by tasks.registering {
    val resultsDir = layout.buildDirectory.dir("test-results/test")
    val label = project.path
    outputs.upToDateWhen { false }
    doLast {
        val reports = resultsDir.get().asFile
            .listFiles { file -> file.name.startsWith("TEST-") && file.extension == "xml" }
            .orEmpty()
        check(reports.isNotEmpty()) { "$label ran no tests at all — the suite is empty." }
    }
}

tasks.test { finalizedBy(verifyTestsRan) }
