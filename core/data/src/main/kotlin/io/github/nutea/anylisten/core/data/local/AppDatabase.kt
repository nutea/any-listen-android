package io.github.nutea.anylisten.core.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [TrackEntity::class, PlaylistEntity::class, PlaylistEntryEntity::class, DownloadEntity::class, ListeningStatEntity::class],
    version = 3,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun libraryDao(): LibraryDao
    abstract fun downloadDao(): DownloadDao
    abstract fun listeningStatDao(): ListeningStatDao

    companion object {
        val MIGRATION_1_2 = object : androidx.room.migration.Migration(1, 2) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE playlists ADD COLUMN position INTEGER NOT NULL DEFAULT 0")
            }
        }

        val MIGRATION_2_3 = object : androidx.room.migration.Migration(2, 3) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS listening_stats (trackKey TEXT NOT NULL, listenedMs INTEGER NOT NULL, plays INTEGER NOT NULL, lastPlayedAt INTEGER NOT NULL, firstPlayedAt INTEGER NOT NULL, PRIMARY KEY(trackKey))")
            }
        }

        fun create(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "any-listen.db").addMigrations(MIGRATION_1_2, MIGRATION_2_3).build()
    }
}
