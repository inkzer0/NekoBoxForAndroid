package io.nekohasekai.sagernet.group

import io.nekohasekai.sagernet.fmt.socks.SOCKSBean
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class RawUpdaterClashImportTest {
    @Test
    fun importsOrdinaryClashNode() {
        val yaml = """
            proxies:
              - name: fixture
                type: socks5
                server: example.com
                port: 1080
        """.trimIndent()

        val nodes = runBlocking { requireNotNull(RawUpdater.parseRaw(yaml)) }
        val node = nodes.single() as SOCKSBean
        assertEquals("fixture", node.name)
        assertEquals("example.com", node.serverAddress)
        assertEquals(1080, node.serverPort.toInt())
    }
}
