package com.nevoit.cresto.data.sync

import com.nevoit.cresto.data.todo.TodoItemWithSubTodos
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Stateless three-way merge engine. Pure logic, no side effects.
 */
object MergeEngine {

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Execute the three-way merge algorithm.
     *
     * @param baseState The state at last successful sync (common ancestor).
     * @param localState The current local state from Room DB.
     * @param remoteState The current remote state from WebDAV.
     * @return MergeResult containing merged state, deletions, and conflicts.
     */
    fun threeWayMerge(
        baseState: Map<String, TodoSyncSnapshot>,
        localState: Map<String, TodoSyncSnapshot>,
        remoteState: Map<String, TodoSyncSnapshot>
    ): MergeResult {
        val allSyncIds = (baseState.keys + localState.keys + remoteState.keys).toSet()
        val merged = mutableMapOf<String, TodoSyncSnapshot>()
        val deleted = mutableSetOf<String>()
        val conflicts = mutableListOf<SyncConflict>()
        var pushed = 0
        var pulled = 0
        var deletedCount = 0

        for (syncId in allSyncIds) {
            val base = baseState[syncId]
            val local = localState[syncId]
            val remote = remoteState[syncId]

            when {
                // 1. Nonexistent on all sides → skip
                base == null && local == null && remote == null -> Unit

                // 2. New locally only → push to remote
                base == null && local != null && remote == null -> {
                    merged[syncId] = local
                    pushed++
                }

                // 3. New remotely only → pull to local
                base == null && local == null && remote != null -> {
                    merged[syncId] = remote
                    pulled++
                }

                // 4. Both created independently → conflict
                base == null && local != null && remote != null -> {
                    if (snapshotsEqual(local, remote)) {
                        merged[syncId] = local // same content, pick either
                    } else {
                        conflicts.add(createConflict(syncId, local, remote, listOf("*created*")))
                        // Keep local by default, user can choose remote
                        merged[syncId] = local
                    }
                }

                // 5. Both deleted → confirm
                base != null && local == null && remote == null -> {
                    deleted.add(syncId)
                    deletedCount++
                }

                // 6. Remote deleted, local exists
                base != null && local != null && remote == null -> {
                    if (snapshotsEqual(base, local)) {
                        // Local unchanged → accept deletion
                        deleted.add(syncId)
                        deletedCount++
                    } else {
                        // Local modified → keep local, conflict on deletion
                        conflicts.add(createConflict(syncId, local, base, listOf("*deleted*")))
                        merged[syncId] = local
                    }
                }

                // 7. Local deleted, remote exists
                base != null && local == null && remote != null -> {
                    if (snapshotsEqual(base, remote)) {
                        // Remote unchanged → propagate deletion
                        deleted.add(syncId)
                        deletedCount++
                    } else {
                        // Remote modified → pull remote (local deletion loses)
                        merged[syncId] = remote
                        pulled++
                    }
                }

                // 8. All three exist → standard three-way merge
                base != null && local != null && remote != null -> {
                    val result = mergeExisting(syncId, base, local, remote)
                    result.merged?.let { merged[syncId] = it }
                    result.deleted?.let {
                        deleted.add(it)
                        deletedCount++
                    }
                    if (result.pushed) pushed++
                    if (result.pulled) pulled++
                    if (result.conflict != null) conflicts.add(result.conflict)
                }
            }
        }

        return MergeResult(
            mergedState = merged,
            deletedSyncIds = deleted,
            conflicts = conflicts,
            summary = MergeSummary(
                itemsPushedToRemote = pushed,
                itemsPulledToLocal = pulled,
                itemsDeleted = deletedCount,
                itemsConflicted = conflicts.size
            )
        )
    }

    private fun mergeExisting(
        syncId: String,
        base: TodoSyncSnapshot,
        local: TodoSyncSnapshot,
        remote: TodoSyncSnapshot
    ): ExistingMergeResult {
        // Not modified on either side
        if (snapshotsEqual(local, remote)) return ExistingMergeResult(merged = local)

        // Only local modified
        if (snapshotsEqual(base, remote) && !snapshotsEqual(base, local)) {
            return ExistingMergeResult(merged = local, pushed = true)
        }

        // Only remote modified
        if (snapshotsEqual(base, local) && !snapshotsEqual(base, remote)) {
            return ExistingMergeResult(merged = remote, pulled = true)
        }

        // Both modified → field-level merge
        return fieldLevelMerge(syncId, base, local, remote)
    }

