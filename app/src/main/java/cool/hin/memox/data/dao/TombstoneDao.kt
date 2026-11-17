package cool.hin.memox.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import cool.hin.memox.data.model.NoteTombstone

@Dao
interface TombstoneDao {

    @Query("SELECT * FROM NoteTombstone") suspend fun getAll(): List<NoteTombstone>

    @Query("SELECT * FROM NoteTombstone WHERE pendingRemote = 1") suspend fun getPending(): List<NoteTombstone>

    @Query("SELECT * FROM NoteTombstone WHERE uuid = :uuid") suspend fun get(uuid: String): NoteTombstone?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(tombstone: NoteTombstone)

    @Query("UPDATE NoteTombstone SET pendingRemote = 0 WHERE uuid = :uuid")
    suspend fun markRemoteApplied(uuid: String)

    @Query("DELETE FROM NoteTombstone WHERE uuid = :uuid AND pendingRemote = 0")
    suspend fun removeApplied(uuid: String)
}
