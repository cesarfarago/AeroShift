/*
 * AeroShift — Multi-Cloud File Migration & Synchronization
 * Author: Cesar Farago (https://github.com/cesarfarago)
 * Copyright (c) 2026 Cesar Farago. All rights reserved.
 */

package com.aeroshift.routes

import com.aeroshift.auth.PkceOAuthClient
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json

object Dependencies {
    val httpClient = HttpClient(CIO) {
        engine {
            maxConnectionsCount = 500
            endpoint.apply {
                maxConnectionsPerRoute = 50
                connectAttempts = 5
                connectTimeout = 30_000L
                keepAliveTime = 30_000L
            }
        }
        install(ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true })
        }
        // O padrão do CIO (15s por requisição) estoura em chunks grandes; arquivos grandes streamam por minutos.
        install(HttpTimeout) {
            requestTimeoutMillis = 30 * 60_000L
            connectTimeoutMillis = 30_000L
            socketTimeoutMillis = 5 * 60_000L
        }
    }

    // In a real application, you'd manage multiple users or instances, 
    // but for this local desktop tool, singletons are fine.
    // Provider instances will be populated via config.
    val clients = mutableMapOf<String, PkceOAuthClient>()
}
