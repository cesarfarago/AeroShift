/*
 * AeroShift — Multi-Cloud File Migration & Synchronization
 * Author: Cesar Farago (https://github.com/cesarfarago)
 * Copyright (c) 2026 Cesar Farago. All rights reserved.
 */

package com.aeroshift.plugins

import com.aeroshift.domain.error.*
import com.aeroshift.routes.authRoutes
import com.aeroshift.routes.transferRoutes
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.plugins.statuspages.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

fun Application.configureRouting() {
    install(StatusPages) {
        exception<Throwable> { call, cause ->
            when (cause) {
                is CloudException -> {
                    val status = when (cause) {
                        is AuthException -> HttpStatusCode.Unauthorized
                        is NotFoundException -> HttpStatusCode.NotFound
                        is RateLimitException -> HttpStatusCode.TooManyRequests
                        else -> HttpStatusCode.InternalServerError
                    }
                    call.respond(status, mapOf("error" to cause.message))
                }
                is IllegalArgumentException -> {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to cause.message))
                }
                else -> {
                    call.respond(HttpStatusCode.InternalServerError, mapOf("error" to "An unexpected error occurred: ${cause.message}"))
                }
            }
        }
    }

    routing {
        authRoutes()
        transferRoutes()
    }
}
