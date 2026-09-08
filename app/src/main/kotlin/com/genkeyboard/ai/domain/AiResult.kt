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

sealed interface AiResult {
    data class Success(
        val text: String,
        val provider: String,
        val remaining: Int?,
    ) : AiResult

    data class Failure(val error: AiError) : AiResult
}

enum class AiError {
    /** Nothing to transform: field empty or selection blank. */
    NO_TEXT,
    /** Password or otherwise sensitive field. Text never leaves the device. */
    SENSITIVE_FIELD,
    TEXT_TOO_LONG,
    NETWORK,
    TIMEOUT,
    UNAUTHORIZED,
    QUOTA_EXCEEDED,
    INVALID_REQUEST,
    UPSTREAM,
    UNKNOWN,
}
