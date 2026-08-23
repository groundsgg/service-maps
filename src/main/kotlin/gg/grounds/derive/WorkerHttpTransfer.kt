package gg.grounds.derive

import java.io.InputStream
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

internal class WorkerTransferException(message: String, cause: Throwable? = null) :
    RuntimeException(message, cause)

internal class WorkerHttpTransfer(private val allowLoopbackHttp: Boolean) {
    fun download(uri: URI, destination: Path): String {
        val connection = connection(uri)
        try {
            if (connection.responseCode !in 200..299)
                throw WorkerTransferException("source download failed (${connection.responseCode})")
            val digest = MessageDigest.getInstance("SHA-256")
            connection.inputStream.use { input ->
                Files.newOutputStream(destination).use { output ->
                    copy(input, output::write, digest)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        } catch (failure: WorkerTransferException) {
            throw failure
        } catch (failure: Exception) {
            throw WorkerTransferException("source transfer failed", failure)
        } finally {
            connection.disconnect()
        }
    }

    fun upload(uri: URI, source: Path) {
        val connection = connection(uri)
        try {
            connection.requestMethod = "PUT"
            connection.doOutput = true
            connection.setFixedLengthStreamingMode(Files.size(source))
            Files.newInputStream(source).use { input ->
                connection.outputStream.use { output -> copy(input, output::write, null) }
            }
            if (connection.responseCode !in 200..299)
                throw WorkerTransferException("artifact upload failed (${connection.responseCode})")
        } catch (failure: WorkerTransferException) {
            throw failure
        } catch (failure: Exception) {
            throw WorkerTransferException("artifact transfer failed", failure)
        } finally {
            connection.disconnect()
        }
    }

    fun upload(uri: URI, bytes: ByteArray) {
        val temporary = Files.createTempFile("derive-result-", ".json")
        try {
            Files.write(temporary, bytes)
            upload(uri, temporary)
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    private fun connection(uri: URI): HttpURLConnection {
        requireAllowed(uri)
        return (uri.toURL().openConnection() as? HttpURLConnection
                ?: throw WorkerTransferException("URL is not HTTP"))
            .apply {
                instanceFollowRedirects = false
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
            }
    }

    private fun requireAllowed(uri: URI) {
        when (uri.scheme?.lowercase()) {
            "https" -> Unit
            "http" ->
                require(
                    allowLoopbackHttp &&
                        uri.host != null &&
                        InetAddress.getAllByName(uri.host).all { it.isLoopbackAddress }
                ) {
                    "worker URLs must use HTTPS"
                }
            else -> throw IllegalArgumentException("worker URLs must use HTTPS")
        }
    }

    private fun copy(
        input: InputStream,
        write: (ByteArray, Int, Int) -> Unit,
        digest: MessageDigest?,
    ) {
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) return
            digest?.update(buffer, 0, count)
            write(buffer, 0, count)
        }
    }

    private companion object {
        const val TIMEOUT_MS = 5_000
    }
}
