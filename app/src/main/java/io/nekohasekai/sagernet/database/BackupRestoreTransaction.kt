package io.nekohasekai.sagernet.database

import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import io.nekohasekai.sagernet.database.preference.KeyValuePair
import android.util.Log

/** The two app databases use rollback journals, so an attached write can commit both together. */
internal object BackupRestoreTransaction {
    @Synchronized
    fun run(database: RoomDatabase, settingsPath: String?, writes: () -> Unit) {
        if (settingsPath == null) {
            database.runInTransaction { writes() }
            return
        }
        val sqlite = database.openHelper.writableDatabase
        check(settingsPath.isNotBlank() && settingsPath != ":memory:" && sqlite.path != ":memory:" && settingsPath != sqlite.path)
        checkJournal(sqlite, "main")
        // Android's rollback-journal SQLite pool uses a single primary connection.
        sqlite.execSQL("ATTACH DATABASE ? AS restore_settings", arrayOf(settingsPath))
        try {
            database.runInTransaction {
                // Verify the attachment and journal modes on the transaction connection, before any write.
                checkJournal(sqlite, "main")
                checkJournal(sqlite, "restore_settings")
                writes()
            }
        } finally {
            runCatching { sqlite.execSQL("DETACH DATABASE restore_settings") }
                .onFailure { Log.w("BackupRestore", "Could not detach settings after backup restore") }
        }
    }

    private fun checkJournal(sqlite: SupportSQLiteDatabase, schema: String) {
        sqlite.query("PRAGMA $schema.journal_mode").use {
            val mode = if (it.moveToFirst()) it.getString(0).lowercase() else "unknown"
            check(mode in setOf("delete", "truncate", "persist")) { "Unexpected ${schema} journal mode: $mode" }
        }
        sqlite.query("PRAGMA $schema.synchronous").use {
            check(it.moveToFirst() && it.getInt(0) > 0)
        }
    }

    fun replaceSettings(sqlite: SupportSQLiteDatabase, settings: List<KeyValuePair>) {
        sqlite.execSQL("DELETE FROM restore_settings.KeyValuePair")
        settings.forEach { value ->
            sqlite.execSQL(
                "INSERT INTO restore_settings.KeyValuePair (`key`, valueType, value) VALUES (?, ?, ?)",
                arrayOf(value.key, value.valueType, value.value),
            )
        }
    }
}
