package io.github.nutea.anylisten.core.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface LibraryDao {
    @Query(
        """
        SELECT * FROM playlists ORDER BY
        CASE id
            WHEN 'default' THEN 0
            WHEN 'love' THEN 1
            WHEN 'last_played' THEN 2
            ELSE 3
        END, name
        """,
    )
    fun observePlaylists(): Flow<List<PlaylistEntity>>

    @Query(
        """
        SELECT * FROM playlists ORDER BY
        CASE id
            WHEN 'default' THEN 0
            WHEN 'love' THEN 1
            WHEN 'last_played' THEN 2
            ELSE 3
        END, name
        """,
    )
    suspend fun playlists(): List<PlaylistEntity>

    @Query("SELECT t.* FROM tracks t INNER JOIN playlist_entries e ON e.trackCacheKey = t.cacheKey WHERE e.playlistId = :playlistId ORDER BY e.position")
    fun observeTracks(playlistId: String): Flow<List<TrackEntity>>

    @Query("SELECT t.* FROM tracks t INNER JOIN playlist_entries e ON e.trackCacheKey = t.cacheKey WHERE e.playlistId = :playlistId ORDER BY e.position")
    suspend fun tracks(playlistId: String): List<TrackEntity>

    @Query("SELECT * FROM tracks WHERE cacheKey = :cacheKey LIMIT 1")
    suspend fun track(cacheKey: String): TrackEntity?

    @Query("SELECT MAX(refreshedAtEpochMs) FROM playlists")
    suspend fun lastRefresh(): Long?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertPlaylists(items: List<PlaylistEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertTracks(items: List<TrackEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertEntries(items: List<PlaylistEntryEntity>)

    @Query("DELETE FROM playlists")
    suspend fun clearPlaylists()

    @Query("DELETE FROM playlist_entries")
    suspend fun clearEntries()

    @Transaction
    suspend fun replaceLibrary(playlists: List<PlaylistEntity>, tracks: List<TrackEntity>, entries: List<PlaylistEntryEntity>) {
        clearPlaylists()
        clearEntries()
        upsertPlaylists(playlists)
        upsertTracks(tracks)
        upsertEntries(entries)
    }
}

@Dao
interface DownloadDao {
    @Query("SELECT * FROM downloads ORDER BY title")
    fun observe(): Flow<List<DownloadEntity>>

    @Query("SELECT * FROM downloads")
    suspend fun all(): List<DownloadEntity>

    @Query("SELECT * FROM downloads WHERE cacheKey = :cacheKey LIMIT 1")
    suspend fun find(cacheKey: String): DownloadEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: DownloadEntity)

    @Query("DELETE FROM downloads WHERE cacheKey = :cacheKey")
    suspend fun delete(cacheKey: String)
}