    private fun fieldLevelMerge(
        syncId: String,
        base: TodoSyncSnapshot,
        local: TodoSyncSnapshot,
        remote: TodoSyncSnapshot
    ): ExistingMergeResult {
        val conflictFields = mutableListOf<String>()

        val title = resolveField(base.title, local.title, remote.title, "title", conflictFields)
        val dueDate = resolveField(base.dueDate, local.dueDate, remote.dueDate, "dueDate", conflictFields)
        val isCompleted = resolveCompleted(base, local, remote, conflictFields)
        val notes = resolveNotes(base.notes, local.notes, remote.notes)
        val flag = resolveField(base.flag.toString(), local.flag.toString(), remote.flag.toString(), "flag", conflictFields)
        val startTime = resolveField(base.startTime, local.startTime, remote.startTime, "startTime", conflictFields)
        val endTime = resolveField(base.endTime, local.endTime, remote.endTime, "endTime", conflictFields)

        val reminderMode = resolveField(base.reminderMode, local.reminderMode, remote.reminderMode, "reminderMode", conflictFields)
        val reminderOffsetMinutes = resolveField(
            base.reminderOffsetMinutes?.toString(),
            local.reminderOffsetMinutes?.toString(),
            remote.reminderOffsetMinutes?.toString(),
            "reminderOffsetMinutes",
            conflictFields
        )?.toIntOrNull()
        val reminderDayOffset = resolveField(
            base.reminderDayOffset?.toString(),
            local.reminderDayOffset?.toString(),
            remote.reminderDayOffset?.toString(),
            "reminderDayOffset",
            conflictFields
        )?.toIntOrNull()
        val reminderTime = resolveField(base.reminderTime, local.reminderTime, remote.reminderTime, "reminderTime", conflictFields)

        // Sub-todos: compare as whole list
        val subTodos = resolveSubTodos(base.subTodos, local.subTodos, remote.subTodos, conflictFields)

        val mergedSnapshot = local.copy(
            title = title ?: "",
            dueDate = dueDate,
            isCompleted = isCompleted.first,
            completedDateTime = isCompleted.second,
            notes = notes,
            flag = flag?.toIntOrNull() ?: local.flag,
            startTime = startTime,
            endTime = endTime,
            reminderMode = reminderMode,
            reminderOffsetMinutes = reminderOffsetMinutes,
            reminderDayOffset = reminderDayOffset,
            reminderTime = reminderTime,
            subTodos = subTodos
        )

        return if (conflictFields.isEmpty()) {
            // All fields auto-merged, no conflict
            ExistingMergeResult(merged = mergedSnapshot, pushed = true, pulled = true)
        } else {
            ExistingMergeResult(
                merged = mergedSnapshot, // use local version as default
                conflict = createConflict(syncId, local, remote, conflictFields)
            )
        }
    }

    private fun resolveField(
        base: String?,
        local: String?,
        remote: String?,
        fieldName: String,
        conflictFields: MutableList<String>
    ): String? {
        if (local == remote) return local
        return if (local == base) remote
        else if (remote == base) local
        else {
            conflictFields.add(fieldName)
            local // default to local on conflict
        }
    }

    private fun resolveCompleted(
        base: TodoSyncSnapshot,
        local: TodoSyncSnapshot,
        remote: TodoSyncSnapshot,
        conflictFields: MutableList<String>
    ): Pair<Boolean, String?> {
        val baseCompleted = base.isCompleted
        val localCompleted = local.isCompleted
        val remoteCompleted = remote.isCompleted

        return if (localCompleted == remoteCompleted) {
            localCompleted to local.completedDateTime
        } else if (localCompleted == baseCompleted) {
            remoteCompleted to remote.completedDateTime
        } else if (remoteCompleted == baseCompleted) {
            localCompleted to local.completedDateTime
        } else {
            // Both changed completion status → LWW by updatedAt
            conflictFields.add("isCompleted")
            val localTime = parseDateTime(local.updatedAt)
            val remoteTime = parseDateTime(remote.updatedAt)
            if (localTime >= remoteTime) localCompleted to local.completedDateTime
            else remoteCompleted to remote.completedDateTime
        }
    }

