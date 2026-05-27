package com.nevoit.cresto.data.todo

import androidx.room.Room
import com.nevoit.cresto.data.sync.CredentialStore
import com.nevoit.cresto.data.sync.ICredentialStore
import com.nevoit.cresto.data.sync.SyncManager
import com.nevoit.cresto.data.sync.SyncManagerAccessor
import com.nevoit.cresto.data.sync.WebDavClient
import com.nevoit.cresto.data.todo.calendar.TodoCalendarSyncManager
import com.nevoit.cresto.data.todo.reminder.TodoAlarmScheduler
import com.nevoit.cresto.feature.settings.SyncSettingsViewModel
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.dsl.singleOf
import org.koin.core.module.dsl.viewModelOf
import org.koin.dsl.module

val appModule = module {

    single {
        Room.databaseBuilder(
            androidContext(),
            TodoDatabase::class.java,
            "todo_database"
        )
            .addMigrations(MIGRATION_25_26)
            .fallbackToDestructiveMigration(true)
            .build()
    }

    single { get<TodoDatabase>().todoDao() }
    single { TodoAlarmScheduler(androidContext()) }
    single { TodoCalendarSyncManager(androidContext()) }
    singleOf(::TodoRepository)
    single<ICredentialStore> { CredentialStore() }
    single { WebDavClient(credentials = get()) }
    single {
        val syncManager = SyncManager(
            webDavClient = get(),
            repository = get(),
            credentialStore = get()
        )
        SyncManagerAccessor.syncManager = syncManager
        get<TodoRepository>().setDataChangeListener {
            syncManager.onDataChanged()
        }
        syncManager
    }
    viewModelOf(::TodoViewModel)
    viewModelOf(::SyncSettingsViewModel)
}