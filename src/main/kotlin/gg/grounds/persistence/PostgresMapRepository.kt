package gg.grounds.persistence

import gg.grounds.domain.ForkOrigin
import gg.grounds.domain.MapAddress
import gg.grounds.domain.MapAlreadyExistsException
import gg.grounds.domain.MapKind
import gg.grounds.domain.MapRecord
import gg.grounds.domain.MapRepository
import gg.grounds.domain.MapTrust
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import java.sql.ResultSet
import java.util.UUID
import javax.sql.DataSource

@ApplicationScoped
class PostgresMapRepository @Inject constructor(private val dataSource: DataSource) :
    MapRepository {

    override fun create(
        address: MapAddress,
        displayName: String,
        kind: MapKind,
        stateful: Boolean,
        trust: MapTrust,
        ownerSub: String,
    ): MapRecord {
        val id = UUID.randomUUID()
        dataSource.connection.use { c ->
            // ON CONFLICT DO NOTHING rather than a SELECT-then-INSERT: two callers
            // racing on the same address both see zero rows in the check, and only
            // the unique constraint decides. An empty result here IS the conflict.
            c.prepareStatement(
                    """
                    INSERT INTO map (id, namespace, name, display_name, kind, stateful, trust, owner_sub)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                    ON CONFLICT ON CONSTRAINT map_address_unique DO NOTHING
                    """
                )
                .use { ps ->
                    ps.setObject(1, id)
                    ps.setString(2, address.namespace)
                    ps.setString(3, address.name)
                    ps.setString(4, displayName)
                    ps.setString(5, kind.name)
                    ps.setBoolean(6, stateful)
                    ps.setString(7, trust.name)
                    ps.setString(8, ownerSub)
                    if (ps.executeUpdate() == 0) throw MapAlreadyExistsException(address)
                }
        }
        return find(address) ?: error("map $address vanished between insert and read")
    }

    override fun find(address: MapAddress): MapRecord? =
        dataSource.connection.use { c ->
            c.prepareStatement("$SELECT_COLUMNS WHERE namespace = ? AND name = ?").use { ps ->
                ps.setString(1, address.namespace)
                ps.setString(2, address.name)
                ps.executeQuery().use { rs -> if (rs.next()) rs.toMapRecord() else null }
            }
        }

    override fun list(namespace: String?): List<MapRecord> =
        dataSource.connection.use { c ->
            val sql =
                if (namespace == null) "$SELECT_COLUMNS ORDER BY namespace, name"
                else "$SELECT_COLUMNS WHERE namespace = ? ORDER BY name"
            c.prepareStatement(sql).use { ps ->
                if (namespace != null) ps.setString(1, namespace)
                ps.executeQuery().use { rs ->
                    buildList { while (rs.next()) add(rs.toMapRecord()) }
                }
            }
        }

    private fun ResultSet.toMapRecord(): MapRecord {
        val forkMap = getObject("forked_from_map", UUID::class.java)
        val forkVersion = getInt("forked_from_version")
        return MapRecord(
            id = getObject("id", UUID::class.java),
            address = MapAddress(getString("namespace"), getString("name")),
            displayName = getString("display_name"),
            kind = MapKind.valueOf(getString("kind")),
            stateful = getBoolean("stateful"),
            trust = MapTrust.valueOf(getString("trust")),
            ownerSub = getString("owner_sub"),
            forkedFrom = forkMap?.let { ForkOrigin(it, forkVersion) },
            createdAt = getTimestamp("created_at").toInstant(),
        )
    }

    private companion object {
        const val SELECT_COLUMNS =
            """
            SELECT id, namespace, name, display_name, kind, stateful, trust, owner_sub,
                   forked_from_map, forked_from_version, created_at
            FROM map
            """
    }
}
