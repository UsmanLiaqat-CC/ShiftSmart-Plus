package com.shiftsmart.plus.database
import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

// Version 7 of the app uses database version 13. Keeps unsent attendance on update
private val MIGRATION_13_17 = object : Migration(13, 17) {
    override fun migrate(db: SupportSQLiteDatabase) {
        listOf("appVersion", "deviceName", "deviceType", "complianceRecordId", "complianceStatus")
            .forEach { column ->
                db.execSQL("ALTER TABLE `record` ADD COLUMN `$column` TEXT NOT NULL DEFAULT ''")
            }
    }
}

@Database(
    entities = [
        RecordModel::class,
        IssueModel::class,
    ],
    version = 17,
    exportSchema = false
)
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
                .addMigrations(MIGRATION_13_17)
                .fallbackToDestructiveMigration()
                .addTypeConverter(Converters())
                .build()
    }
}
