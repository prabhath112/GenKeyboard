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
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

private const val DEVICE = "0f1e2d3c-4b5a-6978-8796-a5b4c3d2e1f0"
private const val BASE_URL = "https://api.example.test/"

/** Answers per path so an attest call and a transform call can be scripted independently. */
private class RoutingTransport(
    private val attest: () -> HttpResponse,
    private val transform: () -> HttpResponse,
) : HttpTransport {
    val calls = mutableListOf<Pair<String, Map<String, String>>>()

    override suspend fun post(url: String, headers: Map<String, String>, body: String): HttpResponse {
        calls += url to headers
        return if (url.endsWith(SessionProvider.ATTEST_PATH)) attest() else transform()
    }
}

private class FakeTokenSource(private val token: String?) : IntegrityTokenSource {
    var requestHash: String? = null
    var calls = 0

    override suspend fun token(requestHash: String): String? {
        this.requestHash = requestHash
        calls++
        return token
    }
}

class SessionProviderTest {
    @Test
    fun `attests once and caches the session`() = runTest {
        val transport = RoutingTransport(
            attest = { HttpResponse(200, """{"session":"sess-abc","expiresIn":86400}""") },
            transform = { HttpResponse(200, "{}") },
        )
        val source = FakeTokenSource("integrity-token")
        val provider = SessionProvider(transport, source)

        provider.session(BASE_URL, DEVICE) shouldBe "sess-abc"
        provider.session(BASE_URL, DEVICE) shouldBe "sess-abc"

        source.calls shouldBe 1
        transport.calls.size shouldBe 1
        transport.calls[0].first shouldBe "https://api.example.test/v1/attest"
        transport.calls[0].second["X-Device-Id"] shouldBe DEVICE
    }

    @Test
    fun `binds the integrity token to the device id`() = runTest {
        val source = FakeTokenSource("integrity-token")
        val provider = SessionProvider(
            RoutingTransport({ HttpResponse(200, """{"session":"s"}""") }, { HttpResponse(200, "{}") }),
            source,
        )

        provider.session(BASE_URL, DEVICE)

        // The worker recomputes this hash and refuses the token if it does not match.
        source.requestHash shouldBe sha256Hex(DEVICE)
    }

    @Test
    fun `forceRefresh discards the cached session`() = runTest {
        val sessions = ArrayDeque(listOf("first", "second"))
        val provider = SessionProvider(
            RoutingTransport({ HttpResponse(200, """{"session":"${sessions.removeFirst()}"}""") }, { HttpResponse(200, "{}") }),
            FakeTokenSource("integrity-token"),
        )

        provider.session(BASE_URL, DEVICE) shouldBe "first"
        provider.session(BASE_URL, DEVICE, forceRefresh = true) shouldBe "second"
    }

    @Test
    fun `returns null when attestation is unavailable or refused`() = runTest {
        val noPlayServices = SessionProvider(
            RoutingTransport({ HttpResponse(200, """{"session":"s"}""") }, { HttpResponse(200, "{}") }),
            FakeTokenSource(null),
        )
        val refused = SessionProvider(
            RoutingTransport({ HttpResponse(403, """{"error":{"code":"attestation_failed"}}""") }, { HttpResponse(200, "{}") }),
            FakeTokenSource("integrity-token"),
        )

        noPlayServices.session(BASE_URL, DEVICE) shouldBe null
        refused.session(BASE_URL, DEVICE) shouldBe null
    }
}

class RemoteAiRepositoryAttestationTest {
    private val config: suspend () -> AiBackendConfig = { AiBackendConfig(BASE_URL, DEVICE) }

    @Test
    fun `sends the session as a bearer token`() = runTest {
        val transport = RoutingTransport(
            attest = { HttpResponse(200, """{"session":"sess-abc"}""") },
            transform = { HttpResponse(200, """{"text":"ok","provider":"gemini"}""") },
        )
        val repo = RemoteAiRepository(transport, config, SessionProvider(transport, FakeTokenSource("t")))

        repo.transform("x", AiAction.Grammar) shouldBe AiResult.Success("ok", "gemini", null)
        transport.calls.last().second["Authorization"] shouldBe "Bearer sess-abc"
    }

    @Test
    fun `re-attests once when the gate rejects a stale session`() = runTest {
        val sessions = ArrayDeque(listOf("stale", "fresh"))
        val transformResponses = ArrayDeque(
            listOf(
                HttpResponse(401, """{"error":{"code":"attestation_required"}}"""),
                HttpResponse(200, """{"text":"ok","provider":"gemini"}"""),
            ),
        )
        val transport = RoutingTransport(
            attest = { HttpResponse(200, """{"session":"${sessions.removeFirst()}"}""") },
            transform = { transformResponses.removeFirst() },
        )
        val repo = RemoteAiRepository(transport, config, SessionProvider(transport, FakeTokenSource("t")))

        repo.transform("x", AiAction.Grammar) shouldBe AiResult.Success("ok", "gemini", null)

        val transforms = transport.calls.filter { it.first.endsWith("/v1/transform") }
        transforms.size shouldBe 2
        transforms[0].second["Authorization"] shouldBe "Bearer stale"
        transforms[1].second["Authorization"] shouldBe "Bearer fresh"
    }

    @Test
    fun `does not retry a plain unauthorized`() = runTest {
        var transformCalls = 0
        val transport = RoutingTransport(
            attest = { HttpResponse(200, """{"session":"sess-abc"}""") },
            transform = {
                transformCalls++
                HttpResponse(401, """{"error":{"code":"device_limit"}}""")
            },
        )
        val repo = RemoteAiRepository(transport, config, SessionProvider(transport, FakeTokenSource("t")))

        repo.transform("x", AiAction.Grammar)
            .shouldBeInstanceOf<AiResult.Failure>().error shouldBe AiError.UNAUTHORIZED
        transformCalls shouldBe 1
    }

    @Test
    fun `omits the header entirely when attestation is unavailable`() = runTest {
        val transport = RoutingTransport(
            attest = { HttpResponse(503, "{}") },
            transform = { HttpResponse(200, """{"text":"ok","provider":"gemini"}""") },
        )
        val repo = RemoteAiRepository(transport, config, SessionProvider(transport, FakeTokenSource(null)))

        repo.transform("x", AiAction.Grammar) shouldBe AiResult.Success("ok", "gemini", null)

        val transform = transport.calls.last()
        transform.second.containsKey("Authorization") shouldBe false
        transform.second["X-Device-Id"] shouldBe DEVICE
    }

    @Test
    fun `sha256Hex matches the worker digest`() {
        sha256Hex("abc") shouldContain "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
    }
}
