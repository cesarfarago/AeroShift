/*
 * AeroShift — Multi-Cloud File Migration & Synchronization
 * Author: Cesar Farago (https://github.com/cesarfarago)
 * Copyright (c) 2026 Cesar Farago. All rights reserved.
 */

package com.aeroshift.auth

import com.aeroshift.domain.error.AuthException
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.forms.FormDataContent
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.Url
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import java.security.MessageDigest
import java.time.Instant
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class PkceOAuthClientTest {
    private val config = OAuthProviders.microsoft("client-1", "secret-1", "http://localhost:8080/callback")
    private val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")
    private var now = Instant.parse("2024-01-01T00:00:00Z")

    private val forms = mutableListOf<Map<String, String>>()

    private fun client(accessToken: String = "at-1"): PkceOAuthClient {
        val engine = MockEngine { request ->
            val body = request.body as FormDataContent
            forms += body.formData.entries().associate { it.key to it.value.first() }
            respond(
                """{"access_token":"$accessToken${forms.size}","refresh_token":"rt-1","expires_in":3600}""",
                HttpStatusCode.OK,
                jsonHeaders,
            )
        }
        return PkceOAuthClient(HttpClient(engine), config) { now }
    }

    @Test
    fun `URL de autorizacao contem PKCE S256 e state`() {
        val request = client().beginAuthorization()
        val params = Url(request.url).parameters

        assertEquals("S256", params["code_challenge_method"])
        assertEquals(request.state, params["state"])
        assertEquals("code", params["response_type"])
        assertEquals("Files.ReadWrite offline_access", params["scope"])
        assertTrue(params["code_challenge"]!!.length >= 43)
    }

    @Test
    fun `troca do code envia o verifier que corresponde ao challenge`() = runTest {
        val oauth = client()
        val request = oauth.beginAuthorization()
        val challenge = Url(request.url).parameters["code_challenge"]!!

        oauth.completeAuthorization("the-code", request.state)

        val form = forms.single()
        val digest = MessageDigest.getInstance("SHA-256").digest(form.getValue("code_verifier").toByteArray())
        assertEquals(challenge, Base64.getUrlEncoder().withoutPadding().encodeToString(digest))
        assertEquals("authorization_code", form["grant_type"])
        assertEquals("the-code", form["code"])
        assertEquals("at-11", oauth.accessToken())
    }

    @Test
    fun `state invalido e rejeitado`() = runTest {
        assertFailsWith<AuthException> { client().completeAuthorization("c", "state-desconhecido") }
    }

    @Test
    fun `token expirado e renovado com refresh_token`() = runTest {
        val oauth = client()
        val request = oauth.beginAuthorization()
        oauth.completeAuthorization("c", request.state)

        now = now.plusSeconds(3600) // dentro da margem de 60s de expiracao
        val renewed = oauth.accessToken()

        assertEquals("refresh_token", forms.last()["grant_type"])
        assertEquals("rt-1", forms.last()["refresh_token"])
        assertEquals("at-12", renewed)
    }

    @Test
    fun `sem login lanca AuthException`() = runTest {
        assertFailsWith<AuthException> { client().accessToken() }
    }
}
