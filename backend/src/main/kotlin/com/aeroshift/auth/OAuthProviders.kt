/*
 * AeroShift — Multi-Cloud File Migration & Synchronization
 * Author: Cesar Farago (https://github.com/cesarfarago)
 * Copyright (c) 2026 Cesar Farago. All rights reserved.
 */

package com.aeroshift.auth

/** Configurações OAuth prontas para cada provedor. Os Client IDs são fornecidos pelo usuário. */
object OAuthProviders {
    /** Google: crie um OAuth Client do tipo "Desktop app" e informe o redirect `http://localhost:<porta>/callback`. */
    fun google(clientId: String, clientSecret: String?, redirectUri: String) = OAuthConfig(
        clientId = clientId,
        clientSecret = clientSecret,
        authorizationEndpoint = "https://accounts.google.com/o/oauth2/v2/auth",
        tokenEndpoint = "https://oauth2.googleapis.com/token",
        scopes = listOf("https://www.googleapis.com/auth/drive"),
        redirectUri = redirectUri,
        // offline + consent garantem o refresh_token.
        extraAuthorizationParams = mapOf("access_type" to "offline", "prompt" to "consent"),
    )

    /**
     * Microsoft: registre o app no Azure AD como cliente público ("Mobile and desktop applications")
     * com redirect `http://localhost:<porta>/callback`, contas pessoais e organizacionais habilitadas.
     * Se for um cliente confidencial ("Web"), envie o clientSecret.
     */
    fun microsoft(clientId: String, clientSecret: String?, redirectUri: String) = OAuthConfig(
        clientId = clientId,
        clientSecret = clientSecret,
        authorizationEndpoint = "https://login.microsoftonline.com/consumers/oauth2/v2.0/authorize",
        tokenEndpoint = "https://login.microsoftonline.com/consumers/oauth2/v2.0/token",
        scopes = listOf("Files.ReadWrite", "offline_access"),
        redirectUri = redirectUri,
    )
}
