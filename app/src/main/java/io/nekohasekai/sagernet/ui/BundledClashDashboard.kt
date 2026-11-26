package io.nekohasekai.sagernet.ui

import android.content.Context
import android.net.Uri
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import io.nekohasekai.sagernet.bg.ClashDashboardAuth
import io.nekohasekai.sagernet.database.DataStore
import org.json.JSONObject
import java.util.UUID
import java.util.zip.ZipInputStream

/** Only packaged pages may receive the managed controller credential. */
internal class BundledClashDashboard(private val context: Context) {
    companion object {
        private const val PATH = "/__nb4a_dashboard__/"
        const val URL = ClashDashboardAuth.ORIGIN + PATH

        fun isConfiguredUrl(url: String): Boolean {
            val uri = Uri.parse(url)
            return isController(uri) && (uri.path == "/ui" || uri.path == "/ui/")
        }

        private fun isController(uri: Uri) =
            uri.scheme == "http" && uri.encodedAuthority == ClashDashboardAuth.CONTROLLER
    }

    private val assets by lazy {
        buildMap<String, ByteArray> {
            ZipInputStream(context.assets.open("yacd.zip")).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    if (!entry.isDirectory) put(entry.name.substringAfter('/'), zip.readBytes())
                }
            }
        }
    }

    fun allowsNavigation(uri: Uri) =
        isController(uri) && uri.encodedPath == PATH && uri.encodedQuery == null

    fun intercept(request: WebResourceRequest): WebResourceResponse? {
        val uri = request.url
        if (!isController(uri)) return missing()
        val path = uri.encodedPath.orEmpty()
        if (!path.startsWith(PATH)) return if (request.isForMainFrame) missing() else null
        if (request.method != "GET" || uri.encodedQuery != null) return missing()
        val name = path.removePrefix(PATH)
        if (name.isEmpty()) {
            if (!request.isForMainFrame) return missing()
            val index = assets["index.html"]?.decodeToString() ?: return missing()
            if (!index.contains("<head>")) return missing()
            val nonce = UUID.randomUUID().toString()
            val credential = if (DataStore.serviceState.connected) {
                ClashDashboardAuth.currentCredential(context)
            } else null
            val script = credential?.let(::bootstrap).orEmpty()
            val html = index.replaceFirst("<head>", "<head><script nonce=\"$nonce\">$script</script>")
            return response("text/html", html.encodeToByteArray(), mapOf(
                "Cache-Control" to "no-store",
                "Referrer-Policy" to "no-referrer",
                "Content-Security-Policy" to "default-src 'self'; script-src 'self' 'nonce-$nonce'; " +
                    "connect-src 'self' ws://" + ClashDashboardAuth.CONTROLLER + "; " +
                    "style-src 'self' 'unsafe-inline'; img-src 'self' data:; font-src 'self' data:; " +
                    "worker-src 'none'; frame-src 'none'; object-src 'none'; base-uri 'none'; form-action 'none'"
            ))
        }

        if (request.isForMainFrame || name == "index.html" || name == "sw.js" || name == "registerSW.js") {
            return missing()
        }
        val asset = assets[name] ?: return missing()
        val mime = when (name.substringAfterLast('.')) {
            "js" -> "text/javascript"
            "css" -> "text/css"
            "png" -> "image/png"
            "ico" -> "image/x-icon"
            "svg" -> "image/svg+xml"
            "woff" -> "font/woff"
            "woff2" -> "font/woff2"
            "webmanifest" -> "application/manifest+json"
            else -> return missing()
        }
        return response(mime, asset, mapOf("X-Content-Type-Options" to "nosniff"))
    }

    private fun response(mime: String, bytes: ByteArray, headers: Map<String, String>) =
        WebResourceResponse(mime, "utf-8", 200, "OK", headers, bytes.inputStream())

    private fun missing() = WebResourceResponse(
        "text/plain", "utf-8", 404, "Not Found", emptyMap(), ByteArray(0).inputStream()
    )

    // yacd keeps its current API settings (which can be shown on screen) unchanged.
    // Add credentials only at the transport boundary; do not put them in storage or the page URL.
    private fun bootstrap(secret: String) = """
        (() => {
          const secret = ${JSONObject.quote(secret)};
          const fetch = window.fetch.bind(window);
          window.fetch = (input, init) => {
            const request = new Request(input, init);
            const url = new URL(request.url);
            if (url.origin !== location.origin || url.username || url.password) {
              return Promise.reject(new TypeError('Only the local controller is supported'));
            }
            const headers = new Headers(request.headers);
            headers.set('Authorization', 'Bearer ' + secret);
            return fetch(new Request(request, {headers, redirect: 'error'}));
          };
          window.WebSocket = new Proxy(window.WebSocket, {
            construct(Socket, [input, ...protocols]) {
              const url = new URL(input, location.href);
              if (url.protocol !== 'ws:' || url.host !== location.host || url.username || url.password) {
                throw new TypeError('Only the local controller is supported');
              }
              url.searchParams.set('token', secret);
              return new Socket(url.href, ...protocols);
            }
          });
        })();
    """.trimIndent()
}
