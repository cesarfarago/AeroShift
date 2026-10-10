/*
 * AeroShift — Multi-Cloud File Migration & Synchronization
 * Author: Cesar Farago (https://github.com/cesarfarago)
 * Copyright (c) 2026 Cesar Farago. All rights reserved.
 */

package com.aeroshift.domain.port

import com.aeroshift.domain.model.CloudEntry
import com.aeroshift.domain.model.CloudFile
import com.aeroshift.domain.model.CloudFolder
import com.aeroshift.domain.model.ProviderId

/** Como o usuário se autentica no provedor. O iCloud (futuro) usará [Credentials2FA]. */
sealed interface AuthMethod {
    data object OAuthPkce : AuthMethod
    data object Credentials2FA : AuthMethod
}

/**
 * Capacidades declaradas por cada conector. O motor se adapta a elas
 * (tamanho de chunk, estratégia de retry, limite de requisições simultâneas).
 */
data class ProviderCapabilities(
    /** Upload retomável por chunk. Se false, o retry reinicia o arquivo inteiro. */
    val supportsResumableUpload: Boolean,
    /** Todo chunk (exceto o último) deve ser múltiplo deste valor. */
    val chunkAlignmentBytes: Int,
    /** Maior chunk aceito pelo provedor. */
    val maxChunkBytes: Int,
    /** Máximo de requisições simultâneas ao provedor (evita HTTP 429). */
    val maxConcurrentRequests: Int,
    val authMethod: AuthMethod,
) {
    init {
        require(chunkAlignmentBytes > 0) { "chunkAlignmentBytes deve ser > 0" }
        require(maxChunkBytes >= chunkAlignmentBytes) { "maxChunkBytes deve ser >= chunkAlignmentBytes" }
        require(maxConcurrentRequests > 0) { "maxConcurrentRequests deve ser > 0" }
    }
}

/**
 * Porta única entre o motor e qualquer nuvem. O motor não conhece OneDrive, Google Drive ou iCloud.
 *
 * Todas as operações são `suspend` e devem lançar [com.aeroshift.domain.error.CloudException].
 */
interface ICloudStorageProvider {
    val id: ProviderId
    val displayName: String
    val capabilities: ProviderCapabilities

    /** Lista os filhos diretos de uma pasta. `null` representa a raiz. */
    suspend fun listChildren(folderId: String?): List<CloudEntry>

    /**
     * Lê até [length] bytes do arquivo a partir de [offset] para o início de [buffer].
     * Retorna a quantidade lida; menos que [length] só ocorre no fim do arquivo.
     * Leituras por faixa (Range) permitem retry por chunk sem reiniciar o download.
     */
    suspend fun readRange(file: CloudFile, offset: Long, buffer: ByteArray, length: Int): Int

    suspend fun createFolder(parentId: String?, name: String): CloudFolder

    suspend fun beginUpload(parentId: String?, name: String, sizeBytes: Long, mimeType: String?): UploadSession
}

/** Sessão de upload em chunks (Graph upload session / Drive resumable upload). */
interface UploadSession {
    /**
     * Envia [length] bytes de [buffer] que começam em [offset] no arquivo de destino.
     * Deve ser idempotente por chunk. [isLast] marca o último chunk (inclusive arquivos vazios).
     */
    suspend fun uploadChunk(offset: Long, buffer: ByteArray, length: Int, isLast: Boolean, bufferOffset: Int = 0)

    /** Quantidade de bytes já confirmada pelo destino (usada para retomar após falha). */
    suspend fun committedOffset(): Long

    /** Finaliza e devolve o arquivo criado. */
    suspend fun complete(): CloudFile

    /** Cancela a sessão e descarta dados parciais no destino. */
    suspend fun abort()
}
