package app.spliit.android.feature.groups

import app.spliit.core.RecentGroupsSnapshot
import app.spliit.core.RecentGroupsStore

/** An in-memory [RecentGroupsStore], shared by this package's ViewModel tests, which fake the
 *  transport with a real [mockwebserver3.MockWebServer] but have no reason to touch a disk. */
internal class FakeRecentGroupsStore(private var snapshot: RecentGroupsSnapshot = RecentGroupsSnapshot()) :
    RecentGroupsStore {
    override suspend fun load(): RecentGroupsSnapshot = snapshot
    override suspend fun save(snapshot: RecentGroupsSnapshot) {
        this.snapshot = snapshot
    }
}

/** A tRPC success envelope carrying [json] as its payload, see :api's own tests for the shape. */
internal fun okBody(json: String): String = """{"result":{"data":{"json":$json}}}"""
