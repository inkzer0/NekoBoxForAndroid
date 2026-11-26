package io.nekohasekai.sagernet.database

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.system.Os
import android.util.Log
import androidx.work.*
import androidx.work.multiprocess.RemoteWorkManager
import io.nekohasekai.sagernet.SagerNet
import libcore.Libcore
import okhttp3.OkHttpClient
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.net.Proxy
import java.util.concurrent.TimeUnit
import java.util.concurrent.ConcurrentHashMap

object RemoteRuleSetManager {
    const val DOWNLOAD_CLIENT = "ruleset-fetch-direct"
    private const val LOG_TAG = "RemoteRuleSetManager"
    private val root get() = File(SagerNet.application.filesDir, "remote-rule-sets")
    private val dao get() = SagerDatabase.remoteRuleSetsDao
    private fun cache(e: RemoteRuleSetEntity) = File(root, RemoteRuleSetStore.cacheName(e))
    private fun legacyCache(id: Long, format: String) = File(root, "$id.$format")
    private val errors = ConcurrentHashMap<String, String>()
    private val downloadLock = Any()
    fun updated(e: RemoteRuleSetEntity): Long = cache(e).lastModified()
    fun error(id: Long): String {
        val e = dao.get(id) ?: return ""
        val source = RemoteRuleSetStore.cacheName(e)
        errors[source]?.let { return it }
        val info = try {
            RemoteWorkManager.getInstance(SagerNet.application)
                .getWorkInfos(WorkQuery.fromUniqueWorkNames(listOf("rule-set-$id"))).get(10, TimeUnit.SECONDS)
                .filter { it.outputData.getString("source") == source }
                .maxByOrNull { it.outputData.getLong("completed", 0) }
        } catch (_: Exception) { return "Background update status unavailable" } ?: return ""
        if (dao.get(id)?.let { RemoteRuleSetStore.sameSource(e, it) } != true) return ""
        return if (info.outputData.getLong("completed", 0) > updated(e)) info.outputData.getString("error") ?: "" else ""
    }

    // Main UI and VPN processes share the same files. Serialize updates, edits and deletion.
    @Synchronized
    private fun <T> locked(block: () -> T): T {
        check(root.isDirectory || root.mkdirs() || root.isDirectory)
        return RandomAccessFile(File(root, ".lock"), "rw").use { file ->
            file.channel.lock().use { block() }
        }
    }

    fun save(value: RemoteRuleSetEntity): Long = locked {
        RemoteRuleSetStore.validate(value)
        val old = if (value.id == 0L) null else dao.get(value.id) ?: error("Rule-set no longer exists")
        require(dao.all().none { it.id != value.id && it.tag == value.tag }) { "Duplicate rule-set tag" }
        if (old != null && old.tag != value.tag) ensureUnreferenced(old.tag)
        SagerDatabase.instance.runInTransaction {
            if (old == null) value.id = dao.insert(value) else dao.update(value)
        }
        if (old != null && !RemoteRuleSetStore.sameSource(old, value)) {
            removeCache(cache(old))
            removeLegacyCaches(old.id)
            errors.remove(RemoteRuleSetStore.cacheName(old))
        }
        errors.remove(RemoteRuleSetStore.cacheName(value))
        scheduleAfterCommit(value)
        value.id
    }

    private fun ensureUnreferenced(tag: String) {
        require(SagerDatabase.rulesDao.allRules().none { tag in RemoteRuleSetStore.tags(it.remoteRuleSetTags) }) {
            "Rule-set is referenced; remove route references first"
        }
    }

    fun delete(id: Long) = locked {
        val entity = dao.get(id) ?: return@locked
        ensureUnreferenced(entity.tag)
        dao.delete(id)
        removeCache(cache(entity))
        removeLegacyCaches(id)
        errors.remove(RemoteRuleSetStore.cacheName(entity))
        cancelAfterCommit(id)
    }

    private fun removeCache(file: File) {
        try {
            if (file.exists() && !file.delete()) Log.w(LOG_TAG, "Cannot remove an obsolete rule-set cache")
        } catch (_: Exception) {
            Log.w(LOG_TAG, "Cannot remove an obsolete rule-set cache")
        }
    }

    private fun removeLegacyCaches(id: Long) {
        listOf("source", "binary").forEach { removeCache(legacyCache(id, it)) }
    }

    private fun cancelAfterCommit(id: Long) {
        try {
            RemoteWorkManager.getInstance(SagerNet.application).cancelUniqueWork("rule-set-$id")
        } catch (_: Exception) {
            Log.w(LOG_TAG, "Could not cancel background rule-set refresh")
        }
    }

