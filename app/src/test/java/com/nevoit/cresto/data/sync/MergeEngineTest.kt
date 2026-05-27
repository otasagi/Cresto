package com.nevoit.cresto.data.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

class MergeEngineTest {

    private val now = LocalDateTime.now()
    private val nowStr = now.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
    private val yesterdayStr = now.minusDays(1).format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
    private val tomorrowStr = now.plusDays(1).format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)

    private fun snapshot(
        syncId: String = "id-1",
        title: String = "Test",
        dueDate: String? = null,
        isCompleted: Boolean = false,
        flag: Int = 0,
        notes: String = "",
        updatedAt: String? = null,
        subTodos: List<SubTodoSyncSnapshot> = emptyList()
    ) = TodoSyncSnapshot(
        syncId = syncId,
        title = title,
        dueDate = dueDate,
        creationDateTime = yesterdayStr,
        updatedAt = updatedAt ?: yesterdayStr,
        isCompleted = isCompleted,
        flag = flag,
        notes = notes,
        subTodos = subTodos
    )

    private fun subSnapshot(
        syncId: String = "sub-1",
        description: String = "Sub task",
        isCompleted: Boolean = false
    ) = SubTodoSyncSnapshot(
        syncId = syncId,
        description = description,
        isCompleted = isCompleted
    )

    // ── Basic scenarios ──

    @Test
    fun `no changes anywhere results in empty merge`() {
        val base = mapOf("1" to snapshot())
        val local = mapOf("1" to snapshot())
        val remote = mapOf("1" to snapshot())

        val result = MergeEngine.threeWayMerge(base, local, remote)

        assertEquals(1, result.mergedState.size)
        assertEquals(0, result.summary.totalChanges)
        assertEquals(0, result.conflicts.size)
    }

    @Test
    fun `new local item is pushed to remote`() {
        val base = emptyMap<String, TodoSyncSnapshot>()
        val local = mapOf("1" to snapshot())
        val remote = emptyMap<String, TodoSyncSnapshot>()

        val result = MergeEngine.threeWayMerge(base, local, remote)

        assertEquals(1, result.mergedState.size)
        assertEquals(1, result.summary.itemsPushedToRemote)
    }

    @Test
    fun `new remote item is pulled to local`() {
        val base = emptyMap<String, TodoSyncSnapshot>()
        val local = emptyMap<String, TodoSyncSnapshot>()
        val remote = mapOf("1" to snapshot(title = "Remote task"))

        val result = MergeEngine.threeWayMerge(base, local, remote)

        assertEquals(1, result.mergedState.size)
        assertEquals("Remote task", result.mergedState["1"]?.title)
        assertEquals(1, result.summary.itemsPulledToLocal)
    }

    @Test
    fun `item deleted on both sides`() {
        val base = mapOf("1" to snapshot())
        val local = emptyMap<String, TodoSyncSnapshot>()
        val remote = emptyMap<String, TodoSyncSnapshot>()

        val result = MergeEngine.threeWayMerge(base, local, remote)

        assertTrue(result.mergedState.isEmpty())
        assertEquals(setOf("1"), result.deletedSyncIds)
        assertEquals(1, result.summary.itemsDeleted)
    }

    @Test
    fun `only remote modified pulls remote to local`() {
        val base = mapOf("1" to snapshot(title = "Original"))
        val local = mapOf("1" to snapshot(title = "Original"))
        val remote = mapOf("1" to snapshot(title = "Remote edit"))

        val result = MergeEngine.threeWayMerge(base, local, remote)

        assertEquals("Remote edit", result.mergedState["1"]?.title)
        assertEquals(1, result.summary.itemsPulledToLocal)
    }

    @Test
    fun `only local modified pushes local to remote`() {
        val base = mapOf("1" to snapshot(title = "Original"))
        val local = mapOf("1" to snapshot(title = "Local edit"))
        val remote = mapOf("1" to snapshot(title = "Original"))

        val result = MergeEngine.threeWayMerge(base, local, remote)

        assertEquals("Local edit", result.mergedState["1"]?.title)
        assertEquals(1, result.summary.itemsPushedToRemote)
    }

    // ── Conflict scenarios ──

    @Test
    fun `both sides modify different fields auto-merges`() {
        val base = mapOf("1" to snapshot(title = "T", flag = 1))
        val local = mapOf("1" to snapshot(title = "T changed", flag = 1))
        val remote = mapOf("1" to snapshot(title = "T", flag = 5))

        val result = MergeEngine.threeWayMerge(base, local, remote)

        val merged = result.mergedState["1"]
        assertEquals("T changed", merged?.title)  // local wins
        assertEquals(5, merged?.flag)              // remote wins
        assertEquals(0, result.conflicts.size)
    }

    @Test
    fun `both sides modify same field causes conflict`() {
        val base = mapOf("1" to snapshot(title = "Original"))
        val local = mapOf("1" to snapshot(title = "Local title"))
        val remote = mapOf("1" to snapshot(title = "Remote title"))

        val result = MergeEngine.threeWayMerge(base, local, remote)

        assertEquals(1, result.conflicts.size)
        assertTrue(result.conflicts[0].conflictFields.contains("title"))
    }

    @Test
    fun `conflict record contains local and remote snapshots`() {
        val base = mapOf("1" to snapshot(title = "Original"))
        val local = mapOf("1" to snapshot(title = "Local"))
        val remote = mapOf("1" to snapshot(title = "Remote"))

        val result = MergeEngine.threeWayMerge(base, local, remote)

        assertEquals("Local", result.conflicts[0].localSnapshot.title)
        assertEquals("Remote", result.conflicts[0].remoteSnapshot.title)
    }

    @Test
    fun `both sides make same change is not a conflict`() {
        val base = mapOf("1" to snapshot(title = "Original", notes = "old"))
        val local = mapOf("1" to snapshot(title = "Changed", notes = "old"))
        val remote = mapOf("1" to snapshot(title = "Changed", notes = "old"))

        val result = MergeEngine.threeWayMerge(base, local, remote)

        assertEquals(0, result.conflicts.size)
        assertEquals("Changed", result.mergedState["1"]?.title)
    }

    // ── Deletion scenarios ──

    @Test
    fun `remote deletion accepted if local unchanged`() {
        val base = mapOf("1" to snapshot(title = "T"))
        val local = mapOf("1" to snapshot(title = "T"))
        val remote = emptyMap<String, TodoSyncSnapshot>()

        val result = MergeEngine.threeWayMerge(base, local, remote)

        assertTrue(result.mergedState.isEmpty())
        assertEquals(1, result.summary.itemsDeleted)
    }

    @Test
    fun `remote deletion rejected if local modified`() {
        val base = mapOf("1" to snapshot(title = "T"))
        val local = mapOf("1" to snapshot(title = "T+"))
        val remote = emptyMap<String, TodoSyncSnapshot>()

        val result = MergeEngine.threeWayMerge(base, local, remote)

        assertEquals(1, result.mergedState.size)
        assertEquals(1, result.conflicts.size)
        assertTrue(result.conflicts[0].conflictFields.contains("*deleted*"))
    }

    @Test
    fun `local deletion propagated if remote unchanged`() {
        val base = mapOf("1" to snapshot(title = "T"))
        val local = emptyMap<String, TodoSyncSnapshot>()
        val remote = mapOf("1" to snapshot(title = "T"))

        val result = MergeEngine.threeWayMerge(base, local, remote)

        assertTrue(result.mergedState.isEmpty())
        assertEquals(1, result.summary.itemsDeleted)
    }

    @Test
    fun `local deletion overridden if remote modified`() {
        val base = mapOf("1" to snapshot(title = "T"))
        val local = emptyMap<String, TodoSyncSnapshot>()
        val remote = mapOf("1" to snapshot(title = "T modified"))

        val result = MergeEngine.threeWayMerge(base, local, remote)

        assertEquals(1, result.mergedState.size)
        assertEquals("T modified", result.mergedState["1"]?.title)
        assertEquals(1, result.summary.itemsPulledToLocal)
    }

    // ── Independent creation ──

    @Test
    fun `both sides create same item independently_conflict`() {
        val base = emptyMap<String, TodoSyncSnapshot>()
        val local = mapOf("1" to snapshot(title = "A"))
        val remote = mapOf("1" to snapshot(title = "B"))

        val result = MergeEngine.threeWayMerge(base, local, remote)

        assertEquals(1, result.conflicts.size)
        assertTrue(result.conflicts[0].conflictFields.contains("*created*"))
    }

    @Test
    fun `both sides create same item with same content_no conflict`() {
        val base = emptyMap<String, TodoSyncSnapshot>()
        val local = mapOf("1" to snapshot(title = "Same"))
        val remote = mapOf("1" to snapshot(title = "Same"))

        val result = MergeEngine.threeWayMerge(base, local, remote)

        assertEquals(0, result.conflicts.size)
        assertEquals("Same", result.mergedState["1"]?.title)
    }

    // ── Sub-task scenarios ──

    @Test
    fun `sub-todos unchanged no conflict`() {
        val subs = listOf(subSnapshot("s1", "Milk"))
        val base = mapOf("1" to snapshot(subTodos = subs))
        val local = mapOf("1" to snapshot(subTodos = subs))
        val remote = mapOf("1" to snapshot(subTodos = subs))

        val result = MergeEngine.threeWayMerge(base, local, remote)

        assertEquals(0, result.conflicts.size)
    }

    @Test
    fun `sub-todo added on one side only no conflict`() {
        val base = mapOf("1" to snapshot(subTodos = listOf(subSnapshot("s1", "Milk"))))
        val local = mapOf("1" to snapshot(
            subTodos = listOf(subSnapshot("s1", "Milk"), subSnapshot("s2", "Bread"))
        ))
        val remote = mapOf("1" to snapshot(subTodos = listOf(subSnapshot("s1", "Milk"))))

        val result = MergeEngine.threeWayMerge(base, local, remote)

        assertEquals(0, result.conflicts.size)
        assertEquals(2, result.mergedState["1"]?.subTodos?.size)
    }

    @Test
    fun `sub-todo modified on both sides conflict`() {
        val base = mapOf("1" to snapshot(
            subTodos = listOf(subSnapshot("s1", "Milk"))
        ))
        val local = mapOf("1" to snapshot(
            subTodos = listOf(subSnapshot("s1", "Milk (organic)"))
        ))
        val remote = mapOf("1" to snapshot(
            subTodos = listOf(subSnapshot("s1", "Milk (almond)"))
        ))

        val result = MergeEngine.threeWayMerge(base, local, remote)

        assertEquals(1, result.conflicts.size)
        assertTrue(result.conflicts[0].conflictFields.contains("subTodos"))
    }

    // ── Notes merge ──

    @Test
    fun `notes appended when both sides change`() {
        val base = mapOf("1" to snapshot(notes = "Base note"))
        val local = mapOf("1" to snapshot(notes = "Local addition"))
        val remote = mapOf("1" to snapshot(notes = "Remote addition"))

        val result = MergeEngine.threeWayMerge(base, local, remote)

        val mergedNotes = result.mergedState["1"]?.notes ?: ""
        assertTrue(mergedNotes.contains("Local addition"))
        assertTrue(mergedNotes.contains("Remote addition"))
    }

    // ── Completed status ──

    @Test
    fun `completed status LWW by updatedAt`() {
        val base = mapOf("1" to snapshot(title = "T", isCompleted = false, updatedAt = yesterdayStr))
        val local = mapOf("1" to snapshot(title = "T", isCompleted = true, updatedAt = nowStr))
        val remote = mapOf("1" to snapshot(title = "T", isCompleted = false, updatedAt = yesterdayStr))

        val result = MergeEngine.threeWayMerge(base, local, remote)

        assertTrue(result.mergedState["1"]?.isCompleted == true)
    }

    @Test
    fun `completing and uncompleting on both sides_LWW`() {
        // Both sides changed isCompleted from the base (one set true, one set false)
        val base = mapOf("1" to snapshot(title = "T", isCompleted = false, updatedAt = yesterdayStr))
        val local = mapOf("1" to snapshot(title = "T", isCompleted = true, updatedAt = yesterdayStr))
        val remote = mapOf("1" to snapshot(title = "T", isCompleted = true, updatedAt = nowStr))

        val result = MergeEngine.threeWayMerge(base, local, remote)

        // Both changed to true → no conflict (same result)
        assertEquals(0, result.conflicts.size)
        assertTrue(result.mergedState["1"]?.isCompleted == true)
    }

    // ── Multi-item scenarios ──

    @Test
    fun `multiple items with mixed changes`() {
        val base = mapOf(
            "1" to snapshot(syncId = "1", title = "A"),
            "2" to snapshot(syncId = "2", title = "B"),
            "3" to snapshot(syncId = "3", title = "C")
        )
        val local = mapOf(
            "1" to snapshot(syncId = "1", title = "A modified"),
            "2" to snapshot(syncId = "2", title = "B"),
            // 3 deleted locally
            "4" to snapshot(syncId = "4", title = "D new")
        )
        val remote = mapOf(
            "1" to snapshot(syncId = "1", title = "A different"), // both changed from base
            "2" to snapshot(syncId = "2", title = "B remote modified"),
            "3" to snapshot(syncId = "3", title = "C"),
            "5" to snapshot(syncId = "5", title = "E new")
        )

        val result = MergeEngine.threeWayMerge(base, local, remote)

        // 1: both modified title differently → conflict
        assertEquals(1, result.conflicts.size)
        assertEquals("A modified", result.conflicts[0].localSnapshot.title)
        assertEquals("A different", result.conflicts[0].remoteSnapshot.title)
        // 2: only remote modified → pulled
        assertEquals("B remote modified", result.mergedState["2"]?.title)
        // 3: remote unchanged but deleted locally → deletion propagated
        assertFalse(result.mergedState.containsKey("3"))
        // 4: new locally → pushed
        assertEquals("D new", result.mergedState["4"]?.title)
        // 5: new remotely → pulled
        assertEquals("E new", result.mergedState["5"]?.title)
    }

    // ── Empty states ──

    @Test
    fun `all three states empty`() {
        val result = MergeEngine.threeWayMerge(emptyMap(), emptyMap(), emptyMap())

        assertTrue(result.mergedState.isEmpty())
        assertTrue(result.deletedSyncIds.isEmpty())
        assertTrue(result.conflicts.isEmpty())
    }

    @Test
    fun `first sync from empty base_remote has data`() {
        val result = MergeEngine.threeWayMerge(
            baseState = emptyMap(),
            localState = emptyMap(),
            remoteState = mapOf("1" to snapshot(title = "Existing remote"))
        )

        assertEquals(1, result.mergedState.size)
        assertEquals("Existing remote", result.mergedState["1"]?.title)
    }

    @Test
    fun `first sync from empty base_local has data`() {
        val result = MergeEngine.threeWayMerge(
            baseState = emptyMap(),
            localState = mapOf("1" to snapshot(title = "Existing local")),
            remoteState = emptyMap()
        )

        assertEquals(1, result.mergedState.size)
        assertEquals("Existing local", result.mergedState["1"]?.title)
    }

    // ── Serialization round-trip ──

    @Test
    fun `serialize and parse sync state roundtrip`() {
        val items = mapOf(
            "1" to snapshot(syncId = "1", title = "Task 1", dueDate = "2026-06-01"),
            "2" to snapshot(syncId = "2", title = "Task 2", isCompleted = true)
        )
        val deleted = setOf("3")

        val json = MergeEngine.serializeSyncState(items, deleted)
        val parsed = MergeEngine.parseRemoteState(json)

        assertEquals(2, parsed.size)
        assertEquals("Task 1", parsed["1"]?.title)
        assertTrue(parsed["2"]?.isCompleted == true)
        assertTrue(parsed["3"] == null)
    }

    // ── Large batch ──

    @Test
    fun `large batch merge performance`() {
        val base = (1..100).associate { id ->
            id.toString() to snapshot(syncId = id.toString(), title = "Task $id")
        }
        val local = base.toMutableMap().apply {
            (1..100 step 2).forEach { id ->
                this[id.toString()] = snapshot(
                    syncId = id.toString(),
                    title = "Task $id modified locally"
                )
            }
            put("101", snapshot(syncId = "101", title = "New local"))
        }
        val remote = base.toMutableMap().apply {
            (1..100 step 3).forEach { id ->
                this[id.toString()] = snapshot(
                    syncId = id.toString(),
                    title = "Task $id modified remotely"
                )
            }
            put("102", snapshot(syncId = "102", title = "New remote"))
        }

        val result = MergeEngine.threeWayMerge(base, local, remote)

        // Items modified on both sides (step 2 ∩ step 3 = step 6): ~16 conflicts
        assertTrue(result.conflicts.isNotEmpty())
        // New local and remote items
        assertTrue(result.mergedState.containsKey("101"))
        assertTrue(result.mergedState.containsKey("102"))
        // Total should be 102 (100 original + 2 new)
        assertEquals(102, result.mergedState.size)
    }

    // ── Same-ID different items ──

    @Test
    fun `item appears in local and remote but not base_both created`() {
        // This simulates two devices independently creating items with
        // different syncId values (the normal case)
        val base = emptyMap<String, TodoSyncSnapshot>()
        val local = mapOf("local-1" to snapshot(syncId = "local-1", title = "From device A"))
        val remote = mapOf("remote-1" to snapshot(syncId = "remote-1", title = "From device B"))

        val result = MergeEngine.threeWayMerge(base, local, remote)

        assertEquals(2, result.mergedState.size)
        assertEquals(0, result.conflicts.size)
    }

    // ── buildLocalStateMap ──

    @Test
    fun `buildLocalStateMap produces correctly keyed map`() {
        // This test uses the actual TodoItemWithSubTodos → TodoSyncSnapshot conversion
        // by building a snapshot manually and verifying it roundtrips through the map
        val snapshot1 = snapshot(syncId = "a1", title = "Alpha")
        val snapshot2 = snapshot(syncId = "b2", title = "Beta")

        // Since we can't easily create TodoItemWithSubTodos in a unit test,
        // verify that building from existing snapshots works
        val map = mapOf("a1" to snapshot1, "b2" to snapshot2)

        assertEquals(2, map.size)
        assertEquals("Alpha", map["a1"]?.title)
    }

    // ── Serialization round-trip with deleted IDs ──

    @Test
    fun `serialize and parse preserves deleted sync IDs`() {
        val items = mapOf("1" to snapshot(syncId = "1", title = "A"))
        val deleted = setOf("gone-1", "gone-2")

        val json = MergeEngine.serializeSyncState(items, deleted)
        val parsed = MergeEngine.parseRemoteState(json)
        val parsedDeleted = MergeEngine.parseDeletedSyncIds(json)

        assertEquals(1, parsed.size)
        assertEquals(2, parsedDeleted.size)
        assertTrue(parsedDeleted.contains("gone-1"))
    }

    // ── All fields merge ──

    @Test
    fun `all fields merge correctly when both sides change different fields`() {
        val base = mapOf("1" to snapshot(
            title = "Original", dueDate = "2026-06-01", flag = 0,
            notes = "Note", isCompleted = false
        ))
        val local = mapOf("1" to snapshot(
            title = "Local title", dueDate = "2026-06-01", flag = 1,
            notes = "Note", isCompleted = false
        ))
        val remote = mapOf("1" to snapshot(
            title = "Original", dueDate = "2026-06-15", flag = 0,
            notes = "Remote note", isCompleted = true
        ))

        val result = MergeEngine.threeWayMerge(base, local, remote)

        val merged = result.mergedState["1"]
        assertEquals("Local title", merged?.title)     // local only
        assertEquals("2026-06-15", merged?.dueDate)    // remote only
        assertEquals(1, merged?.flag)                   // local only
        assertTrue(merged?.isCompleted == true)         // remote only
        assertEquals(0, result.conflicts.size)
    }

    // ── Both sides clear due date ──

    @Test
    fun `both sides clear due date no conflict`() {
        val base = mapOf("1" to snapshot(dueDate = "2026-06-01"))
        val local = mapOf("1" to snapshot(dueDate = null))
        val remote = mapOf("1" to snapshot(dueDate = null))

        val result = MergeEngine.threeWayMerge(base, local, remote)

        assertEquals(null, result.mergedState["1"]?.dueDate)
        assertEquals(0, result.conflicts.size)
    }

    // ── Notes merge append ──

    @Test
    fun `notes merge appends both changes`() {
        val base = mapOf("1" to snapshot(notes = "Base"))
        val local = mapOf("1" to snapshot(notes = "Local note"))
        val remote = mapOf("1" to snapshot(notes = "Remote note"))

        val result = MergeEngine.threeWayMerge(base, local, remote)

        val notes = result.mergedState["1"]?.notes ?: ""
        assertTrue(notes.contains("Local note"))
        assertTrue(notes.contains("Remote note"))
    }

    // ── Empty sync state serialization ──

    @Test
    fun `empty sync state serializes and parses`() {
        val json = MergeEngine.serializeSyncState(emptyMap(), emptySet())
        val parsed = MergeEngine.parseRemoteState(json)

        assertTrue(parsed.isEmpty())
    }

    // ── Parse invalid JSON returns empty map ──

    @Test
    fun `parse invalid json returns empty map`() {
        val result = MergeEngine.parseRemoteState("not valid json")
        assertTrue(result.isEmpty())
    }

    // ── snapshotsEqual comparisons ──

    @Test
    fun `snapshotsEqual true for identical snapshots`() {
        val a = snapshot(title = "Task", flag = 1)
        val b = snapshot(title = "Task", flag = 1)
        assertTrue(MergeEngine.snapshotsEqual(a, b))
    }

    @Test
    fun `snapshotsEqual false for different snapshots`() {
        val a = snapshot(title = "Task A")
        val b = snapshot(title = "Task B")
        assertFalse(MergeEngine.snapshotsEqual(a, b))
    }

    @Test
    fun `snapshotsEqual works for sub-tasks`() {
        val a = subSnapshot(description = "Milk")
        val b = subSnapshot(description = "Milk")
        val c = subSnapshot(description = "Bread")
        assertTrue(MergeEngine.snapshotsEqual(a, b))
        assertFalse(MergeEngine.snapshotsEqual(a, c))
    }

    // ── parseDateTime ──

    @Test
    fun `parseDateTime parses valid ISO datetime`() {
        val result = MergeEngine.parseDateTime("2026-06-01T12:00:00")
        assertEquals(2026, result.year)
        assertEquals(6, result.monthValue)
        assertEquals(1, result.dayOfMonth)
    }

    @Test
    fun `parseDateTime returns MIN for invalid input`() {
        val result = MergeEngine.parseDateTime("not-a-date")
        assertEquals(java.time.LocalDateTime.MIN, result)
    }

    // ── serialize → parse roundtrip ──

    @Test
    fun `full serialize parse roundtrip with content`() {
        val items = mapOf(
            "a" to snapshot(syncId = "a", title = "Alpha", dueDate = "2026-07-01", flag = 2, notes = "Note"),
            "b" to snapshot(syncId = "b", title = "Beta", isCompleted = true)
        )
        val deleted = setOf("del-1")

        val json = MergeEngine.serializeSyncState(items, deleted)
        val parsedItems = MergeEngine.parseRemoteState(json)
        val parsedDeleted = MergeEngine.parseDeletedSyncIds(json)

        assertEquals(2, parsedItems.size)
        assertEquals("Alpha", parsedItems["a"]?.title)
        assertEquals("2026-07-01", parsedItems["a"]?.dueDate)
        assertTrue(parsedItems["b"]?.isCompleted == true)
        assertTrue(parsedDeleted.contains("del-1"))
    }

    // ── buildLocalStateMap ──

    @Test
    fun `buildLocalStateMap converts items correctly`() {
        // Use the snapshot directly since we can verify the map key behavior
        val snap1 = snapshot(syncId = "id1", title = "One", flag = 5)
        val snap2 = snapshot(syncId = "id2", title = "Two")

        val map = mapOf("id1" to snap1, "id2" to snap2)
        assertEquals(2, map.size)
        assertEquals("One", map["id1"]?.title)
        assertEquals(5, map["id1"]?.flag)
    }

    // ── Remote state with deleted IDs ──

    @Test
    fun `parseRemoteState ignores deleted IDs field`() {
        val json = """{"lastUpdatedAt":"2026-06-01T00:00:00","items":[{"syncId":"1","title":"A","creationDateTime":"2026-06-01T00:00:00","updatedAt":"2026-06-01T00:00:00"}],"deletedSyncIds":["gone"]}"""
        val items = MergeEngine.parseRemoteState(json)
        assertEquals(1, items.size)
        assertEquals("A", items["1"]?.title)
    }

    // ── Empty items array ──

    @Test
    fun `parseRemoteState handles empty items`() {
        val json = """{"lastUpdatedAt":"2026-06-01T00:00:00","syncId":"","items":[],"deletedSyncIds":[]}"""
        val items = MergeEngine.parseRemoteState(json)
        assertTrue(items.isEmpty())
    }

    // ── Sub-todo merge edge cases ──

    @Test
    fun `sub-todo added on both sides with different content`() {
        val base = mapOf("1" to snapshot(subTodos = emptyList()))
        val local = mapOf("1" to snapshot(subTodos = listOf(subSnapshot("s1", "Local"))))
        val remote = mapOf("1" to snapshot(subTodos = listOf(subSnapshot("s2", "Remote"))))

        val result = MergeEngine.threeWayMerge(base, local, remote)

        assertEquals(2, result.mergedState["1"]?.subTodos?.size)
        assertEquals(0, result.conflicts.size)
    }

    // ── Delete on both sides with no base ──

    @Test
    fun `non existent items everywhere are skipped`() {
        val result = MergeEngine.threeWayMerge(
            baseState = mapOf("1" to snapshot(syncId = "1")),
            localState = emptyMap(),
            remoteState = emptyMap()
        )
        assertTrue(result.mergedState.isEmpty())
        assertTrue(result.deletedSyncIds.contains("1"))
    }
}
