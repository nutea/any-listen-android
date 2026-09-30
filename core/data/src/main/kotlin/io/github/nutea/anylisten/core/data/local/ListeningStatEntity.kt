package io.github.nutea.anylisten.core.data.local

import androidx.room.*
import kotlinx.coroutines.flow.Flow
import io.github.nutea.anylisten.core.model.ListeningStat

@Entity(tableName = "listening_stats")
data class ListeningStatEntity(@PrimaryKey val trackKey: String, val listenedMs: Long = 0,
    val plays: Long = 0, val lastPlayedAt: Long = 0, val firstPlayedAt: Long = 0) {
    fun toModel() = ListeningStat(trackKey, listenedMs, plays, lastPlayedAt, firstPlayedAt)
}
@Dao
interface ListeningStatDao {
    @Query("SELECT * FROM listening_stats") fun observe(): Flow<List<ListeningStatEntity>>
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insert(item: ListeningStatEntity)
    @Query("UPDATE listening_stats SET listenedMs = listenedMs + :ms, plays = plays + :count, lastPlayedAt = :time WHERE trackKey = :key")
    suspend fun update(key: String, ms: Long, count: Long, time: Long)
    @Query("DELETE FROM listening_stats WHERE trackKey = :key") suspend fun delete(key: String)
    @Transaction suspend fun record(key: String, ms: Long, count: Long, time: Long) {
        require(ms >= 0 && count >= 0)
        insert(ListeningStatEntity(key, firstPlayedAt = time))
        update(key, ms, count, time)
    }
}