    private fun resolveNotes(
        base: String,
        local: String,
        remote: String
    ): String {
        return when {
            local == remote -> local
            local == base -> remote
            remote == base -> local
            else -> {
                // Both modified notes → append with separator
                val localNew = if (local != base) local else null
                val remoteNew = if (remote != base) remote else null
                listOfNotNull(localNew, remoteNew).joinToString("\n\n---\n\n")
            }
        }
    }

    private fun resolveSubTodos(
        base: List<SubTodoSyncSnapshot>,
        local: List<SubTodoSyncSnapshot>,
        remote: List<SubTodoSyncSnapshot>,
        conflictFields: MutableList<String>
    ): List<SubTodoSyncSnapshot> {
        val baseMap = base.associateBy { it.syncId }
        val localMap = local.associateBy { it.syncId }
        val remoteMap = remote.associateBy { it.syncId }

        val allSubSyncIds = (baseMap.keys + localMap.keys + remoteMap.keys).toSet()

        if (allSubSyncIds.isEmpty()) return local

        // Quick check: if lists are identical, no conflict
        if (subTodoListsEqual(local, remote)) return local

        // If only one side changed from base, accept that side
        if (subTodoListsEqual(base, remote)) return local
        if (subTodoListsEqual(base, local)) return remote

        // Both changed → do sub-level merge
        val merged = mutableListOf<SubTodoSyncSnapshot>()
        var anyConflict = false

        for (subId in allSubSyncIds) {
            val subBase = baseMap[subId]
            val subLocal = localMap[subId]
            val subRemote = remoteMap[subId]

            when {
                subLocal == null && subRemote == null -> Unit // deleted on both

                subLocal != null && subRemote == null -> {
                    // deleted on remote, exists locally
                    if (subBase != null && snapshotsEqual(subBase, subLocal)) {
                        Unit // remote deletion accepted
                    } else {
                        merged.add(subLocal) // kept locally
                    }
                }

                subLocal == null && subRemote != null -> {
                    // deleted locally, exists on remote
                    if (subBase != null && snapshotsEqual(subBase, subRemote)) {
                        Unit // local deletion accepted
                    } else {
                        merged.add(subRemote) // keep from remote
                    }
                }

                subLocal != null && subRemote != null -> {
                    if (subBase == null) {
                        // New sub on both sides (created independently)
                        merged.add(subLocal)
                        if (!snapshotsEqual(subLocal, subRemote)) {
                            anyConflict = true
                        }
                    } else if (snapshotsEqual(subLocal, subRemote)) {
                        merged.add(subLocal)
                    } else {
                        // One side might equal base
                        if (snapshotsEqual(subBase, subLocal)) {
                            merged.add(subRemote) // accept remote change
                        } else if (snapshotsEqual(subBase, subRemote)) {
                            merged.add(subLocal) // accept local change
                        } else {
                            merged.add(subLocal) // both changed, prefer local
                            anyConflict = true
                        }
                    }
                }
            }
        }

        if (anyConflict) {
            conflictFields.add("subTodos")
        }

        return merged
    }

    // ── helpers ──

