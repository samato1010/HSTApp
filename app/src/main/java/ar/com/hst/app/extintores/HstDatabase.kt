package ar.com.hst.app.extintores

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(entities = [ControlPendienteEntity::class], version = 1, exportSchema = false)
abstract class HstDatabase : RoomDatabase() {
    abstract fun controlDao(): ControlDao

    companion object {
        @Volatile
        private var INSTANCE: HstDatabase? = null

        fun get(context: Context): HstDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    HstDatabase::class.java,
                    "hst_controles_db"
                ).build()
                INSTANCE = instance
                instance
            }
        }
    }
}
