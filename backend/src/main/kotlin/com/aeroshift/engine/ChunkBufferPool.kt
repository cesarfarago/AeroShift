/*
 * AeroShift — Multi-Cloud File Migration & Synchronization
 * Author: Cesar Farago (https://github.com/cesarfarago)
 * Copyright (c) 2026 Cesar Farago. All rights reserved.
 */

package com.aeroshift.engine

import com.aeroshift.domain.port.ProviderCapabilities
import kotlinx.coroutines.channels.Channel
import java.util.concurrent.atomic.AtomicInteger

/**
 * Pool com limite fixo de buffers em RAM. Esse limite define o teto de memória do motor:
 * `capacity * chunkSize`. Os buffers são alocados sob demanda e reutilizados.
 */
class ChunkBufferPool(val chunkSize: Int, val capacity: Int) {
    init {
        require(chunkSize > 0) { "chunkSize deve ser > 0" }
        require(capacity > 0) { "capacity deve ser > 0" }
    }

    private val free = Channel<ByteArray>(capacity)
    private val allocated = AtomicInteger(0)

    /** Suspende enquanto todos os buffers estiverem em uso (backpressure). */
    suspend fun acquire(): ByteArray {
        free.tryReceive().getOrNull()?.let { return it }
        if (allocated.incrementAndGet() <= capacity) return ByteArray(chunkSize)
        allocated.decrementAndGet()
        return free.receive()
    }

    fun release(buffer: ByteArray) {
        free.trySend(buffer)
    }
}

/** Calcula um tamanho de chunk válido para os dois provedores. */
object ChunkSizing {
    fun resolve(targetBytes: Int, source: ProviderCapabilities, destination: ProviderCapabilities): Int {
        val alignment = lcm(source.chunkAlignmentBytes.toLong(), destination.chunkAlignmentBytes.toLong())
        val cap = minOf(source.maxChunkBytes, destination.maxChunkBytes).toLong()
        require(alignment <= cap) {
            "Os provedores não possuem tamanho de chunk compatível (alinhamento $alignment > máximo $cap)"
        }
        val limited = minOf(targetBytes.toLong(), cap)
        val aligned = (limited / alignment) * alignment
        return maxOf(alignment, aligned).toInt()
    }

    private fun gcd(a: Long, b: Long): Long = if (b == 0L) a else gcd(b, a % b)
    private fun lcm(a: Long, b: Long): Long = a / gcd(a, b) * b
}
