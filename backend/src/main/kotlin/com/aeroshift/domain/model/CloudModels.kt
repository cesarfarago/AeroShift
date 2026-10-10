/*
 * AeroShift — Multi-Cloud File Migration & Synchronization
 * Author: Cesar Farago (https://github.com/cesarfarago)
 * Copyright (c) 2026 Cesar Farago. All rights reserved.
 */

package com.aeroshift.domain.model

import java.time.Instant

/** Identificador estável de um provedor de nuvem (ex.: "google-drive", "onedrive", "icloud"). */
@JvmInline
value class ProviderId(val value: String) {
    init {
        require(value.isNotBlank()) { "ProviderId não pode ser vazio" }
    }

    override fun toString(): String = value
}

/** Entrada genérica de um provedor: arquivo ou pasta. O motor nunca depende de tipos específicos de nuvem. */
sealed interface CloudEntry {
    val id: String
    val name: String
    val providerId: ProviderId
}

data class CloudFile(
    override val id: String,
    override val name: String,
    override val providerId: ProviderId,
    val sizeBytes: Long,
    val mimeType: String? = null,
    val modifiedAt: Instant? = null,
    val hash: String? = null,
) : CloudEntry {
    init {
        require(sizeBytes >= 0) { "sizeBytes não pode ser negativo" }
    }
}

data class CloudFolder(
    override val id: String,
    override val name: String,
    override val providerId: ProviderId,
) : CloudEntry
