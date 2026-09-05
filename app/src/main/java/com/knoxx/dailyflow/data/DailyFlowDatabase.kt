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
    entities = [MemoryEntity::class],
    version = 2,
    exportSchema = false
)
@TypeConverters(FloatArrayConverter::class)
abstract class DailyFlowDatabase : RoomDatabase() {

    abstract fun memoryDao(): MemoryDao

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
                    .addMigrations(MIGRATION_1_2)
                    .build()
                    .also { instance = it }
            }
    }
}
