package io.nekohasekai.sagernet.ui

import android.app.Dialog
import android.os.Bundle
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

class RemoteRuleSetsDialog : DialogFragment() {
    private var items = emptyList<RemoteRuleSetEntity>()
    private lateinit var adapter: ArrayAdapter<String>
    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        adapter = ArrayAdapter(requireContext(), android.R.layout.simple_list_item_1)
        parentFragmentManager.setFragmentResultListener("rule-set-saved", this) { _, _ -> refresh() }
        return MaterialAlertDialogBuilder(requireContext()).setTitle(R.string.remote_rule_sets)
            .setAdapter(adapter) { _, index -> actions(items[index]) }
            .setPositiveButton(R.string.ruleset_add) { _, _ -> editor(0) }
            .setNegativeButton(android.R.string.cancel, null).create()
    }

    override fun onStart() {
        super.onStart()
        val alert = dialog as AlertDialog
        alert.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener { editor(0) }
        // AlertDialog item clicks normally dismiss; keep the management list available.
        alert.listView.setOnItemClickListener { _, _, position, _ -> actions(items[position]) }
        refresh()
    }

    private fun refresh() {
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                SagerDatabase.remoteRuleSetsDao.all().map { e ->
                    val updated = RemoteRuleSetManager.updated(e)
                    val state = if (updated == 0L) getString(R.string.ruleset_no_cache)
                        else DateFormat.getDateTimeInstance().format(Date(updated))
                    e to "${e.name} (${e.tag})\n${getString(if (e.enabled) R.string.ruleset_enabled else R.string.ruleset_disabled)} · $state\n${RemoteRuleSetManager.error(e.id)}"
                }
            }
            items = result.map { it.first }
            adapter.clear(); adapter.addAll(result.map { it.second })
        }
    }

    private fun editor(id: Long) {
        RemoteRuleSetEditDialog().apply { arguments = Bundle().apply { putLong("id", id) } }
            .show(parentFragmentManager, "rule-set-editor")
    }

    private fun actions(e: RemoteRuleSetEntity) {
        val choices = arrayOf(getString(R.string.ruleset_edit), getString(R.string.ruleset_update),
            getString(if (e.enabled) R.string.ruleset_disable else R.string.ruleset_enable), getString(R.string.ruleset_delete))
        MaterialAlertDialogBuilder(requireContext()).setTitle(e.name).setItems(choices) { _, index ->
            when (index) {
                0 -> editor(e.id)
                1 -> operation { RemoteRuleSetManager.update(e.id) }
                2 -> operation { RemoteRuleSetManager.save(e.copy(enabled = !e.enabled)) }
                3 -> MaterialAlertDialogBuilder(requireContext()).setTitle(R.string.ruleset_delete)
                    .setMessage(e.name).setPositiveButton(android.R.string.ok) { _, _ -> operation { RemoteRuleSetManager.delete(e.id) } }
                    .setNegativeButton(android.R.string.cancel, null).show()
            }
        }.show()
    }

    private fun operation(block: () -> Unit) {
        lifecycleScope.launch {
            val failure = withContext(Dispatchers.IO) { runCatching(block).exceptionOrNull() }
            if (isAdded) {
                Toast.makeText(requireContext(), failure?.message ?: getString(R.string.ruleset_done), Toast.LENGTH_LONG).show()
                refresh()
            }
        }
    }
}

class RemoteRuleSetEditDialog : DialogFragment() {
    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val view = layoutInflater.inflate(R.layout.layout_remote_rule_set_edit, null)
        val id = requireArguments().getLong("id")
        val entity = if (id == 0L) RemoteRuleSetEntity() else SagerDatabase.remoteRuleSetsDao.get(id) ?: RemoteRuleSetEntity()
        val name = view.findViewById<EditText>(R.id.ruleset_name)
        val tag = view.findViewById<EditText>(R.id.ruleset_tag)
        val url = view.findViewById<EditText>(R.id.ruleset_url)
        val interval = view.findViewById<EditText>(R.id.ruleset_interval)
        val enabled = view.findViewById<CheckBox>(R.id.ruleset_enabled)
        val format = view.findViewById<Spinner>(R.id.ruleset_format)
        name.setText(entity.name); tag.setText(entity.tag); url.setText(entity.url)
        interval.setText(entity.updateIntervalMinutes.toString()); enabled.isChecked = entity.enabled
        format.adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_dropdown_item, arrayOf("source", "binary"))
        format.setSelection(if (entity.format == "binary") 1 else 0)
        val alert = MaterialAlertDialogBuilder(requireContext()).setTitle(R.string.remote_rule_sets)
            .setView(view).setPositiveButton(android.R.string.ok, null).setNegativeButton(android.R.string.cancel, null).create()
        alert.setOnShowListener {
            alert.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val value = RemoteRuleSetEntity(id, name.text.toString().trim(), tag.text.toString().trim(),
                    url.text.toString().trim(), format.selectedItem.toString(), interval.text.toString().toLongOrNull() ?: 0, enabled.isChecked)
                alert.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = false
                lifecycleScope.launch {
                    val failure = withContext(Dispatchers.IO) { runCatching { RemoteRuleSetManager.save(value) }.exceptionOrNull() }
                    if (failure == null) {
                        parentFragmentManager.setFragmentResult("rule-set-saved", Bundle())
                        dismiss()
                    } else if (isAdded) {
                        Toast.makeText(requireContext(), failure.message, Toast.LENGTH_LONG).show()
                        alert.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = true
                    }
                }
            }
        }
        return alert
    }
}
