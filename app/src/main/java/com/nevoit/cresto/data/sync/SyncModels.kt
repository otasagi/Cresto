package com.nevoit.cresto.data.sync

import kotlinx.serialization.Serializable

/**
 * The complete sync file stored on the WebDAV server.
 */
@Serializable
data class SyncFile(
    val schemaVersion: Int = 1,
    val syncId: String = "",
    val lastUpdatedAt: String,
    val items: List<TodoSyncSnapshot> = emptyList(),
    val deletedSyncIds: List<String> = emptyList()
)

/**
 * Snapshot of a single TodoItem for sync purposes.
 * All date/time fields are ISO-8601 strings for safe JSON serialization.
 */
@Serializable
data class TodoSyncSnapshot(
    val syncId: String,
    val title: String,
    val dueDate: String? = null,
    val creationDateTime: String,
    val updatedAt: String,
    val isCompleted: Boolean = false,
    val completedDateTime: String? = null,
    val flag: Int = 0,
    val notes: String = "",
    val startTime: String? = null,
    val endTime: String? = null,
    val reminderMode: String? = null,
    val reminderOffsetMinutes: Int? = null,
    val reminderDayOffset: Int? = null,
    val reminderTime: String? = null,
    val reminderPersistent: Boolean = false,
    val reminderStrong: Boolean = false,
    val subTodos: List<SubTodoSyncSnapshot> = emptyList()
)

/**
 * Snapshot of a single SubTodoItem for sync purposes.
 */
@Serializable
data class SubTodoSyncSnapshot(
    val syncId: String,
    val description: String,
    val isCompleted: Boolean = false
)

/**
 * Result of a sync operation.
 */
sealed class SyncResult {
    data object NotConfigured : SyncResult()
    data object AlreadyRunning : SyncResult()
    data object SkippedNoChanges : SyncResult()
    data class Success(
        val itemsSynced: Int,
        val conflictsFound: Int,
        val itemsPushed: Int,
        val itemsPulled: Int,
        val itemsDeleted: Int
    ) : SyncResult()
    data class Error(
        val message: String,
        val isRetryable: Boolean
    ) : SyncResult()
    data class Conflict(val count: Int) : SyncResult()
}
