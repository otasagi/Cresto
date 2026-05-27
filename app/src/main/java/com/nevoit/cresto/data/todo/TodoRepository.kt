package com.nevoit.cresto.data.todo

import androidx.room.withTransaction
import com.nevoit.cresto.data.statistics.DailyStat
import com.nevoit.cresto.data.todo.backup.SubTodoBackupDto
import com.nevoit.cresto.data.todo.backup.TodoBackupDto
import com.nevoit.cresto.data.todo.backup.TodoBackupFile
import com.nevoit.cresto.data.todo.calendar.CalendarSyncResult
import com.nevoit.cresto.data.todo.calendar.CalendarSyncStatus
import com.nevoit.cresto.data.todo.calendar.CalendarSyncSummary
import com.nevoit.cresto.data.todo.calendar.TodoCalendarSyncManager
import com.nevoit.cresto.feature.settings.util.SettingsManager
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.Json
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.UUID

fun TodoItem.ensureSyncId(): TodoItem {
    return if (syncId.isBlank()) copy(syncId = UUID.randomUUID().toString()) else this
}

enum class DuplicatePolicy {
    SKIP_DUPLICATES,
    IMPORT_ALL
}

data class ImportResult(
    val total: Int,
    val imported: Int,
    val skipped: Int
)

/**
 * Callback invoked after any write operation on the repository.
 * Used by SyncManager to trigger debounced sync.
 */
fun interface DataChangeListener {
    fun onDataChanged()
}

/**
 * A repository that provides a single source of truth for all to-do data.
 * It abstracts the data source (in this case, a Room database) from the rest of the app.
 *
 * @param todoDao The Data Access Object for the to-do items.
 * @param todoDatabase The Room database instance.
 * @param calendarSyncManager The calendar sync manager.
 */