    fun buildSnapshotFromLocal(todoWithSubTodos: TodoItemWithSubTodos): TodoSyncSnapshot {
        val todo = todoWithSubTodos.todoItem
        return TodoSyncSnapshot(
            syncId = todo.syncId,
            title = todo.title,
            dueDate = todo.dueDate?.toString(),
            creationDateTime = todo.creationDateTime.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME),
            updatedAt = todo.updatedAt.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME),
            isCompleted = todo.isCompleted,
            completedDateTime = todo.completedDateTime?.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME),
            flag = todo.flag,
            notes = todo.notes,
            startTime = todo.startTime?.format(DateTimeFormatter.ISO_LOCAL_TIME),
            endTime = todo.endTime?.format(DateTimeFormatter.ISO_LOCAL_TIME),
            reminderMode = todo.reminderMode?.name,
            reminderOffsetMinutes = todo.reminderOffsetMinutes,
            reminderDayOffset = todo.reminderDayOffset,
            reminderTime = todo.reminderTime?.format(DateTimeFormatter.ISO_LOCAL_TIME),
            reminderPersistent = todo.reminderPersistent,
            reminderStrong = todo.reminderStrong,
            subTodos = todoWithSubTodos.subTodos.map { sub ->
                SubTodoSyncSnapshot(
                    syncId = sub.syncId,
                    description = sub.description,
                    isCompleted = sub.isCompleted
                )
            }
        )
    }

    fun snapshotsEqual(a: TodoSyncSnapshot, b: TodoSyncSnapshot): Boolean {
        return json.encodeToString(a) == json.encodeToString(b)
    }

    fun snapshotsEqual(a: SubTodoSyncSnapshot, b: SubTodoSyncSnapshot): Boolean {
        return json.encodeToString(a) == json.encodeToString(b)
    }

    fun parseDateTime(value: String): LocalDateTime =
        try {
            LocalDateTime.parse(value, DateTimeFormatter.ISO_LOCAL_DATE_TIME)
        } catch (_: Exception) {
            LocalDateTime.MIN
        }

    /**
     * Build a map of syncId → TodoSyncSnapshot from a list of TodoItemWithSubTodos.
     */
    fun buildLocalStateMap(todos: List<TodoItemWithSubTodos>): Map<String, TodoSyncSnapshot> {
        return todos.map { buildSnapshotFromLocal(it) }.associateBy { it.syncId }
    }

    /**
     * Parse remote JSON string into a map of syncId → TodoSyncSnapshot.
     */
    fun parseRemoteState(jsonContent: String): Map<String, TodoSyncSnapshot> {
        return try {
            val syncFile = json.decodeFromString<SyncFile>(jsonContent)
            syncFile.items.associateBy { it.syncId }
        } catch (_: Exception) {
            emptyMap()
        }
    }

    /**
     * Parse deleted sync IDs from remote JSON.
     */
    fun parseDeletedSyncIds(jsonContent: String): Set<String> {
        return try {
            json.decodeFromString<SyncFile>(jsonContent).deletedSyncIds.toSet()
        } catch (_: Exception) {
            emptySet()
        }
    }

    /**
     * Serialize merged state + deleted IDs to JSON.
     */
    fun serializeSyncState(
        mergedState: Map<String, TodoSyncSnapshot>,
        deletedSyncIds: Set<String>
    ): String {
        val syncFile = SyncFile(
            lastUpdatedAt = LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME),
            items = mergedState.values.toList(),
            deletedSyncIds = deletedSyncIds.toList()
        )
        return json.encodeToString(syncFile)
    }

    private fun subTodoListsEqual(a: List<SubTodoSyncSnapshot>, b: List<SubTodoSyncSnapshot>): Boolean {
        if (a.size != b.size) return false
        val aMap = a.associateBy { it.syncId }
        val bMap = b.associateBy { it.syncId }
        if (aMap.keys != bMap.keys) return false
        return aMap.all { (id, subA) ->
            val subB = bMap[id] ?: return@all false
            subA.description == subB.description && subA.isCompleted == subB.isCompleted
        }
    }

    private fun createConflict(
        syncId: String,
        local: TodoSyncSnapshot,
        remote: TodoSyncSnapshot,
        fields: List<String>
    ): SyncConflict {
        return SyncConflict(
            syncId = syncId,
            title = local.title,
            conflictFields = fields,
            localSnapshot = local,
            remoteSnapshot = remote,
            createdAt = LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
        )
    }

    private data class ExistingMergeResult(
        val merged: TodoSyncSnapshot? = null,
        val deleted: String? = null,
        val pushed: Boolean = false,
        val pulled: Boolean = false,
        val conflict: SyncConflict? = null
    )
}

data class MergeResult(
    val mergedState: Map<String, TodoSyncSnapshot>,
    val deletedSyncIds: Set<String>,
    val conflicts: List<SyncConflict>,
    val summary: MergeSummary
)

data class MergeSummary(
    val itemsPushedToRemote: Int,
    val itemsPulledToLocal: Int,
    val itemsDeleted: Int,
    val itemsConflicted: Int
) {
    val totalChanges: Int get() = itemsPushedToRemote + itemsPulledToLocal + itemsDeleted
}
