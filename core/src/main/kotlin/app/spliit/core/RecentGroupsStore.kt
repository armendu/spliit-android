package app.spliit.core

// Implementations stay thin: serialise, write, read back. No merging or stamping.
public interface RecentGroupsStore {
    public suspend fun load(): RecentGroupsSnapshot

    public suspend fun save(snapshot: RecentGroupsSnapshot): Boolean

    // Read and write as one step: a load, a network call and a save would undo whatever landed in between.
    // Null when the write failed.
    public suspend fun update(change: (RecentGroupsSnapshot) -> RecentGroupsSnapshot): RecentGroupsSnapshot? {
        val changed = change(load())
        return changed.takeIf { save(it) }
    }
}
