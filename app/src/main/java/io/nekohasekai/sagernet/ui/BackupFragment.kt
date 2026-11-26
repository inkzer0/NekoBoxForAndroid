package io.nekohasekai.sagernet.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Parcel
import android.os.Parcelable
import android.provider.OpenableColumns
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.content.FileProvider
import androidx.core.view.isVisible
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.jakewharton.processphoenix.ProcessPhoenix
import io.nekohasekai.sagernet.BuildConfig
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.bg.Executable
import io.nekohasekai.sagernet.database.*
import io.nekohasekai.sagernet.database.preference.PublicDatabase
import io.nekohasekai.sagernet.databinding.LayoutBackupBinding
import io.nekohasekai.sagernet.databinding.LayoutImportBinding
import io.nekohasekai.sagernet.databinding.LayoutProgressBinding
import io.nekohasekai.sagernet.ktx.*
import kotlinx.coroutines.delay
import moe.matsuri.nb4a.utils.Util
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.*

class BackupFragment : NamedFragment(R.layout.layout_backup) {

    override fun name0() = app.getString(R.string.backup)

    var content = ""
    private val exportSettings =
        registerForActivityResult(ActivityResultContracts.CreateDocument()) { data ->
            if (data != null) {
                runOnDefaultDispatcher {
                    try {
                        requireActivity().contentResolver.openOutputStream(
                            data
                        )!!.bufferedWriter().use {
                            it.write(content)
                        }
                        onMainDispatcher {
                            snackbar(getString(R.string.action_export_msg)).show()
                        }
                    } catch (e: Exception) {
                        Logs.w(e)
                        onMainDispatcher {
                            snackbar(e.readableMessage).show()
                        }
                    }
                }
            }
        }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val binding = LayoutBackupBinding.bind(view)

        binding.resetSettings.setOnClickListener {
            MaterialAlertDialogBuilder(requireContext()).setTitle(R.string.confirm)
                .setMessage(R.string.reset_settings_message)
                .setNegativeButton(R.string.no, null)
                .setPositiveButton(R.string.yes) { _, _ ->
                    DataStore.configurationStore.reset()
                    triggerFullRestart(requireContext())
                }
                .show()
        }

        binding.actionExport.setOnClickListener {
            runOnDefaultDispatcher {
                content = doBackup(
                    binding.backupConfigurations.isChecked,
                    binding.backupRules.isChecked,
                    binding.backupSettings.isChecked
                )
                onMainDispatcher {
                    startFilesForResult(
                        exportSettings, "nekobox_backup_${Date().toLocaleString()}.json"
                    )
                }
            }
        }

        binding.actionShare.setOnClickListener {
            runOnDefaultDispatcher {
                content = doBackup(
                    binding.backupConfigurations.isChecked,
                    binding.backupRules.isChecked,
                    binding.backupSettings.isChecked
                )
                app.cacheDir.mkdirs()
                val cacheFile = File(
                    app.cacheDir, "nekobox_backup_${Date().toLocaleString()}.json"
                )
                cacheFile.writeText(content)
                onMainDispatcher {
                    startActivity(
                        Intent.createChooser(
                            Intent(Intent.ACTION_SEND).setType("application/json")
                                .setFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                .putExtra(
                                    Intent.EXTRA_STREAM, FileProvider.getUriForFile(
                                        app, BuildConfig.APPLICATION_ID + ".cache", cacheFile
                                    )
                                ), app.getString(R.string.abc_shareactionprovider_share_with)
                        )
                    )
                }

            }
        }