    private fun scheduleAfterCommit(e: RemoteRuleSetEntity) {
        try {
            schedule(e, ExistingWorkPolicy.REPLACE, 0)
        } catch (_: Exception) {
            // The metadata is already saved. Report the worker error separately from database success.
            errors[RemoteRuleSetStore.cacheName(e)] = "Could not schedule rule-set refresh; update it manually"
            Log.w(LOG_TAG, "Could not schedule background rule-set refresh")
        }
    }

    /** Legacy files do not establish which URL produced their bytes. Never read them as valid caches. */
    fun refreshLegacyCaches() {
        try {
            locked {
                dao.all().filter { it.enabled && !cache(it).isFile && legacyCache(it.id, it.format).isFile }
                    .forEach(::scheduleAfterCommit)
            }
        } catch (_: Exception) {
            Log.w(LOG_TAG, "Could not schedule legacy rule-set refresh")
        }
    }

    private fun schedule(e: RemoteRuleSetEntity, policy: ExistingWorkPolicy, delay: Long) {
        val manager = RemoteWorkManager.getInstance(SagerNet.application)
        if (!e.enabled) {
            manager.cancelUniqueWork("rule-set-${e.id}")
            return
        }
        val request = OneTimeWorkRequestBuilder<RemoteRuleSetWorker>()
            .setInputData(workDataOf("id" to e.id, "source" to RemoteRuleSetStore.cacheName(e)))
            .setInitialDelay(delay, TimeUnit.MINUTES)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        manager.enqueueUniqueWork("rule-set-${e.id}", policy, request).get(10, TimeUnit.SECONDS)
    }

    fun update(id: Long) = update(id, null)

    private fun update(id: Long, expectedSource: String?) = synchronized(downloadLock) {
        check(root.isDirectory || root.mkdirs() || root.isDirectory)
        RandomAccessFile(File(root, ".download.lock"), "rw").use { file ->
            file.channel.lock().use {
                root.listFiles { entry -> entry.name.startsWith("candidate-") && entry.name.endsWith(".tmp") }
                    ?.forEach { it.delete() }
                performUpdate(id, expectedSource)
            }
        }
    }

    private fun performUpdate(id: Long, expectedSource: String?) {
        val e = locked { dao.get(id) ?: error("Rule-set no longer exists") }
        val source = RemoteRuleSetStore.cacheName(e)
        if (expectedSource != null && (expectedSource != source || !e.enabled)) return
        try {
            download(e)
            locked {
                if (dao.get(id)?.let { RemoteRuleSetStore.sameSource(e, it) } == true) errors.remove(source)
            }
        } catch (exception: Exception) {
            // Only controlled messages are stored/displayed; HTTP URLs and Core parse errors may contain secrets.
            val message = if (exception is DownloadFailure) exception.message!! else "Rule-set update failed"
            locked {
                if (dao.get(id)?.let { RemoteRuleSetStore.sameSource(e, it) } == true) errors[source] = message
            }
            throw DownloadFailure(message)
        }
    }

    internal fun backgroundUpdate(id: Long, expectedSource: String?, active: () -> Boolean): Pair<String, String>? {
        val initial = locked { dao.get(id)?.takeIf { it.enabled } } ?: return null
        val source = RemoteRuleSetStore.cacheName(initial)
        if (!active() || (expectedSource != null && expectedSource != source)) return null
        val failure = try {
            update(id, source)
            ""
        } catch (exception: Exception) {
            if (exception is DownloadFailure) exception.message!! else "Rule-set update failed"
        }
        return locked {
            val current = dao.get(id)
            if (!active() || current == null || !current.enabled || !RemoteRuleSetStore.sameSource(initial, current)) {
                return@locked null
            }
            schedule(current, ExistingWorkPolicy.APPEND_OR_REPLACE, current.updateIntervalMinutes)
            source to failure
        }
    }

    private fun download(e: RemoteRuleSetEntity) {
        val cm = SagerNet.application.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = (listOfNotNull(SagerNet.underlyingNetwork) + cm.allNetworks).distinct().firstOrNull {
            cm.getNetworkCapabilities(it)?.let { caps ->
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                    caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            } == true
        } ?: throw DownloadFailure("No direct network available")
        val client = OkHttpClient.Builder().proxy(Proxy.NO_PROXY)
            .socketFactory(network.socketFactory).dns { network.getAllByName(it).toList() }
            .connectTimeout(20, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS)
            .callTimeout(90, TimeUnit.SECONDS).followSslRedirects(false).build()
        try {
            RemoteRuleSetDownload.fetch(client, e.url, cache(e), { candidate -> validateContent(e, candidate) }) { from, to ->
                locked {
                    val current = dao.get(e.id)
                    if (current == null || !RemoteRuleSetStore.sameSource(e, current)) {
                        throw DownloadFailure("Subscription changed during update")
                    }
                    Os.rename(from.absolutePath, to.absolutePath)
                    removeLegacyCaches(e.id)
                }
            }
        } finally {
            client.connectionPool.evictAll()
            client.dispatcher.executorService.shutdown()
        }
    }

