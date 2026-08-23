package gg.grounds.derive

import java.io.InputStream
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

internal class WorkerTransferException(message: String, cause: Throwable? = null) :
    RuntimeException(message, cause)

internal class WorkerHttpTransfer(
    private val allowLoopbackHttp: Boolean,
    private val maxSourceBytes: Long = 1L shl 30,
    private val requestDeadlineMillis: Long = 15_000,
) {
    init {
        require(maxSourceBytes > 0 && requestDeadlineMillis > 0)
    }

    fun preflight(uri: URI) = requireAllowed(uri)

    fun download(uri: URI, destination: Path): String {
        val started = System.nanoTime()
        val connection = connection(uri)
        val deadline = disconnectAtDeadline(connection)
        var completed = false
        try {
            if (connection.responseCode !in 200..299)
                throw WorkerTransferException("source download failed (${connection.responseCode})")
            if (connection.contentLengthLong > maxSourceBytes)
                throw WorkerTransferException("source exceeds compressed limit")
            val digest = MessageDigest.getInstance("SHA-256")
            connection.inputStream.use { input ->
                Files.newOutputStream(destination, CREATE_NEW).use { output ->
                    copy(input, output::write, digest, started, maxSourceBytes)
                }
            }
            completed = true
            return digest.digest().hex()
        } catch (failure: WorkerTransferException) {
            throw failure
        } catch (failure: Exception) {
            throw WorkerTransferException("source transfer failed", failure)
        } finally {
            if (!completed) Files.deleteIfExists(destination)
            deadline.shutdownNow()
            connection.disconnect()
        }
    }

    fun upload(uri: URI, source: Path, expectedDigest: String? = null) {
        val started = System.nanoTime()
        val connection = connection(uri)
        val deadline = disconnectAtDeadline(connection)
        try {
            connection.requestMethod = "PUT"
            connection.doOutput = true
            connection.setFixedLengthStreamingMode(Files.size(source))
            val digest = MessageDigest.getInstance("SHA-256")
            Files.newInputStream(source).use { input ->
                connection.outputStream.use { output ->
                    copy(input, output::write, digest, started)
                }
            }
            if (expectedDigest != null && digest.digest().hex() != expectedDigest)
                throw WorkerTransferException("artifact changed during upload")
            if (connection.responseCode !in 200..299)
                throw WorkerTransferException("artifact upload failed (${connection.responseCode})")
        } catch (failure: WorkerTransferException) {
            throw failure
        } catch (failure: Exception) {
            throw WorkerTransferException("artifact transfer failed", failure)
        } finally {
            deadline.shutdownNow()
            connection.disconnect()
        }
    }

    fun upload(uri: URI, bytes: ByteArray) {
        val temporary = Files.createTempFile("derive-result-", ".json")
        try {
            Files.write(temporary, bytes)
            upload(uri, temporary, bytes.digest())
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
        digest: MessageDigest,
        started: Long,
        limit: Long = Long.MAX_VALUE,
    ) {
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0L
        while (true) {
            deadline(started)
            val count = input.read(buffer)
            if (count < 0) return
            total += count
            if (total > limit) throw WorkerTransferException("source exceeds compressed limit")
            digest.update(buffer, 0, count)
            write(buffer, 0, count)
            deadline(started)
        }
    }

    private fun deadline(started: Long) {
        if ((System.nanoTime() - started) / 1_000_000 > requestDeadlineMillis)
            throw WorkerTransferException("request deadline exceeded")
    }

    private fun disconnectAtDeadline(connection: HttpURLConnection) =
        Executors.newSingleThreadScheduledExecutor { runnable ->
                Thread(runnable, "derive-http-deadline").apply { isDaemon = true }
            }
            .also { executor ->
                executor.schedule(
                    connection::disconnect,
                    requestDeadlineMillis,
                    TimeUnit.MILLISECONDS,
                )
            }

    private fun ByteArray.digest() = MessageDigest.getInstance("SHA-256").digest(this).hex()

    private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }

    private companion object {
        const val TIMEOUT_MS = 5_000
    }
}
