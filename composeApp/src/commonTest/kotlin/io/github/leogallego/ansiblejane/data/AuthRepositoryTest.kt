package io.github.leogallego.ansiblejane.data

import io.github.leogallego.ansiblejane.fakes.FakeAapApiProvider
import io.github.leogallego.ansiblejane.fakes.FakeInstanceDiscovery
import io.github.leogallego.ansiblejane.fakes.FakeTokenManager
import io.github.leogallego.ansiblejane.model.AapInstance
import io.github.leogallego.ansiblejane.model.InstanceInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.runTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AuthRepositoryTest {

    private lateinit var tokenManager: FakeTokenManager
    private lateinit var apiProvider: FakeAapApiProvider
    private lateinit var discovery: FakeInstanceDiscovery
    private lateinit var repository: AuthRepository

    private val instance = AapInstance(
        id = "inst-1",
        baseUrl = "https://aap.example.com",
        token = "token-1",
        alias = "Prod",
        apiVersion = "CONTROLLER_V2",
        trustSelfSigned = false
    )

    @BeforeTest
    fun setup() {
        tokenManager = FakeTokenManager()
        apiProvider = FakeAapApiProvider()
        discovery = FakeInstanceDiscovery()
        repository = AuthRepository(
            tokenManager = tokenManager,
            apiProvider = apiProvider,
            instanceDiscovery = discovery,
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        )
    }

    @Test
    fun `logoutInstance evicts cache and removes instance`() = runTest {
        tokenManager.setInstances(listOf(instance))

        repository.logoutInstance("inst-1")

        assertEquals(listOf("inst-1"), apiProvider.evictedInstances)
        assertTrue(tokenManager.instances.value.isEmpty())
        assertNull(tokenManager.activeInstance.value)
    }

    @Test
    fun `saveInstanceEdits updates alias without eviction when auth unchanged`() = runTest {
        tokenManager.setInstances(listOf(instance))

        val result = repository.saveInstanceEdits(
            instanceId = "inst-1",
            token = null,
            alias = "Lab",
            trustSelfSigned = false
        )

        assertTrue(result.isSuccess)
        assertEquals("Lab", result.getOrThrow().alias)
        assertTrue(apiProvider.evictedInstances.isEmpty())
    }

    @Test
    fun `saveInstanceEdits evicts when token changes`() = runTest {
        tokenManager.setInstances(listOf(instance))

        val result = repository.saveInstanceEdits(
            instanceId = "inst-1",
            token = "token-2",
            alias = "Prod",
            trustSelfSigned = false
        )

        assertTrue(result.isSuccess)
        assertEquals("token-2", result.getOrThrow().token)
        assertEquals(listOf("inst-1"), apiProvider.evictedInstances)
    }

    @Test
    fun `saveInstanceEdits fails for unknown instance`() = runTest {
        val result = repository.saveInstanceEdits(
            instanceId = "missing",
            token = null,
            alias = "X",
            trustSelfSigned = false
        )

        assertTrue(result.isFailure)
        assertEquals("Instance not found", result.exceptionOrNull()?.message)
    }

    @Test
    fun `refreshInstanceInfo persists discovery result`() = runTest {
        tokenManager.setInstances(listOf(instance))
        discovery.nextInfo = InstanceInfo(
            controllerVersion = "4.7.0",
            platformType = "AAP",
            aapVersion = "2.5",
            components = listOf("CONTROLLER", "EDA")
        )

        val result = repository.refreshInstanceInfo("inst-1")

        assertTrue(result.isSuccess)
        assertEquals(1, discovery.discoverCalls)
        assertEquals("4.7.0", tokenManager.instances.value.single().instanceInfo?.controllerVersion)
        assertEquals("2.5", tokenManager.instances.value.single().instanceInfo?.aapVersion)
    }

    @Test
    fun `refreshInstanceInfo fails when discovery throws`() = runTest {
        tokenManager.setInstances(listOf(instance))
        discovery.shouldFail = true

        val result = repository.refreshInstanceInfo("inst-1")

        assertTrue(result.isFailure)
        assertFalse(result.isSuccess)
        assertNull(tokenManager.instances.value.single().instanceInfo)
    }
}
