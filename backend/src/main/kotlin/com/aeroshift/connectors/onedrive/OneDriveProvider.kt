/*
 * AeroShift — Multi-Cloud File Migration & Synchronization
 * Author: Cesar Farago (https://github.com/cesarfarago)
 * Copyright (c) 2026 Cesar Farago. All rights reserved.
 */

package com.aeroshift.connectors.onedrive

import com.aeroshift.auth.AccessTokenProvider
import com.aeroshift.connectors.common.SliceContent
import com.aeroshift.connectors.common.readBodyInto
import com.aeroshift.connectors.common.toCloudException
import com.aeroshift.domain.error.FatalException
import com.aeroshift.domain.error.NotFoundException
import com.aeroshift.domain.error.TransientException
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
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.encodeURLPath
import io.ktor.http.isSuccess
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

@Serializable
internal data class GraphHashes(val sha1Hash: String? = null, val quickXorHash: String? = null)

@Serializable
internal data class GraphFileFacet(val mimeType: String? = null, val hashes: GraphHashes? = null)

@Serializable
internal data class GraphItem(
    val id: String,
    val name: String,
    val size: Long = 0,
    val file: GraphFileFacet? = null,
    val folder: JsonElement? = null,
    val lastModifiedDateTime: String? = null,
)

@Serializable
internal data class GraphItemList(
    val value: List<GraphItem> = emptyList(),
    @SerialName("@odata.nextLink") val nextLink: String? = null,
)

@Serializable
internal data class GraphUploadSessionResponse(val uploadUrl: String)

@Serializable
internal data class GraphRanges(val nextExpectedRanges: List<String> = emptyList())

private data class CachedUrl(val url: String, val expiresAt: Instant)

/**
 * Conector do OneDrive via Microsoft Graph.
 *
 * - Download: o endpoint `/content` responde com um redirecionamento para uma URL pré-autenticada.
 *   O redirecionamento é tratado manualmente para **não** enviar o token Bearer ao CDN, e a URL é
 *   reutilizada entre os chunks do mesmo arquivo.
 * - Upload: *upload session* com chunks múltiplos de 320 KiB. As requisições à `uploadUrl` não levam
 *   `Authorization`. Arquivos vazios usam o upload simples, pois a sessão não os suporta.
 * - Itens que não são arquivo nem pasta (ex.: blocos do OneNote) não são listados.
 */
