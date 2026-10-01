package app.spliit.android.feature.groups

import app.spliit.core.RecentGroupsSnapshot
import app.spliit.core.RecentGroupsStore

internal class FakeRecentGroupsStore(
    private var snapshot: RecentGroupsSnapshot = RecentGroupsSnapshot(),
    private val failSaves: Boolean = false,
) : RecentGroupsStore {
    override suspend fun load(): RecentGroupsSnapshot = snapshot

    override suspend fun save(snapshot: RecentGroupsSnapshot): Boolean {
        if (failSaves) return false
        this.snapshot = snapshot
        return true
    }
}

internal fun okBody(json: String): String = """{"result":{"data":{"json":$json}}}"""
