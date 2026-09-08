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
 * A transformation the user can request on the text in the current editor.
 * Wire names must match the backend action registry (backend/src/actions.ts).
 */
sealed interface AiAction {
    val wireName: String

    data object Grammar : AiAction { override val wireName = "grammar" }
    data object Paraphrase : AiAction { override val wireName = "paraphrase" }
    data object Shorten : AiAction { override val wireName = "shorten" }
    data object Expand : AiAction { override val wireName = "expand" }
    data object Summarize : AiAction { override val wireName = "summarize" }
    data object Emojify : AiAction { override val wireName = "emojify" }
    data object Humanize : AiAction { override val wireName = "humanize" }

    data class Tone(val tone: AiTone) : AiAction { override val wireName = "tone" }
    data class Translate(val language: String) : AiAction { override val wireName = "translate" }
    data class Reply(val sentiment: ReplySentiment) : AiAction { override val wireName = "reply" }
    data class Custom(val prompt: String) : AiAction { override val wireName = "custom" }
}

enum class AiTone(val wireName: String) {
    PROFESSIONAL("professional"),
    CASUAL("casual"),
    POLITE("polite"),
    ROMANTIC("romantic"),
    EMPATHETIC("empathetic"),
    FUNNY("funny"),
    POETIC("poetic"),
    SARCASTIC("sarcastic"),
    ANGRY("angry"),
    FLIRTY("flirty"),
    GEN_Z("genz"),
    WITTY("witty"),
}

enum class ReplySentiment(val wireName: String) {
    POSITIVE("positive"),
    NEUTRAL("neutral"),
    NEGATIVE("negative"),
}
