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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
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
        assertEquals(LocalModelDownloadErrorKind.HASH, state.kind)
        assertFalse(repo.isReady(model.id))
        assertNull(repo.modelPath(model.id))
        val expectedPath = LocalModelFiles.join(root, model.id, model.fileName)
        assertFalse(LocalModelFiles.exists(expectedPath))
        assertFalse(LocalModelFiles.exists("$expectedPath.partial"))
    }

    @Test
    fun cancelDownload_keepsNonEmptyPartialForResume() = runTest {
        val model = testModel(sha256 = payloadSha256, sizeBytes = payload.size.toLong())
        val root = newRoot()
        val tempPath = LocalModelFiles.join(root, model.id, model.fileName) + ".partial"
        LocalModelFiles.ensureDirectory(LocalModelFiles.join(root, model.id))
        val prefix = payload.copyOfRange(0, 2)
        val sink = LocalModelFiles.openSink(tempPath, append = false)
        sink.write(prefix, 0, prefix.size)
        sink.close()

        val engine = MockEngine {
            delay(10_000)
            respond(
                content = payload.copyOfRange(2, payload.size),
                status = HttpStatusCode.PartialContent,
                headers = headersOf(HttpHeaders.ContentType, "application/octet-stream"),
            )
        }
        val client = HttpClient(engine) { expectSuccess = false }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        scopes += scope
        val repo = LocalModelRepository(
            deviceResources = FakeDeviceResources(
                freeDiskBytes = model.sizeBytes + diskBufferBytes + 1,
                modelStorageDirectory = root,
            ),
            httpClient = client,
            scope = scope,
            catalog = listOf(model),
            modelRootOverride = root,
        )

        val downloadTask = scope.launch { repo.download(model.id) }

        var attempts = 0
        while (repo.downloadState.value !is LocalModelDownloadState.Downloading && attempts < 100) {
            delay(50)
            attempts++
        }
        assertIs<LocalModelDownloadState.Downloading>(repo.downloadState.value)

        repo.cancelDownload()
        downloadTask.join()

        assertIs<LocalModelDownloadState.Idle>(repo.downloadState.value)
        assertFalse(repo.isReady(model.id))
        assertTrue(LocalModelFiles.exists(tempPath))
        assertEquals(2L, LocalModelFiles.length(tempPath))
    }

    @Test
    fun download_diskCheckCreditsExistingPartial() = runTest {
        val model = testModel(sha256 = payloadSha256, sizeBytes = 1_000L)
        val root = newRoot()
        val tempPath = LocalModelFiles.join(root, model.id, model.fileName) + ".partial"
        LocalModelFiles.ensureDirectory(LocalModelFiles.join(root, model.id))
        val sink = LocalModelFiles.openSink(tempPath, append = false)
        val prefix = ByteArray(900) { 1 }
        sink.write(prefix, 0, prefix.size)
        sink.close()

        val remaining = model.sizeBytes - 900L
        val repo = createRepo(
            catalog = listOf(model),
            modelRoot = root,
            // Enough for remainder + buffer, but not full size + buffer.
            freeDiskBytes = remaining + diskBufferBytes,
            responseBody = payload,
        )

        // Would have failed under the old full-size check; with credit it proceeds
        // (may then fail HASH because suffix isn't real — only assert not DISK).
        repo.download(model.id)

        val state = repo.downloadState.value
        assertFalse(
            state is LocalModelDownloadState.Error &&
                state.kind == LocalModelDownloadErrorKind.DISK,
            "resume disk check should credit existing partial bytes",
        )
    }

    @Test
    fun download_failsWhenDiskBelowRemainingPlus500Mb() = runTest {
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
        assertEquals(LocalModelDownloadErrorKind.DISK, state.kind)
        assertFalse(repo.isReady(model.id))
    }

    @Test
    fun download_resumesPartialWithHttp206() = runTest {
        val full = "hello".encodeToByteArray()
        val prefix = full.copyOfRange(0, 2)
        val suffix = full.copyOfRange(2, full.size)
        val model = testModel(sha256 = payloadSha256, sizeBytes = full.size.toLong())
        val root = newRoot()
        val tempPath = LocalModelFiles.join(root, model.id, model.fileName) + ".partial"
        LocalModelFiles.ensureDirectory(LocalModelFiles.join(root, model.id))
        val sink = LocalModelFiles.openSink(tempPath, append = false)
        sink.write(prefix, 0, prefix.size)
        sink.close()

        var sawRange = false
        val engine = MockEngine { request ->
            val range = request.headers[HttpHeaders.Range]
            sawRange = range == "bytes=2-"
            respond(
                content = suffix,
                status = HttpStatusCode.PartialContent,
                headers = headersOf(
                    HttpHeaders.ContentType to listOf("application/octet-stream"),
                    HttpHeaders.ContentRange to listOf("bytes 2-4/${full.size}"),
                ),
            )
        }
        val client = HttpClient(engine) { expectSuccess = false }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        scopes += scope
        val repo = LocalModelRepository(
            deviceResources = FakeDeviceResources(
                freeDiskBytes = model.sizeBytes + diskBufferBytes + 1,
                modelStorageDirectory = root,
            ),
            httpClient = client,
            scope = scope,
            catalog = listOf(model),
            modelRootOverride = root,
        )

        repo.download(model.id)

        assertTrue(sawRange)
        assertTrue(repo.isReady(model.id))
        assertIs<LocalModelDownloadState.Succeeded>(repo.downloadState.value)
        assertFalse(LocalModelFiles.exists(tempPath))
    }

    @Test
    fun download_restartsWhenServerIgnoresRange() = runTest {
        val model = testModel(sha256 = payloadSha256, sizeBytes = payload.size.toLong())
        val root = newRoot()
        val tempPath = LocalModelFiles.join(root, model.id, model.fileName) + ".partial"
        LocalModelFiles.ensureDirectory(LocalModelFiles.join(root, model.id))
        val sink = LocalModelFiles.openSink(tempPath, append = false)
        sink.write(byteArrayOf(0x00, 0x01), 0, 2)
        sink.close()

        val engine = MockEngine {
            respond(
                content = payload,
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/octet-stream"),
            )
        }
        val client = HttpClient(engine) { expectSuccess = false }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        scopes += scope
        val repo = LocalModelRepository(
            deviceResources = FakeDeviceResources(
                freeDiskBytes = model.sizeBytes + diskBufferBytes + 1,
                modelStorageDirectory = root,
            ),
            httpClient = client,
            scope = scope,
            catalog = listOf(model),
            modelRootOverride = root,
        )

        repo.download(model.id)

        assertTrue(repo.isReady(model.id))
        assertIs<LocalModelDownloadState.Succeeded>(repo.downloadState.value)
    }

    @Test
    fun importFromPath_verifiesSha_andMarksReady() = runTest {
        val model = testModel(sha256 = payloadSha256, sizeBytes = payload.size.toLong())
        val root = newRoot()
        val source = LocalModelFiles.join(root, "import-source.litertlm")
        val sink = LocalModelFiles.openSink(source, append = false)
        sink.write(payload, 0, payload.size)
        sink.close()

        val repo = createRepo(
            catalog = listOf(model),
            modelRoot = root,
            freeDiskBytes = model.sizeBytes + diskBufferBytes + 1,
            responseBody = ByteArray(0),
        )

        repo.importFromPath(model.id, source)

        assertTrue(repo.isReady(model.id))
        assertIs<LocalModelDownloadState.Succeeded>(repo.downloadState.value)
    }

    @Test
    fun importFromPath_rejectsHashMismatch() = runTest {
        val model = testModel(
            sha256 = "0000000000000000000000000000000000000000000000000000000000000000",
            sizeBytes = payload.size.toLong(),
        )
        val root = newRoot()
        val source = LocalModelFiles.join(root, "bad-import.litertlm")
        val sink = LocalModelFiles.openSink(source, append = false)
        sink.write(payload, 0, payload.size)
        sink.close()

        val repo = createRepo(
            catalog = listOf(model),
            modelRoot = root,
            freeDiskBytes = model.sizeBytes + diskBufferBytes + 1,
            responseBody = ByteArray(0),
        )

        repo.importFromPath(model.id, source)

        val state = repo.downloadState.value
        assertIs<LocalModelDownloadState.Error>(state)
        assertEquals(LocalModelDownloadErrorKind.HASH, state.kind)
        assertFalse(repo.isReady(model.id))
    }

    @Test
    fun cancelImport_viaSharedTransferJob() = runTest {
        val large = ByteArray(2 * 1024 * 1024) { it.toByte() }
        val model = testModel(
            sha256 = "0000000000000000000000000000000000000000000000000000000000000000",
            sizeBytes = large.size.toLong(),
        )
        val root = newRoot()
        val source = LocalModelFiles.join(root, "slow-import.litertlm")
        val sink = LocalModelFiles.openSink(source, append = false)
        sink.write(large, 0, large.size)
        sink.close()

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        scopes += scope
        val repo = LocalModelRepository(
            deviceResources = FakeDeviceResources(
                freeDiskBytes = model.sizeBytes + diskBufferBytes + 1,
                modelStorageDirectory = root,
            ),
            httpClient = HttpClient(MockEngine {
                respond(ByteArray(0), HttpStatusCode.OK)
            }) { expectSuccess = false },
            scope = scope,
            catalog = listOf(model),
            modelRootOverride = root,
        )

        val importTask = scope.launch { repo.importFromPath(model.id, source) }
        var attempts = 0
        while (repo.downloadState.value !is LocalModelDownloadState.Downloading && attempts < 200) {
            delay(10)
            attempts++
        }
        val downloading = repo.downloadState.value
        assertIs<LocalModelDownloadState.Downloading>(downloading)
        assertTrue(downloading.isImport)

        repo.cancelDownload()
        importTask.join()

        assertIs<LocalModelDownloadState.Idle>(repo.downloadState.value)
        assertFalse(repo.isReady(model.id))
    }

    @Test
    fun notifyTransferError_setsErrorState() = runTest {
        val model = testModel(sha256 = payloadSha256, sizeBytes = 1)
        val root = newRoot()
        val repo = createRepo(
            catalog = listOf(model),
            modelRoot = root,
            freeDiskBytes = Long.MAX_VALUE,
            responseBody = ByteArray(0),
        )

        repo.notifyTransferError(model.id, LocalModelDownloadErrorKind.IMPORT)

        val state = repo.downloadState.value
        assertIs<LocalModelDownloadState.Error>(state)
        assertEquals(LocalModelDownloadErrorKind.IMPORT, state.kind)
    }

    @Test
    fun classifyTransferError_mapsTimeoutAndHttp() {
        class SocketTimeoutException(message: String) : Exception(message)
        class IOException(message: String) : Exception(message)

        assertEquals(
            LocalModelDownloadErrorKind.TIMEOUT,
            LocalModelRepository.classifyTransferError(
                RuntimeException(SocketTimeoutException("read timed out")),
            ),
        )
        assertEquals(
            LocalModelDownloadErrorKind.NETWORK,
            LocalModelRepository.classifyTransferError(
                IllegalStateException("Download failed with HTTP 503"),
            ),
        )
        assertEquals(
            LocalModelDownloadErrorKind.DISK,
            LocalModelRepository.classifyTransferError(
                IOException("No space left on device"),
            ),
        )
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
