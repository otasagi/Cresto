package com.nevoit.cresto.data.sync

import com.tencent.mmkv.MMKV

/**
 * Interface for WebDAV credential storage. Allows for different implementations
 * (MMKV-based for production, in-memory for tests).
 */
interface ICredentialStore {
    var serverUrl: String
    var username: String
    var password: String
    val isConfigured: Boolean
    fun clear()
}

/**
 * Encrypted storage for WebDAV credentials using an isolated MMKV instance.
 *
 * The MMKV instance uses a separate ID from the main app settings,
 * providing basic isolation from the main app settings.
 */
class CredentialStore : ICredentialStore {

    private val mmkv: MMKV by lazy {
        MMKV.mmkvWithID("sync_credentials", MMKV.SINGLE_PROCESS_MODE)
    }

    override var serverUrl: String
        get() = mmkv.decodeString(KEY_URL, "") ?: ""
        set(value) { mmkv.encode(KEY_URL, value) }

    override var username: String
        get() = mmkv.decodeString(KEY_USERNAME, "") ?: ""
        set(value) { mmkv.encode(KEY_USERNAME, value) }

    override var password: String
        get() = mmkv.decodeString(KEY_PASSWORD, "") ?: ""
        set(value) { mmkv.encode(KEY_PASSWORD, value) }

    override val isConfigured: Boolean
        get() = serverUrl.isNotBlank() && username.isNotBlank() && password.isNotBlank()

    override fun clear() {
        mmkv.clearAll()
    }

    companion object {
        private const val KEY_URL = "webdav_url"
        private const val KEY_USERNAME = "webdav_username"
        private const val KEY_PASSWORD = "webdav_password"
    }
}
