/*
 * AeroShift — Multi-Cloud File Migration & Synchronization
 * Author: Cesar Farago (https://github.com/cesarfarago)
 * Copyright (c) 2026 Cesar Farago. All rights reserved.
 */

package com.aeroshift.connectors.onedrive

import com.aeroshift.auth.AccessTokenProvider
import com.aeroshift.domain.error.RateLimitException
import com.aeroshift.domain.model.CloudFile
import com.aeroshift.domain.model.CloudFolder
import com.aeroshift.domain.model.ProviderId
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandler
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.seconds

class OneDriveProviderTest {
    private val tokens = object : AccessTokenProvider {
        override suspend fun accessToken() = "ms-token"
    }
    private val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")
    private val file = CloudFile("ITEM1", "a.bin", ProviderId("onedrive"), 10)

    private fun provider(handler: MockRequestHandler) = OneDriveProvider(HttpClient(MockEngine(handler)), tokens)

    @Test
    fun `lista com paginacao e ignora itens que nao sao arquivo nem pasta`() = runTest {
        val page1 = """
            {"value":[
              {"id":"1","name":"Docs","size":0,"folder":{"childCount":2}},
              {"id":"2","name":"foto.jpg","size":2048,"file":{"mimeType":"image/jpeg"},"lastModifiedDateTime":"2024-05-01T10:00:00Z"}
            ],"@odata.nextLink":"https://graph.microsoft.com/v1.0/next-page"}
        """.trimIndent()
        val page2 = """{"value":[{"id":"3","name":"Notebook","size":5,"package":{"type":"oneNote"}}]}"""
        val urls = mutableListOf<String>()
        val provider = provider { request ->
            urls += request.url.toString()
            assertEquals("Bearer ms-token", request.headers[HttpHeaders.Authorization])
            respond(if (request.url.encodedPath.endsWith("next-page")) page2 else page1, HttpStatusCode.OK, jsonHeaders)
        }

        val entries = provider.listChildren(null)

        assertEquals(2, entries.size)
        assertIs<CloudFolder>(entries[0])
        assertEquals(2048L, (entries[1] as CloudFile).sizeBytes)
        assertEquals(2, urls.size)
        assertEquals("https://graph.microsoft.com/v1.0/next-page", urls[1])
    }

    @Test
    fun `download segue o redirect sem enviar o token ao CDN e reutiliza a URL`() = runTest {
        val cdnCalls = mutableListOf<String?>()
        var contentCalls = 0
        val provider = provider { request ->
            if (request.url.host == "graph.microsoft.com") {
                contentCalls++
                assertEquals("Bearer ms-token", request.headers[HttpHeaders.Authorization])
                respond("", HttpStatusCode.Found, headersOf(HttpHeaders.Location, "https://cdn.test/blob"))
            } else {
                assertNull(request.headers[HttpHeaders.Authorization], "o token nao pode vazar para o CDN")
                cdnCalls += request.headers[HttpHeaders.Range]
                respond(byteArrayOf(3, 4, 5, 6), HttpStatusCode.PartialContent)
            }
        }
        val buffer = ByteArray(4)

        assertEquals(4, provider.readRange(file, 3, buffer, 4))
        assertEquals(4, provider.readRange(file, 3, buffer, 4))

        assertContentEquals(byteArrayOf(3, 4, 5, 6), buffer)
        assertEquals(1, contentCalls, "a URL pre-autenticada deve ser reutilizada")
        assertEquals(listOf<String?>("bytes=3-6", "bytes=3-6"), cdnCalls)
    }

    @Test
    fun `URL de download expirada e renovada`() = runTest {
        var contentCalls = 0
        val provider = provider { request ->
            if (request.url.host == "graph.microsoft.com") {
                contentCalls++
                respond("", HttpStatusCode.Found, headersOf(HttpHeaders.Location, "https://cdn.test/v$contentCalls"))
            } else if (request.url.encodedPath == "/v1") {
                respond("", HttpStatusCode.Forbidden)
            } else {
                respond(byteArrayOf(9), HttpStatusCode.PartialContent)
            }
        }
        val buffer = ByteArray(1)

        assertEquals(1, provider.readRange(file, 0, buffer, 1))
        assertEquals(2, contentCalls)
    }

