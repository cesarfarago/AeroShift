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
import com.aeroshift.domain.port.UploadSession
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * Decorator que limita as requisições simultâneas a um provedor
 * ([com.aeroshift.domain.port.ProviderCapabilities.maxConcurrentRequests]), reduzindo erros 429.
 */
internal class ThrottledProvider(private val delegate: ICloudStorageProvider) : ICloudStorageProvider by delegate {
    private val gate = Semaphore(delegate.capabilities.maxConcurrentRequests)

    override suspend fun listChildren(folderId: String?): List<CloudEntry> =
        gate.withPermit { delegate.listChildren(folderId) }

    override suspend fun readRange(file: CloudFile, offset: Long, buffer: ByteArray, length: Int): Int =
        gate.withPermit { delegate.readRange(file, offset, buffer, length) }

    override suspend fun createFolder(parentId: String?, name: String): CloudFolder =
        gate.withPermit { delegate.createFolder(parentId, name) }

    override suspend fun beginUpload(
        parentId: String?,
        name: String,
        sizeBytes: Long,
        mimeType: String?,
    ): UploadSession {
        val session = gate.withPermit { delegate.beginUpload(parentId, name, sizeBytes, mimeType) }
        return ThrottledSession(session, gate)
    }

    private class ThrottledSession(private val delegate: UploadSession, private val gate: Semaphore) : UploadSession {
        override suspend fun uploadChunk(offset: Long, buffer: ByteArray, length: Int, isLast: Boolean, bufferOffset: Int) =
            gate.withPermit { delegate.uploadChunk(offset, buffer, length, isLast, bufferOffset) }

        override suspend fun committedOffset(): Long = gate.withPermit { delegate.committedOffset() }
        override suspend fun complete(): CloudFile = gate.withPermit { delegate.complete() }
        override suspend fun abort() = gate.withPermit { delegate.abort() }
    }
}
