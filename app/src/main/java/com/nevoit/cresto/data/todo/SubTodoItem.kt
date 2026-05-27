package com.nevoit.cresto.data.todo

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import java.util.UUID

@Entity(
    tableName = "sub_todo_items",
    foreignKeys = [
        ForeignKey(
            entity = TodoItem::class,
            parentColumns = ["id"],
            childColumns = ["parentId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["parentId"]),
        Index(value = ["syncId"])
    ]
)
data class SubTodoItem(
    @PrimaryKey(autoGenerate = true)
    val id: Int = 0,
    val syncId: String = UUID.randomUUID().toString(),
    val parentId: Int,
    val description: String,
    val isCompleted: Boolean = false
)
