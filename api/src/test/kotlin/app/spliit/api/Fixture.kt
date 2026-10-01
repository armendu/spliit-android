package app.spliit.api

// Recorded by `make fixtures`, never hand-written. Don't assert on server-generated IDs.
internal object Fixture {
    fun text(name: String): String =
        checkNotNull(Fixture::class.java.getResource("/fixtures/$name.json")) {
            "No fixture named $name, run `make fixtures` against a running instance."
        }.readText()
}
