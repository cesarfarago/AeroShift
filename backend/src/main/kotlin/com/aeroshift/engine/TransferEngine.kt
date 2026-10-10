/*
 * AeroShift — Multi-Cloud File Migration & Synchronization
 * Author: Cesar Farago (https://github.com/cesarfarago)
 * Copyright (c) 2026 Cesar Farago. All rights reserved.
 */

package com.aeroshift.engine

import com.aeroshift.domain.model.CloudEntry
import com.aeroshift.domain.model.CloudFile
import com.aeroshift.domain.model.CloudFolder
import com.aeroshift.domain.port.ICloudStorageProvider
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration.Companion.seconds

@Serializable
enum class ProcessingOrder {
    NATURAL,
    SMALLEST_FIRST,
    LARGEST_FIRST
}

data class EngineConfig(
    /** Arquivos copiados em paralelo (recomendado: 4 a 5). */
    val maxConcurrentFiles: Int = 4,
    /** Buffers por arquivo; mínimo 2 (um sendo lido e outro sendo enviado). */
    val buffersPerFile: Int = 3,
    /** Tamanho desejado de chunk; ajustado ao alinhamento dos provedores. */
    val targetChunkBytes: Int = 10 * 1024 * 1024,
    val retry: RetryPolicy = RetryPolicy(),
) {
    init {
        require(maxConcurrentFiles in 1..16) { "maxConcurrentFiles deve estar entre 1 e 16" }
        require(buffersPerFile >= 2) { "buffersPerFile deve ser >= 2" }
        require(targetChunkBytes > 0) { "targetChunkBytes deve ser > 0" }
    }

    /** Teto aproximado de RAM usada pelos buffers de dados. */
    val maxBufferMemoryBytes: Long get() = maxConcurrentFiles.toLong() * buffersPerFile * targetChunkBytes
}

data class TransferRequest(
    val source: ICloudStorageProvider,
    val destination: ICloudStorageProvider,
    /** Arquivos e pastas selecionados na origem (raiz inteira = filhos da raiz). */
    val entries: List<CloudEntry>,
    /** Pasta de destino; `null` = raiz. */
    val destinationFolderId: String? = null,
    /** Se true, ignora arquivos com tamanho 0. */
    val skipEmptyFiles: Boolean = false,
    /** Se true, ignora arquivos que possuem hash e tamanho idênticos a outro já visto (mantém apenas 1). */
    val skipDuplicates: Boolean = false,
    val processingOrder: ProcessingOrder = ProcessingOrder.NATURAL,
)

/**
 * Orquestra a migração: percorre a árvore da origem, recria as pastas no destino e copia os
 * arquivos com um número limitado de workers. A fila é limitada, então a descoberta também
 * respeita backpressure. Falhas em um arquivo não interrompem os demais.
 */
class TransferEngine(
    private val config: EngineConfig = EngineConfig(),
    private val tracker: ProgressTracker = ProgressTracker(),
) {
    val progress get() = tracker.snapshot

    private class FileTask(val file: CloudFile, val destinationParentId: String?, val path: String)

    suspend fun run(request: TransferRequest): TransferSnapshot = coroutineScope {
        val source = ThrottledProvider(request.source)
        val destination = ThrottledProvider(request.destination)
        val chunkSize = ChunkSizing.resolve(config.targetChunkBytes, source.capabilities, destination.capabilities)
        val pool = ChunkBufferPool(chunkSize, config.maxConcurrentFiles * config.buffersPerFile)
        val pipeline = StreamingPipeline(pool, config.retry, config.buffersPerFile - 2)
        val queue = Channel<FileTask>(config.maxConcurrentFiles * 2)

        tracker.reset()
        tracker.setState(TransferState.RUNNING)

        val sampler = launch {
            while (true) {
                delay(1.seconds)
                tracker.sample(System.nanoTime())
            }
        }
        val workers = List(config.maxConcurrentFiles) {
            launch {
                for (task in queue) processFile(task, source, destination, pipeline)
            }
        }

        try {
            val seenHashes = mutableSetOf<String>()
            if (request.processingOrder == ProcessingOrder.NATURAL) {
                walk(request.entries, request.destinationFolderId, "", source, destination, seenHashes, request) { task ->
                    queue.send(task)
                }
            } else {
                val tasks = mutableListOf<FileTask>()
                walk(request.entries, request.destinationFolderId, "", source, destination, seenHashes, request) { task ->
                    tasks.add(task)
                }
                when (request.processingOrder) {
                    ProcessingOrder.SMALLEST_FIRST -> tasks.sortBy { it.file.sizeBytes }
                    ProcessingOrder.LARGEST_FIRST -> tasks.sortByDescending { it.file.sizeBytes }
                    ProcessingOrder.NATURAL -> Unit
                }
                for (task in tasks) {
                    queue.send(task)
                }
            }
            tracker.discoveryFinished()
            queue.close()
            workers.joinAll()
            tracker.setState(
                if (tracker.snapshot.value.failures.isEmpty()) TransferState.COMPLETED
                else TransferState.COMPLETED_WITH_ERRORS,
            )
        } catch (e: CancellationException) {
            tracker.setState(TransferState.CANCELLED)
            throw e
        } catch (e: Exception) {
            tracker.entryFailed("/", e.message ?: e::class.simpleName.orEmpty())
            tracker.setState(TransferState.FAILED)
            throw e
        } finally {
            sampler.cancel()
        }
        tracker.snapshot.value
    }

    private suspend fun processFile(
        task: FileTask,
        source: ICloudStorageProvider,
        destination: ICloudStorageProvider,
        pipeline: StreamingPipeline,
    ) {
        val key = "${task.file.providerId}:${task.file.id}"
        tracker.fileStarted(key, task.path, task.file.sizeBytes)
        try {
            pipeline.transfer(source, destination, task.file, task.destinationParentId) { delta ->
                tracker.bytesTransferred(key, delta)
            }
            tracker.fileCompleted(key)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            tracker.fileFailed(key, task.path, RetryPolicy.describe(e))
        }
    }

    private suspend fun walk(
        entries: List<CloudEntry>,
        destinationParentId: String?,
        parentPath: String,
        source: ICloudStorageProvider,
        destination: ICloudStorageProvider,
        seenHashes: MutableSet<String>,
        request: TransferRequest,
        onTask: suspend (FileTask) -> Unit,
    ) {
        for (entry in entries) {
            val path = "$parentPath/${entry.name}"
            when (entry) {
                is CloudFile -> {
                    if (request.skipEmptyFiles && entry.sizeBytes == 0L) {
                        tracker.fileSkipped(path, "Arquivo vazio")
                        continue
                    }
                    if (request.skipDuplicates && entry.hash != null) {
                        val signature = "${entry.sizeBytes}:${entry.hash}"
                        if (!seenHashes.add(signature)) {
                            tracker.fileSkipped(path, "Arquivo duplicado")
                            continue
                        }
                    }
                    tracker.fileDiscovered(entry.sizeBytes)
                    onTask(FileTask(entry, destinationParentId, path))
                }
                is CloudFolder -> {
                    try {
                        val created = config.retry.execute { destination.createFolder(destinationParentId, entry.name) }
                        val children = config.retry.execute { source.listChildren(entry.id) }
                        walk(children, created.id, path, source, destination, seenHashes, request, onTask)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        tracker.entryFailed(path, e.message ?: e::class.simpleName.orEmpty())
                    }
                }
            }
        }
    }
}
