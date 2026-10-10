/*
 * AeroShift — Multi-Cloud File Migration & Synchronization
 * Author: Cesar Farago (https://github.com/cesarfarago)
 * Copyright (c) 2026 Cesar Farago. All rights reserved.
 */

package com.aeroshift.engine

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.Serializable

@Serializable
enum class TransferState { IDLE, RUNNING, COMPLETED, COMPLETED_WITH_ERRORS, FAILED, CANCELLED }

@Serializable
data class FileProgress(val key: String, val path: String, val sizeBytes: Long, val transferredBytes: Long)

@Serializable
data class FileFailure(val path: String, val reason: String)

@Serializable
data class TransferSnapshot(
    val state: TransferState = TransferState.IDLE,
    val totalFiles: Int = 0,
    val completedFiles: Int = 0,
    val failedFiles: Int = 0,
    val totalBytes: Long = 0,
    val transferredBytes: Long = 0,
    val failedBytes: Long = 0,
    val bytesPerSecond: Double = 0.0,
    val etaSeconds: Long? = null,
    val active: List<FileProgress> = emptyList(),
    val failures: List<FileFailure> = emptyList(),
    val skippedFiles: Int = 0,
    val skipped: List<FileFailure> = emptyList(),
    val discoveryDone: Boolean = false,
)

/** Mantém o estado observável do progresso. A UI consome [snapshot] via SSE. */
class ProgressTracker {
    private val state = MutableStateFlow(TransferSnapshot())
    val snapshot: StateFlow<TransferSnapshot> = state.asStateFlow()

    private var lastSampleNanos: Long = 0
    private var lastSampleBytes: Long = 0

    fun reset() {
        state.value = TransferSnapshot()
        lastSampleNanos = 0
        lastSampleBytes = 0
    }

    fun setState(newState: TransferState) = state.update { it.copy(state = newState) }

    fun fileDiscovered(sizeBytes: Long) = state.update {
        it.copy(totalFiles = it.totalFiles + 1, totalBytes = it.totalBytes + sizeBytes)
    }

    fun fileStarted(key: String, path: String, sizeBytes: Long) = state.update {
        it.copy(active = it.active + FileProgress(key, path, sizeBytes, 0))
    }

    /** [delta] pode ser negativo quando um arquivo é reiniciado por completo. */
    fun bytesTransferred(key: String, delta: Long) = state.update { s ->
        s.copy(
            transferredBytes = s.transferredBytes + delta,
            active = s.active.map {
                if (it.key == key) it.copy(transferredBytes = (it.transferredBytes + delta).coerceAtLeast(0)) else it
            },
        )
    }

    fun fileCompleted(key: String) = state.update { s ->
        s.copy(completedFiles = s.completedFiles + 1, active = s.active.filterNot { it.key == key })
    }

    fun fileFailed(key: String, path: String, reason: String) = state.update { s ->
        val file = s.active.firstOrNull { it.key == key }
        s.copy(
            failedFiles = s.failedFiles + 1,
            transferredBytes = s.transferredBytes - (file?.transferredBytes ?: 0),
            failedBytes = s.failedBytes + (file?.sizeBytes ?: 0),
            active = s.active.filterNot { it.key == key },
            failures = s.failures + FileFailure(path, reason),
        )
    }

    /** Arquivo ignorado pela curadoria (vazio/duplicado). Não é erro. */
    fun fileSkipped(path: String, reason: String) = state.update {
        it.copy(skippedFiles = it.skippedFiles + 1, skipped = it.skipped + FileFailure(path, reason))
    }

    fun discoveryFinished() = state.update { it.copy(discoveryDone = true) }

    /** Falha fora de um arquivo (ex.: criar/listar pasta). */
    fun entryFailed(path: String, reason: String) = state.update {
        it.copy(failures = it.failures + FileFailure(path, reason))
    }

    /** Atualiza velocidade (média móvel exponencial) e ETA. Chamado periodicamente pelo motor. */
    fun sample(nowNanos: Long) = state.update { s ->
        if (lastSampleNanos == 0L) {
            lastSampleNanos = nowNanos
            lastSampleBytes = s.transferredBytes
            return@update s
        }
        val seconds = (nowNanos - lastSampleNanos) / 1_000_000_000.0
        if (seconds <= 0.0) return@update s
        val instant = ((s.transferredBytes - lastSampleBytes) / seconds).coerceAtLeast(0.0)
        val speed = if (s.bytesPerSecond == 0.0) instant else 0.3 * instant + 0.7 * s.bytesPerSecond
        lastSampleNanos = nowNanos
        lastSampleBytes = s.transferredBytes
        val remaining = (s.totalBytes - s.failedBytes - s.transferredBytes).coerceAtLeast(0)
        s.copy(bytesPerSecond = speed, etaSeconds = if (speed > 1.0) (remaining / speed).toLong() else null)
    }
}
