package app.spliit.core

/**
 * Where the recent-groups list lives. `:core` owns the shape and every rule about how it changes.
 *
 * Suspending functions rather than a `Flow`, because `suspend` is a compiler feature and a
 * `Flow` would make `kotlinx-coroutines-core` a real dependency of a module that has none.
 *
 * An implementation must be **thin**: serialise, write, read back. It must not merge, stamp a
 * timestamp or expire a tombstone, those are [RecentGroupsSnapshot]'s rules, tested once.
 */
public interface RecentGroupsStore {
    /** The snapshot as currently stored, or [RecentGroupsSnapshot()][RecentGroupsSnapshot] for a
     *  device that has never written one. */
    public suspend fun load(): RecentGroupsSnapshot

    /** Replaces the stored snapshot with [snapshot], in full. */
    /**
     * Persists [snapshot], answering false when it could not be written.
     *
     * That answer matters in one place: a group just created exists nowhere else, since Spliit
     * has no accounts and no server-side list, so a row that fails to store is a group gone for
     * good and the caller has to say so rather than report success.
     */
    public suspend fun save(snapshot: RecentGroupsSnapshot): Boolean
}
