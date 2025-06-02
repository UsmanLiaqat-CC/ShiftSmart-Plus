package com.shiftsmart.plus.database

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName =  DbConstants.ISSUE_TABLE_NAME)
data class IssueModel(
    @PrimaryKey val issueKey: String, // e.g., "internet_off"
    val userId: String,
    val issueTitle: String,
    val solution: String,
    val timestamp: Long = System.currentTimeMillis()
)
