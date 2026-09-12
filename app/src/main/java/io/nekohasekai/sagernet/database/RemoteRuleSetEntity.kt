package io.nekohasekai.sagernet.database

import androidx.room.*

@Entity(tableName = "remote_rule_sets", indices = [Index(value = ["tag"], unique = true)])
data class RemoteRuleSetEntity(
    @PrimaryKey(autoGenerate = true) var id: Long = 0,
    var name: String = "",
    var tag: String = "",
    var url: String = "",
    var format: String = "source",
    var updateIntervalMinutes: Long = 1440,
    var enabled: Boolean = true,
) {
    @androidx.room.Dao
    interface Dao {
        @Query("SELECT * FROM remote_rule_sets ORDER BY id")
        fun all(): List<RemoteRuleSetEntity>
        @Query("SELECT * FROM remote_rule_sets WHERE id = :id")
        fun get(id: Long): RemoteRuleSetEntity?
        @Insert fun insert(entity: RemoteRuleSetEntity): Long
        @Update fun update(entity: RemoteRuleSetEntity)
        @Query("DELETE FROM remote_rule_sets WHERE id = :id") fun delete(id: Long)
        @Query("DELETE FROM remote_rule_sets") fun reset()
        @Insert fun insertAll(entities: List<RemoteRuleSetEntity>)
    }
}
