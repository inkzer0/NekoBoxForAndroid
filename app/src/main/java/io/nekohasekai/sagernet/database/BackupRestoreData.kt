package io.nekohasekai.sagernet.database

import android.os.Parcel
import io.nekohasekai.sagernet.database.preference.KeyValuePair
import moe.matsuri.nb4a.utils.Util
import org.json.JSONArray
import org.json.JSONObject

/** Parse every selected section without touching Room, files, or WorkManager. */
internal data class BackupRestoreData(
    val profiles: List<ProxyEntity>?,
    val groups: List<ProxyGroup>?,
    val rules: List<RuleEntity>?,
    val remoteSets: List<RemoteRuleSetEntity>,
    val settings: List<KeyValuePair>?,
) {
    val hasChanges get() = profiles != null || rules != null || settings != null

    companion object {
        fun parse(content: JSONObject, profile: Boolean, rule: Boolean, setting: Boolean): BackupRestoreData {
            fun <T> read(name: String, decode: (Parcel) -> T): List<T> {
                val values = content.getJSONArray(name)
                return (0 until values.length()).map { index ->
                    val bytes = Util.b64Decode(values.getString(index))
                    require(bytes.isNotEmpty())
                    val parcel = Parcel.obtain()
                    try {
                        parcel.unmarshall(bytes, 0, bytes.size)
                        parcel.setDataPosition(0)
                        decode(parcel)
                    } finally {
                        parcel.recycle()
                    }
                }
            }

            val profiles = if (profile && content.has("profiles"))
                read("profiles", ProxyEntity.CREATOR::createFromParcel) else null
            val groups = if (profiles != null) read("groups", ProxyGroup.CREATOR::createFromParcel) else null
            val settings = if (setting && content.has("settings"))
                read("settings", KeyValuePair.CREATOR::createFromParcel) else null

            val withRules = rule && content.has("rules")
            val (remoteSets, tags) = try {
                val sets = if (withRules && content.has("remoteRuleSets"))
                    RemoteRuleSetStore.parse(content.getJSONArray("remoteRuleSets")) else emptyList()
                val references = if (withRules && content.has("remoteRuleSetReferences"))
                    content.getJSONObject("remoteRuleSetReferences") else JSONObject()
                val decodedTags = references.keys().asSequence().associateWith { key ->
                    require(key.toLongOrNull() != null)
                    val array = references.getJSONArray(key)
                    (0 until array.length()).map(array::getString).also { values ->
                        require(values.all { tag -> sets.any { it.tag == tag } })
                    }.joinToString("\n")
                }
                sets to decodedTags
            } catch (_: Exception) {
                // Raw JSON exceptions can include private URLs or embedded backup content.
                throw IllegalArgumentException("Invalid remote rule-set backup data")
            }
            val rules = if (withRules) read("rules") { parcel ->
                ParcelizeBridge.createRule(parcel).apply {
                    remoteRuleSetTags = tags[id.toString()].orEmpty()
                }
            } else null
            if (rules != null) {
                require(tags.keys.all { key -> rules.any { it.id.toString() == key } })
                RemoteRuleSetStore.validateReferences(remoteSets, rules)
            }
            require(settings == null || settings.map { it.key }.toSet().size == settings.size)
            require(profiles == null || profiles.map { it.id }.toSet().size == profiles.size)
            require(groups == null || groups.map { it.id }.toSet().size == groups.size)
            require(rules == null || rules.map { it.id }.toSet().size == rules.size)
            return BackupRestoreData(profiles, groups, rules, remoteSets, settings)
        }
    }
}
