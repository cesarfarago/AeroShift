/*
 * AeroShift — Multi-Cloud File Migration & Synchronization
 * Author: Cesar Farago (https://github.com/cesarfarago)
 * Copyright (c) 2026 Cesar Farago. All rights reserved.
 */

package com.aeroshift.connectors.googledrive

import com.aeroshift.auth.AccessTokenProvider
import com.aeroshift.connectors.common.SliceContent
import com.aeroshift.connectors.common.readBodyInto
import com.aeroshift.connectors.common.toCloudException
import com.aeroshift.domain.error.FatalException
import com.aeroshift.domain.model.CloudEntry
import com.aeroshift.domain.model.CloudFile
import com.aeroshift.domain.model.CloudFolder
import com.aeroshift.domain.model.ProviderId
import com.aeroshift.domain.port.AuthMethod
import com.aeroshift.domain.port.ICloudStorageProvider
import com.aeroshift.domain.port.ProviderCapabilities
import com.aeroshift.domain.port.UploadSession
import io.ktor.client.HttpClient
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.prepareGet
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.http.content.OutgoingContent
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readAvailable
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Instant

@Serializable
internal data class DriveFile(
    val id: String,
    val name: String,
    val mimeType: String? = null,
    val size: String? = null,
    val modifiedTime: String? = null,
    val md5Checksum: String? = null,
)

@Serializable
internal data class DriveFileList(val nextPageToken: String? = null, val files: List<DriveFile> = emptyList())

/**
 * Conector do Google Drive API v3.
 *
 * - Leitura por faixa de bytes (`Range`), o que permite repetir só o chunk que falhou.
 * - Upload via *resumable upload*: cada chunk é enviado com `Content-Range` e a posição confirmada
 *   pode ser consultada para retomar após uma falha.
 * - Arquivos nativos do Google (Docs, Sheets, Slides, atalhos) não possuem bytes para download direto
 *   e por isso não são listados.
 */
