package io.nekohasekai.sagernet.group

import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.database.ProxyGroup
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.database.SubscriptionBean
import io.nekohasekai.sagernet.fmt.AbstractBean

internal data class SubscriptionRefreshMetadata(
    val userInfo: String?,
    val suggestedName: String?,
    val updatedAt: Int
)

internal data class SubscriptionRefreshResult(
    val group: ProxyGroup,
    val changed: Int,
    val added: List<String>,
    val updated: Map<String, String>,
    val deleted: List<String>
)

internal fun SubscriptionBean.sameRefreshInputs(other: SubscriptionBean): Boolean =
    type == other.type &&
        link == other.link &&
        updateWhenConnectedOnly == other.updateWhenConnectedOnly &&
        customUserAgent == other.customUserAgent &&
        forceResolve == other.forceResolve &&
        deduplication == other.deduplication

internal fun persistSubscriptionRefresh(
    database: SagerDatabase,
    groupId: Long,
    requested: SubscriptionBean,
    proxies: List<AbstractBean>,
    metadata: SubscriptionRefreshMetadata
): SubscriptionRefreshResult {
    require(proxies.isNotEmpty()) { "A refresh must retain at least one supported node" }
    val incoming = proxies.associateBy { it.displayName() }
    return database.runInTransaction<SubscriptionRefreshResult> {
        val groupDao = database.groupDao()
        val proxyDao = database.proxyDao()
        val group = checkNotNull(groupDao.getById(groupId)) { "Subscription group no longer exists" }
        val current = checkNotNull(group.subscription) { "Group no longer has a subscription" }
        // Keep concurrent changes to names or schedules. Never replace nodes using a stale source.
        check(current.sameRefreshInputs(requested)) { "Subscription settings changed during the refresh; try again" }

        val existing = proxyDao.getByGroup(groupId).groupBy { it.displayName() }
        val toDelete = existing.values.flatMap { matches ->
            if (matches.first().displayName() in incoming) matches.dropLast(1) else matches
        }
        val toInsert = mutableListOf<ProxyEntity>()
        val toUpdate = mutableListOf<ProxyEntity>()
        val added = mutableListOf<String>()
        val updated = linkedMapOf<String, String>()

        incoming.entries.forEachIndexed { index, (name, bean) ->
            val order = index + 1L
            val entity = existing[name]?.last()
            if (entity == null) {
                toInsert += ProxyEntity(groupId = groupId, userOrder = order).apply { putBean(bean) }
                added += name
            } else {
                val previous = entity.requireBean()
                bean.customOutboundJson = previous.customOutboundJson
                bean.customConfigJson = previous.customConfigJson
                val changed = previous != bean
                if (changed) {
                    entity.putBean(bean)
                    updated[name] = name
                }
                if (changed || entity.userOrder != order) {
                    entity.userOrder = order
                    toUpdate += entity
                }
            }
        }

        if (toInsert.isNotEmpty()) proxyDao.insert(toInsert)
        if (toUpdate.isNotEmpty()) proxyDao.updateProxy(toUpdate)
        if (toDelete.isNotEmpty()) proxyDao.deleteProxy(toDelete)
        check(proxyDao.countByGroup(groupId) == incoming.size.toLong()) { "Subscription node count differs after refresh" }

        if (metadata.userInfo != null) current.subscriptionUserinfo = metadata.userInfo
        if (metadata.suggestedName != null && group.name?.startsWith("Subscription #") == true) {
            group.name = metadata.suggestedName
        }
        current.lastUpdated = metadata.updatedAt
        groupDao.updateGroup(group)

        SubscriptionRefreshResult(
            group, added.size + updated.size + toDelete.size, added, updated, toDelete.map { it.displayName() }
        )
    }
}
