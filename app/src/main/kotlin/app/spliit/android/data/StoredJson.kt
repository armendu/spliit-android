package app.spliit.android.data

import kotlinx.serialization.json.Json

internal val StoredJson: Json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}
