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

import com.genkeyboard.ai.domain.AiAction
import com.genkeyboard.ai.domain.AiError
import com.genkeyboard.ai.domain.AiRepository
import com.genkeyboard.ai.domain.AiResult
import java.io.IOException
import java.net.SocketTimeoutException
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Where to send requests and who we are. Read fresh per request so settings changes apply immediately. */
data class AiBackendConfig(
    val baseUrl: String,
    val deviceId: String,
)

@Serializable
internal data class TransformRequestDto(
    val action: String,
    val text: String,
    val tone: String? = null,
    val language: String? = null,
    val sentiment: String? = null,
    val prompt: String? = null,
)

@Serializable
internal data class TransformResponseDto(
    val text: String,
    val provider: String = "unknown",
    val remaining: Int? = null,
)

@Serializable
internal data class ErrorEnvelopeDto(val error: ErrorDto) {
    @Serializable
    data class ErrorDto(val code: String, val message: String = "")
}

class RemoteAiRepository(
    private val transport: HttpTransport,
    private val config: suspend () -> AiBackendConfig,
    /** Null when the build cannot attest; requests then carry only the device id. */
    private val sessions: SessionProvider? = null,
) : AiRepository {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
        explicitNulls = false
    }

    override suspend fun transform(text: String, action: AiAction): AiResult {
        val cfg = config()
        val url = cfg.baseUrl.trimEnd('/') + TRANSFORM_PATH
        val body = json.encodeToString(TransformRequestDto.serializer(), action.toDto(text))

        val response = try {
            val first = transport.post(url, headers(cfg, refresh = false), body)
            // A cached session expires, or the backend gate gets switched on mid-session:
            // re-attest once and retry before surfacing an error the user cannot act on.
            if (sessions != null && first.needsAttestation()) {
                transport.post(url, headers(cfg, refresh = true), body)
            } else {
                first
            }
        } catch (_: SocketTimeoutException) {
            return AiResult.Failure(AiError.TIMEOUT)
        } catch (_: IOException) {
            return AiResult.Failure(AiError.NETWORK)
        } catch (_: IllegalArgumentException) {
            return AiResult.Failure(AiError.INVALID_REQUEST)
        }

        return response.toResult()
    }

    private suspend fun headers(cfg: AiBackendConfig, refresh: Boolean): Map<String, String> {
        val base = mapOf(HEADER_DEVICE_ID to cfg.deviceId)
        val session = sessions?.session(cfg.baseUrl, cfg.deviceId, refresh) ?: return base
        return base + (HEADER_AUTHORIZATION to "Bearer $session")
    }

    private fun HttpResponse.needsAttestation(): Boolean = code == 401 && errorCode() == "attestation_required"

    private fun HttpResponse.errorCode(): String? =
        runCatching { json.decodeFromString(ErrorEnvelopeDto.serializer(), body).error.code }.getOrNull()

    private fun HttpResponse.toResult(): AiResult {
        if (code in 200..299) {
            return runCatching { json.decodeFromString(TransformResponseDto.serializer(), body) }
                .map { AiResult.Success(it.text, it.provider, it.remaining) }
                .getOrElse { AiResult.Failure(AiError.UPSTREAM) }
        }
        return AiResult.Failure(mapError(code, errorCode()))
    }

    private fun mapError(httpCode: Int, errorCode: String?): AiError = when {
        errorCode == "quota_exceeded" || httpCode == 429 -> AiError.QUOTA_EXCEEDED
        httpCode == 401 || httpCode == 403 -> AiError.UNAUTHORIZED
        httpCode == 400 -> AiError.INVALID_REQUEST
        httpCode in 500..599 -> AiError.UPSTREAM
        else -> AiError.UNKNOWN
    }

    companion object {
        const val TRANSFORM_PATH = "/v1/transform"
        const val HEADER_DEVICE_ID = "X-Device-Id"
        const val HEADER_AUTHORIZATION = "Authorization"
    }
}

internal fun AiAction.toDto(text: String): TransformRequestDto = when (this) {
    is AiAction.Tone -> TransformRequestDto(wireName, text, tone = tone.wireName)
    is AiAction.Translate -> TransformRequestDto(wireName, text, language = language)
    is AiAction.Reply -> TransformRequestDto(wireName, text, sentiment = sentiment.wireName)
    is AiAction.Custom -> TransformRequestDto(wireName, text, prompt = prompt)
    else -> TransformRequestDto(wireName, text)
}
