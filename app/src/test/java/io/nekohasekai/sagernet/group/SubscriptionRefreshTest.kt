package io.nekohasekai.sagernet.group

import android.app.Application
import androidx.room.Room
import io.nekohasekai.sagernet.GroupType
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.database.ProxyGroup
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.database.SubscriptionBean
import io.nekohasekai.sagernet.fmt.socks.SOCKSBean
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, manifest = Config.NONE)
class SubscriptionRefreshTest {
    private fun withDatabase(block: (SagerDatabase, Long, SubscriptionBean, Long) -> Unit) {
        val database = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), SagerDatabase::class.java)
            .allowMainThreadQueries().build()
        try {
            val subscription = SubscriptionBean().apply {
                initializeDefaultValues()
                link = "https://example.com/subscription"
                subscriptionUserinfo = "old metadata"
                lastUpdated = 11
            }
            val groupId = database.groupDao().createGroup(
                ProxyGroup(type = GroupType.SUBSCRIPTION, name = "Subscription #1", subscription = subscription)
            )
            val existingId = database.proxyDao().addProxy(
                ProxyEntity(groupId = groupId).apply { putBean(node("old")) }
            )
            block(database, groupId, subscription, existingId)
        } finally {
            database.close()
        }
    }

    private fun node(nodeName: String) = SOCKSBean().apply {
        name = nodeName
        serverAddress = "example.com"
        serverPort = 1080
        initializeDefaultValues()
    }

    private fun assertUnchanged(db: SagerDatabase, groupId: Long, existingId: Long) {
        val stored = db.proxyDao().getByGroup(groupId).single()
        assertEquals(existingId, stored.id)
        assertEquals("old", stored.displayName())
        val group = checkNotNull(db.groupDao().getById(groupId))
        assertEquals("Subscription #1", group.name)
        val subscription = checkNotNull(group.subscription)
        assertEquals("old metadata", subscription.subscriptionUserinfo)
        assertEquals(11, subscription.lastUpdated.toInt())
    }

    private val metadata = SubscriptionRefreshMetadata("new metadata", "New group", 22)

    @Test fun emptyAndUnsupportedResponsesCannotReplaceSavedNodes() = withDatabase { db, groupId, subscription, oldId ->
        val empty = runBlocking { RawUpdater.parseRaw("proxies: []") }.orEmpty()
        val unsupported = runBlocking { RawUpdater.parseRaw("proxies: [{type: unsupported}]") }.orEmpty()
        for (proxies in listOf(empty, unsupported)) {
            assertThrows(IllegalArgumentException::class.java) {
                persistSubscriptionRefresh(db, groupId, subscription, proxies, metadata)
            }
            assertUnchanged(db, groupId, oldId)
        }
    }

    @Test fun metadataWriteFailureRollsBackNodeInsertAndDeletion() = withDatabase { db, groupId, subscription, oldId ->
        db.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER abort_refresh_metadata BEFORE UPDATE ON proxy_groups " +
                "BEGIN SELECT RAISE(ABORT, 'fixture metadata error'); END"
        )
        val failure = assertThrows(RuntimeException::class.java) {
            persistSubscriptionRefresh(db, groupId, subscription, listOf(node("new")), metadata)
        }
        assertTrue(failure.message.orEmpty().contains("fixture metadata error"))
        assertUnchanged(db, groupId, oldId)
    }

    @Test fun validRefreshCommitsNodesAndMetadataTogether() = withDatabase { db, groupId, subscription, _ ->
        persistSubscriptionRefresh(db, groupId, subscription, listOf(node("new")), metadata)
        assertEquals("new", db.proxyDao().getByGroup(groupId).single().displayName())
        val saved = checkNotNull(db.groupDao().getById(groupId))
        assertEquals("New group", saved.name)
        assertEquals("new metadata", saved.subscription?.subscriptionUserinfo)
        assertEquals(22, saved.subscription?.lastUpdated?.toInt() ?: 0)
    }
}
