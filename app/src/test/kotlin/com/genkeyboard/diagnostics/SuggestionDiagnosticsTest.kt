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

package com.genkeyboard.diagnostics

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test

class SuggestionDiagnosticsTest {
    private val dir = createTempDirectory("suggestion-diagnostics-test").toFile().apply { deleteOnExit() }
    private val file = File(dir, "log.txt")

    @Test
    fun `write appends a line with level, event and details`() {
        SuggestionDiagnostics.write(file, "OK", "suggest", "candidates=3 ms=5")

        SuggestionDiagnostics.readAll(file) shouldContain "OK suggest - candidates=3 ms=5"
    }

    @Test
    fun `readAll returns empty string when nothing was ever logged`() {
        SuggestionDiagnostics.readAll(File(dir, "missing.txt")) shouldBe ""
    }

    @Test
    fun `clear removes the file`() {
        SuggestionDiagnostics.write(file, "OK", "x", "")

        SuggestionDiagnostics.clear(file)

        SuggestionDiagnostics.readAll(file) shouldBe ""
    }

    @Test
    fun `file is truncated instead of growing without bound`() {
        val bigDetails = "x".repeat(1000)

        repeat(500) { SuggestionDiagnostics.write(file, "OK", "spam", bigDetails) }

        (file.length() < 500L * 1000).shouldBe(true)
    }

    @Test
    fun `formatError combines details and exception summary`() {
        val error = IllegalStateException("boom")

        SuggestionDiagnostics.formatError("provider=latin", error) shouldBe "provider=latin | IllegalStateException: boom"
        SuggestionDiagnostics.formatError("", error) shouldBe "IllegalStateException: boom"
    }
}
