package gg.grounds.lod.format

import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.Duration
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** No redirects/retries. Independent disconnect deadline also bounds a stalled streaming body. */
class LodHttp(
    private val allowLoopbackHttp: Boolean = false,
    private val timeout: Duration = Duration.ofMinutes(5),
) {
    init {
        require(!timeout.isNegative && !timeout.isZero && timeout <= Duration.ofMinutes(5))
    }

    fun preflight(url: String) {
        val uri = URI.create(url)
        require(uri.userInfo == null && uri.fragment == null && uri.host != null) {
            "invalid transfer URL"
        }
        require(
            uri.scheme == "https" ||
                (uri.scheme == "http" &&
                    allowLoopbackHttp &&
                    InetAddress.getAllByName(uri.host).all { it.isLoopbackAddress })
        ) {
            "transfer URL must use HTTPS"
        }
    }

    fun download(url: String, destination: Path, max: Long) {
        require(max in 1..LodBounds.MAX_BUNDLE_BYTES)
        var complete = false
        try {
            transfer(url) { connection ->
                connection.requestMethod = "GET"
                require(connection.responseCode == 200) { "source download failed" }
                require(connection.contentLengthLong <= max) { "source exceeds limit" }
                connection.inputStream.use { input ->
                    Files.newOutputStream(destination, StandardOpenOption.CREATE_NEW).use { output
                        ->
                        var count = 0L
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            count += read
                            require(count <= max) { "source exceeds limit" }
                            output.write(buffer, 0, read)
                        }
                    }
                }
                complete = true
            }
        } finally {
            if (!complete) Files.deleteIfExists(destination)
        }
    }

    fun upload(url: String, file: Path) {
        require(Files.size(file) <= LodBounds.MAX_BUNDLE_BYTES) { "upload exceeds limit" }
        transfer(url) { connection ->
            connection.requestMethod = "PUT"
            connection.doOutput = true
            connection.setFixedLengthStreamingMode(Files.size(file))
            Files.newInputStream(file).use { input ->
                connection.outputStream.use { input.copyTo(it) }
            }
            require(connection.responseCode in 200..299) { "artifact upload failed" }
        }
    }

    private fun transfer(url: String, block: (HttpURLConnection) -> Unit) {
        preflight(url)
        val connection = URI.create(url).toURL().openConnection() as HttpURLConnection
        connection.instanceFollowRedirects = false
        connection.connectTimeout = 10_000
        connection.readTimeout = 10_000
        val deadline =
            deadlines.schedule(
                { connection.disconnect() },
                timeout.toMillis(),
                TimeUnit.MILLISECONDS,
            )
        try {
            block(connection)
        } finally {
            deadline.cancel(false)
            connection.disconnect()
        }
    }

    private companion object {
        val deadlines =
            Executors.newSingleThreadScheduledExecutor { runnable ->
                Thread(runnable, "lod-transfer-deadline").apply { isDaemon = true }
            }
    }
}
