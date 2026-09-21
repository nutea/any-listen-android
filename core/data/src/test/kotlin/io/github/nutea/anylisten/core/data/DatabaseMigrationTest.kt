package io.github.nutea.anylisten.core.data

import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import io.github.nutea.anylisten.core.data.local.AppDatabase
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class DatabaseMigrationTest {
    @Test fun versionOneUpgradePreservesLibraryAndDownloads() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val name = "migration-test.db"
        context.deleteDatabase(name)
        val file = context.getDatabasePath(name)
        file.parentFile!!.mkdirs()
        SQLiteDatabase.openOrCreateDatabase(file, null).use { legacy ->
            legacy.execSQL("CREATE TABLE IF NOT EXISTS `tracks` (`cacheKey` TEXT NOT NULL, `serverProfileId` TEXT NOT NULL, `remoteTrackId` TEXT NOT NULL, `title` TEXT NOT NULL, `artist` TEXT NOT NULL, `album` TEXT NOT NULL, `durationMs` INTEGER, `coverUrl` TEXT, `fingerprint` TEXT, `playlistId` TEXT, `rawJson` TEXT, `sizeLabel` TEXT, `extension` TEXT, PRIMARY KEY(`cacheKey`))")
            legacy.execSQL("CREATE TABLE IF NOT EXISTS `playlists` (`id` TEXT NOT NULL, `name` TEXT NOT NULL, `type` TEXT NOT NULL, `trackCount` INTEGER NOT NULL, `coverUrl` TEXT, `canMutateOnline` INTEGER NOT NULL, `refreshedAtEpochMs` INTEGER NOT NULL, PRIMARY KEY(`id`))")
            legacy.execSQL("CREATE TABLE IF NOT EXISTS `playlist_entries` (`playlistId` TEXT NOT NULL, `entryId` TEXT NOT NULL, `trackCacheKey` TEXT NOT NULL, `position` INTEGER NOT NULL, PRIMARY KEY(`playlistId`, `entryId`))")
            legacy.execSQL("CREATE TABLE IF NOT EXISTS `downloads` (`cacheKey` TEXT NOT NULL, `serverProfileId` TEXT NOT NULL, `remoteTrackId` TEXT NOT NULL, `title` TEXT NOT NULL, `artist` TEXT NOT NULL, `status` TEXT NOT NULL, `bytesDownloaded` INTEGER NOT NULL, `bytesTotal` INTEGER, `filePath` TEXT, `error` TEXT, `integrity` TEXT, `isolated` INTEGER NOT NULL, `fingerprint` TEXT, PRIMARY KEY(`cacheKey`))")
            legacy.execSQL("INSERT INTO playlists VALUES ('playlist', 'Saved', 'user', 1, NULL, 1, 123)")
            legacy.execSQL("INSERT INTO tracks VALUES ('key', 'server', 'song', 'Title', 'Artist', 'Album', 1000, NULL, NULL, 'playlist', NULL, NULL, NULL)")
            legacy.execSQL("INSERT INTO playlist_entries VALUES ('playlist', 'entry', 'key', 0)")
            legacy.execSQL("INSERT INTO downloads VALUES ('key', 'server', 'song', 'Title', 'Artist', 'COMPLETED', 100, 100, '/saved/song.mp3', NULL, NULL, 0, NULL)")
            legacy.version = 1
        }
        val db = Room.databaseBuilder(context, AppDatabase::class.java, name)
            .addMigrations(AppDatabase.MIGRATION_1_2).build()
        try {
            val playlist = db.libraryDao().playlists().single()
            assertEquals("Saved", playlist.name)
            assertEquals(0, playlist.position)
            assertEquals("Title", db.libraryDao().tracks("playlist").single().title)
            assertEquals("/saved/song.mp3", db.downloadDao().find("key")!!.filePath)
            assertEquals(2, db.openHelper.readableDatabase.version)
        } finally {
            db.close()
            context.deleteDatabase(name)
        }
    }
}
