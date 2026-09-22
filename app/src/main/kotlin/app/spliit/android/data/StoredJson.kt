package app.spliit.android.data

import kotlinx.serialization.json.Json

/**
 * How both stored blobs are encoded.
 *
 * `ignoreUnknownKeys` plus a default on every field is what lets a later version add one without
 * breaking an install that already has a file on disk; `encodeDefaults` is what puts those fields
 * on disk in the first place, so an older build reading a newer file still finds them.
 */
internal val StoredJson: Json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}
