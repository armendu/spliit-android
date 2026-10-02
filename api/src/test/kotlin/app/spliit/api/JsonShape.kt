package app.spliit.api

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

// Every field path with its value kind; array indices collapse to `[]`. Empty arrays and nulls
// are data, not schema, so they never count as a difference.
internal object JsonShape {
    // Objects keyed by data (participant IDs). An explicit list: a heuristic could hide a real field.
    private val DICTIONARY_PATHS = setOf("result.data.json.balances")

    internal enum class Kind { OBJECT, ARRAY, STRING, NUMBER, BOOLEAN, NULL, EMPTY_ARRAY }

    internal fun of(element: JsonElement): Map<String, Kind> {
        val out = sortedMapOf<String, Kind>()
        walk("", element, out)
        return out
    }

    private fun walk(path: String, element: JsonElement, out: MutableMap<String, Kind>) {
        when (element) {
            is JsonObject -> {
                out[path] = Kind.OBJECT
                if (path in DICTIONARY_PATHS) {
                    for (value in element.values) walk("$path{}", value, out)
                    return
                }
                for ((key, value) in element) {
                    walk(if (path.isEmpty()) key else "$path.$key", value, out)
                }
            }

            is JsonArray -> {
                if (element.isEmpty()) {
                    out[path] = Kind.EMPTY_ARRAY
                    return
                }
                out[path] = Kind.ARRAY
                for (item in element) walk("$path[]", item, out)
            }

            is JsonNull -> out[path] = Kind.NULL

            is JsonPrimitive -> out[path] = when {
                element.isString -> Kind.STRING
                element.content == "true" || element.content == "false" -> Kind.BOOLEAN
                else -> Kind.NUMBER
            }
        }
    }

    internal data class Difference(val path: String, val describe: String)

    internal fun diff(recorded: Map<String, Kind>, live: Map<String, Kind>): List<Difference> {
        val pruned = pruneUnderEmptyArrays(recorded.withoutTransportMeta(), live.withoutTransportMeta())
        val recordedShape = pruned.first
        val liveShape = pruned.second
        val differences = mutableListOf<Difference>()

        for ((path, kind) in recordedShape) {
            val liveKind = liveShape[path]
            when {
                liveKind == null ->
                    differences += Difference(path, "gone, the fixture has it, the server no longer sends it")
                kind == Kind.NULL || liveKind == Kind.NULL -> Unit
                kind == liveKind -> Unit
                kind.isArray() && liveKind.isArray() -> Unit
                else -> differences += Difference(path, "was $kind, is now $liveKind")
            }
        }
        for (path in liveShape.keys) {
            if (path !in recordedShape) {
                differences += Difference(path, "new, the server sends it, the fixture has never seen it")
            }
        }
        return differences.sortedBy { it.path }
    }

    private fun Kind.isArray(): Boolean = this == Kind.ARRAY || this == Kind.EMPTY_ARRAY

    // Drops superjson's meta: the decoder ignores it, and it's keyed by array index.
    private fun Map<String, Kind>.withoutTransportMeta(): Map<String, Kind> =
        filterKeys { it != "result.data.meta" && !it.startsWith("result.data.meta.") }

    private fun pruneUnderEmptyArrays(
        recorded: Map<String, Kind>,
        live: Map<String, Kind>,
    ): Pair<Map<String, Kind>, Map<String, Kind>> {
        val emptyOnEitherSide = buildSet {
            for ((path, kind) in recorded) if (kind == Kind.EMPTY_ARRAY) add(path)
            for ((path, kind) in live) if (kind == Kind.EMPTY_ARRAY) add(path)
        }
        if (emptyOnEitherSide.isEmpty()) return recorded to live
        fun prune(shape: Map<String, Kind>) = shape.filterKeys { path ->
            emptyOnEitherSide.none { path.startsWith("$it[]") }
        }
        return prune(recorded) to prune(live)
    }

    internal fun report(procedure: String, differences: List<Difference>): String = buildString {
        appendLine("The live server's `$procedure` no longer matches the recorded fixture:")
        appendLine()
        for (difference in differences) appendLine("  ${difference.path}: ${difference.describe}")
        appendLine()
        appendLine("If the change is expected, re-record with `make fixtures` and read the diff")
        appendLine("before committing it, a fixture updated without being read is a contract")
        appendLine("nobody is checking any more.")
    }
}
