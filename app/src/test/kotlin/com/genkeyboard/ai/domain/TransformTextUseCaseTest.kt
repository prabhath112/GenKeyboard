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

package com.genkeyboard.ai.domain

import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

class TransformTextUseCaseTest {
    private class FakeRepository : AiRepository {
        var calls = 0
        override suspend fun transform(text: String, action: AiAction): AiResult {
            calls++
            return AiResult.Success("ok:$text", "fake", null)
        }
    }

    private val repo = FakeRepository()
    private val useCase = TransformTextUseCase(repo, maxChars = 10)
    private val target = TextTarget("hello", start = 0, isSelection = false)

    @Test
    fun `sensitive field never reaches the repository`() = runTest {
        useCase.execute(target, isSensitiveField = true, AiAction.Grammar) shouldBe AiResult.Failure(AiError.SENSITIVE_FIELD)
        repo.calls shouldBe 0
    }

    @Test
    fun `missing or blank text fails fast`() = runTest {
        useCase.execute(null, false, AiAction.Grammar) shouldBe AiResult.Failure(AiError.NO_TEXT)
        useCase.execute(TextTarget("   ", 0, false), false, AiAction.Grammar) shouldBe AiResult.Failure(AiError.NO_TEXT)
        repo.calls shouldBe 0
    }

    @Test
    fun `oversized text fails without a round trip`() = runTest {
        useCase.execute(TextTarget("a".repeat(11), 0, false), false, AiAction.Grammar) shouldBe
            AiResult.Failure(AiError.TEXT_TOO_LONG)
        repo.calls shouldBe 0
    }

    @Test
    fun `valid target is forwarded`() = runTest {
        useCase.execute(target, false, AiAction.Paraphrase) shouldBe AiResult.Success("ok:hello", "fake", null)
        repo.calls shouldBe 1
    }

    @Test
    fun `text target end offset is start plus length`() {
        TextTarget("abc", start = 5, isSelection = true).end shouldBe 8
    }
}
