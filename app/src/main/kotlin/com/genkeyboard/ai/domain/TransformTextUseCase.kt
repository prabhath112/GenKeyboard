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
 * Validates a target and runs it through the repository.
 * All safety checks that decide whether text may leave the device live here, in one place.
 */
class TransformTextUseCase(
    private val repository: AiRepository,
    private val maxChars: Int = DEFAULT_MAX_CHARS,
) {
    suspend fun execute(target: TextTarget?, isSensitiveField: Boolean, action: AiAction): AiResult {
        if (isSensitiveField) return AiResult.Failure(AiError.SENSITIVE_FIELD)
        if (target == null || target.text.isBlank()) return AiResult.Failure(AiError.NO_TEXT)
        if (target.text.length > maxChars) return AiResult.Failure(AiError.TEXT_TOO_LONG)
        return repository.transform(target.text, action)
    }

    companion object {
        /** Mirrors MAX_TEXT_CHARS on the backend so we fail fast without a round trip. */
        const val DEFAULT_MAX_CHARS = 4000
    }
}
