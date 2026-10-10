/*
 * AeroShift — Multi-Cloud File Migration & Synchronization
 * Author: Cesar Farago (https://github.com/cesarfarago)
 * Copyright (c) 2026 Cesar Farago. All rights reserved.
 */

package com.aeroshift.auth

import com.aeroshift.connectors.common.toCloudException
import com.aeroshift.domain.error.AuthException
import io.ktor.client.HttpClient
import io.ktor.client.request.forms.submitForm
import io.ktor.client.statement.bodyAsText
import io.ktor.http.Parameters
import io.ktor.http.URLBuilder
import io.ktor.http.isSuccess
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

/** Fornece um access token válido (renovando-o quando necessário). */
interface AccessTokenProvider {
    suspend fun accessToken(): String
}

data class OAuthConfig(
    val clientId: String,
    val authorizationEndpoint: String,
    val tokenEndpoint: String,
    val scopes: List<String>,
    val redirectUri: String,
    /** Apps desktop do Google exigem o client_secret (que não é confidencial nesse tipo de app). */
    val clientSecret: String? = null,
    val extraAuthorizationParams: Map<String, String> = emptyMap(),
)

data class AuthorizationRequest(val url: String, val state: String)

@Serializable
private data class TokenResponse(
    @SerialName("access_token") val accessToken: String,
    @SerialName("refresh_token") val refreshToken: String? = null,
    @SerialName("expires_in") val expiresIn: Long = 3600,
)

private data class Tokens(val accessToken: String, val refreshToken: String?, val expiresAt: Instant)

/**
 * OAuth 2.0 Authorization Code com PKCE (RFC 7636), reutilizável por Google e Microsoft.
 * Os tokens ficam somente em memória: nada é persistido em disco.
 */
class PkceOAuthClient(
    private val http: HttpClient,
    private val config: OAuthConfig,
    private val clock: () -> Instant = Instant::now,
) : AccessTokenProvider {
    private val json = Json { ignoreUnknownKeys = true }
    private val random = SecureRandom()
    private val mutex = Mutex()
    private val pendingVerifiers = ConcurrentHashMap<String, String>()

    @Volatile
    private var tokens: Tokens? = null

    val isAuthorized: Boolean get() = tokens != null

    /** Gera a URL de consentimento. O navegador deve ser redirecionado para ela. */
    fun beginAuthorization(): AuthorizationRequest {
        val verifier = randomUrlSafe(48)
        val state = randomUrlSafe(24)
        pendingVerifiers[state] = verifier
        val url = URLBuilder(config.authorizationEndpoint).apply {
            parameters.append("client_id", config.clientId)
            parameters.append("response_type", "code")
            parameters.append("redirect_uri", config.redirectUri)
            parameters.append("scope", config.scopes.joinToString(" "))
            parameters.append("state", state)
            parameters.append("code_challenge", challengeFor(verifier))
            parameters.append("code_challenge_method", "S256")
            config.extraAuthorizationParams.forEach { (k, v) -> parameters.append(k, v) }
        }.buildString()
        return AuthorizationRequest(url, state)
    }

    /** Troca o `code` recebido no callback por tokens. Valida o `state` (proteção contra CSRF). */
    suspend fun completeAuthorization(code: String, state: String) {
        val verifier = pendingVerifiers.remove(state) ?: throw AuthException("State OAuth inválido ou expirado")
        val response = requestTokens(
            Parameters.build {
                append("grant_type", "authorization_code")
                append("code", code)
                append("redirect_uri", config.redirectUri)
                append("code_verifier", verifier)
            },
        )
        mutex.withLock { tokens = response.toTokens(previousRefresh = null) }
    }

    override suspend fun accessToken(): String = mutex.withLock {
        val current = tokens ?: throw AuthException("Usuário não autenticado")
        if (current.expiresAt.isAfter(clock().plus(EXPIRY_MARGIN))) return@withLock current.accessToken
        val refresh = current.refreshToken ?: throw AuthException("Sessão expirada; faça login novamente")
        val response = requestTokens(
            Parameters.build {
                append("grant_type", "refresh_token")
                append("refresh_token", refresh)
            },
        )
        val renewed = response.toTokens(previousRefresh = refresh)
        tokens = renewed
        renewed.accessToken
    }

    /** Descarta os tokens da memória (logout). */
    suspend fun signOut() = mutex.withLock { tokens = null }

    private suspend fun requestTokens(extra: Parameters): TokenResponse {
        val response = http.submitForm(
            url = config.tokenEndpoint,
            formParameters = Parameters.build {
                append("client_id", config.clientId)
                config.clientSecret?.let { append("client_secret", it) }
                appendAll(extra)
            },
        )
        if (response.status.isSuccess()) return json.decodeFromString(response.bodyAsText())
        if (response.status.value == 400 || response.status.value == 401) {
            throw AuthException("Falha na autenticação OAuth: ${response.bodyAsText().take(300)}")
        }
        throw response.toCloudException("Endpoint de token")
    }

    private fun TokenResponse.toTokens(previousRefresh: String?) = Tokens(
        accessToken = accessToken,
        refreshToken = refreshToken ?: previousRefresh,
        expiresAt = clock().plusSeconds(expiresIn),
    )

    private fun randomUrlSafe(bytes: Int): String {
        val buffer = ByteArray(bytes).also(random::nextBytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(buffer)
    }

    private fun challengeFor(verifier: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII))
        return Base64.getUrlEncoder().withoutPadding().encodeToString(digest)
    }

    private companion object {
        val EXPIRY_MARGIN: Duration = Duration.ofSeconds(60)
    }
}
