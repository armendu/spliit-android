// The tRPC/superjson client: models, endpoints, transport.
//
// Kotlin/JVM ONLY. There is no Android plugin here and there must never be one. That is not
// tidiness, it is what makes `make test` run the protocol handling in seconds on the JVM
// instead of minutes on an emulator, and it is the boundary that stops transport concerns from
// quietly acquiring a Context. If something here seems to need Android, it belongs in :app.
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    jvmToolchain(21)

    // Every public declaration in these modules is about to become someone else's API: :app
    // consumes both, and Parts 1-8 grow the real surface. Explicit API mode makes each `public`
    // a decision rather than a default, and requires a declared return type on it, far cheaper
    // to adopt now, while the surface is empty, than to retrofit over a finished module.
    explicitApi()
}

dependencies {
    // OkHttp stands in for URLSession; kotlinx-serialization for Codable. Nothing else, the
    // iOS repo's "no third-party dependency without a good reason" rule carries over intact.
    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotlinx.coroutines.test)
    // A real HTTP server on a loopback port, so request-building tests assert on bytes that
    // actually went over a socket rather than on our own idea of what we would have sent.
    testImplementation(libs.okhttp.mockwebserver)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform {
        // Live tests need a real Spliit instance (`make e2e-up`) and must never run as a side
        // effect of plain `make test`, which has to stay usable with nothing running. `testLive`
        // below is the only task that includes them.
        excludeTags("live")
    }

    // Already the Gradle 9 default; pinned so a future change to that default cannot quietly
    // take the check away. It covers the misconfiguration case, test classes present but the
    // engine discovers nothing in them, e.g. the wrong JUnit version or a missing annotation.
    failOnNoDiscoveredTests = true
}

// What `failOnNoDiscoveredTests` cannot see: with no test sources at all the task is NO-SOURCE
// and never runs, so an empty suite is BUILD SUCCESSFUL, the same green as a suite that passed.
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
        check(reports.isNotEmpty()) { "$label ran no tests at all, the suite is empty." }
    }
}

tasks.test { finalizedBy(verifyTestsRan) }

// A separate task rather than a flag on `test`, so `make test` can stay the thing that needs
// nothing running. Selects by JUnit5 tag rather than by class name (`*LiveTest`) so a live test
// can live beside the ordinary ones for the same procedure instead of in its own file.
val testLive by tasks.registering(Test::class) {
    group = "verification"
    description = "Tests tagged @Tag(\"live\") against a real Spliit instance."
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnitPlatform {
        includeTags("live")
    }
    failOnNoDiscoveredTests = true
    // The one property name every part of this app reads for a test-only base URL, see
    // CLAUDE.md. Forwarded as a JVM system property because that's what a test process actually
    // sees; a Gradle project property does not otherwise cross into the test JVM.
    systemProperty("spliit.baseUrl", providers.gradleProperty("spliit.baseUrl").getOrElse(""))
    outputs.upToDateWhen { false }
}

val verifyLiveTestsRan by tasks.registering {
    val resultsDir = layout.buildDirectory.dir("test-results/testLive")
    val label = project.path
    outputs.upToDateWhen { false }
    doLast {
        val reports = resultsDir.get().asFile
            .listFiles { file -> file.name.startsWith("TEST-") && file.extension == "xml" }
            .orEmpty()
        check(reports.isNotEmpty()) { "$label ran no live tests at all, the suite is empty." }
    }
}

testLive { finalizedBy(verifyLiveTestsRan) }
