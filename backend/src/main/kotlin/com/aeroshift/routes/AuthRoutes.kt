/*
 * AeroShift — Multi-Cloud File Migration & Synchronization
 * Author: Cesar Farago (https://github.com/cesarfarago)
 * Copyright (c) 2026 Cesar Farago. All rights reserved.
 */

package com.aeroshift.routes

import com.aeroshift.auth.OAuthProviders
import com.aeroshift.auth.PkceOAuthClient
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

fun Route.authRoutes() {
    route("/auth") {
        get("/{provider}/start") {
            val provider = call.parameters["provider"] ?: return@get call.respond(HttpStatusCode.BadRequest, "Missing provider")
            
            // For now, hardcode reading from environment variables for simplicity in this demo,
            // or we could let the frontend pass the client_id. Since the user said they are
            // receiving credentials via environment variables:
            val clientId = System.getenv("${provider.uppercase()}_CLIENT_ID") ?: return@get call.respond(HttpStatusCode.InternalServerError, "Missing Client ID in env")
            val clientSecret = System.getenv("${provider.uppercase()}_CLIENT_SECRET") // Optional
            val redirectUri = "http://localhost:8080/auth/$provider/callback"
            
            val config = when (provider.lowercase()) {
                "google" -> OAuthProviders.google(clientId, clientSecret, redirectUri)
                "microsoft" -> OAuthProviders.microsoft(clientId, clientSecret, redirectUri)
                "icloud" -> return@get call.respond(HttpStatusCode.NotImplemented, "iCloud not implemented yet")
                else -> return@get call.respond(HttpStatusCode.BadRequest, "Unsupported provider: $provider")
            }

            val client = Dependencies.clients.getOrPut(provider) {
                PkceOAuthClient(Dependencies.httpClient, config)
            }

            val authRequest = client.beginAuthorization()
            
            // Redirect the user to the authorization URL
            call.respondRedirect(authRequest.url)
        }

        get("/{provider}/callback") {
            val provider = call.parameters["provider"] ?: return@get call.respond(HttpStatusCode.BadRequest, "Missing provider")
            val code = call.request.queryParameters["code"]
            val state = call.request.queryParameters["state"]
            
            if (code == null || state == null) {
                return@get call.respond(HttpStatusCode.BadRequest, "Missing code or state")
            }

            val client = Dependencies.clients[provider] ?: return@get call.respond(HttpStatusCode.BadRequest, "No OAuth flow started for $provider")

            try {
                client.completeAuthorization(code, state)
                call.respondText("Successfully authenticated with $provider! You can close this window.")
            } catch (e: Exception) {
                call.respond(HttpStatusCode.InternalServerError, "Failed to complete authentication: ${e.message}")
            }
        }
        
        get("/{provider}/status") {
             val provider = call.parameters["provider"] ?: return@get call.respond(HttpStatusCode.BadRequest, "Missing provider")
             val client = Dependencies.clients[provider]
             call.respond(mapOf("authenticated" to (client?.isAuthorized == true)))
        }
    }
}
