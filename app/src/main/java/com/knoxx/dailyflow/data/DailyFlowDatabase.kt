package com.knoxx.dailyflow.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

/**
 * The local memory store — the only place DailyFlow keeps what it understands.
 *
 * Version 1 with no migrations, deliberately: this is a new app, not a
 * continuation of Wick's database, and there is nothing in the field to
 * migrate from. The schema carries a couple of columns the UI does not use yet
 * ([MemoryEntity.syncedToCloud], [MemoryEntity.projectName]) so that shipping
 * those features later is not gated on a migration.
 */
@Database(
    entities = [MemoryEntity::class],
    version = 1,
    exportSchema = false
)
@TypeConverters(FloatArrayConverter::class)
abstract class DailyFlowDatabase : RoomDatabase() {

    abstract fun memoryDao(): MemoryDao

    companion object {
        private const val DB_NAME = "dailyflow.db"

        @Volatile
        private var instance: DailyFlowDatabase? = null

        fun getInstance(context: Context): DailyFlowDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    DailyFlowDatabase::class.java,
                    DB_NAME
                ).build().also { instance = it }
            }
    }
}
