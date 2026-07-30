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
    ): MapRecord

    fun find(address: MapAddress): MapRecord?

    /** Every map, or every map in one namespace. Ordered by address so paging is stable. */
    fun list(namespace: String?): List<MapRecord>
}
