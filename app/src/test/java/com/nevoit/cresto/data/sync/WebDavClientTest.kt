package com.nevoit.cresto.data.sync

import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class WebDavClientTest {

    private lateinit var mockServer: MockWebServer
    private lateinit var client: WebDavClient
    private lateinit var credentialStore: ICredentialStore

    @Before
    fun setup() {
        mockServer = MockWebServer()
        credentialStore = TestCredentialStore(
            username = "user",
            password = "pass",
            serverUrl = mockServer.url("/webdav/").toString()
        )
        client = WebDavClient(
            credentials = credentialStore
        )
    }

    @After
    fun teardown() {
        mockServer.shutdown()
    }

    // ── download ──

    @Test
    fun `download returns content and ETag on success`() = runTest {
        mockServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("ETag", "\"abc123\"")
                .setBody("{\"items\":[]}")
        )

        val result = client.download()

        assertTrue(result.isSuccess)
        val download = result.getOrNull()
        assertEquals("{\"items\":[]}", download?.content)
        assertEquals("\"abc123\"", download?.etag)

        val request = mockServer.takeRequest()
        assertEquals("GET", request.method)
    }

    @Test
    fun `download returns error on 404`() = runTest {
        mockServer.enqueue(MockResponse().setResponseCode(404))

        val result = client.download()

        assertFalse(result.isSuccess)
        assertTrue(result.exceptionOrNull() is WebDavException.NotFound)
    }

    @Test
    fun `download returns error on 401`() = runTest {
        // Authenticator retries once with credentials, so enqueue 2x 401
        mockServer.enqueue(MockResponse().setResponseCode(401))
        mockServer.enqueue(MockResponse().setResponseCode(401))

        val result = client.download()

        assertFalse(result.isSuccess)
        assertTrue(result.exceptionOrNull() is WebDavException.AuthFailed)
    }

    @Test
    fun `download handles empty body`() = runTest {
        mockServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("")
        )

        val result = client.download()
        assertTrue(result.isSuccess)
        assertEquals("", result.getOrNull()?.content)
    }

    // ── upload ──

    @Test
    fun `upload succeeds without ETag`() = runTest {
        mockServer.enqueue(MockResponse().setResponseCode(201))

        val result = client.upload("{\"items\":[]}")

        assertTrue(result.isSuccess)

        val request = mockServer.takeRequest()
        assertEquals("PUT", request.method)
        assertEquals("{\"items\":[]}", request.body.readUtf8())
    }

    @Test
    fun `upload succeeds with matching ETag`() = runTest {
        mockServer.enqueue(MockResponse().setResponseCode(204))

        val result = client.upload("{}", etag = "\"abc123\"")

        assertTrue(result.isSuccess)

        val request = mockServer.takeRequest()
        assertEquals("PUT", request.method)
        assertEquals("\"abc123\"", request.getHeader("If-Match"))
    }

    @Test
    fun `upload returns PreconditionFailed on 412`() = runTest {
        mockServer.enqueue(MockResponse().setResponseCode(412))

        val result = client.upload("{}", etag = "\"stale\"")

        assertFalse(result.isSuccess)
        val error = result.exceptionOrNull()
        assertTrue("Expected PreconditionFailed but got ${error?.javaClass?.simpleName}: ${error?.message}", error is WebDavException.PreconditionFailed)

        val putRequest = mockServer.takeRequest()
        assertEquals("PUT", putRequest.method)
        assertEquals("\"stale\"", putRequest.getHeader("If-Match"))
    }

    @Test
    fun `upload returns AuthFailed on 401`() = runTest {
        mockServer.enqueue(MockResponse().setResponseCode(401))
        mockServer.enqueue(MockResponse().setResponseCode(401))

        val result = client.upload("{}")

        assertFalse(result.isSuccess)
        assertTrue(result.exceptionOrNull() is WebDavException.AuthFailed)
    }

    // ── exist ──

    @Test
    fun `exists returns true when file found`() = runTest {
        mockServer.enqueue(MockResponse().setResponseCode(207))

        val result = client.exists()

        assertTrue(result.isSuccess)
        assertTrue(result.getOrDefault(false))

        val request = mockServer.takeRequest()
        assertEquals("PROPFIND", request.method)
    }

    @Test
    fun `exists returns false on 404`() = runTest {
        mockServer.enqueue(MockResponse().setResponseCode(404))

        val result = client.exists()

        assertTrue(result.isSuccess)
        assertFalse(result.getOrDefault(true))
    }

    // ── testConnection ──

    @Test
    fun `testConnection succeeds on 200`() = runTest {
        mockServer.enqueue(MockResponse().setResponseCode(207))

        val result = client.testConnection()

        assertTrue(result.isSuccess)

        val request = mockServer.takeRequest()
        assertEquals("PROPFIND", request.method)
    }

    @Test
    fun `testConnection fails on 401`() = runTest {
        mockServer.enqueue(MockResponse().setResponseCode(401))
        mockServer.enqueue(MockResponse().setResponseCode(401))

        val result = client.testConnection()

        assertFalse(result.isSuccess)
        assertTrue(result.exceptionOrNull() is WebDavException.AuthFailed)
    }

    // ── delete ──

    @Test
    fun `delete returns success`() = runTest {
        mockServer.enqueue(MockResponse().setResponseCode(204))

        val result = client.delete()
        assertTrue(result.isSuccess)

        val request = mockServer.takeRequest()
        assertEquals("DELETE", request.method)
    }

    @Test
    fun `delete returns success when file not exists`() = runTest {
        mockServer.enqueue(MockResponse().setResponseCode(404))

        val result = client.delete()
        assertTrue(result.isSuccess)
    }

    // ── upload with MKCOL retry ──

    @Test
    fun `upload retries with MKCOL on 404`() = runTest {
        // First PUT → 404, then MKCOL → 201, then retry PUT → 204
        mockServer.enqueue(MockResponse().setResponseCode(404))
        mockServer.enqueue(MockResponse().setResponseCode(201)) // MKCOL
        mockServer.enqueue(MockResponse().setResponseCode(204)) // retry

        val result = client.upload("{}")
        assertTrue(result.isSuccess)

        val requests = (0..2).map { mockServer.takeRequest() }
        assertEquals("PUT", requests[0].method)
        assertEquals("MKCOL", requests[1].method)
        assertEquals("PUT", requests[2].method)
    }

    @Test
    fun `upload retries with MKCOL on 409`() = runTest {
        mockServer.enqueue(MockResponse().setResponseCode(409))
        mockServer.enqueue(MockResponse().setResponseCode(201)) // MKCOL
        mockServer.enqueue(MockResponse().setResponseCode(204)) // retry

        val result = client.upload("{}")
        assertTrue(result.isSuccess)

        assertEquals("PUT", mockServer.takeRequest().method)
        assertEquals("MKCOL", mockServer.takeRequest().method)
        assertEquals("PUT", mockServer.takeRequest().method)
    }

    @Test
    fun `upload fails when MKCOL fails`() = runTest {
        mockServer.enqueue(MockResponse().setResponseCode(404))
        mockServer.enqueue(MockResponse().setResponseCode(403)) // MKCOL fails
        mockServer.enqueue(MockResponse().setResponseCode(404)) // retry PUT

        val result = client.upload("{}")
        assertFalse(result.isSuccess)
    }

    // ── URL handling ──

    @Test
    fun `file URL with full path uses direct path`() = runTest {
        val store = TestCredentialStore(
            username = "user",
            password = "pass",
            serverUrl = mockServer.url("/webdav/cresto-sync.json").toString()
        )
        val davClient = WebDavClient(credentials = store)
        mockServer.enqueue(MockResponse().setResponseCode(200).setBody("{}"))

        davClient.download()
        val request = mockServer.takeRequest()
        // Should request the URL directly, not append another filename
        assertTrue(request.path!!.endsWith("cresto-sync.json"))
        assertFalse(request.path!!.endsWith("cresto-sync.json/cresto-sync.json"))
    }

    @Test
    fun `directory URL appends sync filename`() = runTest {
        val store = TestCredentialStore(
            username = "user",
            password = "pass",
            serverUrl = mockServer.url("/webdav/").toString()
        )
        val davClient = WebDavClient(credentials = store)
        mockServer.enqueue(MockResponse().setResponseCode(200).setBody("{}"))

        davClient.download()
        val request = mockServer.takeRequest()
        assertTrue(request.path!!.endsWith("cresto-sync.json"))
    }

    // ── server errors ──

    @Test
    fun `upload returns ServerError on 507`() = runTest {
        mockServer.enqueue(MockResponse().setResponseCode(507))

        val result = client.upload("{}")
        assertFalse(result.isSuccess)
        assertTrue(result.exceptionOrNull() is WebDavException.ServerError)
    }

    // ── integration: full round-trip ──

    @Test
    fun `full download-modify-upload round trip`() = runTest {
        // Download
        mockServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("ETag", "\"v1\"")
                .setBody("{\"items\":[{\"syncId\":\"1\",\"title\":\"test\"}]}")
        )

        val download = client.download().getOrNull()!!
        assertEquals("\"v1\"", download.etag)
        assertTrue(download.content.contains("test"))

        // Upload with the same ETag
        mockServer.enqueue(MockResponse().setResponseCode(204))

        val upload = client.upload(
            json = "{\"items\":[{\"syncId\":\"1\",\"title\":\"test updated\"}]}",
            etag = download.etag
        )
        assertTrue(upload.isSuccess)

        // Verify PUT had the ETag (take first GET, then PUT)
        mockServer.takeRequest() // consume the GET request
        val putRequest = mockServer.takeRequest() // the PUT request
        assertEquals("PUT", putRequest.method)
        assertEquals(download.etag, putRequest.getHeader("If-Match"))
    }
}

/**
 * A test implementation of ICredentialStore that doesn't need MMKV.
 */
private class TestCredentialStore(
    username: String,
    password: String,
    serverUrl: String = "http://test/"
) : ICredentialStore {
    override var serverUrl: String = serverUrl
    override var username: String = username
    override var password: String = password
    override val isConfigured: Boolean get() = true
    override fun clear() {}
}
