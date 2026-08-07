package io.github.leogallego.ansiblejane.assistant.local

import io.github.leogallego.ansiblejane.platform.IDeviceResources
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runTest

class LocalModelRepositoryTest {

    private val payload = "hello".encodeToByteArray()
    private val payloadSha256 = "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824"
    private val diskBufferBytes = 500L * 1024 * 1024

    private val scopes = mutableListOf<CoroutineScope>()
    private val roots = mutableListOf<String>()

    @AfterTest
    fun tearDown() {
        scopes.forEach { it.cancel() }
        roots.forEach { LocalModelFiles.deleteRecursively(it) }
    }

    @Test
    fun download_verifiesSha256_andMarksReady() = runTest {
        val model = testModel(sha256 = payloadSha256, sizeBytes = payload.size.toLong())
        val root = newRoot()
        val repo = createRepo(
            catalog = listOf(model),
            modelRoot = root,
            freeDiskBytes = model.sizeBytes + diskBufferBytes + 1,
            responseBody = payload,
        )

        repo.download(model.id)

        assertTrue(repo.isReady(model.id))
        assertNotNull(repo.modelPath(model.id))
        assertTrue(LocalModelFiles.exists(checkNotNull(repo.modelPath(model.id))))
        assertIs<LocalModelDownloadState.Succeeded>(repo.downloadState.value)
    }

    @Test
    fun download_rejectsShaMismatch_deletesPartial() = runTest {
        val model = testModel(
            sha256 = "0000000000000000000000000000000000000000000000000000000000000000",
            sizeBytes = payload.size.toLong(),
        )
        val root = newRoot()
        val repo = createRepo(
            catalog = listOf(model),
            modelRoot = root,
            freeDiskBytes = model.sizeBytes + diskBufferBytes + 1,
            responseBody = payload,
        )

        repo.download(model.id)

        val state = repo.downloadState.value
        assertIs<LocalModelDownloadState.Error>(state)
        assertEquals(model.id, state.modelId)
        assertFalse(repo.isReady(model.id))
        assertNull(repo.modelPath(model.id))
        val expectedPath = LocalModelFiles.join(root, model.id, model.fileName)
        assertFalse(LocalModelFiles.exists(expectedPath))
    }

    @Test
    fun download_failsWhenDiskBelowSizePlus500Mb() = runTest {
        val model = testModel(sha256 = payloadSha256, sizeBytes = 1_000L)
        val root = newRoot()
        val repo = createRepo(
            catalog = listOf(model),
            modelRoot = root,
            freeDiskBytes = model.sizeBytes + diskBufferBytes - 1,
            responseBody = payload,
        )

        repo.download(model.id)

        val state = repo.downloadState.value
        assertIs<LocalModelDownloadState.Error>(state)
        assertTrue(state.message.contains("disk", ignoreCase = true))
        assertFalse(repo.isReady(model.id))
    }

    private fun newRoot(): String {
        val root = LocalModelFiles.join(
            "build",
            "tmp",
            "local-model-repo-test-${scopes.size}-${kotlin.random.Random.nextInt(1_000_000)}",
        )
        LocalModelFiles.ensureDirectory(root)
        roots += root
        return root
    }

    private fun createRepo(
        catalog: List<LocalModel>,
        modelRoot: String,
        freeDiskBytes: Long,
        responseBody: ByteArray,
    ): LocalModelRepository {
        val engine = MockEngine {
            respond(
                content = responseBody,
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/octet-stream"),
            )
        }
        val client = HttpClient(engine) { expectSuccess = false }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        scopes += scope
        return LocalModelRepository(
            deviceResources = FakeDeviceResources(
                freeDiskBytes = freeDiskBytes,
                modelStorageDirectory = modelRoot,
            ),
            httpClient = client,
            scope = scope,
            catalog = catalog,
            modelRootOverride = modelRoot,
        )
    }

    private fun testModel(sha256: String, sizeBytes: Long): LocalModel =
        LocalModel(
            id = "test-model",
            displayName = "Test Model",
            fileName = "test.litertlm",
            sizeBytes = sizeBytes,
            downloadUrl = "https://example.com/test.litertlm",
            sha256 = sha256,
            gpuMemoryMb = 100,
            defaultContextTokens = 4_096,
            maxContextTokens = 8_192,
            kvPerTokenBytes = 1_000,
            onDeviceTier = OnDeviceTier.E4B,
        )

    private class FakeDeviceResources(
        private val freeDiskBytes: Long,
        private val modelStorageDirectory: String,
        private val totalMemoryBytes: Long = 8L * 1024 * 1024 * 1024,
        private val hasAvx2: Boolean = true,
    ) : IDeviceResources {
        override fun totalMemoryBytes(): Long = totalMemoryBytes
        override fun freeDiskBytes(absolutePath: String): Long = freeDiskBytes
        override fun modelStorageDirectory(): String = modelStorageDirectory
        override fun hasAvx2Support(): Boolean = hasAvx2
    }
}
