package com.nevoit.cresto.data.sync

/**
 * Exceptions thrown by the WebDAV client.
 */
sealed class WebDavException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class AuthFailed(url: String) :
        WebDavException("Authentication failed for $url")

    class NetworkError(cause: Throwable) :
        WebDavException("Network error: ${cause.message}", cause)

    class PreconditionFailed(val serverETag: String) :
        WebDavException("Remote file changed since last download (412 Precondition Failed)")

    class ServerError(code: Int, message: String) :
        WebDavException("Server returned $code: $message")

    class NotFound(path: String) :
        WebDavException("File not found at $path")

    class ParseError(cause: Throwable) :
        WebDavException("Failed to parse server response", cause)

    class SslError(cause: Throwable) :
        WebDavException("SSL error: ${cause.message}", cause)
}
