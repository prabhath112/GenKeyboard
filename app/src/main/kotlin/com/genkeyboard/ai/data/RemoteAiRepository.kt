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
        val headers = mapOf(HEADER_DEVICE_ID to cfg.deviceId)

        val response = try {
            transport.post(url, headers, body)
        } catch (_: SocketTimeoutException) {
            return AiResult.Failure(AiError.TIMEOUT)
        } catch (_: IOException) {
            return AiResult.Failure(AiError.NETWORK)
        } catch (_: IllegalArgumentException) {
            return AiResult.Failure(AiError.INVALID_REQUEST)
        }

        return response.toResult()
    }

    private fun HttpResponse.toResult(): AiResult {
        if (code in 200..299) {
            return runCatching { json.decodeFromString(TransformResponseDto.serializer(), body) }
                .map { AiResult.Success(it.text, it.provider, it.remaining) }
                .getOrElse { AiResult.Failure(AiError.UPSTREAM) }
        }
        val errorCode = runCatching { json.decodeFromString(ErrorEnvelopeDto.serializer(), body).error.code }
            .getOrNull()
        return AiResult.Failure(mapError(code, errorCode))
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
    }
}

internal fun AiAction.toDto(text: String): TransformRequestDto = when (this) {
    is AiAction.Tone -> TransformRequestDto(wireName, text, tone = tone.wireName)
    is AiAction.Translate -> TransformRequestDto(wireName, text, language = language)
    is AiAction.Reply -> TransformRequestDto(wireName, text, sentiment = sentiment.wireName)
    is AiAction.Custom -> TransformRequestDto(wireName, text, prompt = prompt)
    else -> TransformRequestDto(wireName, text)
}
