/*
 * AeroShift — Multi-Cloud File Migration & Synchronization
 * Author: Cesar Farago (https://github.com/cesarfarago)
 * Copyright (c) 2026 Cesar Farago. All rights reserved.
 */

package com.aeroshift.routes

import com.aeroshift.connectors.googledrive.GoogleDriveProvider
import com.aeroshift.connectors.onedrive.OneDriveProvider
import com.aeroshift.domain.model.CloudEntry
import com.aeroshift.domain.model.CloudFile
import com.aeroshift.domain.model.CloudFolder
import com.aeroshift.domain.model.ProviderId
import com.aeroshift.domain.port.ICloudStorageProvider
import com.aeroshift.engine.TransferEngine
import com.aeroshift.engine.TransferRequest
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.sse.*
import io.ktor.sse.ServerSentEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.transformWhile
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

import com.aeroshift.engine.ProcessingOrder

@Serializable
data class StartTransferRequest(
    val sourceProvider: String,
    val destinationProvider: String,
    val entries: List<CloudEntryDto>,
    val destinationFolderId: String? = null,
    val skipEmptyFiles: Boolean = false,
    val skipDuplicates: Boolean = false,
    val processingOrder: ProcessingOrder = ProcessingOrder.NATURAL
)

@Serializable
data class CloudEntryDto(
    val id: String,
    val name: String,
    val isFolder: Boolean,
    val sizeBytes: Long = 0,
    val hash: String? = null
)

fun CloudEntryDto.toDomain(providerId: String): CloudEntry = if (isFolder) {
    CloudFolder(id, name, ProviderId(providerId))
} else {
    CloudFile(id, name, ProviderId(providerId), sizeBytes, hash = hash)
}

val activeTransfers = ConcurrentHashMap<String, TransferEngine>()

@Serializable
data class CreateFolderReq(val name: String, val parentId: String? = null)

fun Route.transferRoutes() {
    route("/api") {
        
        post("/files/{provider}/folder") {
            val providerName = call.parameters["provider"] ?: return@post call.respond(HttpStatusCode.BadRequest, "Missing provider")
            val provider = getProviderInstance(providerName) ?: return@post call.respond(HttpStatusCode.Unauthorized, "Provider not authorized")
            
            val req = call.receive<CreateFolderReq>()
            try {
                val newFolderId = provider.createFolder(req.parentId, req.name)
                call.respond(mapOf("id" to newFolderId.id, "name" to req.name))
            } catch (e: Exception) {
                call.respond(HttpStatusCode.InternalServerError, mapOf("error" to e.message))
            }
        }

        get("/files/{provider}") {
            val providerName = call.parameters["provider"] ?: return@get call.respond(HttpStatusCode.BadRequest, "Missing provider")
            val folderId = call.request.queryParameters["folderId"]
            
            val provider = getProviderInstance(providerName)
                ?: return@get call.respond(HttpStatusCode.Unauthorized, "Provider $providerName not authenticated")
            
            val children = provider.listChildren(folderId)
            call.respond(children.map { 
                CloudEntryDto(
                    id = it.id, 
                    name = it.name, 
                    isFolder = it is CloudFolder,
                    sizeBytes = if (it is CloudFile) it.sizeBytes else 0,
                    hash = if (it is CloudFile) it.hash else null
                ) 
            })
        }

        post("/transfer") {
            val request = call.receive<StartTransferRequest>()
            
            val sourceProvider = getProviderInstance(request.sourceProvider)
                ?: return@post call.respond(HttpStatusCode.Unauthorized, "Source provider ${request.sourceProvider} not authenticated")
                
            val destinationProvider = getProviderInstance(request.destinationProvider)
                ?: return@post call.respond(HttpStatusCode.Unauthorized, "Destination provider ${request.destinationProvider} not authenticated")

            val engine = TransferEngine()
            val transferId = UUID.randomUUID().toString()
            activeTransfers[transferId] = engine

            // Launch transfer in background
            CoroutineScope(Dispatchers.IO + Job()).launch {
                try {
                    engine.run(
                        TransferRequest(
                            source = sourceProvider,
                            destination = destinationProvider,
                            entries = request.entries.map { it.toDomain(request.sourceProvider) },
                            destinationFolderId = request.destinationFolderId,
                            skipEmptyFiles = request.skipEmptyFiles,
                            skipDuplicates = request.skipDuplicates,
                            processingOrder = request.processingOrder
                        )
                    )
                } finally {
                    // Keep for a while to allow final status fetch, then could be cleaned up
                    delay(30 * 60_000L)
                    activeTransfers.remove(transferId)
                }
            }

            call.respond(mapOf("transferId" to transferId))
        }

        sse("/transfer/{id}/status") {
            val transferId = call.parameters["id"]
            if (transferId == null) {
                close()
                return@sse
            }
            val engine = activeTransfers[transferId]
            if (engine == null) {
                close()
                return@sse
            }

            val json = Json { encodeDefaults = true }

            val terminal = setOf("COMPLETED", "COMPLETED_WITH_ERRORS", "FAILED", "CANCELLED")
            try {
                engine.progress
                    .transformWhile { snapshot ->
                        emit(snapshot)
                        snapshot.state.name !in terminal
                    }
                    .collect { snapshot ->
                        send(ServerSentEvent(data = json.encodeToString(snapshot)))
                    }
            } catch (e: java.io.IOException) {
                // Cliente fechou a conexão (refresh/EventSource.close()); não é um erro.
            }
        }
    }
}

private fun getProviderInstance(providerName: String): ICloudStorageProvider? {
    val client = Dependencies.clients[providerName] ?: return null
    if (!client.isAuthorized) return null
    return when (providerName.lowercase()) {
        "google" -> GoogleDriveProvider(Dependencies.httpClient, client)
        "microsoft" -> OneDriveProvider(Dependencies.httpClient, client)
        else -> null
    }
}
