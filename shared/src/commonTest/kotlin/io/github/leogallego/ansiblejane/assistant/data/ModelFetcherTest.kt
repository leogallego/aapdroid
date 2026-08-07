package io.github.leogallego.ansiblejane.assistant.data

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.Url
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ModelFetcherTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun assertSuccess(result: ModelFetcher.Result): List<String> {
        assertIs<ModelFetcher.Result.Success>(result)
        return result.models
    }

    private fun assertError(result: ModelFetcher.Result, substring: String) {
        assertIs<ModelFetcher.Result.Error>(result)
        assertTrue(result.message.contains(substring), "Expected error containing '$substring', got: ${result.message}")
    }

    private fun createFetcher(
        statusCode: HttpStatusCode = HttpStatusCode.OK,
        responseBody: String = """{"data":[]}""",
        assertRequest: ((MockRequestHandleScope, Url, Headers) -> Unit)? = null
    ): ModelFetcher {
        val engine = MockEngine { request ->
            assertRequest?.invoke(this, request.url, request.headers)
            respond(
                content = responseBody,
                status = statusCode,
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        }
        return ModelFetcher(json) { _ ->
            HttpClient(engine) {
                expectSuccess = false
            }
        }
    }

    @Test
    fun `SHOULD return sorted model IDs WHEN server returns valid response GIVEN models endpoint`() = runTest {
        val fetcher = createFetcher(
            responseBody = """{"data":[{"id":"zmodel","object":"model"},{"id":"amodel","object":"model"}]}"""
        )
        val result = fetcher.fetchModels("https://example.com/v1", "test-key")
        assertEquals(listOf("amodel", "zmodel"), assertSuccess(result))
    }

    @Test
    fun `SHOULD send authorization header WHEN apiKey provided GIVEN valid request`() = runTest {
        var capturedAuth: String? = null
        val fetcher = createFetcher { _, _, headers ->
            capturedAuth = headers[HttpHeaders.Authorization]
        }
        fetcher.fetchModels("https://example.com/v1", "my-secret-key")
        assertEquals("Bearer my-secret-key", capturedAuth)
    }

    @Test
    fun `SHOULD not send authorization header WHEN apiKey is null GIVEN Ollama-like server`() = runTest {
        var capturedAuth: String? = null
        val fetcher = createFetcher(
            responseBody = """{"data":[{"id":"llama3.1:8b","object":"model"}]}"""
        ) { _, _, headers ->
            capturedAuth = headers[HttpHeaders.Authorization]
        }
        fetcher.fetchModels("https://example.com/v1", null)
        assertEquals(null, capturedAuth)
    }

    @Test
    fun `SHOULD return auth error WHEN server returns 401 GIVEN invalid key`() = runTest {
        val fetcher = createFetcher(statusCode = HttpStatusCode.Unauthorized, responseBody = "")
        val result = fetcher.fetchModels("https://example.com/v1", "bad-key")
        assertError(result, "Authentication")
    }

    @Test
    fun `SHOULD return auth error WHEN server returns 403 GIVEN forbidden key`() = runTest {
        val fetcher = createFetcher(statusCode = HttpStatusCode.Forbidden, responseBody = "")
        val result = fetcher.fetchModels("https://example.com/v1", "bad-key")
        assertError(result, "Authentication")
    }

    @Test
    fun `SHOULD return empty list WHEN data array is empty GIVEN valid response`() = runTest {
        val fetcher = createFetcher(responseBody = """{"data":[]}""")
        val result = fetcher.fetchModels("https://example.com/v1", "key")
        assertTrue(assertSuccess(result).isEmpty())
    }

    @Test
    fun `SHOULD return error WHEN response is malformed JSON GIVEN broken server`() = runTest {
        val fetcher = createFetcher(responseBody = "not json at all")
        val result = fetcher.fetchModels("https://example.com/v1", "key")
        assertError(result, "parse")
    }

    @Test
    fun `SHOULD return server error WHEN status is 500 GIVEN server error`() = runTest {
        val fetcher = createFetcher(statusCode = HttpStatusCode.InternalServerError, responseBody = "")
        val result = fetcher.fetchModels("https://example.com/v1", "key")
        assertError(result, "500")
    }

    @Test
    fun `SHOULD call models endpoint WHEN fetching GIVEN base URL`() = runTest {
        var capturedPath: String? = null
        val fetcher = createFetcher { _, url, _ ->
            capturedPath = url.encodedPath
        }
        fetcher.fetchModels("https://example.com/v1", "key")
        assertTrue(capturedPath?.endsWith("/v1/models") == true)
    }
}
