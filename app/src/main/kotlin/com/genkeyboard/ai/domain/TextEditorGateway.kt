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

/**
 * The span of editor text an action operates on.
 *
 * @property text   The text as read from the editor.
 * @property start  Absolute start offset of [text] inside the editor.
 * @property isSelection True when the user had a selection; false when the whole field was taken.
 */
data class TextTarget(
    val text: String,
    val start: Int,
    val isSelection: Boolean,
) {
    val end: Int get() = start + text.length
}

/** Abstracts the IME's InputConnection so domain logic and tests never touch Android. */
interface TextEditorGateway {
    /** True for password-like fields. Callers must not send such text anywhere. */
    val isSensitiveField: Boolean

    /** Selected text if any, otherwise the whole field. Null when nothing usable is present. */
    fun readTarget(): TextTarget?

    /** Replaces [target] with [newText]. Returns the target now occupying that position, or null on failure. */
    fun replace(target: TextTarget, newText: String): TextTarget?
}
