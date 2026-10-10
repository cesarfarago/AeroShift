/*
 * AeroShift — Multi-Cloud File Migration & Synchronization
 * Author: Cesar Farago (https://github.com/cesarfarago)
 * Copyright (c) 2026 Cesar Farago. All rights reserved.
 */

package com.aeroshift.connectors.common

import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.ContentType
import io.ktor.http.content.OutgoingContent
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readAvailable

/** Lê até [length] bytes do corpo da resposta direto para [buffer]; retorna o total lido. */
suspend fun HttpResponse.readBodyInto(buffer: ByteArray, length: Int): Int {
    val channel = bodyAsChannel()
    var filled = 0
    while (filled < length) {
        val read = channel.readAvailable(buffer, filled, length - filled)
        if (read < 0) break
        filled += read
    }
    return filled
}

/** Corpo HTTP que envia apenas [length] bytes de [buffer] a partir de [offset]. */
class SliceContent(
    private val buffer: ByteArray,
    private val length: Int,
    private val offset: Int = 0,
) : OutgoingContent.ByteArrayContent() {
    override val contentLength: Long = length.toLong()
    override val contentType: ContentType = ContentType.Application.OctetStream
    override fun bytes(): ByteArray =
        if (offset == 0 && buffer.size == length) buffer
        else buffer.copyOfRange(offset, offset + length)
}
