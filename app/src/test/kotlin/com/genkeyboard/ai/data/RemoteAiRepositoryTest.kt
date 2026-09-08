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
import com.genkeyboard.ai.domain.AiResult
import com.genkeyboard.ai.domain.AiTone
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import java.io.IOException
import java.net.SocketTimeoutException
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

class RemoteAiRepositoryTest {
    private class RecordingTransport(private val respond: () -> HttpResponse) : HttpTransport {
        var url = ""
        var headers: Map<String, String> = emptyMap()
        var body = ""

        override suspend fun post(url: String, headers: Map<String, String>, body: String): HttpResponse {
            this.url = url; this.headers = headers; this.body = body
            return respond()
        }
    }

    private val config: suspend () -> AiBackendConfig =
        { AiBackendConfig("https://api.example.test/", "0f1e2d3c-4b5a-6978-8796-a5b4c3d2e1f0") }

    @Test
    fun `success response maps to Success and sends device id`() = runTest {
        val transport = RecordingTransport { HttpResponse(200, """{"text":"Hello world","provider":"xai","remaining":41}""") }
        val repo = RemoteAiRepository(transport, config)

        val result = repo.transform("helo wrld", AiAction.Tone(AiTone.GEN_Z))

        result shouldBe AiResult.Success("Hello world", "xai", 41)
        transport.url shouldBe "https://api.example.test/v1/transform"
        transport.headers["X-Device-Id"] shouldBe "0f1e2d3c-4b5a-6978-8796-a5b4c3d2e1f0"
        transport.body shouldContain "\"action\":\"tone\""
        transport.body shouldContain "\"tone\":\"genz\""
    }

    @Test
    fun `http errors map to typed failures`() = runTest {
        suspend fun failureFor(code: Int, body: String = "{}"): AiError {
            val repo = RemoteAiRepository(RecordingTransport { HttpResponse(code, body) }, config)
            return repo.transform("x", AiAction.Grammar).shouldBeInstanceOf<AiResult.Failure>().error
        }

        failureFor(429) shouldBe AiError.QUOTA_EXCEEDED
        failureFor(401) shouldBe AiError.UNAUTHORIZED
        failureFor(400) shouldBe AiError.INVALID_REQUEST
        failureFor(502, """{"error":{"code":"upstream_failed"}}""") shouldBe AiError.UPSTREAM
        failureFor(418) shouldBe AiError.UNKNOWN
    }

    @Test
    fun `transport exceptions map to network and timeout`() = runTest {
        val timeout = RemoteAiRepository(RecordingTransport { throw SocketTimeoutException() }, config)
        val offline = RemoteAiRepository(RecordingTransport { throw IOException("down") }, config)

        timeout.transform("x", AiAction.Grammar) shouldBe AiResult.Failure(AiError.TIMEOUT)
        offline.transform("x", AiAction.Grammar) shouldBe AiResult.Failure(AiError.NETWORK)
    }

    @Test
    fun `action dto carries only the fields the action needs`() {
        AiAction.Grammar.toDto("t") shouldBe TransformRequestDto("grammar", "t")
        AiAction.Translate("Danish").toDto("t") shouldBe TransformRequestDto("translate", "t", language = "Danish")
        AiAction.Custom("make it rhyme").toDto("t") shouldBe TransformRequestDto("custom", "t", prompt = "make it rhyme")
    }
}
