pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

// Android Studio's bundled JBR moved to 25, and this project compiles against 21 (CLAUDE.md
// explains why). Rather than let the toolchain drift with whatever JDK happens to be installed,
// this lets Gradle fetch the one the build asks for. CI already provides 21, so it downloads
// nothing there. `pluginManagement` has to come first, so this block sits below it.
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "spliit-android"

include(":api")
include(":core")
include(":app")
