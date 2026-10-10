/*
 * AeroShift — Multi-Cloud File Migration & Synchronization
 * Author: Cesar Farago (https://github.com/cesarfarago)
 * Copyright (c) 2026 Cesar Farago. All rights reserved.
 */

package com.aeroshift.engine

import com.aeroshift.domain.error.FatalException
import com.aeroshift.domain.model.CloudFile
import com.aeroshift.domain.port.ICloudStorageProvider
import com.aeroshift.domain.port.UploadSession
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Copia um arquivo da origem para o destino somente via RAM.
 *
 * Um leitor lê chunks da origem para buffers do [ChunkBufferPool] e os entrega, por um canal
 * limitado (backpressure), a um escritor que os envia ao destino. O buffer só volta ao pool
 * depois que o destino confirma o chunk. Nada é gravado em disco.
 */
class StreamingPipeline(
    private val pool: ChunkBufferPool,
    private val retry: RetryPolicy,
    /** Chunks aguardando no canal. Buffers por arquivo = [queuedChunks] + 2 (leitor e escritor). */
    private val queuedChunks: Int,
) {
    init {
        require(queuedChunks >= 0) { "queuedChunks deve ser >= 0" }
    }

    private class Chunk(val offset: Long, val buffer: ByteArray, val length: Int, val isLast: Boolean)

    /**
     * @param onBytes recebe o delta de bytes confirmados no destino (negativo ao reiniciar o arquivo).
     */
    suspend fun transfer(
        source: ICloudStorageProvider,
        destination: ICloudStorageProvider,
        file: CloudFile,
        destinationParentId: String?,
        onBytes: (Long) -> Unit,
    ): CloudFile {
        if (destination.capabilities.supportsResumableUpload) {
            return attemptTransfer(source, destination, file, destinationParentId, onBytes)
        }
        // Sem upload retomável: qualquer falha reinicia o arquivo inteiro.
        return retry.execute {
            var sent = 0L
            try {
                attemptTransfer(source, destination, file, destinationParentId) { delta ->
                    sent += delta
                    onBytes(delta)
                }
            } catch (e: Exception) {
                if (sent != 0L) onBytes(-sent)
                throw e
            }
        }
    }

    private suspend fun attemptTransfer(
        source: ICloudStorageProvider,
        destination: ICloudStorageProvider,
        file: CloudFile,
        destinationParentId: String?,
        onBytes: (Long) -> Unit,
    ): CloudFile = coroutineScope {
        val session = retry.execute {
            destination.beginUpload(destinationParentId, file.name, file.sizeBytes, file.mimeType)
        }
        val channel = Channel<Chunk>(queuedChunks, onUndeliveredElement = { pool.release(it.buffer) })
        try {
            val reader = launch {
                try {
                    readAll(source, file, channel)
                    channel.close()
                } catch (t: Throwable) {
                    channel.close(t)
                    throw t
                }
            }
            for (chunk in channel) {
                try {
                    writeChunk(session, chunk, destination.capabilities.supportsResumableUpload)
                    onBytes(chunk.length.toLong())
                } finally {
                    pool.release(chunk.buffer)
                }
            }
            reader.join()
            retry.execute { session.complete() }
        } catch (t: Throwable) {
            channel.cancel()
            withContext(NonCancellable) { runCatching { session.abort() } }
            throw t
        }
    }

    private suspend fun readAll(source: ICloudStorageProvider, file: CloudFile, channel: Channel<Chunk>) {
        var offset = 0L
        do {
            val wanted = minOf(pool.chunkSize.toLong(), file.sizeBytes - offset).toInt()
            val buffer = pool.acquire()
            try {
                retry.execute { readFully(source, file, offset, buffer, wanted) }
                val isLast = offset + wanted >= file.sizeBytes
                channel.send(Chunk(offset, buffer, wanted, isLast))
            } catch (t: Throwable) {
                pool.release(buffer)
                throw t
            }
            offset += wanted
        } while (offset < file.sizeBytes)
    }

    private suspend fun readFully(
        source: ICloudStorageProvider,
        file: CloudFile,
        offset: Long,
        buffer: ByteArray,
        wanted: Int,
    ) {
        var filled = 0
        while (filled < wanted) {
            val read = if (filled == 0) {
                source.readRange(file, offset, buffer, wanted)
            } else {
                // Provedor devolveu menos que o pedido: completa o restante em um buffer auxiliar curto.
                val rest = ByteArray(wanted - filled)
                val n = source.readRange(file, offset + filled, rest, rest.size)
                if (n > 0) System.arraycopy(rest, 0, buffer, filled, n)
                n
            }
            if (read <= 0) {
                throw FatalException("Fim inesperado de '${file.name}' em ${offset + filled} de ${file.sizeBytes} bytes")
            }
            filled += read
        }
    }

    private suspend fun writeChunk(session: UploadSession, chunk: Chunk, resumable: Boolean) {
        if (!resumable) {
            // Sem retomada: a falha sobe e o arquivo inteiro é reiniciado em [transfer].
            session.uploadChunk(chunk.offset, chunk.buffer, chunk.length, chunk.isLast)
            return
        }
        val end = chunk.offset + chunk.length
        retry.execute { attempt ->
            val committed = if (attempt > 0) session.committedOffset() else chunk.offset
            when {
                committed >= end -> return@execute
                committed < chunk.offset ->
                    throw FatalException("Offset do destino ($committed) inconsistente com o chunk em ${chunk.offset}")
                else -> {
                    val bufferOffset = (committed - chunk.offset).toInt()
                    val remainingLength = chunk.length - bufferOffset
                    session.uploadChunk(committed, chunk.buffer, remainingLength, chunk.isLast, bufferOffset)
                }
            }
        }
    }
}
