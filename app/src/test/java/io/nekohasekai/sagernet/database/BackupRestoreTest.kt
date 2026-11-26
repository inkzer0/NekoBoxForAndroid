package io.nekohasekai.sagernet.database

import android.app.Application
import android.os.Parcel
import android.os.Parcelable
import android.util.Base64
import androidx.room.Room
import androidx.room.RoomDatabase
import io.nekohasekai.sagernet.database.preference.KeyValuePair
import io.nekohasekai.sagernet.database.preference.PublicDatabase
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import org.robolectric.shadows.ShadowSQLiteConnection

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, manifest = Config.NONE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class BackupRestoreTest {
    @Test fun malformedLaterSectionAndDuplicateSettingsAreRejectedUpFront() {
        val malformed = JSONObject().put("profiles", JSONArray()).put("groups", JSONObject())
        assertThrows(org.json.JSONException::class.java) {
            BackupRestoreData.parse(malformed, profile = true, rule = false, setting = true)
        }

        val settings = JSONArray().put(encoded(KeyValuePair("same").put("a")))
            .put(encoded(KeyValuePair("same").put("b")))
        val duplicate = JSONObject().put("settings", settings)
        assertThrows(IllegalArgumentException::class.java) {
            BackupRestoreData.parse(duplicate, profile = false, rule = false, setting = true)
        }
    }

    @Test fun attachedSettingsAndProfileWritesRollBackTogetherAndCanBeRetried() {
        // Match the on-device file-backed durability settings, not Robolectric's MEMORY/OFF defaults.
        ShadowSQLiteConnection.setDefaultJournalMode("TRUNCATE")
        ShadowSQLiteConnection.setDefaultSyncMode("FULL")
        val context = RuntimeEnvironment.getApplication()
        val db = Room.databaseBuilder(context, SagerDatabase::class.java, "backup-test-main")
            .setJournalMode(RoomDatabase.JournalMode.TRUNCATE).allowMainThreadQueries().build()
        val preferences = Room.databaseBuilder(context, PublicDatabase::class.java, "backup-test-prefs")
            .setJournalMode(RoomDatabase.JournalMode.TRUNCATE).allowMainThreadQueries().build()
        try {
            db.groupDao().insert(listOf(ProxyGroup(id = 1, name = "original")))
            preferences.keyValuePairDao().put(KeyValuePair("name").put("original"))
            val path = preferences.openHelper.writableDatabase.path
            val settings = listOf(KeyValuePair("name").put("replacement"))
            val replace = {
                db.groupDao().reset()
                db.groupDao().insert(listOf(ProxyGroup(id = 2, name = "replacement")))
                BackupRestoreTransaction.replaceSettings(db.openHelper.writableDatabase, settings)
            }
            val failure = assertThrows(IllegalStateException::class.java) {
                BackupRestoreTransaction.run(db, path) {
                    replace()
                    error("injected failure")
                }
            }
            assertEquals("injected failure", failure.message)
            assertEquals("original", db.groupDao().allGroups().single().name)
            assertEquals("original", preferences.keyValuePairDao()["name"]!!.string)

            BackupRestoreTransaction.run(db, path, replace)
            assertEquals("replacement", db.groupDao().allGroups().single().name)
            assertEquals("replacement", preferences.keyValuePairDao()["name"]!!.string)
        } finally {
            db.close()
            preferences.close()
            context.deleteDatabase("backup-test-main")
            context.deleteDatabase("backup-test-prefs")
        }
    }

    private fun encoded(value: Parcelable): String {
        val p = Parcel.obtain()
        return try {
            value.writeToParcel(p, 0)
            Base64.encodeToString(p.marshall(), Base64.NO_WRAP)
        } finally {
            p.recycle()
        }
    }
}
