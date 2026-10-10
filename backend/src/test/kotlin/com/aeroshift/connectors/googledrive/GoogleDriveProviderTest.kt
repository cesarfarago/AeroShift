/*
 * AeroShift — Multi-Cloud File Migration & Synchronization
 * Author: Cesar Farago (https://github.com/cesarfarago)
 * Copyright (c) 2026 Cesar Farago. All rights reserved.
 */

package com.aeroshift.connectors.googledrive

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
import kotlin.time.Duration.Companion.seconds

class GoogleDriveProviderTest {
    private val tokens = object : AccessTokenProvider {
        override suspend fun accessToken() = "token-123"
    }
    private val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")

    private fun provider(handler: MockRequestHandler) =
        GoogleDriveProvider(HttpClient(MockEngine(handler)), tokens)

    private val file = CloudFile("abc", "a.bin", ProviderId("google-drive"), 10)

    @Test
    fun `lista pastas e arquivos e ignora documentos nativos do Google`() = runTest {
        val body = """
            {"files":[
              {"id":"1","name":"Pasta","mimeType":"application/vnd.google-apps.folder"},
              {"id":"2","name":"foto.jpg","mimeType":"image/jpeg","size":"2048","modifiedTime":"2024-05-01T10:00:00.000Z"},
              {"id":"3","name":"Planilha","mimeType":"application/vnd.google-apps.spreadsheet"}
            ]}
        """.trimIndent()
        val provider = provider { request ->
            assertEquals("Bearer token-123", request.headers[HttpHeaders.Authorization])
            respond(body, HttpStatusCode.OK, jsonHeaders)
        }

        val entries = provider.listChildren(null)

        assertEquals(2, entries.size)
        assertIs<CloudFolder>(entries[0])
        assertEquals(2048L, (entries[1] as CloudFile).sizeBytes)
    }

    @Test
    fun `readRange envia Range e le os bytes`() = runTest {
        val provider = provider { request ->
            assertEquals("bytes=3-6", request.headers[HttpHeaders.Range])
            respond(byteArrayOf(3, 4, 5, 6), HttpStatusCode.PartialContent)
        }
        val buffer = ByteArray(8)

        val read = provider.readRange(file, 3, buffer, 4)

        assertEquals(4, read)
        assertContentEquals(byteArrayOf(3, 4, 5, 6), buffer.copyOf(4))
    }

    @Test
    fun `upload resumable em dois chunks`() = runTest {
        val ranges = mutableListOf<String?>()
        val provider = provider { request ->
            when (request.method) {
                HttpMethod.Post -> {
                    assertEquals("10", request.headers["X-Upload-Content-Length"])
                    respond("", HttpStatusCode.OK, headersOf(HttpHeaders.Location, "https://upload.test/session"))
                }
                HttpMethod.Put -> {
                    ranges += request.headers[HttpHeaders.ContentRange]
                    if (request.headers[HttpHeaders.ContentRange] == "bytes 0-4/10") {
                        respond("", HttpStatusCode.PermanentRedirect, headersOf(HttpHeaders.Range, "bytes=0-4"))
                    } else {
                        respond("""{"id":"novo","name":"a.bin","size":"10"}""", HttpStatusCode.OK, jsonHeaders)
                    }
                }
                else -> error("método inesperado ${request.method}")
            }
        }

        val session = provider.beginUpload(null, "a.bin", 10, null)
        session.uploadChunk(0, ByteArray(5), 5, isLast = false)
        session.uploadChunk(5, ByteArray(5), 5, isLast = true)
        val created = session.complete()

        assertEquals(listOf<String?>("bytes 0-4/10", "bytes 5-9/10"), ranges)
        assertEquals("novo", created.id)
        assertEquals(10L, session.committedOffset())
    }

    @Test
    fun `committedOffset interpreta o cabecalho Range do 308`() = runTest {
        val provider = provider { request ->
            when (request.method) {
                HttpMethod.Post -> respond("", HttpStatusCode.OK, headersOf(HttpHeaders.Location, "https://upload.test/s"))
                else -> {
                    assertEquals("bytes */10", request.headers[HttpHeaders.ContentRange])
                    respond("", HttpStatusCode.PermanentRedirect, headersOf(HttpHeaders.Range, "bytes=0-4"))
                }
            }
        }

        val session = provider.beginUpload(null, "a.bin", 10, null)

        assertEquals(5L, session.committedOffset())
    }

    @Test
    fun `429 vira RateLimitException com Retry-After`() = runTest {
        val provider = provider {
            respond("", HttpStatusCode.TooManyRequests, headersOf(HttpHeaders.RetryAfter, "7"))
        }

        val error = assertFailsWith<RateLimitException> { provider.listChildren(null) }

        assertEquals(7.seconds, error.retryAfter)
    }

    @Test
    fun `403 com userRateLimitExceeded vira RateLimitException`() = runTest {
        val provider = provider {
            respond(
                """{"error":{"errors":[{"reason":"userRateLimitExceeded"}]}}""",
                HttpStatusCode.Forbidden,
                jsonHeaders,
            )
        }

        assertFailsWith<RateLimitException> { provider.listChildren(null) }
    }
}