class OneDriveProvider(
    private val http: HttpClient,
    private val tokens: AccessTokenProvider,
    private val json: Json = Json { ignoreUnknownKeys = true },
    private val clock: () -> Instant = Instant::now,
) : ICloudStorageProvider {
    override val id = ProviderId("onedrive")
    override val displayName = "OneDrive"
    override val capabilities = ProviderCapabilities(
        supportsResumableUpload = true,
        chunkAlignmentBytes = 320 * 1024,
        maxChunkBytes = 60 * 1024 * 1024,
        maxConcurrentRequests = 4,
        authMethod = AuthMethod.OAuthPkce,
    )

    private val noRedirect = http.config { followRedirects = false }
    private val downloadUrls = ConcurrentHashMap<String, CachedUrl>()

    override suspend fun listChildren(folderId: String?): List<CloudEntry> {
        val token = tokens.accessToken()
        val result = mutableListOf<CloudEntry>()
        var nextUrl: String? = "$API/${childrenPath(folderId)}"
        var first = true
        while (nextUrl != null) {
            val response = http.get(nextUrl) {
                bearer(token)
                if (first) {
                    parameter("\$select", SELECT)
                    parameter("\$top", 200)
                }
            }
            first = false
            if (!response.status.isSuccess()) throw response.toCloudException("Listar pasta")
            val page = json.decodeFromString<GraphItemList>(response.bodyAsText())
            page.value.mapNotNullTo(result) { it.toEntry() }
            nextUrl = page.nextLink
        }
        return result
    }

    override suspend fun readRange(file: CloudFile, offset: Long, buffer: ByteArray, length: Int): Int {
        if (length <= 0) return 0
        return fetchRange(downloadUrl(file, refresh = false), file, offset, buffer, length)
            ?: fetchRange(downloadUrl(file, refresh = true), file, offset, buffer, length)
            ?: throw NotFoundException("Download indisponível para '${file.name}'")
    }

    override suspend fun createFolder(parentId: String?, name: String): CloudFolder {
        val response = http.post("$API/${childrenPath(parentId)}") {
            bearer(tokens.accessToken())
            contentType(ContentType.Application.Json)
            setBody(
                buildJsonObject {
                    put("name", name)
                    put("folder", buildJsonObject {})
                    put("@microsoft.graph.conflictBehavior", "fail")
                }.toString(),
            )
        }
        if (response.status == HttpStatusCode.Conflict) return existingFolder(parentId, name)
        if (!response.status.isSuccess()) throw response.toCloudException("Criar pasta '$name'")
        val created = json.decodeFromString<GraphItem>(response.bodyAsText())
        return CloudFolder(created.id, created.name, id)
    }

    override suspend fun beginUpload(parentId: String?, name: String, sizeBytes: Long, mimeType: String?): UploadSession {
        if (sizeBytes == 0L) return EmptyFileSession(parentId, name)
        val response = http.post("$API/${itemPath(parentId, name)}/createUploadSession") {
            bearer(tokens.accessToken())
            contentType(ContentType.Application.Json)
            setBody(
                buildJsonObject {
                    put("item", buildJsonObject { put("@microsoft.graph.conflictBehavior", "rename") })
                }.toString(),
            )
        }
        if (!response.status.isSuccess()) throw response.toCloudException("Iniciar upload de '$name'")
        val session = json.decodeFromString<GraphUploadSessionResponse>(response.bodyAsText())
        return GraphUploadSession(session.uploadUrl, sizeBytes)
    }

    /** Retorna os bytes lidos, ou `null` se a URL pré-autenticada em cache já não é válida. */
    private suspend fun fetchRange(url: String, file: CloudFile, offset: Long, buffer: ByteArray, length: Int): Int? =
        http.prepareGet(url) {
            header(HttpHeaders.Range, "bytes=$offset-${offset + length - 1}")
        }.execute { response ->
            when (response.status.value) {
                416 -> 0
                200 -> {
                    if (offset > 0) throw FatalException("O OneDrive ignorou o cabeçalho Range para '${file.name}'")
                    response.readBodyInto(buffer, length)
                }
                206 -> response.readBodyInto(buffer, length)
                401, 403, 404 -> null
                else -> throw response.toCloudException("Baixar '${file.name}'")
            }
        }

    private suspend fun downloadUrl(file: CloudFile, refresh: Boolean): String {
        val now = clock()
        if (!refresh) {
            downloadUrls[file.id]?.takeIf { it.expiresAt.isAfter(now) }?.let { return it.url }
        }
        val response = noRedirect.get("$API/me/drive/items/${file.id}/content") { bearer(tokens.accessToken()) }
        val location = response.headers[HttpHeaders.Location]
        if (response.status.value !in REDIRECTS || location == null) {
            throw response.toCloudException("Obter URL de download de '${file.name}'")
        }
        if (downloadUrls.size > MAX_CACHED_URLS) downloadUrls.values.removeIf { !it.expiresAt.isAfter(now) }
        downloadUrls[file.id] = CachedUrl(location, now.plus(URL_TTL))
        return location
    }

    private suspend fun existingFolder(parentId: String?, name: String): CloudFolder {
        val response = http.get("$API/${itemPath(parentId, name)}") { bearer(tokens.accessToken()) }
        if (!response.status.isSuccess()) throw response.toCloudException("Buscar pasta existente '$name'")
        val item = json.decodeFromString<GraphItem>(response.bodyAsText())
        if (item.folder == null) throw FatalException("Já existe um arquivo chamado '$name' no destino")
        return CloudFolder(item.id, item.name, id)
    }

    private fun childrenPath(folderId: String?) =
        "me/drive/${if (folderId == null) "root" else "items/$folderId"}/children"

    /** Endereça um item por caminho relativo a uma pasta: `/items/{id}:/{nome}:`. */
    private fun itemPath(parentId: String?, name: String) =
        "me/drive/${if (parentId == null) "root" else "items/$parentId"}:/${name.encodeURLPath()}:"

    private fun GraphItem.toEntry(): CloudEntry? = when {
        folder != null -> CloudFolder(id, name, this@OneDriveProvider.id)
        file != null -> toCloudFile()
        else -> null
    }

    private fun GraphItem.toCloudFile() = CloudFile(
        id = id,
        name = name,
        providerId = this@OneDriveProvider.id,
        sizeBytes = size,
        mimeType = file?.mimeType,
        modifiedAt = lastModifiedDateTime?.let { runCatching { Instant.parse(it) }.getOrNull() },
        hash = file?.hashes?.sha1Hash ?: file?.hashes?.quickXorHash
    )

    private fun HttpRequestBuilder.bearer(token: String) = header(HttpHeaders.Authorization, "Bearer $token")

    private inner class GraphUploadSession(private val uploadUrl: String, private val total: Long) : UploadSession {
        private var created: CloudFile? = null

        override suspend fun uploadChunk(offset: Long, buffer: ByteArray, length: Int, isLast: Boolean, bufferOffset: Int) {
            val response = http.put(uploadUrl) {
                header(HttpHeaders.ContentRange, "bytes $offset-${offset + length - 1}/$total")
                setBody(SliceContent(buffer, length, bufferOffset))
            }
            when (response.status.value) {
                200, 201 -> created = json.decodeFromString<GraphItem>(response.bodyAsText()).toCloudFile()
                202 -> if (isLast) throw TransientException("O OneDrive não finalizou o upload após o último chunk")
                else -> throw response.toCloudException("Enviar chunk em $offset")
            }
        }

        override suspend fun committedOffset(): Long {
            if (created != null) return total
            val response = http.get(uploadUrl)
            if (!response.status.isSuccess()) throw response.toCloudException("Consultar progresso do upload")
            val ranges = json.decodeFromString<GraphRanges>(response.bodyAsText())
            // "26-" ou "26-100": o primeiro número é o próximo byte esperado.
            return ranges.nextExpectedRanges.firstOrNull()?.substringBefore('-')?.toLongOrNull() ?: total
        }

        override suspend fun complete(): CloudFile =
            created ?: throw FatalException("Upload não concluído no OneDrive")

        override suspend fun abort() {
            runCatching { http.delete(uploadUrl) }
        }
    }

    /** Upload simples (PUT /content) para arquivos de 0 byte, que a upload session não aceita. */
    private inner class EmptyFileSession(private val parentId: String?, private val name: String) : UploadSession {
        private var created: CloudFile? = null

        override suspend fun uploadChunk(offset: Long, buffer: ByteArray, length: Int, isLast: Boolean, bufferOffset: Int) {
            val response = http.put("$API/${itemPath(parentId, name)}/content") {
                bearer(tokens.accessToken())
                parameter("@microsoft.graph.conflictBehavior", "rename")
                setBody(SliceContent(buffer, 0, bufferOffset))
            }
            if (!response.status.isSuccess()) throw response.toCloudException("Criar arquivo vazio '$name'")
            created = json.decodeFromString<GraphItem>(response.bodyAsText()).toCloudFile()
        }

        override suspend fun committedOffset(): Long = 0

        override suspend fun complete(): CloudFile = created ?: throw FatalException("Upload não concluído no OneDrive")

        override suspend fun abort() = Unit
    }

    private companion object {
        const val API = "https://graph.microsoft.com/v1.0"
        const val SELECT = "id,name,size,file,folder,lastModifiedDateTime"
        const val MAX_CACHED_URLS = 512
        val URL_TTL: Duration = Duration.ofMinutes(10)
        val REDIRECTS = setOf(301, 302, 303, 307, 308)
    }
}
