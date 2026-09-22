package app.spliit.core

/**
 * Where the recent-groups list actually lives. `:core` owns the shape
 * ([RecentGroupsSnapshot]) and every rule about how it changes, the merge, the tombstones, the
 * ordering; this interface is deliberately just enough for `:app` to plug a real disk into that,
 * and nothing more.
 *
 * **Suspending functions, not a `Flow`.** `:core` carries
 * no dependency beyond the Kotlin stdlib, see `core/build.gradle.kts`, and `suspend fun` costs
 * nothing to declare: it is a compiler feature, not a library. A `Flow`-shaped API would pull
 * `kotlinx-coroutines-core` in as a real dependency of this module for what is, on the read side,
 * a single value, this store has no notion of "watch for changes made elsewhere on this device"
 * to justify a stream. `:app` already depends on the coroutines library for its own use and is
 * free to wrap [load] in a `flow { emit(load()) }` if a screen wants one; that decision belongs
 * to whichever screen wants it, not to this interface.
 *
 * An implementation must be **thin**: it serialises a [RecentGroupsSnapshot], writes it, reads it
 * back, and nothing else. In particular, it must not decide *when* to merge, stamp a timestamp,
 * or expire a tombstone, those are [RecentGroupsSnapshot]'s rules, tested once in `:core`, and a
 * store that duplicated any of them would be a second place for the same bug to hide.
 */
public interface RecentGroupsStore {
    /** The snapshot as currently stored, or [RecentGroupsSnapshot()][RecentGroupsSnapshot] for a
     *  device that has never written one. */
    public suspend fun load(): RecentGroupsSnapshot

    /** Replaces the stored snapshot with [snapshot], in full. */
    /**
     * Persists [snapshot], answering false when it could not be written.
     *
     * The return value matters in exactly one place and is ignorable everywhere else: a group
     * just created on the server exists nowhere else, because Spliit has no accounts and no
     * server-side list of what this phone can reach. A row that fails to store there is a group
     * gone for good, so that caller has to say so rather than carry on.
     */
    public suspend fun save(snapshot: RecentGroupsSnapshot): Boolean
}