    private fun validateContent(e: RemoteRuleSetEntity, candidate: File) {
        val config = JSONObject().put("log", JSONObject().put("disabled", true))
            .put("route", JSONObject().put("rule_set", JSONArray().put(JSONObject()
                .put("type", "local").put("tag", e.tag).put("format", e.format).put("path", candidate.absolutePath))))
        try {
            Libcore.newSingBoxInstance(config.toString(), null).close()
        } catch (_: Exception) {
            throw DownloadFailure("Invalid rule-set content")
        }
    }

    // Only DNS-safe cached rule-sets are mirrored into generated DNS rules.
    fun domainRuleSetTags(tags: List<String>): List<String> {
        if (tags.isEmpty()) return emptyList()
        return locked {
            val byTag = dao.all().associateBy { it.tag }
            tags.distinct().filter { tag ->
                val entity = byTag[tag]?.takeIf { it.enabled } ?: return@filter false
                val file = cache(entity)
                file.isFile && Libcore.isDomainRuleSet(file.absolutePath, entity.format)
            }
        }
    }

    fun addToConfig(config: MutableMap<String, Any?>, rules: List<RuleEntity>) {
        val referenced = RemoteRuleSetStore.referencedTags(config)
        if (referenced.isEmpty()) return

        @Suppress("UNCHECKED_CAST")
        val route = config["route"] as? Map<String, Any?>
        if (route == null && config["route"] != null) return // Let the core reject malformed overrides.
        val definitions = route?.get("rule_set") as? List<*>
        if (definitions == null && route?.get("rule_set") != null) return
        val definedTags = definitions.orEmpty().mapNotNull { (it as? Map<*, *>)?.get("tag") as? String }.toSet()
        val needed = referenced - definedTags
        if (needed.isEmpty()) return

        val uiTags = rules.filter { it.enabled }.flatMap { RemoteRuleSetStore.tags(it.remoteRuleSetTags) }.toSet()
        val additions = locked {
            val managed = dao.all().filter { it.tag in needed }
            val managedTags = managed.map { it.tag }.toSet()
            (uiTags.intersect(needed) - managedTags).firstOrNull()?.let { error("Missing rule-set: $it") }
            managed.map { entity ->
                require(entity.enabled) { "Enabled rule references disabled rule-set: ${entity.tag}" }
                val file = cache(entity)
                require(file.isFile) { "Rule-set ${entity.tag} has no valid cache; update it first" }
                mapOf("type" to "local", "tag" to entity.tag, "format" to entity.format, "path" to file.absolutePath)
            }
        }
        if (additions.isNotEmpty()) {
            config["route"] = route.orEmpty() + ("rule_set" to (definitions.orEmpty() + additions))
        }
    }

    fun restore(
        entities: List<RemoteRuleSetEntity>,
        transaction: (() -> Unit) -> Unit = { writes -> SagerDatabase.instance.runInTransaction { writes() } },
        replaceOtherRows: () -> Unit = {},
    ) = locked {
        val previous = dao.all()
        transaction {
            dao.reset()
            dao.insertAll(entities)
            replaceOtherRows()
        }
        val restoredById = entities.associateBy { it.id }
        previous.forEach { old ->
            val restored = restoredById[old.id]
            if (restored == null || !RemoteRuleSetStore.sameSource(old, restored)) {
                removeCache(cache(old))
                removeLegacyCaches(old.id)
            }
            if (restored == null) cancelAfterCommit(old.id)
            errors.remove(RemoteRuleSetStore.cacheName(old))
        }
        entities.forEach(::scheduleAfterCommit)
    }

    class DownloadFailure(message: String) : Exception(message)
}

class RemoteRuleSetWorker(context: Context, parameters: WorkerParameters) : Worker(context, parameters) {
    override fun doWork(): Result {
        val (source, error) = RemoteRuleSetManager.backgroundUpdate(
            inputData.getLong("id", 0), inputData.getString("source")
        ) { !isStopped } ?: return Result.success()
        return Result.success(workDataOf("source" to source, "error" to error, "completed" to System.currentTimeMillis()))
    }
}
