// Root build script. It declares the plugins the subprojects use so Gradle resolves each one
// once, and applies none of them here — the modules decide what they are.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.compose.compiler) apply false
}
