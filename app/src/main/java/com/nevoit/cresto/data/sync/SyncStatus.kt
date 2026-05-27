package com.nevoit.cresto.data.sync

import java.time.LocalDateTime

/**
 * Observable sync status for the UI layer.
 */
sealed class SyncStatus {
    data object Idle : SyncStatus()
    data object Syncing : SyncStatus()
    data class Success(
        val itemsSynced: Int,
        val conflictsFound: Int,
        val timestamp: LocalDateTime = LocalDateTime.now()
    ) : SyncStatus()
    data class Error(
        val message: String,
        val isRetryable: Boolean,
        val timestamp: LocalDateTime = LocalDateTime.now()
    ) : SyncStatus()
    data object NotConfigured : SyncStatus()
    data object WaitingForNetwork : SyncStatus()
}
