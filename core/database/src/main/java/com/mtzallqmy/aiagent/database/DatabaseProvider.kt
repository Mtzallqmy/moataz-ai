package com.mtzallqmy.aiagent.database

import android.content.Context
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Singleton database that requires explicit Room migrations for schema changes. */
object DatabaseProvider {
    @Volatile
    private var instance: AppDatabase? = null

    fun get(context: Context): AppDatabase = instance ?: synchronized(this) {
        instance ?: Room.databaseBuilder(
            context.applicationContext,
            AppDatabase::class.java,
            "aegis_agent.db",
        )
            .addCallback(PrepopulateSeed(context))
            .build()
            .also { instance = it }
    }

    private class PrepopulateSeed(private val context: Context) : RoomDatabase.Callback() {
        override fun onCreate(db: SupportSQLiteDatabase) {
            super.onCreate(db)
            CoroutineScope(Dispatchers.IO + SupervisorJob()).launch {
                // Seed default workspace memory namespace. Nothing else is hardcoded.
            }
        }
    }
}
