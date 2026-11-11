package gg.grounds.lod

import gg.grounds.domain.*
import java.lang.reflect.Proxy
import java.time.Instant
import java.util.UUID
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class LodPreparationTest {
    private val id = UUID.randomUUID()
    private val sha = "a".repeat(64)
    private val source = "b".repeat(64)
    private val selection =
        AutoLodSelection(
            "stage",
            MapAddress("lobby", "mainlobby"),
            "measure-v1",
            "c".repeat(64),
            "d".repeat(64),
            "worker@sha256:" + "e".repeat(64),
        )
    private val map =
        MapRecord(
            id,
            selection.address,
            "Lobby",
            MapKind.LOBBY,
            false,
            MapTrust.FIRST_PARTY,
            "builder",
            null,
            Instant.EPOCH,
        )
    private val version =
        MapVersionRecord(
            id,
            19,
            VersionState.PUBLISHED,
            sha,
            sha,
            null,
            null,
            18,
            100,
            1812,
            null,
            null,
            null,
            false,
            SceneProjection(SceneStatus.NONE, null, null, null, null, emptyList(), emptyList()),
            "builder",
            null,
            Instant.EPOCH,
            true,
        )
    private var progress: LodProgress? = null
    private var budget: LodReservation? = null
    private var selected = 19
    private var lit: LitRecord? =
        LitRecord(sha, 2, LitState.LIT, UUID.randomUUID(), 1, source, 1000, Instant.EPOCH)
    private val calls = mutableListOf<String>()
    private val lods =
        proxy<MapLodRepository> { method, args ->
            calls += method
            when (method) {
                "progress" -> {
                    assertEquals(
                        listOf(
                            MapTrust.FIRST_PARTY,
                            sha,
                            source,
                            selection.registrySha256,
                            selection.generatorSha256,
                        ),
                        args.toList(),
                    )
                    progress
                }
                "budget" -> budget
                else -> error("Status attempted mutation: $method")
            }
        }
    private val lits =
        proxy<MapLitRepository> { method, args ->
            assertEquals("find", method)
            assertEquals(listOf(sha, 2), args.toList())
            lit
        }
    private val pins =
        proxy<MapPinRepository> { method, _ ->
            assertEquals("find", method)
            PinRecord("stage", id, selected, "admin", Instant.EPOCH)
        }

    private fun reader(enabled: Boolean = true) =
        LodPreparation(AutoLodAdmission(lods, if (enabled) selection else null), lods, lits, pins)

    @Test
    fun `reports exact artifact identity without admitting work or reading private objects`() {
        assertEquals("QUEUED", reader().status(map, version).stage)
        progress = LodProgress("GENERATING")
        assertEquals("GENERATING", reader().status(map, version).stage)
        progress = LodProgress("READY", 1812, 4113662)
        val ready = reader().status(map, version)
        assertEquals("READY", ready.stage)
        assertEquals(1812, ready.columns)
        assertEquals(4113662L, ready.bundleBytes)
        assertTrue(calls.all { it in setOf("progress", "budget") })
    }

    @Test
    fun `cached readiness takes priority over spent quota and changed activation`() {
        budget = LodReservation(2, 811728896)
        selected = 20
        progress = LodProgress("READY", 1812, 4113662)
        assertEquals("READY", reader().status(map, version).stage)
        progress = null
        assertEquals("LIMIT_REACHED", reader().status(map, version).stage)
    }

    @Test
    fun `distinguishes activation lighting and safe terminal failure`() {
        selected = 18
        assertEquals("WAITING_FOR_ACTIVATION", reader().status(map, version).stage)
        progress = LodProgress("FAILED")
        val failed = reader().status(map, version)
        assertEquals("FAILED", failed.stage)
        assertEquals("LOD_GENERATION_FAILED", failed.failureCode)
        lit = null
        assertEquals("WAITING_FOR_LIGHT", reader().status(map, version).stage)
    }

    @Test
    fun `unconfigured untrusted and unrelated maps never query LOD state or quota`() {
        assertEquals("NOT_CONFIGURED", reader(false).status(map, version).stage)
        assertEquals(
            "NOT_CONFIGURED",
            reader().status(map.copy(address = MapAddress("arena", "other")), version).stage,
        )
        assertEquals(
            "NOT_CONFIGURED",
            reader()
                .status(
                    map.copy(trust = MapTrust.UNTRUSTED),
                    version.copy(trust = MapTrust.UNTRUSTED),
                )
                .stage,
        )
        assertTrue(calls.isEmpty())
    }

    @Test
    fun `byte envelope also refuses new generation while existing work stays visible`() {
        budget = LodReservation(1, 1073741824)
        assertEquals("LIMIT_REACHED", reader().status(map, version).stage)
        progress = LodProgress("GENERATING")
        assertEquals("GENERATING", reader().status(map, version).stage)
    }

    private inline fun <reified T> proxy(crossinline call: (String, Array<out Any?>) -> Any?): T =
        Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, args
            ->
            call(method.name, args ?: emptyArray())
        } as T
}
