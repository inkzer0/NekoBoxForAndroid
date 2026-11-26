package io.nekohasekai.sagernet.group

import org.junit.Assert.assertEquals
import org.junit.Test

class SubscriptionFetchModeTest {
    @Test
    fun connectedOnlyNeverUsesDirectFallbackWhileConnected() {
        assertEquals(SubscriptionFetchMode.PROXY_REQUIRED, subscriptionFetchMode(true, false, false, true))
        assertEquals(SubscriptionFetchMode.PROXY_REQUIRED, subscriptionFetchMode(true, true, true, true))
    }

    @Test
    fun disconnectedRequiresExplicitManualApprovalForRestrictedSubscriptions() {
        assertEquals(SubscriptionFetchMode.SKIP, subscriptionFetchMode(true, false, false, false))
        assertEquals(SubscriptionFetchMode.CONFIRM_DIRECT, subscriptionFetchMode(true, false, true, false))
        assertEquals(SubscriptionFetchMode.CONFIRM_DIRECT, subscriptionFetchMode(false, true, true, false))
    }

    @Test
    fun unrestrictedSubscriptionsKeepExistingFallback() {
        assertEquals(SubscriptionFetchMode.PREFER_PROXY, subscriptionFetchMode(false, false, true, false))
        assertEquals(SubscriptionFetchMode.PREFER_PROXY, subscriptionFetchMode(false, false, false, true))
    }
}