class GoogleDriveProvider(
    private val http: HttpClient,
    private val tokens: AccessTokenProvider,
    private val json: Json = Json { ignoreUnknownKeys = true },
) : ICloudStorageProvider {
    override val id = ProviderId("google-drive")
    override val displayName = "Google Drive"
    override val capabilities = ProviderCapabilities(
        supportsResumableUpload = true,
        chunkAlignmentBytes = 256 * 1024,
        maxChunkBytes = 64 * 1024 * 1024,
        maxConcurrentRequests = 4,
        authMethod = AuthMethod.OAuthPkce,
    )

    override suspend fun listChildren(folderId: String?): List<CloudEntry> {
        val parent = (folderId ?: "root").replace("'", "\\'")
        val token = tokens.accessToken()
        val result = mutableListOf<CloudEntry>()
        var pageToken: String? = null
        do {
            val response = http.get("$API/files") {
                bearer(token)
                parameter("q", "'$parent' in parents and trashed = false")
                parameter("fields", "nextPageToken,files($FILE_FIELDS)")
                parameter("pageSize", 1000)
                parameter("orderBy", "folder,name")
                pageToken?.let { parameter("pageToken", it) }
            }
            if (!response.status.isSuccess()) throw response.toCloudException("Listar pasta")
            val page = json.decodeFromString<DriveFileList>(response.bodyAsText())
            page.files.mapNotNullTo(result) { it.toEntry() }
            pageToken = page.nextPageToken
        } while (pageToken != null)
        return result
    }

    override suspend fun readRange(file: CloudFile, offset: Long, buffer: ByteArray, length: Int): Int {
        if (length <= 0) return 0
        val token = tokens.accessToken()
        val last = offset + length - 1
        return http.prepareGet("$API/files/${file.id}") {
            bearer(token)
            parameter("alt", "media")
            header(HttpHeaders.Range, "bytes=$offset-$last")
        }.execute { response ->
            when (response.status.value) {
                416 -> 0 // offset além do fim do arquivo
                200 -> {
                    if (offset > 0) throw FatalException("O Drive ignorou o cabeçalho Range para '${file.name}'")
                    response.readBodyInto(buffer, length)
                }
                206 -> response.readBodyInto(buffer, length)
                else -> throw response.toCloudException("Baixar '${file.name}'")
            }
        }
    }

    override suspend fun createFolder(parentId: String?, name: String): CloudFolder {
        val response = http.post("$API/files") {
            bearer(tokens.accessToken())
            parameter("fields", FILE_FIELDS)
            contentType(ContentType.Application.Json)
            setBody(metadata(name, parentId, FOLDER_MIME).toString())
        }
        if (!response.status.isSuccess()) throw response.toCloudException("Criar pasta '$name'")
        val created = json.decodeFromString<DriveFile>(response.bodyAsText())
        return CloudFolder(created.id, created.name, id)
    }

    override suspend fun beginUpload(parentId: String?, name: String, sizeBytes: Long, mimeType: String?): UploadSession {
        val response = http.post("$UPLOAD_API/files") {
            bearer(tokens.accessToken())
            parameter("uploadType", "resumable")
            parameter("fields", FILE_FIELDS)
            header("X-Upload-Content-Length", sizeBytes.toString())
            mimeType?.let { header("X-Upload-Content-Type", it) }
            contentType(ContentType.Application.Json)
            setBody(metadata(name, parentId, null).toString())
        }
        if (!response.status.isSuccess()) throw response.toCloudException("Iniciar upload de '$name'")
        val sessionUri = response.headers[HttpHeaders.Location]
            ?: throw FatalException("O Drive não retornou a URI da sessão de upload para '$name'")
        return DriveUploadSession(sessionUri, sizeBytes)
    }

    private fun metadata(name: String, parentId: String?, mimeType: String?) = buildJsonObject {
        put("name", name)
        mimeType?.let { put("mimeType", it) }
        parentId?.let { put("parents", JsonArray(listOf(JsonPrimitive(it)))) }
    }

    private fun DriveFile.toEntry(): CloudEntry? = when {
        mimeType == FOLDER_MIME -> CloudFolder(id, name, this@GoogleDriveProvider.id)
        mimeType?.startsWith(GOOGLE_APPS_PREFIX) == true -> null
        else -> toCloudFile()
    }

    private fun DriveFile.toCloudFile() = CloudFile(
        id = id,
        name = name,
        providerId = this@GoogleDriveProvider.id,
        sizeBytes = size?.toLongOrNull() ?: 0L,
        mimeType = mimeType,
        modifiedAt = modifiedTime?.let { runCatching { Instant.parse(it) }.getOrNull() },
        hash = md5Checksum
    )

    private fun HttpRequestBuilder.bearer(token: String) = header(HttpHeaders.Authorization, "Bearer $token")

    private inner class DriveUploadSession(private val sessionUri: String, private val total: Long) : UploadSession {
        private var created: CloudFile? = null

        override suspend fun uploadChunk(offset: Long, buffer: ByteArray, length: Int, isLast: Boolean, bufferOffset: Int) {
            val response = http.put(sessionUri) {
                bearer(tokens.accessToken())
                // Arquivo vazio: PUT sem Content-Range finaliza o upload.
                if (length > 0) header(HttpHeaders.ContentRange, "bytes $offset-${offset + length - 1}/$total")
                setBody(SliceContent(buffer, length, bufferOffset))
            }
            when (response.status.value) {
                200, 201 -> created = json.decodeFromString<DriveFile>(response.bodyAsText()).toCloudFile()
                308 -> if (isLast) {
                    throw com.aeroshift.domain.error.TransientException("O Drive não finalizou o upload após o último chunk")
                }
                else -> throw response.toCloudException("Enviar chunk em $offset")
            }
        }

        override suspend fun committedOffset(): Long {
            if (created != null) return total
            val response = http.put(sessionUri) {
                bearer(tokens.accessToken())
                header(HttpHeaders.ContentRange, "bytes */$total")
                setBody(ByteArray(0))
            }
            return when (response.status.value) {
                200, 201 -> {
                    created = json.decodeFromString<DriveFile>(response.bodyAsText()).toCloudFile()
                    total
                }
                308 -> response.headers[HttpHeaders.Range]
                    ?.substringAfter('-', "")?.toLongOrNull()?.plus(1) ?: 0L
                else -> throw response.toCloudException("Consultar progresso do upload")
            }
        }

        override suspend fun complete(): CloudFile {
            created?.let { return it }
            committedOffset()
            return created ?: throw FatalException("Upload não concluído no Drive")
        }

        override suspend fun abort() {
            runCatching {
                http.delete(sessionUri) { bearer(tokens.accessToken()) }
            }
        }
    }

    private companion object {
        const val API = "https://www.googleapis.com/drive/v3"
        const val UPLOAD_API = "https://www.googleapis.com/upload/drive/v3"
        const val FOLDER_MIME = "application/vnd.google-apps.folder"
        const val GOOGLE_APPS_PREFIX = "application/vnd.google-apps."
        const val FILE_FIELDS = "id,name,mimeType,size,modifiedTime,md5Checksum"
    }
}
