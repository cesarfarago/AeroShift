/*
 * AeroShift — Multi-Cloud File Migration & Synchronization
 * Author: Cesar Farago (https://github.com/cesarfarago)
 * Copyright (c) 2026 Cesar Farago. All rights reserved.
 */

package com.aeroshift.domain.error

import kotlin.time.Duration

/** Hierarquia de erros tipados. Os conectores devem traduzir erros HTTP/IO para estes tipos. */
sealed class CloudException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    /** Se true, o [com.aeroshift.engine.RetryPolicy] tentará novamente. */
    abstract val retryable: Boolean
}

/** Falha temporária (timeout, 5xx, queda de rede). */
class TransientException(message: String, cause: Throwable? = null) : CloudException(message, cause) {
    override val retryable: Boolean = true
}

/** HTTP 429 / throttling. [retryAfter] vem do header Retry-After quando disponível. */
class RateLimitException(
    message: String,
    val retryAfter: Duration? = null,
    cause: Throwable? = null,
) : CloudException(message, cause) {
    override val retryable: Boolean = true
}

/** Token inválido/expirado ou permissão insuficiente. */
class AuthException(message: String, cause: Throwable? = null) : CloudException(message, cause) {
    override val retryable: Boolean = false
}

class NotFoundException(message: String, cause: Throwable? = null) : CloudException(message, cause) {
    override val retryable: Boolean = false
}

/** Erro irrecuperável (contrato violado, arquivo alterado durante a cópia, etc.). */
class FatalException(message: String, cause: Throwable? = null) : CloudException(message, cause) {
    override val retryable: Boolean = false
}
