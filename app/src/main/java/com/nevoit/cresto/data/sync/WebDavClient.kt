package com.nevoit.cresto.data.sync

import okhttp3.Authenticator
import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.Route
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import java.io.IOException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException

/**
 * OkHttp-based WebDAV client with ETag optimistic locking support.
 *
 * Both the URL and credentials are read from [credentials] on each request,
 * so updating the CredentialStore automatically takes effect on the next sync
 * without needing to recreate this client.
 *
 * @param syncFilename The filename for the sync file (default: "cresto-sync.json").
 * @param credentials Provider for server URL, username, and password.
 */
class WebDavClient(
    private val syncFilename: String = SYNC_FILENAME,
    private val credentials: ICredentialStore
) {
    private val baseUrl: String
        get() {
            val raw = credentials.serverUrl.trimEnd('/')
            // If the URL already contains the filename, strip it
            return if (raw.endsWith("/$syncFilename")) {
                raw.removeSuffix("/$syncFilename")
            } else raw
        }

    private val fileUrl: String
        get() {
            val raw = credentials.serverUrl.trimEnd('/')
            // If the URL ends with the filename, use directly
            return if (raw.endsWith("/$syncFilename")) {
                raw
            } else {
                raw + "/" + syncFilename
            }
        }

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .authenticator(WebDavAuthenticator(credentials))
            .build()
    }

    /**
     * Test the WebDAV connection by performing PROPFIND on the base directory.
     */
    suspend fun testConnection(): Result<Unit> = runCatching {
        executeWithErrorHandling {
            val request = Request.Builder()
                .url(baseUrl.trimEnd('/') + "/")
                .method("PROPFIND", null)
                .addHeader("Depth", "0")
                .build()
            val response = client.newCall(request).await()
            if (!response.isSuccessful) {
                throw when (response.code) {
                    401, 403 -> WebDavException.AuthFailed(baseUrl)
                    404 -> WebDavException.NotFound(baseUrl)
                    else -> WebDavException.ServerError(response.code, response.message)
                }
            }
        }
    }

    /**
     * Download the sync file from WebDAV.
     * Returns the JSON content and the ETag for optimistic locking.
     */
    suspend fun download(): Result<DownloadResult> = runCatching {
        val request = Request.Builder()
            .url(fileUrl)
            .get()
            .build()

        var etag: String? = null
        val content = executeWithErrorHandling {
            val response = client.newCall(request).await()
            if (!response.isSuccessful) {
                throw when (response.code) {
                    404 -> WebDavException.NotFound(fileUrl)
                    401, 403 -> WebDavException.AuthFailed(fileUrl)
                    else -> WebDavException.ServerError(response.code, response.message)
                }
            }
            val body = response.body?.string() ?: ""
            etag = response.header("ETag")
            body
        }

        DownloadResult(content = content, etag = etag)
    }

    /**
     * Upload the sync file to WebDAV with optional ETag check.
     * On 404/409, attempts to create the parent directory first, then retries.
     *
     * @param json The JSON content to upload.
     * @param etag If non-null, the request will include If-Match header. If the server's ETag
     *             doesn't match, a 412 PreconditionFailed is returned.
     */
    suspend fun upload(json: String, etag: String? = null): Result<Unit> = runCatching {
        val mediaType = JSON_MEDIA_TYPE
        val body = json.toRequestBody(mediaType)

        val requestBuilder = Request.Builder()
            .url(fileUrl)
            .put(body)

        if (etag != null) {
            requestBuilder.addHeader("If-Match", etag)
        }

        executeWithErrorHandling {
            val response = client.newCall(requestBuilder.build()).await()

            when (response.code) {
                in 200..299 -> { /* success */ }
                412 -> throw WebDavException.PreconditionFailed(
                        serverETag = response.header("ETag") ?: ""
                    )
                401, 403 -> throw WebDavException.AuthFailed(baseUrl)
                404, 409 -> {
                    // Parent directory may not exist — try to create it then retry
                    createDirectoryHierarchy()
                    val retryResponse = client.newCall(requestBuilder.build()).await()
                    if (!retryResponse.isSuccessful) {
                        throw WebDavException.ServerError(retryResponse.code, retryResponse.message)
                    }
                }
                507 -> throw WebDavException.ServerError(507, "Server storage insufficient")
                else -> throw WebDavException.ServerError(response.code, response.message)
            }
        }
    }

    /**
     * Create the directory hierarchy for the sync file on the server.
     * Tries each path segment from root to the final directory.
     */
    private suspend fun createDirectoryHierarchy() {
        // Parse the file path relative to base URL
        val base = baseUrl.trimEnd('/') + "/"
        val file = fileUrl.removePrefix(base)

        // Build each subdirectory path and call MKCOL (failures are OK if dir exists)
        val segments = file.split("/").dropLast(1) // drop the filename
        var path = base
        for (segment in segments) {
            if (segment.isNotBlank()) {
                path += segment + "/"
                runCatching { mkcol(path) }
            }
        }
    }

    /**
     * WebDAV MKCOL: create a collection (directory) on the server.
     */
    private suspend fun mkcol(url: String) {
        val request = Request.Builder()
            .url(url)
            .method("MKCOL", null)
            .build()
        val response = client.newCall(request).await()
        if (response.code !in 200..299 && response.code != 405 && response.code != 201) {
            throw WebDavException.ServerError(response.code, "Failed to create directory: ${response.message}")
        }
    }

    /**
     * Delete the sync file from WebDAV.
     */
    suspend fun delete(): Result<Unit> = runCatching {
        executeWithErrorHandling {
            val request = Request.Builder()
                .url(fileUrl)
                .delete()
                .build()
            val response = client.newCall(request).await()
            if (!response.isSuccessful && response.code != 404) {
                throw when (response.code) {
                    401, 403 -> WebDavException.AuthFailed(baseUrl)
                    else -> WebDavException.ServerError(response.code, response.message)
                }
            }
        }
    }

    /**
     * Check if the sync file exists on the server.
     */
    suspend fun exists(): Result<Boolean> = runCatching {
        executeWithErrorHandling {
            val request = Request.Builder()
                .url(fileUrl)
                .method("PROPFIND", null)
                .addHeader("Depth", "0")
                .build()
            val response = client.newCall(request).await()
            when (response.code) {
                in 200..299 -> true
                404 -> false
                401, 403 -> throw WebDavException.AuthFailed(baseUrl)
                else -> throw WebDavException.ServerError(response.code, response.message)
            }
        }
    }

    /**
     * Execute a WebDAV operation with common error handling wrappers.
     */
    private inline fun <T> executeWithErrorHandling(action: () -> T): T {
        return try {
            action()
        } catch (e: WebDavException) {
            throw e
        } catch (e: UnknownHostException) {
            throw WebDavException.NetworkError(e)
        } catch (e: SocketTimeoutException) {
            throw WebDavException.NetworkError(e)
        } catch (e: SocketException) {
            throw WebDavException.NetworkError(e)
        } catch (e: SSLException) {
            throw WebDavException.SslError(e)
        } catch (e: IOException) {
            throw WebDavException.NetworkError(e)
        }
    }

    data class DownloadResult(
        val content: String,
        val etag: String?
    )

    companion object {
        const val SYNC_FILENAME = "Cresto/cresto-sync.json"
        private val JSON_MEDIA_TYPE = "application/json".toMediaType()
    }
}

/**
 * OkHttp Authenticator that provides Basic Auth credentials for WebDAV.
 */
private class WebDavAuthenticator(
    private val credentials: ICredentialStore
) : Authenticator {
    override fun authenticate(route: Route?, response: Response): Request? {
        if (response.request.header("Authorization") != null) {
            // Already tried with credentials, don't retry
            return null
        }
        val creds = if (credentials.isConfigured) {
            Credentials.basic(credentials.username, credentials.password)
        } else {
            return null
        }
        return response.request.newBuilder()
            .header("Authorization", creds)
            .build()
    }
}

/**
 * Suspend-friendly OkHttp call execution.
 */
private suspend fun okhttp3.Call.await(): okhttp3.Response {
    return kotlinx.coroutines.suspendCancellableCoroutine { continuation ->
        enqueue(object : okhttp3.Callback {
            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                continuation.resume(response) {}
            }

            override fun onFailure(call: okhttp3.Call, e: IOException) {
                if (continuation.isCancelled) return
                continuation.resumeWithException(e)
            }
        })
        continuation.invokeOnCancellation {
            try {
                cancel()
            } catch (_: Exception) {
            }
        }
    }
}
