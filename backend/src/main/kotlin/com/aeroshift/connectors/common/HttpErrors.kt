/*
 * AeroShift — Multi-Cloud File Migration & Synchronization
 * Author: Cesar Farago (https://github.com/cesarfarago)
 * Copyright (c) 2026 Cesar Farago. All rights reserved.
 */

package com.aeroshift.connectors.common

import com.aeroshift.domain.error.AuthException
import com.aeroshift.domain.error.CloudException
import com.aeroshift.domain.error.FatalException
import com.aeroshift.domain.error.NotFoundException
import com.aeroshift.domain.error.RateLimitException
import com.aeroshift.domain.error.TransientException
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** Valor do header Retry-After em segundos (o formato de data HTTP não é usado pelas APIs de nuvem alvo). */
fun HttpResponse.retryAfter(): Duration? = headers[HttpHeaders.RetryAfter]?.trim()?.toLongOrNull()?.seconds

/** Traduz uma resposta HTTP de erro para a hierarquia [CloudException] usada pelo motor. */
suspend fun HttpResponse.toCloudException(context: String): CloudException {
    val code = status.value
    val body = runCatching { bodyAsText() }.getOrDefault("").take(500)
    val message = "$context: HTTP $code $body"
    return when {
        code == 401 -> AuthException(message)
        code == 429 -> RateLimitException(message, retryAfter())
        code == 403 && RATE_LIMIT_REASONS.any(body::contains) -> RateLimitException(message, retryAfter())
        code == 403 -> AuthException(message)
        code == 404 -> NotFoundException(message)
        (code == 503 || code == 509) && retryAfter() != null -> RateLimitException(message, retryAfter())
        code == 408 || code in 500..599 -> TransientException(message)
        else -> FatalException(message)
    }
}

private val RATE_LIMIT_REASONS = listOf("rateLimitExceeded", "userRateLimitExceeded", "activityLimitReached")
