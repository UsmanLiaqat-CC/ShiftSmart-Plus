package com.shiftsmart.plus.database
import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

@Database(entities = [RecordModel::class], version = 12 , exportSchema = false)
abstract class ShiftSmartPlusDatabase : RoomDatabase() {
     abstract fun dbDao(): DBDao
    companion object {

        private var INSTANCE: ShiftSmartPlusDatabase? = null
        fun getInstance(context: Context): ShiftSmartPlusDatabase {
            if (INSTANCE == null) {
                synchronized(ShiftSmartPlusDatabase::class) {
                    INSTANCE = buildRoomDB(context)
                }
            }
            return INSTANCE!!
        }
        private fun buildRoomDB(context: Context) =
            Room.databaseBuilder(
                context.applicationContext,
                ShiftSmartPlusDatabase::class.java,
                DbConstants.DATABASE_NAME
            ).allowMainThreadQueries()
                .fallbackToDestructiveMigration()
                .addTypeConverter(Converters())
                .build()
    }
}
