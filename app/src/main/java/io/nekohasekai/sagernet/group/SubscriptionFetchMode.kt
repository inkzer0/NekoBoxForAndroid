package io.nekohasekai.sagernet.group

internal enum class SubscriptionFetchMode {
    PREFER_PROXY, PROXY_REQUIRED, CONFIRM_DIRECT, SKIP
}

internal fun subscriptionFetchMode(
    connectedOnly: Boolean,
    insecureHttp: Boolean,
    byUser: Boolean,
    connected: Boolean
): SubscriptionFetchMode = when {
    connectedOnly && connected -> SubscriptionFetchMode.PROXY_REQUIRED
    connectedOnly && !byUser -> SubscriptionFetchMode.SKIP
    !connected && byUser && (connectedOnly || insecureHttp) -> SubscriptionFetchMode.CONFIRM_DIRECT
    else -> SubscriptionFetchMode.PREFER_PROXY
}
