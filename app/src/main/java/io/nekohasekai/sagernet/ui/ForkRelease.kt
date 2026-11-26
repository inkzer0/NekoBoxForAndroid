package io.nekohasekai.sagernet.ui

internal object ForkRelease {
    private const val REPO = "inkzer0/NekoBoxForAndroid"
    const val RELEASES_URL = "https://github.com/" + REPO + "/releases"
    const val LATEST_API = "https://api.github.com/repos/" + REPO + "/releases/latest"

    private val version = Regex("v?(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)")

    fun newerReleaseUrl(installed: String, tag: String): String? {
        val local = version.matchEntire(installed)?.groupValues ?: return null
        val latest = version.matchEntire(tag)?.groupValues ?: return null
        for (i in 1..3) {
            val sizeOrder = latest[i].length.compareTo(local[i].length)
            val order = if (sizeOrder == 0) latest[i].compareTo(local[i]) else sizeOrder
            if (order != 0) return if (order > 0) "$RELEASES_URL/tag/$tag" else null
        }
        return null
    }
}
