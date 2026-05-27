package com.nevoit.cresto.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nevoit.cresto.data.sync.ConflictResolution
import com.nevoit.cresto.data.sync.ICredentialStore
import com.nevoit.cresto.data.sync.SyncConflict
import com.nevoit.cresto.data.sync.SyncManager
import com.nevoit.cresto.data.sync.SyncStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class SyncConfigUiState(
    val serverUrl: String = "",
    val username: String = "",
    val password: String = "",
    val isTesting: Boolean = false,
    val testResult: TestConnectionResult? = null
)

sealed class TestConnectionResult {
    data object Success : TestConnectionResult()
    data class Failed(val message: String) : TestConnectionResult()
}

class SyncSettingsViewModel(
    private val syncManager: SyncManager,
    private val credentialStore: ICredentialStore
) : ViewModel() {

    private val _configState = MutableStateFlow(SyncConfigUiState())
    val configState: StateFlow<SyncConfigUiState> = _configState.asStateFlow()

    val syncStatus: StateFlow<SyncStatus> = syncManager.syncStatus

    init {
        // Load saved credentials
        _configState.value = SyncConfigUiState(
            serverUrl = credentialStore.serverUrl,
            username = credentialStore.username,
            password = credentialStore.password
        )
    }

    fun updateServerUrl(url: String) {
        _configState.value = _configState.value.copy(serverUrl = url, testResult = null)
        credentialStore.serverUrl = url
    }

    fun updateUsername(username: String) {
        _configState.value = _configState.value.copy(username = username, testResult = null)
        credentialStore.username = username
    }

    fun updatePassword(password: String) {
        _configState.value = _configState.value.copy(password = password, testResult = null)
        credentialStore.password = password
    }

    fun testConnection() {
        viewModelScope.launch {
            _configState.value = _configState.value.copy(isTesting = true, testResult = null)
            val result = syncManager.testConnection()
            _configState.value = _configState.value.copy(
                isTesting = false,
                testResult = if (result.isSuccess) {
                    TestConnectionResult.Success
                } else {
                    TestConnectionResult.Failed(
                        result.exceptionOrNull()?.message ?: "Unknown error"
                    )
                }
            )
        }
    }

    fun syncNow() {
        syncManager.requestSync()
    }

    fun clearRemoteData() {
        viewModelScope.launch {
            syncManager.clearRemoteData()
        }
    }

    val conflicts: StateFlow<List<SyncConflict>> = syncManager.syncStatus
        .filter { it is SyncStatus.Success || it is SyncStatus.Error }
        .distinctUntilChanged()
        .map { syncManager.getConflicts() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun acceptLocal(syncId: String) {
        syncManager.resolveConflict(syncId, ConflictResolution.AcceptLocal(syncId))
    }

    fun acceptRemote(syncId: String) {
        syncManager.resolveConflict(syncId, ConflictResolution.AcceptRemote(syncId))
    }

    fun formatLastSyncTime(): String? {
        val lastSync = syncManager.lastSyncTime ?: return null
        val now = java.time.LocalDateTime.now()
        val minutes = java.time.Duration.between(lastSync, now).toMinutes()
        return when {
            minutes < 1 -> "Just now"
            minutes < 60 -> "${minutes}m ago"
            minutes < 1440 -> "${minutes / 60}h ago"
            else -> "${minutes / 1440}d ago"
        }
    }
}
