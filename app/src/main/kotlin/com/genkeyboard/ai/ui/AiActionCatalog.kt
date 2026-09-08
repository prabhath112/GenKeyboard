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

package com.genkeyboard.ai.ui

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.Compress
import androidx.compose.material.icons.filled.EmojiEmotions
import androidx.compose.material.icons.filled.Expand
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Spellcheck
import androidx.compose.material.icons.filled.Summarize
import androidx.compose.material.icons.filled.Loop
import androidx.compose.material.icons.filled.SentimentDissatisfied
import androidx.compose.material.icons.filled.SentimentNeutral
import androidx.compose.material.icons.filled.SentimentSatisfied
import androidx.compose.ui.graphics.vector.ImageVector
import com.genkeyboard.ai.domain.AiAction
import com.genkeyboard.ai.domain.AiTone
import com.genkeyboard.ai.domain.ReplySentiment
import dev.patrickgold.florisboard.R

/** One chip in the panel. Pure data so the catalog is trivially extendable and testable. */
data class AiChip(
    @StringRes val label: Int,
    val action: AiAction,
    val icon: ImageVector? = null,
)

/** Static catalog of what the panel offers. Add a chip here, nothing else changes. */
object AiActionCatalog {
    val quickFixes: List<AiChip> = listOf(
        AiChip(R.string.ai__action_grammar, AiAction.Grammar, Icons.Default.Spellcheck),
        AiChip(R.string.ai__action_paraphrase, AiAction.Paraphrase, Icons.Default.Loop),
        AiChip(R.string.ai__action_shorten, AiAction.Shorten, Icons.Default.Compress),
        AiChip(R.string.ai__action_expand, AiAction.Expand, Icons.Default.Expand),
        AiChip(R.string.ai__action_summarize, AiAction.Summarize, Icons.Default.Summarize),
        AiChip(R.string.ai__action_emojify, AiAction.Emojify, Icons.Default.EmojiEmotions),
        AiChip(R.string.ai__action_humanize, AiAction.Humanize, Icons.Default.Person),
    )

    val tones: List<AiChip> = AiTone.entries.map { tone ->
        AiChip(tone.labelRes(), AiAction.Tone(tone), Icons.Default.AutoFixHigh)
    }

    val replies: List<AiChip> = listOf(
        AiChip(R.string.ai__reply_positive, AiAction.Reply(ReplySentiment.POSITIVE), Icons.Default.SentimentSatisfied),
        AiChip(R.string.ai__reply_neutral, AiAction.Reply(ReplySentiment.NEUTRAL), Icons.Default.SentimentNeutral),
        AiChip(R.string.ai__reply_negative, AiAction.Reply(ReplySentiment.NEGATIVE), Icons.Default.SentimentDissatisfied),
    )

    /**
     * ponytail: fixed language list. Upgrade path: user-editable list in settings + recent languages first.
     */
    val translateLanguages: List<String> = listOf(
        "English", "Spanish", "French", "German", "Danish", "Norwegian", "Swedish",
        "Portuguese", "Italian", "Dutch", "Hindi", "Arabic", "Japanese", "Korean",
    )
}

@StringRes
fun AiTone.labelRes(): Int = when (this) {
    AiTone.PROFESSIONAL -> R.string.ai__tone_professional
    AiTone.CASUAL -> R.string.ai__tone_casual
    AiTone.POLITE -> R.string.ai__tone_polite
    AiTone.ROMANTIC -> R.string.ai__tone_romantic
    AiTone.EMPATHETIC -> R.string.ai__tone_empathetic
    AiTone.FUNNY -> R.string.ai__tone_funny
    AiTone.POETIC -> R.string.ai__tone_poetic
    AiTone.SARCASTIC -> R.string.ai__tone_sarcastic
    AiTone.ANGRY -> R.string.ai__tone_angry
    AiTone.FLIRTY -> R.string.ai__tone_flirty
    AiTone.GEN_Z -> R.string.ai__tone_genz
    AiTone.WITTY -> R.string.ai__tone_witty
}
