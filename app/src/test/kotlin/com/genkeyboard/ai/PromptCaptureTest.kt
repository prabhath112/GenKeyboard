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

package com.genkeyboard.ai

import io.kotest.matchers.shouldBe
import kotlin.test.AfterTest
import kotlin.test.Test

class PromptCaptureTest {
    @AfterTest
    fun reset() = PromptCapture.stop()

    @Test
    fun `typing is ignored until a draft is started`() {
        PromptCapture.type("abc")

        PromptCapture.draft.value shouldBe null
    }

    @Test
    fun `type and backspace edit the draft`() {
        PromptCapture.start()
        PromptCapture.type("hello")
        PromptCapture.backspace()

        PromptCapture.draft.value shouldBe "hell"
    }

    @Test
    fun `line breaks collapse to a single space`() {
        PromptCapture.start()
        PromptCapture.type("make it\n\nshort")

        PromptCapture.draft.value shouldBe "make it short"
    }

    @Test
    fun `draft is capped at the backend prompt limit`() {
        PromptCapture.start()
        PromptCapture.type("a".repeat(PromptCapture.MAX_CHARS + 50))

        PromptCapture.draft.value!!.length shouldBe PromptCapture.MAX_CHARS
    }
}
