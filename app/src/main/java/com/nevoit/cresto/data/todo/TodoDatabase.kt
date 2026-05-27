package com.nevoit.cresto.data.todo

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.nevoit.cresto.data.utils.Converters
import java.util.UUID

@Database(
    entities = [TodoItem::class, SubTodoItem::class],
    version = 26,
    exportSchema = false
)

@TypeConverters(Converters::class)
abstract class TodoDatabase : RoomDatabase() {
    abstract fun todoDao(): TodoDao
}

val MIGRATION_25_26 = object : Migration(25, 26) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE todo_items ADD COLUMN syncId TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE todo_items ADD COLUMN updatedAt TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE sub_todo_items ADD COLUMN syncId TEXT NOT NULL DEFAULT ''")

        // Generate UUIDs for existing todo_items
        val cursor = db.query("SELECT id, creationDateTime FROM todo_items")
        cursor.use {
            while (it.moveToNext()) {
                val id = it.getInt(0)
                val creationDate = it.getString(1) ?: java.time.LocalDateTime.now().format(
                    java.time.format.DateTimeFormatter.ISO_LOCAL_DATE_TIME
                )
                val uuid = UUID.randomUUID().toString()
                db.execSQL(
                    "UPDATE todo_items SET syncId = ?, updatedAt = ? WHERE id = ?",
                    arrayOf<Any>(uuid, creationDate, id)
                )
            }
        }

        // Generate UUIDs for existing sub_todo_items
        val subCursor = db.query("SELECT id FROM sub_todo_items")
        subCursor.use {
            while (it.moveToNext()) {
                val id = it.getInt(0)
                val uuid = UUID.randomUUID().toString()
                db.execSQL(
                    "UPDATE sub_todo_items SET syncId = ? WHERE id = ?",
                    arrayOf<Any>(uuid, id)
                )
            }
        }

        // Create indexes for syncId columns
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_todo_items_syncId ON todo_items(syncId)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_sub_todo_items_syncId ON sub_todo_items(syncId)")
    }
}