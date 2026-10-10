package com.knoxx.dailyflow.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * The local memory store — the only place DailyFlow keeps what it understands.
 *
 * The schema carries a couple of columns the UI does not use yet
 * ([MemoryEntity.syncedToCloud], [MemoryEntity.projectName]) so that shipping
 * those features later is not gated on a migration.
 */
@Database(
    entities = [MemoryEntity::class, SyncTombstone::class],
    version = 3,
    exportSchema = false
)
@TypeConverters(FloatArrayConverter::class)
abstract class DailyFlowDatabase : RoomDatabase() {

    abstract fun memoryDao(): MemoryDao

    abstract fun syncDao(): SyncDao

    companion object {
        private const val DB_NAME = "dailyflow.db"

        /**
         * Adds [MemoryEntity.eventType].
         *
         * Migrated rather than rebuilt because by the time this was needed
         * there were already real captures on the device, and destroying them
         * would have thrown away the only evidence of the capture bugs it was
         * added to diagnose. Existing rows keep NULL, which reads correctly as
         * "recorded before the source was tracked" rather than as a wrong
         * source.
         */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE memories ADD COLUMN eventType TEXT")
            }
        }

        /**
         * PC sync: cross-device ids, origin, change numbers, and tombstones.
         *
         * Migrated, not rebuilt, for the reason MIGRATION_1_2 gives — the
         * device already holds real memories. The column declarations must
         * match what Room derives from [MemoryEntity] exactly (NOT NULL with
         * DEFAULT 0 for the two counters, which carry @ColumnInfo
         * defaultValue = "0"; the index name is Room's own index_<table>_<col>),
         * or Room rejects the migrated schema at open.
         *
         * Existing memories get an id and a change number (their row id —
         * unique and positive, all a change number needs) so the first sync
         * sends them to the PC.
         */
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE memories ADD COLUMN syncId TEXT")
                db.execSQL("ALTER TABLE memories ADD COLUMN originDevice TEXT")
                db.execSQL("ALTER TABLE memories ADD COLUMN originName TEXT")
                db.execSQL("ALTER TABLE memories ADD COLUMN originKind TEXT")
                db.execSQL("ALTER TABLE memories ADD COLUMN updatedAt INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE memories ADD COLUMN changeSeq INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE memories ADD COLUMN lastChangedBy TEXT")
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_memories_syncId` ON `memories` (`syncId`)"
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `sync_tombstones` (`syncId` TEXT NOT NULL, " +
                        "`deletedAt` INTEGER NOT NULL, `changeSeq` INTEGER NOT NULL, " +
                        "`lastChangedBy` TEXT, PRIMARY KEY(`syncId`))"
                )
                db.execSQL(
                    "UPDATE memories SET syncId = lower(hex(randomblob(16))), " +
                        "updatedAt = timestamp, changeSeq = id " +
                        "WHERE isLoop = 1 AND summary IS NOT NULL"
                )
            }
        }

        @Volatile
        private var instance: DailyFlowDatabase? = null

        fun getInstance(context: Context): DailyFlowDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    DailyFlowDatabase::class.java,
                    DB_NAME
                )
                    // Deliberately no fallbackToDestructiveMigration: a missing
                    // migration should fail loudly in development rather than
                    // silently wipe someone's memory in the field.
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                    .build()
                    .also { instance = it }
            }
    }
}