class TodoRepository(
    private val todoDao: TodoDao,
    private val todoDatabase: TodoDatabase,
    private val calendarSyncManager: TodoCalendarSyncManager
) {

    @Volatile
    private var dataChangeListener: DataChangeListener? = null

    /**
     * Register a listener to be notified after data changes.
     * Wired by DI in AppModule to avoid circular dependencies.
     */
    fun setDataChangeListener(listener: DataChangeListener) {
        dataChangeListener = listener
    }

    private fun notifyDataChanged() {
        dataChangeListener?.onDataChanged()
    }

    val allTodos: Flow<List<TodoItemWithSubTodos>> = todoDao.getAllTodosWithSubTodos()

    fun getTodosByDate(date: LocalDate): Flow<List<TodoItemWithSubTodos>> {
        return todoDao.getTodosByDate(date)
    }

    fun getDatesWithTodo(): Flow<List<LocalDate>> {
        return todoDao.getDatesWithTodo()
    }

    fun getTodoById(id: Int): Flow<TodoItemWithSubTodos?> {
        return todoDao.getTodoWithSubTodosById(id)
    }

    suspend fun insert(item: TodoItem): Long {
        val itemWithSync = item.ensureSyncId().markUpdated()
        val id = todoDao.insertTodo(itemWithSync)
        syncTodoByIdIfAutoEnabled(id.toInt())
        notifyDataChanged()
        return id
    }

    suspend fun insertAll(items: List<TodoItem>) {
        todoDao.insertAll(items.map { it.ensureSyncId().markUpdated() })
        notifyDataChanged()
    }

    suspend fun update(item: TodoItem) {
        val existingCalendarState = todoDao.getTodoWithSubTodosByIdSnapshot(item.id)?.todoItem
        val itemToPersist = item.markUpdated().copy(
            calendarEventId = item.calendarEventId ?: existingCalendarState?.calendarEventId,
            calendarSyncedAt = item.calendarSyncedAt ?: existingCalendarState?.calendarSyncedAt
        )
        todoDao.updateTodo(itemToPersist)
        syncTodoByIdIfAutoEnabled(itemToPersist.id)
        notifyDataChanged()
    }

    suspend fun delete(item: TodoItem) {
        deleteCalendarEventIfPresent(item)
        todoDao.deleteTodo(item)
        notifyDataChanged()
    }

    suspend fun insertSubTodo(item: SubTodoItem) {
        todoDao.insertSubTodo(item)
        syncTodoByIdIfAutoEnabled(item.parentId)
        notifyDataChanged()
    }

    suspend fun insertAiGeneratedTodosWithSubTasks(aiItems: List<com.nevoit.cresto.data.utils.EventItem>): List<TodoItem> {
        if (aiItems.isEmpty()) return emptyList()

        val now = LocalDateTime.now()
        val insertedTodos = todoDatabase.withTransaction {
            aiItems.map { eventItem ->
                val todo = TodoItem(
                    syncId = UUID.randomUUID().toString(),
                    title = eventItem.title,
                    creationDateTime = now,
                    updatedAt = now,
                    isCompleted = eventItem.isCompleted,
                    completedDateTime = if (eventItem.isCompleted) now else null,
                    dueDate = try {
                        LocalDate.parse(eventItem.date, DateTimeFormatter.ISO_LOCAL_DATE)
                    } catch (_: Exception) {
                        LocalDate.now()
                    },
                    startTime = eventItem.startTime?.let {
                        try {
                            LocalTime.parse(it, DateTimeFormatter.ofPattern("HH:mm"))
                        } catch (_: Exception) {
                            null
                        }
                    },
                    endTime = eventItem.endTime?.let {
                        try {
                            LocalTime.parse(it, DateTimeFormatter.ofPattern("HH:mm"))
                        } catch (_: Exception) {
                            null
                        }
                    },
                    reminderMode = eventItem.reminderMode?.let {
                        try {
                            TodoReminderMode.valueOf(it)
                        } catch (_: Exception) {
                            null
                        }
                    },
                    reminderOffsetMinutes = eventItem.reminderOffsetMinutes,
                    reminderDayOffset = eventItem.reminderDayOffset,
                    reminderTime = eventItem.reminderTime?.let {
                        try {
                            LocalTime.parse(it, DateTimeFormatter.ofPattern("HH:mm"))
                        } catch (_: Exception) {
                            null
                        }
                    }
                )
                val insertedTodo = todo.copy(
                    id = todoDao.insertTodoForImport(todo).toInt()
                )

                val subTodos = eventItem.subTasks
                    .map(String::trim)
                    .filter(String::isNotEmpty)
                    .distinct()
                    .map { subTitle ->
                        SubTodoItem(
                            syncId = UUID.randomUUID().toString(),
                            parentId = insertedTodo.id,
                            description = subTitle,
                            isCompleted = eventItem.isCompleted
                        )
                    }

                subTodos.forEach { subTodo ->
                    todoDao.insertSubTodoForImport(subTodo)
                }

                insertedTodo
            }
        }

        syncTodoIdsIfAutoEnabled(insertedTodos.map { it.id })
        notifyDataChanged()
        return insertedTodos
    }

    suspend fun updateSubTodo(item: SubTodoItem) {
        todoDao.updateSubTodo(item)
        syncTodoByIdIfAutoEnabled(item.parentId)
        notifyDataChanged()
    }

    suspend fun deleteSubTodo(item: SubTodoItem) {
        todoDao.deleteSubTodo(item)
        syncTodoByIdIfAutoEnabled(item.parentId)
        notifyDataChanged()
    }

    suspend fun deleteById(id: Int) {
        todoDao.getTodoWithSubTodosByIdSnapshot(id)?.let { deleteCalendarEventIfPresent(it.todoItem) }
        todoDao.deleteById(id)
        notifyDataChanged()
    }

    suspend fun deleteByIds(ids: List<Int>) {
        todoDao.getTodosWithSubTodosByIds(ids)
            .map { it.todoItem }
            .forEach { deleteCalendarEventIfPresent(it) }
        todoDao.deleteByIds(ids)
        notifyDataChanged()
    }

    suspend fun updateCompletedStatusByIds(
        ids: List<Int>,
        isCompleted: Boolean,
        completedDateTime: LocalDateTime?
    ) {
        todoDao.updateCompletedStatusByIds(ids, isCompleted, completedDateTime)
        notifyDataChanged()
    }

    suspend fun getCompletedCountByIds(ids: List<Int>): Int {
        return todoDao.getCompletedCountByIds(ids)
    }

    suspend fun updateFlagByIds(ids: List<Int>, flag: Int) {
        todoDao.updateFlagByIds(ids, flag)
        notifyDataChanged()
    }

    suspend fun duplicateByIds(ids: List<Int>): List<TodoItem> {
        if (ids.isEmpty()) return emptyList()

        val insertedTodos = todoDatabase.withTransaction {
            val sourceTodosById = todoDao.getTodosWithSubTodosByIds(ids)
                .associateBy { it.todoItem.id }
            val orderedSourceTodos = ids.mapNotNull(sourceTodosById::get).asReversed()
            if (orderedSourceTodos.isEmpty()) return@withTransaction emptyList()

            val now = LocalDateTime.now()
            val todoCopies = orderedSourceTodos.mapIndexed { index, source ->
                source.todoItem.copy(
                    id = 0,
                    syncId = UUID.randomUUID().toString(),
                    creationDateTime = now.plusNanos(index * 1000000L),
                    updatedAt = now,
                    isCompleted = false,
                    completedDateTime = null,
                    calendarEventId = null,
                    calendarSyncedAt = null
                )
            }

            val newTodoIds = todoDao.insertTodosForDuplicate(todoCopies)
                .map(Long::toInt)

            val subTodoCopies =
                orderedSourceTodos.zip(newTodoIds).flatMap { (source, newTodoId) ->
                    source.subTodos.map { subTodo ->
                        subTodo.copy(
                            id = 0,
                            syncId = UUID.randomUUID().toString(),
                            parentId = newTodoId
                        )
                    }
                }

            if (subTodoCopies.isNotEmpty()) {
                todoDao.insertSubTodosForDuplicate(subTodoCopies)
            }

            todoCopies.zip(newTodoIds).map { (todo, newTodoId) ->
                todo.copy(id = newTodoId)
            }
        }

        syncTodoIdsIfAutoEnabled(insertedTodos.map { it.id })
        notifyDataChanged()
        return insertedTodos
    }

    suspend fun mergeByIdsAsSubTodos(ids: List<Int>, newTodoTitle: String): Int {
        if (ids.isEmpty()) return 0

        var sourceTodosForCalendar = emptyList<TodoItem>()
        var mergedTodoId = 0
        val mergedSubTodoCount = todoDatabase.withTransaction {
            val sourceTodosById = todoDao.getTodosWithSubTodosByIds(ids)
                .associateBy { it.todoItem.id }
            val orderedSourceTodos = ids.mapNotNull(sourceTodosById::get)
            if (orderedSourceTodos.isEmpty()) return@withTransaction 0
            sourceTodosForCalendar = orderedSourceTodos.map { it.todoItem }

            val latestDueDate = orderedSourceTodos
                .mapNotNull { it.todoItem.dueDate }
                .maxOrNull()

            val now = LocalDateTime.now()
            val newTodoId = todoDao.insertTodoForMerge(
                TodoItem(
                    id = 0,
                    syncId = UUID.randomUUID().toString(),
                    title = newTodoTitle,
                    creationDateTime = now,
                    updatedAt = now,
                    dueDate = latestDueDate
                )
            ).toInt()
            mergedTodoId = newTodoId

            val mergedSubTodos = orderedSourceTodos.flatMap { source ->
                buildList {
                    add(
                        SubTodoItem(
                            id = 0,
                            syncId = UUID.randomUUID().toString(),
                            parentId = newTodoId,
                            description = source.todoItem.title,
                            isCompleted = source.todoItem.isCompleted
                        )
                    )
                    addAll(
                        source.subTodos.map { subTodo ->
                            subTodo.copy(
                                id = 0,
                                syncId = UUID.randomUUID().toString(),
                                parentId = newTodoId
                            )
                        }
                    )
                }
            }

            if (mergedSubTodos.isNotEmpty()) {
                todoDao.insertSubTodosForMerge(mergedSubTodos)
            }

            todoDao.deleteByIds(orderedSourceTodos.map { it.todoItem.id })

            mergedSubTodos.size
        }

        sourceTodosForCalendar.forEach { deleteCalendarEventIfPresent(it) }
        syncTodoByIdIfAutoEnabled(mergedTodoId)
        notifyDataChanged()
        return mergedSubTodoCount
    }

    fun getTotalCount(): Flow<Int> {
        return todoDao.getTotalCount()
    }

    fun getCompletedCount(): Flow<Int> {
        return todoDao.getCompletedCount()
    }

    fun getDailyStatistics(): Flow<List<DailyStat>> {
        return todoDao.getDailyStats()
    }

    fun getTodoCountByDueDate(date: LocalDate): Flow<Int> {
        return todoDao.getTodoCountByDueDate(date)
    }

    fun getCompletedTodoCountByDueDate(date: LocalDate): Flow<Int> {
        return todoDao.getCompletedTodoCountByDueDate(date)
    }

    fun getTodoCountByDueDateRange(startDate: LocalDate, endDate: LocalDate): Flow<Int> {
        return todoDao.getTodoCountByDueDateRange(startDate, endDate)
    }

    fun getCompletedTodoCountByDueDateRange(
        startDate: LocalDate,
        endDate: LocalDate
    ): Flow<Int> {
        return todoDao.getCompletedTodoCountByDueDateRange(startDate, endDate)
    }

    fun getPendingTodoCount(): Flow<Int> {
        return todoDao.getPendingTodoCount()
    }

    fun getOverdueTodoCount(today: LocalDate): Flow<Int> {
        return todoDao.getOverdueTodoCount(today)
    }

    fun getStalePendingTodoCount(thresholdDate: LocalDate): Flow<Int> {
        return todoDao.getStalePendingTodoCount(thresholdDate)
    }

    fun getOldestPendingReferenceDate(): Flow<LocalDate?> {
        return todoDao.getOldestPendingReferenceDate()
    }

    fun getCompletedStatisticsBetween(
        startDateTime: LocalDateTime,
        endDateTime: LocalDateTime
    ): Flow<List<DailyStat>> {
        return todoDao.getCompletedStatsBetween(startDateTime, endDateTime)
    }

    suspend fun deleteAll() {
        todoDao.getAllTodosSnapshot().forEach { deleteCalendarEventIfPresent(it) }
        todoDao.deleteAllTodos()
        notifyDataChanged()
    }

    suspend fun getReminderTodosSnapshot(): List<TodoItem> {
        return todoDao.getReminderTodosSnapshot()
    }

    private data class SubTodoFingerprint(
        val description: String,
        val isCompleted: Boolean
    )

    private data class TodoFingerprint(
        val title: String,
        val dueDate: String?,
        val creationDateTime: String,
        val isCompleted: Boolean,
        val flag: Int,
        val completedDateTime: String?,
        val startTime: String?,
        val endTime: String?,
        val reminderMode: String?,
        val reminderOffsetMinutes: Int?,
        val reminderDayOffset: Int?,
        val reminderTime: String?,
        val reminderPersistent: Boolean,
        val reminderStrong: Boolean,
        val subTodos: List<SubTodoFingerprint>
    )

    private val backupJson = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
    }

    suspend fun exportToJson(): String {
        val todos = todoDao.getAllTodosSnapshot()
        val subTodos = todoDao.getAllSubTodosSnapshot()

        val backup = TodoBackupFile(
            schemaVersion = 1,
            exportedAt = LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME),
            todos = todos.map {
                TodoBackupDto(
                    id = it.id,
                    title = it.title,
                    dueDate = it.dueDate?.toString(),
                    creationDateTime = it.creationDateTime.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME),
                    isCompleted = it.isCompleted,
                    flag = it.flag,
                    completedDateTime = it.completedDateTime?.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME),
                    startTime = it.startTime?.format(DateTimeFormatter.ISO_LOCAL_TIME),
                    endTime = it.endTime?.format(DateTimeFormatter.ISO_LOCAL_TIME),
                    reminderMode = it.reminderMode?.name,
                    reminderOffsetMinutes = it.reminderOffsetMinutes,
                    reminderDayOffset = it.reminderDayOffset,
                    reminderTime = it.reminderTime?.format(DateTimeFormatter.ISO_LOCAL_TIME),
                    reminderPersistent = it.reminderPersistent,
                    reminderStrong = it.reminderStrong
                )
            },
            subTodos = subTodos.map {
                SubTodoBackupDto(
                    id = it.id,
                    parentId = it.parentId,
                    description = it.description,
                    isCompleted = it.isCompleted
                )
            }
        )

        return backupJson.encodeToString(backup)
    }

    suspend fun importFromJson(
        json: String,
        policy: DuplicatePolicy
    ): ImportResult {
        val backup = backupJson.decodeFromString<TodoBackupFile>(json)

        val subTodosByParent = backup.subTodos.groupBy { it.parentId }

        val existingFingerprints = todoDao.getAllTodosWithSubTodosSnapshot()
            .map { it.toFingerprint() }
            .toMutableSet()

        var imported = 0
        var skipped = 0
        val importedTodoIds = mutableListOf<Int>()

        for (todoDto in backup.todos) {
            val relatedSubDtos = subTodosByParent[todoDto.id].orEmpty()
            val fp = buildFingerprint(todoDto, relatedSubDtos)

            if (policy == DuplicatePolicy.SKIP_DUPLICATES && fp in existingFingerprints) {
                skipped++
                continue
            }

            val importNow = LocalDateTime.now()
            val newTodoId = todoDao.insertTodoForImport(
                TodoItem(
                    id = 0, // auto-generate
                    syncId = UUID.randomUUID().toString(),
                    title = todoDto.title,
                    dueDate = todoDto.dueDate?.let(LocalDate::parse),
                    creationDateTime = LocalDateTime.parse(todoDto.creationDateTime),
                    updatedAt = importNow,
                    isCompleted = todoDto.isCompleted,
                    flag = todoDto.flag,
                    completedDateTime = todoDto.completedDateTime?.let(LocalDateTime::parse),
                    startTime = todoDto.startTime?.let(LocalTime::parse),
                    endTime = todoDto.endTime?.let(LocalTime::parse),
                    reminderMode = todoDto.reminderMode?.let(TodoReminderMode::valueOf),
                    reminderOffsetMinutes = todoDto.reminderOffsetMinutes,
                    reminderDayOffset = todoDto.reminderDayOffset,
                    reminderTime = todoDto.reminderTime?.let(LocalTime::parse),
                    reminderPersistent = todoDto.reminderPersistent,
                    reminderStrong = todoDto.reminderStrong
                )
            ).toInt()
            importedTodoIds += newTodoId

            relatedSubDtos.forEach { subDto ->
                todoDao.insertSubTodoForImport(
                    SubTodoItem(
                        id = 0,
                        syncId = UUID.randomUUID().toString(),
                        parentId = newTodoId,
                        description = subDto.description,
                        isCompleted = subDto.isCompleted
                    )
                )
            }

            imported++
            if (policy == DuplicatePolicy.SKIP_DUPLICATES) {
                existingFingerprints.add(fp)
            }
        }

        syncTodoIdsIfAutoEnabled(importedTodoIds)

        return ImportResult(
            total = backup.todos.size,
            imported = imported,
            skipped = skipped
        )
    }

    private fun TodoItemWithSubTodos.toFingerprint(): TodoFingerprint {
        return TodoFingerprint(
            title = todoItem.title,
            dueDate = todoItem.dueDate?.toString(),
            creationDateTime = todoItem.creationDateTime.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME),
            isCompleted = todoItem.isCompleted,
            flag = todoItem.flag,
            completedDateTime = todoItem.completedDateTime?.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME),
            startTime = todoItem.startTime?.format(DateTimeFormatter.ISO_LOCAL_TIME),
            endTime = todoItem.endTime?.format(DateTimeFormatter.ISO_LOCAL_TIME),
            reminderMode = todoItem.reminderMode?.name,
            reminderOffsetMinutes = todoItem.reminderOffsetMinutes,
            reminderDayOffset = todoItem.reminderDayOffset,
            reminderTime = todoItem.reminderTime?.format(DateTimeFormatter.ISO_LOCAL_TIME),
            reminderPersistent = todoItem.reminderPersistent,
            reminderStrong = todoItem.reminderStrong,
            subTodos = subTodos
                .map { SubTodoFingerprint(it.description, it.isCompleted) }
                .sortedWith(
                    compareBy(
                        SubTodoFingerprint::description,
                        SubTodoFingerprint::isCompleted
                    )
                )
        )
    }


    private fun buildFingerprint(
        todo: TodoBackupDto,
        subTodos: List<SubTodoBackupDto>
    ): TodoFingerprint {
        return TodoFingerprint(
            title = todo.title,
            dueDate = todo.dueDate,
            creationDateTime = todo.creationDateTime,
            isCompleted = todo.isCompleted,
            flag = todo.flag,
            completedDateTime = todo.completedDateTime,
            startTime = todo.startTime,
            endTime = todo.endTime,
            reminderMode = todo.reminderMode,
            reminderOffsetMinutes = todo.reminderOffsetMinutes,
            reminderDayOffset = todo.reminderDayOffset,
            reminderTime = todo.reminderTime,
            reminderPersistent = todo.reminderPersistent,
            reminderStrong = todo.reminderStrong,
            subTodos = subTodos
                .map { SubTodoFingerprint(it.description, it.isCompleted) }
                .sortedWith(
                    compareBy(
                        SubTodoFingerprint::description,
                        SubTodoFingerprint::isCompleted
                    )
                )
        )
    }

    data class ImportPreviewResult(
        val total: Int,
        val duplicate: Int,
        val unique: Int
    )

    suspend fun previewImportDuplicates(json: String): ImportPreviewResult {
        val backup = backupJson.decodeFromString<TodoBackupFile>(json)
        val subTodosByParent = backup.subTodos.groupBy { it.parentId }

        val existing = todoDao.getAllTodosWithSubTodosSnapshot()
            .map { it.toFingerprint() }
            .toMutableSet()

        var duplicateCount = 0
        var uniqueCount = 0

        val seenInThisBackup = mutableSetOf<TodoFingerprint>()

        for (todoDto in backup.todos) {
            val fp = buildFingerprint(todoDto, subTodosByParent[todoDto.id].orEmpty())

            val isDuplicate = fp in existing || fp in seenInThisBackup
            if (isDuplicate) {
                duplicateCount++
            } else {
                uniqueCount++
                seenInThisBackup.add(fp)
            }
        }

        return ImportPreviewResult(
            total = backup.todos.size,
            duplicate = duplicateCount,
            unique = uniqueCount
        )
    }

    fun searchTodos(query: String): Flow<List<TodoItemWithSubTodos>> {
        return todoDao.searchTodosWithSubTodos(query.trim())
    }

    // ── syncId operations ──

    suspend fun getTodoWithSubTodosBySyncId(syncId: String): TodoItemWithSubTodos? {
        return todoDao.getTodoWithSubTodosBySyncId(syncId)
    }

    suspend fun getAllSyncIds(): Set<String> {
        return todoDao.getAllSyncIds().toSet()
    }

    suspend fun upsertBySyncId(item: TodoItem): Int {
        val existing = todoDao.getLocalIdBySyncId(item.syncId)
        return if (existing != null) {
            val updated = item.copy(id = existing).markUpdated()
            todoDao.updateTodo(updated)
            notifyDataChanged()
            existing
        } else {
            val id = todoDao.insertTodo(item.ensureSyncId().markUpdated()).toInt()
            notifyDataChanged()
            id
        }
    }

    suspend fun deleteBySyncIds(syncIds: List<String>) {
        todoDao.deleteBySyncIds(syncIds)
        notifyDataChanged()
    }

    suspend fun filterExistingSyncIds(syncIds: Collection<String>): Set<String> {
        return todoDao.filterExistingSyncIds(syncIds.toList()).toSet()
    }

    suspend fun getTodosWithSubTodosBySyncIds(syncIds: List<String>): List<TodoItemWithSubTodos> {
        return todoDao.getTodosWithSubTodosBySyncIds(syncIds)
    }

    suspend fun getLocalIdBySyncId(syncId: String): Int? {
        return todoDao.getLocalIdBySyncId(syncId)
    }

    suspend fun getAllTodosSnapshotOrderedByUpdatedAt(): List<TodoItemWithSubTodos> {
        return todoDao.getAllTodosWithSubTodosSnapshotOrderedByUpdatedAt()
    }

    suspend fun updateBySyncIdFromSnapshot(snapshot: com.nevoit.cresto.data.sync.TodoSyncSnapshot) {
        val existingId = todoDao.getLocalIdBySyncId(snapshot.syncId)
        if (existingId != null) {
            todoDao.updateBySyncId(
                syncId = snapshot.syncId,
                title = snapshot.title,
                dueDate = snapshot.dueDate?.let(LocalDate::parse),
                isCompleted = snapshot.isCompleted,
                completedDateTime = snapshot.completedDateTime?.let(LocalDateTime::parse),
                flag = snapshot.flag,
                notes = snapshot.notes,
                startTime = snapshot.startTime?.let(LocalTime::parse),
                endTime = snapshot.endTime?.let(LocalTime::parse),
                reminderMode = snapshot.reminderMode?.let(TodoReminderMode::valueOf),
                reminderOffsetMinutes = snapshot.reminderOffsetMinutes,
                reminderDayOffset = snapshot.reminderDayOffset,
                reminderTime = snapshot.reminderTime?.let(LocalTime::parse),
                reminderPersistent = snapshot.reminderPersistent,
                reminderStrong = snapshot.reminderStrong,
                updatedAt = LocalDateTime.parse(snapshot.updatedAt)
            )
            // Replace sub-todos
            todoDao.deleteSubTodosByParentId(existingId)
            snapshot.subTodos.forEach { sub ->
                todoDao.insertSubTodo(
                    SubTodoItem(
                        syncId = sub.syncId,
                        parentId = existingId,
                        description = sub.description,
                        isCompleted = sub.isCompleted
                    )
                )
            }
        }
    }

    suspend fun insertSyncSnapshot(snapshot: com.nevoit.cresto.data.sync.TodoSyncSnapshot): Int {
        val todo = TodoItem(
            syncId = snapshot.syncId,
            title = snapshot.title,
            dueDate = snapshot.dueDate?.let(LocalDate::parse),
            creationDateTime = LocalDateTime.parse(snapshot.creationDateTime),
            updatedAt = LocalDateTime.parse(snapshot.updatedAt),
            isCompleted = snapshot.isCompleted,
            completedDateTime = snapshot.completedDateTime?.let(LocalDateTime::parse),
            flag = snapshot.flag,
            notes = snapshot.notes,
            startTime = snapshot.startTime?.let(LocalTime::parse),
            endTime = snapshot.endTime?.let(LocalTime::parse),
            reminderMode = snapshot.reminderMode?.let(TodoReminderMode::valueOf),
            reminderOffsetMinutes = snapshot.reminderOffsetMinutes,
            reminderDayOffset = snapshot.reminderDayOffset,
            reminderTime = snapshot.reminderTime?.let(LocalTime::parse),
            reminderPersistent = snapshot.reminderPersistent,
            reminderStrong = snapshot.reminderStrong
        )
        val newId = todoDao.insertTodo(todo).toInt()
        // Insert sub-todos
        snapshot.subTodos.forEach { sub ->
            todoDao.insertSubTodo(
                SubTodoItem(
                    syncId = sub.syncId,
                    parentId = newId,
                    description = sub.description,
                    isCompleted = sub.isCompleted
                )
            )
        }
        notifyDataChanged()
        return newId
    }

    suspend fun syncTodoToCalendar(todoId: Int): CalendarSyncResult {
        val todo = todoDao.getTodoWithSubTodosByIdSnapshot(todoId)
            ?: return CalendarSyncResult(todoId, CalendarSyncStatus.Failed)
        return syncTodoToCalendar(todo)
    }

    suspend fun syncTodosToCalendar(todoIds: List<Int>): CalendarSyncSummary {
        if (todoIds.isEmpty()) return CalendarSyncSummary.from(emptyList())

        val todosById = todoDao.getTodosWithSubTodosByIds(todoIds)
            .associateBy { it.todoItem.id }
        val results = todoIds
            .mapNotNull(todosById::get)
            .map { syncTodoToCalendar(it) }

        return CalendarSyncSummary.from(results)
    }

    private suspend fun syncTodoToCalendar(todo: TodoItemWithSubTodos): CalendarSyncResult {
        val result = calendarSyncManager.sync(todo)
        if (result.status == CalendarSyncStatus.Synced && result.calendarEventId != null) {
            todoDao.updateCalendarSyncState(
                id = todo.todoItem.id,
                calendarEventId = result.calendarEventId,
                calendarSyncedAt = LocalDateTime.now()
            )
        }
        return result
    }

    private suspend fun syncTodoByIdIfAutoEnabled(todoId: Int) {
        if (!SettingsManager.isAutoAddToSystemCalendar) return
        val todo = todoDao.getTodoWithSubTodosByIdSnapshot(todoId) ?: return
        if (todo.todoItem.dueDate == null) {
            deleteCalendarEventAndClearSyncState(todo.todoItem)
            return
        }
        syncTodoToCalendar(todo)
    }

    private suspend fun syncTodoIdsIfAutoEnabled(todoIds: List<Int>) {
        if (!SettingsManager.isAutoAddToSystemCalendar || todoIds.isEmpty()) return
        todoIds.forEach { syncTodoByIdIfAutoEnabled(it) }
    }

    private suspend fun deleteCalendarEventAndClearSyncState(todo: TodoItem) {
        if (todo.calendarEventId == null) return
        if (calendarSyncManager.deleteEvent(todo)) {
            todoDao.clearCalendarSyncState(todo.id)
        }
    }

    private suspend fun deleteCalendarEventIfPresent(todo: TodoItem) {
        if (todo.calendarEventId != null) {
            calendarSyncManager.deleteEvent(todo)
        }
    }
}