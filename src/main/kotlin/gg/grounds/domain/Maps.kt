package gg.grounds.domain

import java.time.Instant
import java.util.UUID

enum class MapKind {
    LOBBY,
    ARENA,
    HUB,
    PLOT,
}

enum class MapTrust {
    FIRST_PARTY,
    UNTRUSTED,
}

/**
 * Where a map lives in the naming scheme: `bedwars/4x4-baumhaus`, `grounds/lobby`,
 * `u/hendrik/treehouse`.
 *
 * A namespace may itself contain one slash (`u/<creator>`), so an address is not reliably two
 * segments and callers must split from the right. That is why the API takes the whole address as
 * one path parameter rather than two.
 */
data class MapAddress(val namespace: String, val name: String) {

    init {
        require(NAMESPACE.matches(namespace)) { "invalid namespace: $namespace" }
        require(NAME.matches(name)) { "invalid map name: $name" }
        // The API addresses a map with a catch-all path parameter and hangs its
        // sub-resources off literal suffixes, so a map actually named `versions` would be
        // shadowed by `/{address}/versions` and become unreachable. Refusing the name is
        // cheaper than discovering that from a support ticket.
        require(name !in RESERVED) { "reserved map name: $name" }
    }

    override fun toString(): String = "$namespace/$name"

    companion object {
        private val SEGMENT = "[a-z0-9](?:[a-z0-9-]*[a-z0-9])?"
        private val NAMESPACE = Regex("^(?:$SEGMENT|u/$SEGMENT)$")
        private val NAME = Regex("^$SEGMENT$")

        /** Names that would collide with a sub-resource of a map. */
        val RESERVED = setOf("uploads", "versions", "pins", "forks")

        /**
         * Splits an address at its **last** slash, so the creator namespace `u/hendrik` survives.
         * Returns null rather than throwing, because the caller turns this into a 400 with the
         * offending string in it.
         */
        fun parse(address: String): MapAddress? {
            val cut = address.lastIndexOf('/')
            if (cut <= 0 || cut == address.length - 1) return null
            return runCatching { MapAddress(address.substring(0, cut), address.substring(cut + 1)) }
                .getOrNull()
        }
    }
}

/** Provenance of a fork. Recorded ancestry, not a live link — there is no merge. */
data class ForkOrigin(val mapId: UUID, val version: Int)

data class MapRecord(
    val id: UUID,
    val address: MapAddress,
    val displayName: String,
    val kind: MapKind,
    val stateful: Boolean,
    val trust: MapTrust,
    val ownerSub: String,
    val forkedFrom: ForkOrigin?,
    val createdAt: Instant,
)

/** Thrown when an address is already taken; the API turns it into a 409. */
class MapAlreadyExistsException(val address: MapAddress) :
    RuntimeException("map already exists: $address")

/**
 * A content digest, as it appears in an object key.
 *
 * Validated rather than trusted, because it becomes a path: the pin file hands game servers
 * `bundle/sha256/<ab>/<digest>.tar.zst` to append to the CDN base, so a caller who can put
 * arbitrary text here can point every server at an arbitrary URL. Lowercase hex is also the only
 * form that round-trips through the key layout — `AB` and `ab` would be two keys for one blob.
 */
object Digest {
    private val SHA256 = Regex("^[0-9a-f]{64}$")

    fun isValid(value: String?): Boolean = value != null && SHA256.matches(value)
}

/**
 * An environment name. It becomes the object key `pins/<environment>.json` in the public bucket and
 * the first half of a pin's primary key, so it is an allowlist rather than a free string.
 */
object EnvironmentName {
    private val VALID = Regex("^[a-z0-9](?:[a-z0-9-]{0,30}[a-z0-9])?$")

    fun isValid(value: String): Boolean = VALID.matches(value)
}
