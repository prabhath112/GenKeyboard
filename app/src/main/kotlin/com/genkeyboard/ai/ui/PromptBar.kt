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

import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.genkeyboard.ai.PromptCapture
import dev.patrickgold.florisboard.R
import dev.patrickgold.florisboard.aiManager
import dev.patrickgold.florisboard.ime.ImeUiMode
import dev.patrickgold.florisboard.ime.keyboard.FlorisImeSizing
import dev.patrickgold.florisboard.keyboardManager
import org.florisboard.lib.compose.stringRes
import org.florisboard.lib.snygg.ui.SnyggIcon
import org.florisboard.lib.snygg.ui.SnyggIconButton
import org.florisboard.lib.snygg.ui.SnyggRow
import org.florisboard.lib.snygg.ui.SnyggText

/**
 * Replaces the smartbar while a new AI prompt is being written. The real keys stay below; what the user
 * types lands in [PromptCapture], not in the app. Save or cancel returns to the AI panel.
 * ponytail: reuses the clipboard header's Snygg elements, same reasoning as AiInputLayout.
 */
@Composable
fun PromptBar(draft: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val keyboardManager by context.keyboardManager()
    val aiManager by context.aiManager()
    val buttonModifier = Modifier.sizeIn(maxHeight = FlorisImeSizing.smartbarHeight).aspectRatio(1f)

    SnyggRow(
        "clipboard-header",
        modifier = modifier.fillMaxWidth().height(FlorisImeSizing.smartbarHeight),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SnyggIconButton(
            elementName = "clipboard-header-button",
            onClick = {
                PromptCapture.stop()
                keyboardManager.activeState.imeUiMode = ImeUiMode.AI
            },
            modifier = buttonModifier,
        ) { SnyggIcon(imageVector = Icons.Default.Close) }
        SnyggText(
            elementName = "clipboard-header-text",
            modifier = Modifier.weight(1f).padding(horizontal = 4.dp),
            // Show the tail so the caret end is always visible on long prompts.
            text = if (draft.isBlank()) stringRes(R.string.ai__prompt_bar_hint) else draft.takeLast(70),
        )
        SnyggIconButton(
            elementName = "clipboard-header-button",
            onClick = {
                aiManager.savePrompt()
                keyboardManager.activeState.imeUiMode = ImeUiMode.AI
            },
            modifier = buttonModifier,
        ) { SnyggIcon(imageVector = Icons.Default.Check) }
    }
}
