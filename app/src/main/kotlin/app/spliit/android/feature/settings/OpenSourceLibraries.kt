package app.spliit.android.feature.settings

data class OpenSourceLibrary(
    val name: String,
    val author: String,
    val url: String,
    // Maven coordinate prefixes this entry stands for; the test holds the catalogue to them.
    val modules: List<String>,
)

const val APACHE_LICENSE_URL = "https://www.apache.org/licenses/LICENSE-2.0"

val openSourceLibraries = listOf(
    OpenSourceLibrary(
        name = "AndroidX and Jetpack Compose",
        author = "Google",
        url = "https://developer.android.com/jetpack/androidx",
        modules = listOf("androidx."),
    ),
    OpenSourceLibrary(
        name = "Kotlin",
        author = "JetBrains",
        url = "https://kotlinlang.org",
        modules = listOf("org.jetbrains.kotlin:"),
    ),
    OpenSourceLibrary(
        name = "kotlinx.coroutines",
        author = "JetBrains",
        url = "https://github.com/Kotlin/kotlinx.coroutines",
        modules = listOf("org.jetbrains.kotlinx:kotlinx-coroutines-"),
    ),
    OpenSourceLibrary(
        name = "kotlinx.serialization",
        author = "JetBrains",
        url = "https://github.com/Kotlin/kotlinx.serialization",
        modules = listOf("org.jetbrains.kotlinx:kotlinx-serialization-"),
    ),
    OpenSourceLibrary(
        name = "OkHttp and Okio",
        author = "Square",
        url = "https://square.github.io/okhttp/",
        modules = listOf("com.squareup.okhttp3:", "com.squareup.okio:"),
    ),
)
