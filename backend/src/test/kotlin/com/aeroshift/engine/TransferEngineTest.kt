/*
 * AeroShift — Multi-Cloud File Migration & Synchronization
 * Author: Cesar Farago (https://github.com/cesarfarago)
 * Copyright (c) 2026 Cesar Farago. All rights reserved.
 */

package com.aeroshift.engine

import com.aeroshift.domain.model.ProviderId
import com.aeroshift.domain.port.ProviderCapabilities
import kotlinx.coroutines.test.runTest
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

class TransferEngineTest {
    private val config = EngineConfig(
        maxConcurrentFiles = 3,
        buffersPerFile = 3,
        targetChunkBytes = 16 * 1024,
        retry = RetryPolicy(maxAttempts = 4, baseDelay = 1.milliseconds, maxDelay = 4.milliseconds),
    )

    private fun providers() = FakeProvider(ProviderId("src")) to FakeProvider(ProviderId("dst"))

    private fun randomBytes(size: Int) = Random(size).nextBytes(size)

    @Test
    fun `copia arquivos e pastas preservando o conteudo`() = runTest {
        val (src, dst) = providers()
        val a = randomBytes(100_000)
        val b = randomBytes(5)
        val c = randomBytes(40_000)
        val rootFile = src.addFile(null, "a.bin", a)
        val folder = src.addFolder(null, "docs")
        src.addFile(folder.id, "b.bin", b)
        val sub = src.addFolder(folder.id, "sub")
        src.addFile(sub.id, "c.bin", c)

        val result = TransferEngine(config).run(TransferRequest(src, dst, listOf(rootFile, folder)))

        assertEquals(TransferState.COMPLETED, result.state)
        assertEquals(3, result.completedFiles)
        assertEquals(0, result.failedFiles)
        assertEquals(a.size + b.size + c.size.toLong(), result.transferredBytes)
        assertContentEquals(a, dst.uploaded["a.bin"])
        assertContentEquals(b, dst.uploaded["b.bin"])
        assertContentEquals(c, dst.uploaded["c.bin"])
    }

    @Test
    fun `arquivo vazio e transferido`() = runTest {
        val (src, dst) = providers()
        val empty = src.addFile(null, "empty.txt", ByteArray(0))

        val result = TransferEngine(config).run(TransferRequest(src, dst, listOf(empty)))

        assertEquals(TransferState.COMPLETED, result.state)
        assertContentEquals(ByteArray(0), dst.uploaded["empty.txt"])
    }

    @Test
    fun `falha transitoria reenvia apenas o chunk`() = runTest {
        val (src, dst) = providers()
        val data = randomBytes(64 * 1024) // 4 chunks de 16 KiB
        val file = src.addFile(null, "big.bin", data)
        dst.failBeforeWrite.set(2)

        val result = TransferEngine(config).run(TransferRequest(src, dst, listOf(file)))

        assertEquals(TransferState.COMPLETED, result.state)
        assertContentEquals(data, dst.uploaded["big.bin"])
        assertEquals(1, dst.beginUploadCalls.get(), "o arquivo nao deve ser reiniciado")
        assertEquals(4 + 2, dst.uploadChunkCalls.get())
    }

    @Test
    fun `ACK perdido nao duplica dados`() = runTest {
        val (src, dst) = providers()
        val data = randomBytes(48 * 1024)
        val file = src.addFile(null, "ack.bin", data)
        dst.failAfterWrite.set(1)

        val result = TransferEngine(config).run(TransferRequest(src, dst, listOf(file)))

        assertEquals(TransferState.COMPLETED, result.state)
        assertContentEquals(data, dst.uploaded["ack.bin"])
        // 3 chunks, e o chunk com ACK perdido nao e reenviado (committedOffset ja cobre o chunk)
        assertEquals(3, dst.uploadChunkCalls.get())
    }

    @Test
    fun `falhas persistentes marcam o arquivo como falho sem interromper os demais`() = runTest {
        val (src, dst) = providers()
        val bad = src.addFile(null, "bad.bin", randomBytes(20_000))
        dst.failBeforeWrite.set(Int.MAX_VALUE)

        val result = TransferEngine(config).run(TransferRequest(src, dst, listOf(bad)))

        assertEquals(TransferState.COMPLETED_WITH_ERRORS, result.state)
        assertEquals(1, result.failedFiles)
        assertEquals(0L, result.transferredBytes)
        assertTrue(result.failures.single().path.endsWith("bad.bin"))
    }

    @Test
    fun `destino sem upload retomavel reinicia o arquivo inteiro`() = runTest {
        val src = FakeProvider(ProviderId("src"))
        val dst = FakeProvider(
            ProviderId("dst"),
            FakeProvider.DEFAULT_CAPS.copy(supportsResumableUpload = false),
        )
        val data = randomBytes(40_000)
        val file = src.addFile(null, "x.bin", data)
        dst.failBeforeWrite.set(1)

        val result = TransferEngine(config).run(TransferRequest(src, dst, listOf(file)))

        assertEquals(TransferState.COMPLETED, result.state)
        assertContentEquals(data, dst.uploaded["x.bin"])
        assertEquals(2, dst.beginUploadCalls.get())
        assertEquals(data.size.toLong(), result.transferredBytes)
    }

    @Test
    fun `chunk respeita alinhamento comum dos provedores`() {
        val graph = FakeProvider.DEFAULT_CAPS.copy(chunkAlignmentBytes = 320 * 1024, maxChunkBytes = 60 * 1024 * 1024)
        val google = FakeProvider.DEFAULT_CAPS.copy(chunkAlignmentBytes = 256 * 1024, maxChunkBytes = 1024 * 1024 * 1024)

        val size = ChunkSizing.resolve(10 * 1024 * 1024, graph, google)

        assertEquals(0, size % (1280 * 1024))
        assertEquals(10 * 1024 * 1024, size)
    }

    @Test
    fun `provedores incompativeis lancam erro`() {
        val a: ProviderCapabilities = FakeProvider.DEFAULT_CAPS.copy(chunkAlignmentBytes = 3000, maxChunkBytes = 3000)
        val b: ProviderCapabilities = FakeProvider.DEFAULT_CAPS.copy(chunkAlignmentBytes = 4000, maxChunkBytes = 4000)

        assertFailsWith<IllegalArgumentException> { ChunkSizing.resolve(1_000_000, a, b) }
    }
}
