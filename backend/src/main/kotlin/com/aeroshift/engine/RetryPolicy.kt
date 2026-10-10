/*
 * AeroShift — Multi-Cloud File Migration & Synchronization
 * Author: Cesar Farago (https://github.com/cesarfarago)
 * Copyright (c) 2026 Cesar Farago. All rights reserved.
 */

package com.aeroshift.engine

import com.aeroshift.domain.error.CloudException
import com.aeroshift.domain.error.RateLimitException
import kotlinx.coroutines.delay
import org.slf4j.LoggerFactory
import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

import java.nio.channels.UnresolvedAddressException

/**
 * Retry com exponential backoff + jitter. Respeita o `Retry-After` em respostas 429.
 * [IOException], [UnresolvedAddressException] e falhas de rede embrulhadas são tratadas como falhas transitórias.
 */
class RetryPolicy(
    val maxAttempts: Int = 8,
    val baseDelay: Duration = 500.milliseconds,
    val maxDelay: Duration = 30.seconds,
    private val random: Random = Random.Default,
) {
    init {
        require(maxAttempts >= 1) { "maxAttempts deve ser >= 1" }
        require(baseDelay.isPositive()) { "baseDelay deve ser positivo" }
        require(maxDelay >= baseDelay) { "maxDelay deve ser >= baseDelay" }
    }

    private val log = LoggerFactory.getLogger(RetryPolicy::class.java)

    /** Executa [block] com o índice da tentativa (0 = primeira). */
    suspend fun <T> execute(block: suspend (attempt: Int) -> T): T {
        var attempt = 0
        while (true) {
            try {
                return block(attempt)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val retryable = isRetryable(e)
                if (!retryable || attempt + 1 >= maxAttempts) throw e
                val wait = delayFor(attempt, e)
                log.warn("Tentativa {} falhou ({}); nova tentativa em {}", attempt + 1, describe(e), wait)
                delay(wait)
                attempt++
            }
        }
    }

    companion object {
        fun isRetryable(e: Throwable): Boolean {
            var curr: Throwable? = e
            while (curr != null) {
                if (curr is CloudException) return curr.retryable
                if (curr is IOException) return true
                if (curr is UnresolvedAddressException) return true
                val name = curr::class.simpleName ?: ""
                val msg = curr.message ?: ""
                if (name.contains("CompletionHandler") ||
                    name.contains("Timeout") ||
                    name.contains("ClosedChannel") ||
                    name.contains("UnresolvedAddress") ||
                    name.contains("UnknownHost") ||
                    name.contains("Connect") ||
                    name.contains("Socket") ||
                    msg.contains("timeout", ignoreCase = true) ||
                    msg.contains("connection", ignoreCase = true) ||
                    msg.contains("reset", ignoreCase = true) ||
                    msg.contains("broken pipe", ignoreCase = true) ||
                    msg.contains("unresolved", ignoreCase = true)
                ) {
                    return true
                }
                curr = curr.cause
            }
            return false
        }

        /** Mensagem limpa sem o wrapper do coroutine / invokeOnCompletion. */
        fun describe(e: Throwable): String {
            val causes = generateSequence(e) { it.cause }.take(6).toList()
            val relevant = causes.lastOrNull {
                val name = it::class.simpleName ?: ""
                !name.contains("CompletionHandler") && !it.message.isNullOrBlank()
            } ?: causes.lastOrNull() ?: e

            val name = relevant::class.simpleName ?: "Error"
            val msg = when {
                relevant is UnresolvedAddressException || name.contains("UnresolvedAddress") ->
                    "Falha temporária de DNS / resolução de rede"
                !relevant.message.isNullOrBlank() -> relevant.message!!.take(200)
                else -> "Falha de conexão / rede"
            }
            return "$name: $msg"
        }
    }

    internal fun delayFor(attempt: Int, cause: Exception): Duration {
        val exponent = attempt.coerceAtMost(30)
        val backoff = minOf(maxDelay, baseDelay * (1L shl exponent).toDouble())
        val jittered = backoff * (0.5 + random.nextDouble() * 0.5)
        val retryAfter = (cause as? RateLimitException)?.retryAfter
        return if (retryAfter != null) maxOf(retryAfter, jittered) else jittered
    }
}
