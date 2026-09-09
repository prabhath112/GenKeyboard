/*
 * Copyright (C) 2026 GenKeyboard authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.genkeyboard.ai.data

import android.content.Context
import com.google.android.gms.tasks.Task
import com.google.android.play.core.integrity.IntegrityManagerFactory
import com.google.android.play.core.integrity.StandardIntegrityManager.PrepareIntegrityTokenRequest
import com.google.android.play.core.integrity.StandardIntegrityManager.StandardIntegrityTokenProvider
import com.google.android.play.core.integrity.StandardIntegrityManager.StandardIntegrityTokenRequest
import java.io.IOException
import java.security.MessageDigest
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** SHA-256 hex, matching `sha256Hex` in the worker. Binds an integrity token to one device id. */
internal fun sha256Hex(text: String): String =
    MessageDigest.getInstance("SHA-256")
        .digest(text.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

/** Seam over the Play Integrity API so the session logic is testable without Play Services. */
interface IntegrityTokenSource {
    /** Null when attestation is unavailable (no Play Services, sideloaded, not configured). */
    suspend fun token(requestHash: String): String?
}

/**
 * Standard Integrity API. `prepareIntegrityToken` is the expensive warm-up, so the provider is
 * created once and reused; only `request` runs per attestation.
 *
 * ponytail: Play's Task is bridged by hand instead of adding kotlinx-coroutines-play-services
 * for a single call site.
 */
class PlayIntegrityTokenSource(
    context: Context,
    private val cloudProjectNumber: Long,
) : IntegrityTokenSource {

    private val appContext = context.applicationContext
    private val mutex = Mutex()
    private var provider: StandardIntegrityTokenProvider? = null

    override suspend fun token(requestHash: String): String? {
        if (cloudProjectNumber <= 0L) return null
        return runCatching {
            val ready = mutex.withLock {
                provider ?: prepareProvider().also { provider = it }
            }
            ready.request(StandardIntegrityTokenRequest.builder().setRequestHash(requestHash).build())
                .await()
                .token()
        }.getOrElse {
            // A failed warm-up must not be cached, or the app never recovers from a transient error.
            mutex.withLock { provider = null }
            null
        }
    }

    private suspend fun prepareProvider(): StandardIntegrityTokenProvider =
        IntegrityManagerFactory.createStandard(appContext)
            .prepareIntegrityToken(
                PrepareIntegrityTokenRequest.builder().setCloudProjectNumber(cloudProjectNumber).build(),
            )
            .await()
}

private suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { cont ->
    addOnSuccessListener { cont.resume(it) }
    addOnFailureListener { cont.resumeWithException(it) }
}

@Serializable
private data class AttestRequestDto(val token: String)

@Serializable
private data class AttestResponseDto(val session: String, val expiresIn: Long = 0)

/**
 * Holds the backend session token and re-attests when it is missing or rejected.
 *
 * ponytail: in-memory only. The keyboard process is long-lived, and a restart costing one extra
 * attestation is cheaper than adding a preference and its migration.
 * Upgrade path: persist to prefs if cold starts turn out to be frequent enough to matter.
 */
class SessionProvider(
    private val transport: HttpTransport,
    private val tokenSource: IntegrityTokenSource,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val mutex = Mutex()
    private var cached: String? = null

    /** Returns a session token, or null when attestation is unavailable or refused. */
    suspend fun session(baseUrl: String, deviceId: String, forceRefresh: Boolean = false): String? =
        mutex.withLock {
            if (!forceRefresh) cached?.let { return@withLock it }
            cached = null
            val integrityToken = tokenSource.token(sha256Hex(deviceId)) ?: return@withLock null
            val response = try {
                transport.post(
                    baseUrl.trimEnd('/') + ATTEST_PATH,
                    mapOf(RemoteAiRepository.HEADER_DEVICE_ID to deviceId),
                    json.encodeToString(AttestRequestDto.serializer(), AttestRequestDto(integrityToken)),
                )
            } catch (_: IOException) {
                return@withLock null
            }
            if (response.code !in 200..299) return@withLock null
            cached = runCatching {
                json.decodeFromString(AttestResponseDto.serializer(), response.body).session
            }.getOrNull()
            cached
        }

    companion object {
        const val ATTEST_PATH = "/v1/attest"
    }
}
