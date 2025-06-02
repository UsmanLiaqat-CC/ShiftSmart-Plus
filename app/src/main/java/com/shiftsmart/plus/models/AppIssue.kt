package com.shiftsmart.plus.models

data class AppIssue(
    val key: String,
    val title: String,
    val solution: String,
    val isIssue: Boolean = false
)
