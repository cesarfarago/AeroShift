/*
 * AeroShift — Multi-Cloud File Migration & Synchronization
 * Author: Cesar Farago (https://github.com/cesarfarago)
 * Copyright (c) 2026 Cesar Farago. All rights reserved.
 */

package com.aeroshift.engine

import com.aeroshift.domain.error.TransientException
import com.aeroshift.domain.model.CloudEntry
import com.aeroshift.domain.model.CloudFile
import com.aeroshift.domain.model.CloudFolder
import com.aeroshift.domain.model.ProviderId
import com.aeroshift.domain.port.AuthMethod
import com.aeroshift.domain.port.ICloudStorageProvider
import com.aeroshift.domain.port.ProviderCapabilities
import com.aeroshift.domain.port.UploadSession
import java.io.ByteArrayOutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/** Provedor em memória para testes. Nenhum dado toca o disco. */
class FakeProvider(
    override val id: ProviderId,
    override val capabilities: ProviderCapabilities = DEFAULT_CAPS,
) : ICloudStorageProvider {
    override val displayName: String = id.value

    /** folderId (null = raiz) -> filhos */
    val tree = ConcurrentHashMap<String, MutableList<CloudEntry>>()
    val contents = ConcurrentHashMap<String, ByteArray>()
    val uploaded = ConcurrentHashMap<String, ByteArray>()

    /** Falhas transitórias a injetar antes de gravar um chunk. */
    val failBeforeWrite = AtomicInteger(0)

    /** Falhas após gravar o chunk (ACK perdido). */
    val failAfterWrite = AtomicInteger(0)
    val uploadChunkCalls = AtomicInteger(0)
    val beginUploadCalls = AtomicInteger(0)
    private val idSeq = AtomicInteger(0)

    private fun key(folderId: String?) = folderId ?: ROOT

    fun addFile(parentId: String?, name: String, data: ByteArray): CloudFile {
        val file = CloudFile("f${idSeq.incrementAndGet()}", name, id, data.size.toLong())
        contents[file.id] = data
        tree.getOrPut(key(parentId)) { mutableListOf() }.add(file)
        return file
    }

    fun addFolder(parentId: String?, name: String): CloudFolder {
        val folder = CloudFolder("d${idSeq.incrementAndGet()}", name, id)
        tree.getOrPut(key(parentId)) { mutableListOf() }.add(folder)
        return folder
    }

    override suspend fun listChildren(folderId: String?): List<CloudEntry> = tree[key(folderId)].orEmpty().toList()

    override suspend fun readRange(file: CloudFile, offset: Long, buffer: ByteArray, length: Int): Int {
        val data = contents.getValue(file.id)
        val n = minOf(length.toLong(), data.size - offset).toInt().coerceAtLeast(0)
        System.arraycopy(data, offset.toInt(), buffer, 0, n)
        return n
    }

    override suspend fun createFolder(parentId: String?, name: String): CloudFolder = addFolder(parentId, name)

    override suspend fun beginUpload(parentId: String?, name: String, sizeBytes: Long, mimeType: String?): UploadSession {
        beginUploadCalls.incrementAndGet()
        return object : UploadSession {
            private val out = ByteArrayOutputStream()

            override suspend fun uploadChunk(offset: Long, buffer: ByteArray, length: Int, isLast: Boolean, bufferOffset: Int) {
                uploadChunkCalls.incrementAndGet()
                if (failBeforeWrite.getAndUpdate { if (it > 0) it - 1 else 0 } > 0) {
                    throw TransientException("falha injetada antes da gravação")
                }
                if (offset == out.size().toLong()) out.write(buffer, bufferOffset, length)
                if (failAfterWrite.getAndUpdate { if (it > 0) it - 1 else 0 } > 0) {
                    throw TransientException("ACK perdido")
                }
            }

            override suspend fun committedOffset(): Long = out.size().toLong()

            override suspend fun complete(): CloudFile {
                val file = CloudFile("u${idSeq.incrementAndGet()}", name, id, out.size().toLong())
                uploaded[name] = out.toByteArray()
                tree.getOrPut(key(parentId)) { mutableListOf() }.add(file)
                return file
            }

            override suspend fun abort() = Unit
        }
    }

    companion object {
        const val ROOT = "__root__"
        const val KIB = 1024

        val DEFAULT_CAPS = ProviderCapabilities(
            supportsResumableUpload = true,
            chunkAlignmentBytes = 4 * KIB,
            maxChunkBytes = 64 * KIB,
            maxConcurrentRequests = 4,
            authMethod = AuthMethod.OAuthPkce,
        )
    }
}
