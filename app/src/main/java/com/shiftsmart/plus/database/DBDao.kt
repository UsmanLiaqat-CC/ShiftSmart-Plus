package com.shiftsmart.plus.database
import androidx.lifecycle.LiveData
import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update

/**
 * Created by Usman Liaqat on 28,Jan,2025
 * usmanliaqat@codecoytechnologies.com,
 * CodeCoy Technologies,
 * Lahore, Pakistan.
 */
@Dao
interface DBDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertRecord(recordModel: RecordModel)

    @Update
    fun updateRecord(recordModel: RecordModel)

    @Query("DELETE FROM record WHERE UUID = :uuid")
    suspend fun deleteRecordByUuid(uuid: Int)

    @Query("SELECT * FROM record WHERE user_id = :uId ORDER BY time ASC")
    fun getAllRecords(uId: String): List<RecordModel>

    @Query("SELECT * FROM record WHERE user_id = :uId ORDER BY time ASC ")
    fun getAllLiveRecords(uId: String): LiveData<List<RecordModel>>

    @Query("DELETE FROM record")
    suspend fun deleteAllRecords()

    @Query("DELETE FROM record WHERE user_id = :uId")
    suspend fun deleteAllRecordsByUserId(uId: String)

    @Query("SELECT * FROM record WHERE user_id = :uId ORDER BY time DESC LIMIT 1")
    fun getLatestRecord(uId: String): RecordModel?

    @Query("SELECT * FROM record WHERE user_id = :uId AND attendanceType = 'default' ORDER BY time DESC LIMIT 1")
    fun getLatestDefaultRecord(uId: String): RecordModel?
    @Query("SELECT COUNT(*) FROM record WHERE time = :recordTime")
    suspend fun countRecordByTime(recordTime: String): Int


    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertIssue(issue: IssueModel)


    @Query("DELETE FROM issues WHERE issueKey = :key")
    suspend fun deleteIssueByKey(key: String)

    @Query("SELECT * FROM issues WHERE userId = :uId ORDER BY timestamp ASC")
    suspend fun getAllIssues(uId: String): List<IssueModel>

}