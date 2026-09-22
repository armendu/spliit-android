package app.spliit.api

/**
 * A response body recorded from a real Spliit instance by `make fixtures`.
 *
 * Fixtures are recorded, never written: a hand-written one only proves the decoder agrees with
 * whoever wrote it. Assertions against them avoid the server-generated IDs, which change with
 * every re-record, they assert on titles, amounts and shapes instead.
 */
internal object Fixture {
    fun text(name: String): String =
        checkNotNull(Fixture::class.java.getResource("/fixtures/$name.json")) {
            "No fixture named $name, run `make fixtures` against a running instance."
        }.readText()
}
