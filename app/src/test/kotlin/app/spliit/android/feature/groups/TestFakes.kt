package app.spliit.android.feature.groups

import app.spliit.core.RecentGroupsSnapshot
import app.spliit.core.RecentGroupsStore

/**
 * An in-memory [RecentGroupsStore], shared by the ViewModel tests, which fake the transport with
 * a real [mockwebserver3.MockWebServer] but have no reason to touch a disk.
 *
 * @param failSaves makes [save] report failure, which is what a real store does when the write
 *   cannot land. The paths that create a group have to notice.
 */
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

/** A tRPC success envelope carrying [json] as its payload, see :api's own tests for the shape. */
internal fun okBody(json: String): String = """{"result":{"data":{"json":$json}}}"""