        binding.actionImportFile.setOnClickListener {
            startFilesForResult(importFile, "*/*")
        }
    }

    fun Parcelable.toBase64Str(): String {
        val parcel = Parcel.obtain()
        writeToParcel(parcel, 0)
        try {
            return Util.b64EncodeUrlSafe(parcel.marshall())
        } finally {
            parcel.recycle()
        }
    }

    fun doBackup(profile: Boolean, rule: Boolean, setting: Boolean): String {
        val out = JSONObject().apply {
            put("version", 1)
            if (profile) {
                put("profiles", JSONArray().apply {
                    SagerDatabase.proxyDao.getAll().forEach {
                        put(it.toBase64Str())
                    }
                })

                put("groups", JSONArray().apply {
                    SagerDatabase.groupDao.allGroups().forEach {
                        put(it.toBase64Str())
                    }
                })
            }
            if (rule) {
                put("remoteRuleSets", RemoteRuleSetStore.export(SagerDatabase.remoteRuleSetsDao.all()))
                put("remoteRuleSetReferences", JSONObject().apply {
                    SagerDatabase.rulesDao.allRules().forEach {
                        if (it.remoteRuleSetTags.isNotBlank()) put(it.id.toString(), JSONArray(RemoteRuleSetStore.tags(it.remoteRuleSetTags)))
                    }
                })
                put("rules", JSONArray().apply {
                    SagerDatabase.rulesDao.allRules().forEach {
                        put(it.toBase64Str())
                    }
                })
            }
            if (setting) {
                put("settings", JSONArray().apply {
                    PublicDatabase.kvPairDao.all().forEach {
                        put(it.toBase64Str())
                    }
                })
            }
        }
        return out.toStringPretty()
    }

    val importFile = registerForActivityResult(ActivityResultContracts.GetContent()) { file ->
        if (file != null) {
            runOnDefaultDispatcher {
                startImport(file)
            }
        }
    }

    suspend fun startImport(file: Uri) {
        val fileName = requireContext().contentResolver.query(file, null, null, null, null)
            ?.use { cursor ->
                cursor.moveToFirst()
                cursor.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME).let(cursor::getString)
            }
            ?.takeIf { it.isNotBlank() } ?: file.pathSegments.last()
            .substringAfterLast('/')
            .substringAfter(':')

        if (!fileName.endsWith(".json")) {
            onMainDispatcher {
                snackbar(getString(R.string.backup_not_file, fileName)).show()
            }
            return
        }

        suspend fun invalid() = onMainDispatcher {
            onMainDispatcher {
                snackbar(getString(R.string.invalid_backup_file)).show()
            }
        }

        val content = try {
            JSONObject((requireContext().contentResolver.openInputStream(file) ?: return).use {
                it.bufferedReader().readText()
            })
        } catch (e: Exception) {
            // JSON parser errors can embed the backup, including subscription URL tokens.
            Logs.w("Invalid backup JSON")
            invalid()
            return
        }
        val version = content.optInt("version", 0)
        if (version < 1 || version > 1) {
            invalid()
            return
        }

        onMainDispatcher {
            val import = LayoutImportBinding.inflate(layoutInflater)
            if (!content.has("profiles")) {
                import.backupConfigurations.isVisible = false
            }
            if (!content.has("rules")) {
                import.backupRules.isVisible = false
            }
            if (!content.has("settings")) {
                import.backupSettings.isVisible = false
            }
            MaterialAlertDialogBuilder(requireContext()).setTitle(R.string.backup_import)
                .setView(import.root)
                .setPositiveButton(R.string.backup_import) { _, _ ->
                    val profiles = import.backupConfigurations.isChecked
                    val rules = import.backupRules.isChecked
                    val settings = import.backupSettings.isChecked
                    val binding = LayoutProgressBinding.inflate(layoutInflater)
                    binding.content.text = getString(R.string.backup_importing)
                    val dialog = AlertDialog.Builder(requireContext())
                        .setView(binding.root)
                        .setCancelable(false)
                        .show()
                    runOnDefaultDispatcher {
                        runCatching {
                            finishImport(content, profiles, rules, settings)
                        }.onSuccess { changed ->
                            if (changed) {
                                SagerNet.stopService()
                                triggerFullRestart(app)
                            }
                        }.onFailure {
                            Logs.w("Backup restore failed: ${it.javaClass.simpleName}")
                            onMainDispatcher {
                                alert(getString(R.string.backup_restore_failed)).tryToShow()
                            }
                        }

                        onMainDispatcher {
                            dialog.dismiss()
                        }
                    }
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
    }

    fun finishImport(
        content: JSONObject, profile: Boolean, rule: Boolean, setting: Boolean
    ): Boolean {
        val backup = BackupRestoreData.parse(content, profile, rule, setting)
        if (!backup.hasChanges) return false
        if (backup.profiles == null && backup.rules == null && backup.settings != null) {
            val database = PublicDatabase.instance
            database.runInTransaction {
                database.keyValuePairDao().reset()
                database.keyValuePairDao().insert(backup.settings)
            }
            return true
        }

        val database = SagerDatabase.instance
        val transaction = { writes: () -> Unit ->
            BackupRestoreTransaction.run(database, backup.settings?.let {
                PublicDatabase.instance.openHelper.writableDatabase.path
            }, writes)
        }
        val replaceRows: () -> Unit = {
            backup.profiles?.let {
                database.proxyDao().reset()
                database.proxyDao().insert(it)
                database.groupDao().reset()
                database.groupDao().insert(checkNotNull(backup.groups))
            }
            backup.rules?.let {
                database.rulesDao().reset()
                database.rulesDao().insert(it)
            }
            backup.settings?.let {
                BackupRestoreTransaction.replaceSettings(database.openHelper.writableDatabase, it)
            }
        }
        if (backup.rules != null) RemoteRuleSetManager.restore(backup.remoteSets, transaction, replaceRows)
        else transaction(replaceRows)
        return true
    }
}
