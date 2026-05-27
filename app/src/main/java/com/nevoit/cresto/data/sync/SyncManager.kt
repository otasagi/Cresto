package com.nevoit.cresto.data.sync

import com.nevoit.cresto.data.todo.TodoRepository
import com.tencent.mmkv.MMKV
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Public API for WebDAV sync. Manages sync status, debounced change tracking,
 * and the full sync cycle orchestration.
 *
 * Thread-safe: all operations run on the internal syncScope (Dispatchers.IO).
 */
class SyncManager(
    private val webDavClient: WebDavClient,
    private val repository: TodoRepository,
    private val credentialStore: ICredentialStore
) {
    private val syncScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mmkv = MMKV.defaultMMKV()
    private val json = Json { ignoreUnknownKeys = true }

    private val _syncStatus = MutableStateFlow<SyncStatus>(SyncStatus.Idle)
    val syncStatus: StateFlow<SyncStatus> = _syncStatus.asStateFlow()

    @Volatile
    private var isSyncing = false

    @Volatile
    private var pendingSync = false

    private var retryCount = 0
    private var debounceJob: Job? = null

    /**
     * Notify that local data has changed. Triggers a debounced sync.
     * Multiple rapid changes are batched into a single sync.
     * Changes during active sync are queued and trigger another sync after completion.
     */
    fun onDataChanged() {
        if (!credentialStore.isConfigured) return
        if (isSyncing) {
            pendingSync = true
            return
        }
        // Debounce: reset timer on each change, sync fires after quiet period
        debounceJob?.cancel()
        debounceJob = syncScope.launch {
            delay(DEBOUNCE_MS)
            requestSyncInternal()
        }
    }

    /**
     * Manually trigger sync (called from "Sync Now" button).
     */
    fun requestSync() {
        if (!credentialStore.isConfigured) {
            _syncStatus.value = SyncStatus.NotConfigured
            return
        }
        syncScope.launch {
            requestSyncInternal()
        }
    }

    /**
     * Request sync that runs in the caller's coroutine context and returns the result.
     * Used by SyncWorker for background sync.
     */
    suspend fun requestSyncAndGetResult(): SyncResult {
        if (!credentialStore.isConfigured) return SyncResult.NotConfigured
        if (isSyncing) return SyncResult.AlreadyRunning

        isSyncing = true
        _syncStatus.value = SyncStatus.Syncing

        return try {
            val result = executeSync()
            retryCount = 0
            when (result) {
                is SyncResult.Success -> {
                    mmkv.encode(KEY_LAST_SYNC_TIME, LocalDateTime.now().format(
                        DateTimeFormatter.ISO_LOCAL_DATE_TIME
                    ))
                    _syncStatus.value = SyncStatus.Success(
                        itemsSynced = result.itemsSynced,
                        conflictsFound = result.conflictsFound
                    )
                }
                is SyncResult.Error -> {
                    _syncStatus.value = SyncStatus.Error(
                        message = result.message,
                        isRetryable = result.isRetryable
                    )
                }
                is SyncResult.SkippedNoChanges -> {
                    _syncStatus.value = SyncStatus.Idle
                }
                is SyncResult.Conflict -> {
                    mmkv.encode(KEY_LAST_SYNC_TIME, LocalDateTime.now().format(
                        DateTimeFormatter.ISO_LOCAL_DATE_TIME
                    ))
                    _syncStatus.value = SyncStatus.Success(
                        itemsSynced = 0,
                        conflictsFound = result.count
                    )
                }
                else -> {} // NotConfigured, AlreadyRunning
            }
            result
        } catch (e: Exception) {
            _syncStatus.value = SyncStatus.Error(
                message = e.message ?: "Unknown sync error",
                isRetryable = true
            )
            SyncResult.Error(e.message ?: "Unknown error", true)
        } finally {
            isSyncing = false
        }
    }

    /**
     * Test the WebDAV connection.
     */
    suspend fun testConnection(): Result<Unit> {
        return webDavClient.testConnection()
    }

    /**
     * Clear all remote data on the WebDAV server.
     */
    suspend fun clearRemoteData(): Result<Unit> {
        return webDavClient.delete()
    }

    /**
     * Reset sync status to Idle. Called from UI after a timeout.
     */
    fun resetSyncStatus() {
        _syncStatus.value = SyncStatus.Idle
    }

    // ── conflict store ──

    fun getConflicts(): List<SyncConflict> {
        val raw = mmkv.decodeString(KEY_CONFLICTS) ?: return emptyList()
        return try {
            val list = json.decodeFromString<MutableList<SyncConflict>>(raw)
            list.filter { !it.resolved }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun resolveConflict(syncId: String, resolution: ConflictResolution) {
        val raw = mmkv.decodeString(KEY_CONFLICTS) ?: return
        val conflicts = try {
            json.decodeFromString<MutableList<SyncConflict>>(raw)
        } catch (_: Exception) {
            return
        }

        val remoteSnapshot = conflicts.firstOrNull { it.syncId == syncId }?.remoteSnapshot
        val updatedConflicts = conflicts.map { conflict ->
            if (conflict.syncId == syncId) {
                conflict.copy(resolved = true)
            } else conflict
        }

        mmkv.encode(KEY_CONFLICTS, json.encodeToString(updatedConflicts))

        // Apply the resolution to the local DB
        syncScope.launch {
            when (resolution) {
                is ConflictResolution.AcceptLocal -> {
                    // Local version is already in DB. Trigger sync to push it.
                    requestSyncInternal()
                }
                is ConflictResolution.AcceptRemote -> {
                    // Write remote snapshot to local DB (with sub-todos)
                    remoteSnapshot?.let { repository.updateBySyncIdFromSnapshot(it) }
                }
            }
        }
    }

    fun clearConflictStore() {
        mmkv.remove(KEY_CONFLICTS)
    }

    // ── sync metadata ──

    val lastSyncTime: LocalDateTime?
        get() {
            val raw = mmkv.decodeString(KEY_LAST_SYNC_TIME) ?: return null
            return try {
                LocalDateTime.parse(raw, DateTimeFormatter.ISO_LOCAL_DATE_TIME)
            } catch (_: Exception) {
                null
            }
        }

    val isConfigured: Boolean
        get() = credentialStore.isConfigured && com.nevoit.cresto.feature.settings.util.SettingsManager.isWebDavSyncEnabled

    // ── internal ──

    private suspend fun requestSyncInternal() {
        if (isSyncing) {
            pendingSync = true
            return
        }
        if (!credentialStore.isConfigured) return

        isSyncing = true
        _syncStatus.value = SyncStatus.Syncing

        try {
            val result = executeSync()
            retryCount = 0

            when (result) {
                is SyncResult.Success -> {
                    mmkv.encode(KEY_LAST_SYNC_TIME, LocalDateTime.now().format(
                        DateTimeFormatter.ISO_LOCAL_DATE_TIME
                    ))
                    _syncStatus.value = SyncStatus.Success(
                        itemsSynced = result.itemsSynced,
                        conflictsFound = result.conflictsFound
                    )
                }
                is SyncResult.Error -> {
                    _syncStatus.value = SyncStatus.Error(
                        message = result.message,
                        isRetryable = result.isRetryable
                    )
                    if (result.isRetryable) {
                        scheduleRetry()
                    }
                }
                is SyncResult.SkippedNoChanges -> {
                    _syncStatus.value = SyncStatus.Idle
                }
                is SyncResult.NotConfigured -> {
                    _syncStatus.value = SyncStatus.NotConfigured
                }
                is SyncResult.AlreadyRunning -> {
                    // should not happen since we check isSyncing
                }
                is SyncResult.Conflict -> {
                    mmkv.encode(KEY_LAST_SYNC_TIME, LocalDateTime.now().format(
                        DateTimeFormatter.ISO_LOCAL_DATE_TIME
                    ))
                    _syncStatus.value = SyncStatus.Success(
                        itemsSynced = 0,
                        conflictsFound = result.count
                    )
                }
            }
        } catch (e: Exception) {
            _syncStatus.value = SyncStatus.Error(
                message = e.message ?: "Unknown sync error",
                isRetryable = true
            )
            scheduleRetry()
        } finally {
            isSyncing = false
            _syncStatus.value.let {
                if (it is SyncStatus.Syncing) {
                    _syncStatus.value = SyncStatus.Idle
                }
            }
            if (pendingSync) {
                pendingSync = false
                requestSyncInternal() // run the queued one
            }
        }
    }

    /**
     * The core sync cycle:
     * 1. Export local state from Room DB
     * 2. Download remote state from WebDAV
     * 3. Load last synced snapshot from MMKV
     * 4. Three-way merge
     * 5. Upload merged state (with ETag)
     * 6. Apply merged state to local DB
     * 7. Save conflicts
     */
    private suspend fun executeSync(): SyncResult {
        // Step 1: Export local state
        val localTodos = repository.getAllTodosSnapshotOrderedByUpdatedAt()
        val localState = MergeEngine.buildLocalStateMap(localTodos)

        // Step 2: Download remote state
        val downloadResult = webDavClient.download()
        if (downloadResult.isFailure) {
            val error = downloadResult.exceptionOrNull()!!
            return when (error) {
                is WebDavException.NotFound -> {
                    val json = MergeEngine.serializeSyncState(localState, emptySet())
                    val uploadResult = webDavClient.upload(json)
                    if (uploadResult.isSuccess) {
                        saveLastSyncedSnapshot(localState)
                        saveDeletedSyncIds(emptySet())
                        SyncResult.Success(
                            itemsSynced = localState.size,
                            conflictsFound = 0,
                            itemsPushed = localState.size,
                            itemsPulled = 0,
                            itemsDeleted = 0
                        )
                    } else {
                        val uploadError = uploadResult.exceptionOrNull()
                        SyncResult.Error("Failed to upload during initial sync: ${uploadError?.message}", true)
                    }
                }
                is WebDavException.AuthFailed -> {
                    SyncResult.Error("Authentication failed. Check your WebDAV credentials.", false)
                }
                else -> {
                    SyncResult.Error(error.message ?: "Download failed", true)
                }
            }
        }

        val (remoteContent, remoteETag) = downloadResult.getOrNull()!!
            .let { it.content to it.etag }

        val remoteState = MergeEngine.parseRemoteState(remoteContent)

        // Step 3: Load last synced snapshot (base)
        val baseState = loadLastSyncedSnapshot()

        // Quick check: if nothing changed locally and nothing changed remotely, skip
        val localSnapshotJson = MergeEngine.serializeSyncState(localState, emptySet())
        val remoteSnapshotJson = MergeEngine.serializeSyncState(remoteState, emptySet())
        if (localSnapshotJson == remoteSnapshotJson) {
            // States are identical — nothing to do
            saveLastSyncedSnapshot(localState)
            return SyncResult.SkippedNoChanges
        }

        // Step 4: Three-way merge
        var mergeResult = MergeEngine.threeWayMerge(baseState, localState, remoteState)

        var currentETag = remoteETag
        var mergedJson = MergeEngine.serializeSyncState(
            mergeResult.mergedState,
            mergeResult.deletedSyncIds
        )

        // Step 5: Upload merged state with retry on 412
        var uploadAttempts = 0
        var uploadSuccess = false
        while (uploadAttempts < MAX_UPLOAD_RETRIES && !uploadSuccess) {
            val uploadResult = webDavClient.upload(mergedJson, etag = currentETag)
            if (uploadResult.isSuccess) {
                uploadSuccess = true
            } else {
                val error = uploadResult.exceptionOrNull()
                if (error is WebDavException.PreconditionFailed) {
                    // Re-download, re-merge
                    val reDownload = webDavClient.download()
                    if (reDownload.isSuccess) {
                        val (newContent, newETag) = reDownload.getOrNull()!!
                            .let { it.content to it.etag }
                        currentETag = newETag
                        val newRemoteState = MergeEngine.parseRemoteState(newContent)
                        // Re-merge with the new remote state as base
                        val reMerge = MergeEngine.threeWayMerge(
                            loadLastSyncedSnapshot(),
                            localState,
                            newRemoteState
                        )
                        mergedJson = MergeEngine.serializeSyncState(
                            reMerge.mergedState,
                            reMerge.deletedSyncIds
                        )
                        // Update conflicts
                        mergeResult = mergeResult.copy(
                            conflicts = mergeResult.conflicts + reMerge.conflicts
                        )
                    }
                } else {
                    return SyncResult.Error(
                        error?.message ?: "Upload failed",
                        error is WebDavException.NetworkError
                    )
                }
            }
            uploadAttempts++
        }

        if (!uploadSuccess) {
            return SyncResult.Error("Upload failed after retries", true)
        }

        // Step 6: Apply to local DB
        var itemsApplied = 0
        for ((syncId, snapshot) in mergeResult.mergedState) {
            val existing = repository.getLocalIdBySyncId(syncId)
            if (existing != null) {
                repository.updateBySyncIdFromSnapshot(snapshot)
            } else {
                repository.insertSyncSnapshot(snapshot)
            }
            itemsApplied++
        }

        // Delete items that were deleted during merge
        if (mergeResult.deletedSyncIds.isNotEmpty()) {
            repository.deleteBySyncIds(mergeResult.deletedSyncIds.toList())
        }

        // Step 7: Save state for next sync
        saveDeletedSyncIds(mergeResult.deletedSyncIds)
        saveLastSyncedSnapshot(mergeResult.mergedState)

        // Step 8: Persist conflicts
        if (mergeResult.conflicts.isNotEmpty()) {
            saveConflicts(mergeResult.conflicts)
        }

        return SyncResult.Success(
            itemsSynced = mergeResult.summary.totalChanges,
            conflictsFound = mergeResult.conflicts.size,
            itemsPushed = mergeResult.summary.itemsPushedToRemote,
            itemsPulled = mergeResult.summary.itemsPulledToLocal,
            itemsDeleted = mergeResult.summary.itemsDeleted
        )
    }

    private fun saveLastSyncedSnapshot(state: Map<String, TodoSyncSnapshot>) {
        val jsonStr = MergeEngine.serializeSyncState(state, getStoredDeletedSyncIds())
        mmkv.encode(KEY_LAST_SYNCED, jsonStr)
    }

    private fun loadLastSyncedSnapshot(): Map<String, TodoSyncSnapshot> {
        val raw = mmkv.decodeString(KEY_LAST_SYNCED) ?: return emptyMap()
        return try {
            val parsed = json.decodeFromString<SyncFile>(raw)
            parsed.items.associateBy { it.syncId }
        } catch (_: Exception) {
            emptyMap()
        }
    }

    private fun saveDeletedSyncIds(ids: Set<String>) {
        mmkv.encode(KEY_DELETED_IDS, json.encodeToString(ids.toList()))
    }

    private fun getStoredDeletedSyncIds(): Set<String> {
        val raw = mmkv.decodeString(KEY_DELETED_IDS) ?: return emptySet()
        return try {
            json.decodeFromString<List<String>>(raw).toSet()
        } catch (_: Exception) {
            emptySet()
        }
    }

    private fun saveConflicts(conflicts: List<SyncConflict>) {
        val existing = getConflicts().toMutableList()
        existing.addAll(conflicts)
        mmkv.encode(KEY_CONFLICTS, json.encodeToString(existing))
    }

    private fun scheduleRetry() {
        if (retryCount >= MAX_RETRY_COUNT) return
        retryCount++
        syncScope.launch {
            delay(RETRY_DELAY_MS * retryCount)
            requestSyncInternal()
        }
    }

    companion object {
        private const val DEBOUNCE_MS = 2000L
        private const val MAX_UPLOAD_RETRIES = 3
        private const val MAX_RETRY_COUNT = 5
        private const val RETRY_DELAY_MS = 30_000L

        private const val KEY_LAST_SYNCED = "sync_last_synced_snapshot"
        private const val KEY_LAST_SYNC_TIME = "sync_last_sync_time"
        private const val KEY_CONFLICTS = "sync_conflicts"
        private const val KEY_DELETED_IDS = "sync_deleted_ids"
    }
}
