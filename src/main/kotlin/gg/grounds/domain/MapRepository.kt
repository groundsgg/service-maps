package gg.grounds.domain

interface MapRepository {

    /** @throws MapAlreadyExistsException if the address is taken. */
    fun create(
        address: MapAddress,
        displayName: String,
        kind: MapKind,
        stateful: Boolean,
        trust: MapTrust,
        ownerSub: String,
        forkedFrom: ForkOrigin? = null,
        /**
         * When forking, the source version to write in as the new map's version 1 — in the same
         * transaction as the map row. A fork is one fact: a map that exists without its first
         * version has burned its address on something nobody can fork again.
         */
        firstVersion: MapVersionRecord? = null,
    ): MapRecord

    fun find(address: MapAddress): MapRecord?

    /** Every map, or every map in one namespace. Ordered by address so paging is stable. */
    fun list(namespace: String?): List<MapRecord>
}
