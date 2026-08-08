package io.github.leogallego.ansiblejane.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import io.github.leogallego.ansiblejane.model.ServerToolCache
import io.github.leogallego.ansiblejane.model.ToolManifest
import io.github.leogallego.ansiblejane.model.McpServerInfo
import io.github.leogallego.ansiblejane.model.McpToolDefinition
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ToolManifestRepositoryTest {

    @get:Rule
    val tmpFolder = TemporaryFolder()

    private lateinit var dataStore: DataStore<Preferences>
    private lateinit var repo: ToolManifestRepository
    private var fakeClock: Long = System.currentTimeMillis()

    @Before
    fun setup() {
        dataStore = PreferenceDataStoreFactory.create {
            File(tmpFolder.root, "test_manifest.preferences_pb")
        }
        repo = ToolManifestRepository(
            dataStore = dataStore,
            clock = { fakeClock }
        )
    }

    @After
    fun tearDown() = runTest {
        dataStore.edit { it.clear() }
    }

    private fun freshManifest(
        instanceId: String = "inst-1",
        schemaVersion: Int = ToolManifest.CURRENT_SCHEMA_VERSION,
        cachedAt: Long = fakeClock
    ) = ToolManifest(
        schemaVersion = schemaVersion,
        instanceId = instanceId,
        servers = listOf(
            ServerToolCache(
                serverUrl = "http://mcp.example.com/mcp",
                label = "test-server",
                serverInfo = McpServerInfo("aap-mcp", "2.1.0"),
                tools = listOf(McpToolDefinition("ping", "Ping server")),
                readOnly = false
            )
        ),
        cachedAt = cachedAt
    )

    // -- save and load --

    @Test
    fun `SHOULD return saved manifest WHEN loaded with same instanceId`() = runTest {
        val manifest = freshManifest()
        repo.saveManifest("inst-1", manifest)

        val loaded = repo.loadManifest("inst-1")
        assertNotNull(loaded)
        assertEquals("inst-1", loaded!!.instanceId)
        assertEquals(1, loaded.servers.size)
        assertEquals("ping", loaded.servers[0].tools[0].name)
    }

    @Test
    fun `SHOULD return null WHEN no manifest saved for instanceId`() = runTest {
        assertNull(repo.loadManifest("nonexistent"))
    }

    @Test
    fun `SHOULD isolate manifests WHEN different instanceIds used`() = runTest {
        repo.saveManifest("inst-1", freshManifest(instanceId = "inst-1"))
        repo.saveManifest("inst-2", freshManifest(instanceId = "inst-2"))

        assertNotNull(repo.loadManifest("inst-1"))
        assertNotNull(repo.loadManifest("inst-2"))
        assertNull(repo.loadManifest("inst-3"))
    }

    // -- delete --

    @Test
    fun `SHOULD return null WHEN manifest deleted`() = runTest {
        repo.saveManifest("inst-1", freshManifest())
        assertNotNull(repo.loadManifest("inst-1"))

        repo.deleteManifest("inst-1")
        assertNull(repo.loadManifest("inst-1"))
    }

    @Test
    fun `SHOULD not fail WHEN deleting nonexistent manifest`() = runTest {
        repo.deleteManifest("never-saved")
    }

    // -- TTL expiration --

    @Test
    fun `SHOULD return null WHEN manifest exceeds 7-day TTL`() = runTest {
        val sevenDaysAgo = fakeClock - ToolManifestRepository.MAX_CACHE_AGE_MS - 1
        repo.saveManifest("inst-1", freshManifest(cachedAt = sevenDaysAgo))

        val loaded = repo.loadManifest("inst-1")
        assertNull(loaded)
    }

    @Test
    fun `SHOULD delete expired manifest from store WHEN TTL exceeded`() = runTest {
        val sevenDaysAgo = fakeClock - ToolManifestRepository.MAX_CACHE_AGE_MS - 1
        repo.saveManifest("inst-1", freshManifest(cachedAt = sevenDaysAgo))

        repo.loadManifest("inst-1")

        val key = stringPreferencesKey("manifest_inst-1")
        val prefs = dataStore.data.first()
        assertNull(prefs[key])
    }

    @Test
    fun `SHOULD return manifest WHEN within 7-day TTL`() = runTest {
        val sixDaysAgo = fakeClock - (6L * 24 * 60 * 60 * 1000)
        repo.saveManifest("inst-1", freshManifest(cachedAt = sixDaysAgo))

        val loaded = repo.loadManifest("inst-1")
        assertNotNull(loaded)
    }

    @Test
    fun `SHOULD return manifest WHEN TTL exactly at boundary`() = runTest {
        val exactlySevenDays = fakeClock - ToolManifestRepository.MAX_CACHE_AGE_MS
        repo.saveManifest("inst-1", freshManifest(cachedAt = exactlySevenDays))

        val loaded = repo.loadManifest("inst-1")
        assertNotNull(loaded)
    }

    // -- schema version --

    @Test
    fun `SHOULD return null WHEN schema version mismatches`() = runTest {
        repo.saveManifest("inst-1", freshManifest(schemaVersion = 999))

        val loaded = repo.loadManifest("inst-1")
        assertNull(loaded)
    }

    @Test
    fun `SHOULD delete stale manifest WHEN schema version mismatches`() = runTest {
        repo.saveManifest("inst-1", freshManifest(schemaVersion = 999))

        repo.loadManifest("inst-1")

        val key = stringPreferencesKey("manifest_inst-1")
        val prefs = dataStore.data.first()
        assertNull(prefs[key])
    }

    @Test
    fun `SHOULD return manifest WHEN schema version matches current`() = runTest {
        repo.saveManifest("inst-1", freshManifest(
            schemaVersion = ToolManifest.CURRENT_SCHEMA_VERSION
        ))

        val loaded = repo.loadManifest("inst-1")
        assertNotNull(loaded)
    }

    // -- corrupted data --

    @Test
    fun `SHOULD return null WHEN stored JSON is corrupted`() = runTest {
        val key = stringPreferencesKey("manifest_inst-1")
        dataStore.edit { prefs ->
            prefs[key] = """{"schemaVersion":1,"instanceId":"x","server"""
        }

        val loaded = repo.loadManifest("inst-1")
        assertNull(loaded)
    }

    @Test
    fun `SHOULD delete corrupted entry from store`() = runTest {
        val key = stringPreferencesKey("manifest_inst-1")
        dataStore.edit { prefs ->
            prefs[key] = "not-json-at-all"
        }

        repo.loadManifest("inst-1")

        val prefs = dataStore.data.first()
        assertNull(prefs[key])
    }

    @Test
    fun `SHOULD return null WHEN stored JSON has wrong type`() = runTest {
        val key = stringPreferencesKey("manifest_inst-1")
        dataStore.edit { prefs ->
            prefs[key] = """{"schemaVersion":"not_a_number"}"""
        }

        val loaded = repo.loadManifest("inst-1")
        assertNull(loaded)
    }

    // -- overwrite --

    @Test
    fun `SHOULD overwrite existing manifest WHEN saved again`() = runTest {
        repo.saveManifest("inst-1", freshManifest())

        val updated = freshManifest().copy(
            servers = listOf(
                ServerToolCache(
                    serverUrl = "http://new-url/mcp",
                    label = "new-server",
                    serverInfo = McpServerInfo("new", "3.0"),
                    tools = listOf(
                        McpToolDefinition("new_tool", "New"),
                        McpToolDefinition("another_tool", "Another")
                    ),
                    readOnly = true
                )
            )
        )
        repo.saveManifest("inst-1", updated)

        val loaded = repo.loadManifest("inst-1")
        assertNotNull(loaded)
        assertEquals(1, loaded!!.servers.size)
        assertEquals("new-server", loaded.servers[0].label)
        assertEquals(2, loaded.servers[0].tools.size)
        assertTrue(loaded.servers[0].readOnly)
    }
}