    @Test
    fun `upload session em dois chunks sem Authorization na uploadUrl`() = runTest {
        val ranges = mutableListOf<String?>()
        val provider = provider { request ->
            when {
                request.method == HttpMethod.Post -> {
                    assertEquals("/v1.0/me/drive/root:/a.bin:/createUploadSession", request.url.encodedPath)
                    respond("""{"uploadUrl":"https://upload.test/s1"}""", HttpStatusCode.OK, jsonHeaders)
                }
                request.method == HttpMethod.Put -> {
                    assertNull(request.headers[HttpHeaders.Authorization])
                    ranges += request.headers[HttpHeaders.ContentRange]
                    if (request.headers[HttpHeaders.ContentRange] == "bytes 0-4/10") {
                        respond("""{"nextExpectedRanges":["5-"]}""", HttpStatusCode.Accepted, jsonHeaders)
                    } else {
                        respond("""{"id":"novo","name":"a.bin","size":10,"file":{}}""", HttpStatusCode.Created, jsonHeaders)
                    }
                }
                else -> error("metodo inesperado ${request.method}")
            }
        }

        val session = provider.beginUpload(null, "a.bin", 10, null)
        session.uploadChunk(0, ByteArray(5), 5, isLast = false)
        session.uploadChunk(5, ByteArray(5), 5, isLast = true)

        assertEquals(listOf<String?>("bytes 0-4/10", "bytes 5-9/10"), ranges)
        assertEquals("novo", session.complete().id)
    }

    @Test
    fun `committedOffset le nextExpectedRanges`() = runTest {
        val provider = provider { request ->
            if (request.method == HttpMethod.Post) {
                respond("""{"uploadUrl":"https://upload.test/s1"}""", HttpStatusCode.OK, jsonHeaders)
            } else {
                respond("""{"nextExpectedRanges":["327680-"]}""", HttpStatusCode.OK, jsonHeaders)
            }
        }

        val session = provider.beginUpload("PARENT", "a.bin", 1_000_000, null)

        assertEquals(327_680L, session.committedOffset())
    }

    @Test
    fun `arquivo vazio usa upload simples`() = runTest {
        val provider = provider { request ->
            assertEquals(HttpMethod.Put, request.method)
            assertEquals("/v1.0/me/drive/items/PARENT:/vazio.txt:/content", request.url.encodedPath)
            respond("""{"id":"e1","name":"vazio.txt","size":0,"file":{}}""", HttpStatusCode.Created, jsonHeaders)
        }

        val session = provider.beginUpload("PARENT", "vazio.txt", 0, null)
        session.uploadChunk(0, ByteArray(0), 0, isLast = true)

        assertEquals("e1", session.complete().id)
    }

    @Test
    fun `pasta ja existente e reutilizada`() = runTest {
        val provider = provider { request ->
            if (request.method == HttpMethod.Post) {
                respond("""{"error":{"code":"nameAlreadyExists"}}""", HttpStatusCode.Conflict, jsonHeaders)
            } else {
                respond("""{"id":"existente","name":"Docs","folder":{}}""", HttpStatusCode.OK, jsonHeaders)
            }
        }

        assertEquals("existente", provider.createFolder(null, "Docs").id)
    }

    @Test
    fun `429 vira RateLimitException com Retry-After`() = runTest {
        val provider = provider { respond("", HttpStatusCode.TooManyRequests, headersOf(HttpHeaders.RetryAfter, "12")) }

        val error = assertFailsWith<RateLimitException> { provider.listChildren(null) }

        assertEquals(12.seconds, error.retryAfter)
    }
}
