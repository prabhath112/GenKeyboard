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

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Prompt-bar draft. While [draft] is non-null the editor feeds typed keys here instead of the app's text
 * field, so the user can write a new AI prompt on the real keyboard without leaving it.
 * A plain object (not a manager) so EditorInstance can consult it without depending on AiManager.
 */
object PromptCapture {
    /** Matches MAX_PROMPT_CHARS on the backend. */
    const val MAX_CHARS = 500

    private val _draft = MutableStateFlow<String?>(null)
    val draft: StateFlow<String?> = _draft.asStateFlow()

    val isActive: Boolean get() = _draft.value != null

    fun start() {
        _draft.value = ""
    }

    fun stop() {
        _draft.value = null
    }

    /** Appends typed text. Line breaks become spaces because saved prompts are stored one per line. */
    fun type(text: String) {
        _draft.update { cur -> cur?.let { (it + text.replace(Regex("\\s+"), " ")).take(MAX_CHARS) } }
    }

    fun backspace() {
        _draft.update { cur -> cur?.dropLast(1) }
    }
}
