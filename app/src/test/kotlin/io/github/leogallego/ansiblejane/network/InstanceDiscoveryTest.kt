package io.github.leogallego.ansiblejane.network

import io.github.leogallego.ansiblejane.model.ApiVersion

import io.github.leogallego.ansiblejane.model.AapComponent
import io.github.leogallego.ansiblejane.model.PlatformType
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class InstanceDiscoveryTest {

    private lateinit var server: MockWebServer
    private lateinit var discovery: InstanceDiscovery
    private val json = Json { ignoreUnknownKeys = true }

    @Before
    fun setup() {
        server = MockWebServer()
        server.start()
        discovery = InstanceDiscovery(json)
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `detects AAP instance with gateway and license`() = runTest {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path ?: ""
                return when {
                    path.contains("gateway/v1/ping") -> MockResponse()
                        .setBody("""{"version": "2.6", "status": "good"}""")
                        .setHeader("X-API-Product-Version", "2.6.3")
                    path.contains("controller/v2/ping") -> MockResponse().setBody(
                        """{"ha": true, "version": "4.7.9", "active_node": "node1", "install_uuid": "abc", "instances": []}"""
                    )
                    path.contains("eda/v1/config") -> MockResponse().setBody(
                        """{"version": "1.1.3", "time_zone": "UTC", "deployment_type": "k8s"}"""
                    )
                    path.contains("config") -> MockResponse().setBody(
                        """{"version": "4.7.9", "license_info": {"license_type": "enterprise", "valid_key": true}}"""
                    )
                    path.contains("galaxy") -> MockResponse().setResponseCode(404)
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }

        val info = discovery.discover(
            server.url("/").toString(),
            "test-token",
            ApiVersion.CONTROLLER_V2,
            false
        )

        assertEquals("4.7.9", info.controllerVersion)
        assertEquals("2.6.3", info.gatewayVersion)
        assertEquals("1.1.3", info.edaVersion)
        assertEquals("2.6.3", info.aapVersion)
        assertEquals(PlatformType.AAP.name, info.platformType)
        assertTrue(info.hasComponent(AapComponent.CONTROLLER))
        assertTrue(info.hasComponent(AapComponent.GATEWAY))
        assertTrue(info.hasComponent(AapComponent.EDA))
        assertTrue(!info.hasComponent(AapComponent.HUB))
    }

    @Test
    fun `detects AWX instance without gateway and no license`() = runTest {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path ?: ""
                return when {
                    path.contains("v2/ping") -> MockResponse().setBody(
                        """{"ha": false, "version": "24.6.1", "active_node": "awx", "install_uuid": "def", "instances": []}"""
                    )
                    path.contains("gateway/v1") -> MockResponse().setResponseCode(404)
                    path.contains("eda/v1") -> MockResponse().setResponseCode(404)
                    path.contains("config") -> MockResponse().setBody(
                        """{"version": "24.6.1", "license_info": {}}"""
                    )
                    path.contains("galaxy") -> MockResponse().setResponseCode(404)
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }

        val info = discovery.discover(
            server.url("/").toString(),
            "test-token",
            ApiVersion.V2,
            false
        )

        assertEquals("24.6.1", info.controllerVersion)
        assertEquals("", info.gatewayVersion)
        assertEquals(PlatformType.AWX.name, info.platformType)
        assertNull(info.aapVersion)
        assertTrue(info.hasComponent(AapComponent.CONTROLLER))
        assertTrue(!info.hasComponent(AapComponent.GATEWAY))
    }

    @Test
    fun `detects Jewel instance with gateway but no license`() = runTest {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path ?: ""
                return when {
                    path.contains("gateway/v1/ping") -> MockResponse().setBody(
                        """{"version": "1.0.0", "status": "good"}"""
                    )
                    path.contains("controller/v2/ping") -> MockResponse().setBody(
                        """{"ha": false, "version": "4.6.0", "active_node": "node1", "install_uuid": "ghi", "instances": []}"""
                    )
                    path.contains("eda/v1") -> MockResponse().setResponseCode(404)
                    path.contains("config") -> MockResponse().setBody(
                        """{"version": "4.6.0", "license_info": {"license_type": "open"}}"""
                    )
                    path.contains("galaxy") -> MockResponse().setResponseCode(404)
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }

        val info = discovery.discover(
            server.url("/").toString(),
            "test-token",
            ApiVersion.CONTROLLER_V2,
            false
        )

        assertEquals(PlatformType.JEWEL.name, info.platformType)
        assertEquals("1.0.0", info.gatewayVersion)
        assertTrue(info.hasComponent(AapComponent.GATEWAY))
        assertTrue(!info.hasComponent(AapComponent.EDA))
    }

    @Test
    fun `handles all probes failing gracefully`() = runTest {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                return MockResponse().setResponseCode(500)
            }
        }

        val info = discovery.discover(
            server.url("/").toString(),
            "test-token",
            ApiVersion.V2,
            false
        )

        assertEquals("", info.controllerVersion)
        assertEquals("", info.gatewayVersion)
        assertEquals(PlatformType.UNKNOWN.name, info.platformType)
        assertNull(info.aapVersion)
        assertTrue(info.hasComponent(AapComponent.CONTROLLER))
        assertTrue(!info.hasComponent(AapComponent.GATEWAY))
    }

    @Test
    fun `detects all four components when all available`() = runTest {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path ?: ""
                return when {
                    path.contains("gateway/v1/ping") -> MockResponse()
                        .setBody("""{"version": "2.6", "status": "good"}""")
                        .setHeader("X-API-Product-Version", "2.6.3")
                    path.contains("controller/v2/ping") -> MockResponse().setBody(
                        """{"ha": true, "version": "4.7.9", "active_node": "n1", "install_uuid": "x", "instances": []}"""
                    )
                    path.contains("eda/v1/config") -> MockResponse().setBody(
                        """{"version": "1.1.3", "time_zone": "UTC", "deployment_type": "k8s"}"""
                    )
                    path.contains("config") -> MockResponse().setBody(
                        """{"version": "4.7.9", "license_info": {"license_type": "enterprise", "valid_key": true}}"""
                    )
                    path.contains("galaxy") -> MockResponse().setBody("""{"results": []}""")
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }

        val info = discovery.discover(
            server.url("/").toString(),
            "test-token",
            ApiVersion.CONTROLLER_V2,
            false
        )

        assertEquals(4, info.components.size)
        assertTrue(info.hasComponent(AapComponent.CONTROLLER))
        assertTrue(info.hasComponent(AapComponent.GATEWAY))
        assertTrue(info.hasComponent(AapComponent.EDA))
        assertTrue(info.hasComponent(AapComponent.HUB))
        assertEquals("2.6.3", info.aapVersion)
        assertEquals("4.7.9", info.controllerVersion)
        assertEquals("1.1.3", info.edaVersion)
    }

    @Test
    fun `falls back to body version when header is missing`() = runTest {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path ?: ""
                return when {
                    path.contains("gateway/v1/ping") -> MockResponse()
                        .setBody("""{"version": "2.6", "status": "good"}""")
                    path.contains("controller/v2/ping") -> MockResponse().setBody(
                        """{"ha": true, "version": "4.7.9", "active_node": "n1", "install_uuid": "x", "instances": []}"""
                    )
                    path.contains("eda/v1") -> MockResponse().setResponseCode(404)
                    path.contains("config") -> MockResponse().setBody(
                        """{"version": "4.7.9", "license_info": {"license_type": "enterprise", "valid_key": true}}"""
                    )
                    path.contains("galaxy") -> MockResponse().setResponseCode(404)
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }

        val info = discovery.discover(
            server.url("/").toString(),
            "test-token",
            ApiVersion.CONTROLLER_V2,
            false
        )

        assertEquals("2.6", info.gatewayVersion)
        assertEquals("2.6", info.aapVersion)
    }
}
