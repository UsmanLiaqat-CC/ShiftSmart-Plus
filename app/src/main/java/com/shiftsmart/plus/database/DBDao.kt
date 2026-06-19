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
    suspend fun deleteRecordByUuid(uuid: String)

    @Query("SELECT * FROM record WHERE user_id = :uId ORDER BY time ASC")
    fun getAllRecords(uId: String): List<RecordModel>

    @Query("SELECT * FROM record WHERE user_id = :uId ORDER BY time ASC ")
    fun getAllLiveRecords(uId: String): LiveData<List<RecordModel>>

    @Query("DELETE FROM record")
    suspend fun deleteAllRecords()

    @Query("DELETE FROM record WHERE user_id = :uId")
    suspend fun deleteAllRecordsByUserId(uId: String)

    @Query("SELECT * FROM record WHERE user_id = :uId ORDER BY time DESC LIMIT 1")
    suspend fun getLatestRecord(uId: String): RecordModel?

    @Query("SELECT * FROM record WHERE user_id = :uId AND attendanceType = 'default' ORDER BY time DESC LIMIT 1")
    suspend fun getLatestDefaultRecord(uId: String): RecordModel?
    @Query("SELECT COUNT(*) FROM record WHERE time = :recordTime")
    suspend fun countRecordByTime(recordTime: String): Int

    /**
     * Counts records that match a specific UTC time in HH:mm format (ignoring seconds).
     * This prevents duplicate records at the same UTC hour and minute.
     *
     * Example: If utcTimeHHMM = "05:15", it will match:
     * - "2025-10-28T05:15:00Z"
     * - "2025-10-28T05:15:23Z"
     * - "2025-10-28T05:15:59Z"
     *
     * @param userId The user ID
     * @param utcTimeHHMM The UTC time in HH:mm format (e.g., "05:15")
     * @return Count of records matching this UTC HH:mm
     */
    @Query("SELECT COUNT(*) FROM record WHERE user_id = :userId AND substr(time, 12, 5) = :utcTimeHHMM")
    suspend fun countRecordByUtcTimeHHMM(userId: String, utcTimeHHMM: String): Int

    /**
     * Counts records that match a specific LOCAL time for a user.
     * This is used for duplicate detection during backfilling to prevent inserting
     * records at the same local time, even if their UTC timestamps differ.
     *
     * This is critical for timezones with positive offsets (e.g., GMT+2) where
     * UTC timestamps can overlap across midnight:
     * - Oct 29 00:05 GMT+2 = Oct 28 22:05 UTC
     * - Oct 28 22:05 GMT+2 = Oct 28 20:05 UTC
     *
     * @param userId The user ID
     * @param localTime The local time in HH:mm:ss format (e.g., "00:05:00")
     * @return Count of records matching this local time for this user
     */
    @Query("SELECT COUNT(*) FROM record WHERE user_id = :userId AND localTime = :localTime")
    suspend fun countRecordByLocalTime(userId: String, localTime: String): Int


    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertIssue(issue: IssueModel)


    @Query("DELETE FROM issues WHERE issueKey = :key")
    suspend fun deleteIssueByKey(key: String)

    @Query("SELECT * FROM issues WHERE userId = :uId ORDER BY timestamp ASC")
    suspend fun getAllIssues(uId: String): List<IssueModel>

    @Query("SELECT complianceRecordId FROM record WHERE user_id = :userId AND complianceRecordId != '' AND complianceStatus = 'acknowledged'")
    suspend fun getAcknowledgedComplianceIds(userId: String): List<String>

}