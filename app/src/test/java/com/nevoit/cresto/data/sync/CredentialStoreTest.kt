package com.nevoit.cresto.data.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CredentialStoreTest {

    @Test
    fun `default values are empty`() {
        val store = InMemoryCredentialStore()
        assertEquals("", store.serverUrl)
        assertEquals("", store.username)
        assertEquals("", store.password)
        assertFalse(store.isConfigured)
    }

    @Test
    fun `setting values persists them`() {
        val store = InMemoryCredentialStore()
        store.serverUrl = "https://example.com/dav/"
        store.username = "user1"
        store.password = "pass1"

        assertEquals("https://example.com/dav/", store.serverUrl)
        assertEquals("user1", store.username)
        assertEquals("pass1", store.password)
    }

    @Test
    fun `isConfigured returns true when all fields set`() {
        val store = InMemoryCredentialStore()
        assertFalse(store.isConfigured)
        store.serverUrl = "url"
        assertFalse(store.isConfigured)
        store.username = "user"
        assertFalse(store.isConfigured)
        store.password = "pass"
        assertTrue(store.isConfigured)
    }

    @Test
    fun `clear resets all values`() {
        val store = InMemoryCredentialStore()
        store.serverUrl = "url"
        store.username = "user"
        store.password = "pass"
        store.clear()

        assertEquals("", store.serverUrl)
        assertEquals("", store.username)
        assertEquals("", store.password)
        assertFalse(store.isConfigured)
    }
}

/**
 * In-memory ICredentialStore for testing.
 */
class InMemoryCredentialStore : ICredentialStore {
    override var serverUrl: String = ""
    override var username: String = ""
    override var password: String = ""
    override val isConfigured: Boolean
        get() = serverUrl.isNotBlank() && username.isNotBlank() && password.isNotBlank()
    override fun clear() {
        serverUrl = ""
        username = ""
        password = ""
    }
}
