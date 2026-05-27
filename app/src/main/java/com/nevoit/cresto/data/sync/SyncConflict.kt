package com.nevoit.cresto.data.sync

/**
 * Record of a sync conflict that needs user resolution.
 */
data class SyncConflict(
    val syncId: String,
    val title: String,
    val conflictFields: List<String>,
    val localSnapshot: TodoSyncSnapshot,
    val remoteSnapshot: TodoSyncSnapshot,
    val createdAt: String,
    val resolved: Boolean = false
)

/**
 * User resolution choice for a conflict.
 */
sealed class ConflictResolution {
    data class AcceptLocal(val syncId: String) : ConflictResolution()
    data class AcceptRemote(val syncId: String) : ConflictResolution()
}
