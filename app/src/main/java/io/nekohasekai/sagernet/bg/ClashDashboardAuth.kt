package io.nekohasekai.sagernet.bg

import android.content.Context
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.SecureRandom

internal object ClashDashboardAuth {
    const val CONTROLLER = "127.0.0.1:9090"
    const val ORIGIN = "http://$CONTROLLER"
    const val LOCAL_DASHBOARD = "$ORIGIN/ui"
    private const val FILE_NAME = "clash-dashboard-token"
    private val tokenPattern = Regex("[0-9a-f]{64}")
    private val hex = "0123456789abcdef"

    // Keep the credential out of backups; rotate before starting each new listener.
    // A socket owned by another process after shutdown can see only the obsolete token.
    @Synchronized
    fun rotateCredential(context: Context): String {
        val token = AtomicFile(File(context.noBackupFilesDir, FILE_NAME))
        val bytes = ByteArray(32).also(SecureRandom()::nextBytes)
        val value = buildString(64) {
            for (byte in bytes) {
                val n = byte.toInt() and 255
                append(hex[n ushr 4])
                append(hex[n and 15])
            }
        }
        val stream = token.startWrite()
        try {
            stream.write(value.toByteArray(Charsets.US_ASCII))
            token.finishWrite(stream)
        } catch (e: Exception) {
            token.failWrite(stream)
            throw e
        }
        check(currentCredential(context) == value) { "Could not store Clash dashboard credentials" }
        return value
    }

    fun currentCredential(context: Context): String? {
        // Read the committed base file directly; do not let AtomicFile's reader delete a
        // writer's in-progress .new file while the UI and :bg process overlap.
        val token = File(context.noBackupFilesDir, FILE_NAME)
        return try {
            token.readText(Charsets.US_ASCII).takeIf(tokenPattern::matches)
        } catch (_: java.io.IOException) {
            null
        }
    }

    /** Harden the final merged config, immediately before handing it to the core. */
    fun protectRuntime(config: String, enableManagedDashboard: Boolean, credential: () -> String): String {
        val root = JSONObject(config)
        val clash = root.optJSONObject("experimental")?.optJSONObject("clash_api") ?: return config
        val controller = (clash.opt("external_controller") as? String).orEmpty()
        if (controller.isBlank()) return config
        if (enableManagedDashboard && controller == CONTROLLER) {
            clash.put("secret", credential())
            clash.put("access_control_allow_origin", JSONArray().put(ORIGIN))
            clash.put("access_control_allow_private_network", false)
            return root.toString()
        }
        if (!(clash.opt("secret") as? String).isNullOrBlank()) return config
        // Keep internal Clash mode/routing options, but do not expose an anonymous listener.
        clash.remove("external_controller")
        return root.toString()
    }
}
