package app.spliit.core

// Implementations stay thin: serialise, write, read back. No merging or stamping.
public interface RecentGroupsStore {
    public suspend fun load(): RecentGroupsSnapshot

    public suspend fun save(snapshot: RecentGroupsSnapshot): Boolean
}
