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

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.genkeyboard.ai.AiPanelState
import com.genkeyboard.ai.domain.AiAction
import com.genkeyboard.ai.domain.AiError
import dev.patrickgold.florisboard.R
import dev.patrickgold.florisboard.aiManager
import dev.patrickgold.florisboard.app.FlorisPreferenceStore
import dev.patrickgold.jetpref.datastore.model.collectAsState
import dev.patrickgold.florisboard.ime.ImeUiMode
import dev.patrickgold.florisboard.ime.keyboard.FlorisImeSizing
import dev.patrickgold.florisboard.ime.theme.FlorisImeUi
import dev.patrickgold.florisboard.keyboardManager
import org.florisboard.lib.compose.florisHorizontalScroll
import org.florisboard.lib.compose.stringRes
import org.florisboard.lib.snygg.ui.SnyggBox
import org.florisboard.lib.snygg.ui.SnyggButton
import org.florisboard.lib.snygg.ui.SnyggChip
import org.florisboard.lib.snygg.ui.SnyggColumn
import org.florisboard.lib.snygg.ui.SnyggIcon
import org.florisboard.lib.snygg.ui.SnyggIconButton
import org.florisboard.lib.snygg.ui.SnyggRow
import org.florisboard.lib.snygg.ui.SnyggText

/*
 * ponytail: reuses the clipboard panel's Snygg element names so every existing theme styles this panel
 * with zero stylesheet changes. Upgrade path: dedicated "ai-*" elements in FlorisImeUi + all bundled
 * stylesheets + the stylesheet JSON schema, once we want AI-specific looks.
 */
private object Ui {
    const val Header = "clipboard-header"
    const val HeaderButton = "clipboard-header-button"
    const val HeaderText = "clipboard-header-text"
    const val Subheader = "clipboard-subheader"
    const val Content = "clipboard-content"
    const val Chip = "clipboard-filter-chip"
    const val Card = "clipboard-item"
    const val CardText = "clipboard-item-description"
    const val Action = "clipboard-item-action"
    const val ActionText = "clipboard-item-action-text"
}

@Composable
fun AiInputLayout(
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val keyboardManager by context.keyboardManager()
    val aiManager by context.aiManager()
    val state by aiManager.state.collectAsState()

    SnyggColumn(
        modifier = modifier
            .fillMaxWidth()
            .height(FlorisImeSizing.imeUiHeight()),
    ) {
        HeaderRow(
            state = state,
            onBack = {
                aiManager.cancel()
                keyboardManager.activeState.imeUiMode = ImeUiMode.TEXT
            },
        )
        SnyggBox(Ui.Content, modifier = Modifier.fillMaxSize()) {
            when (val s = state) {
                is AiPanelState.Idle -> ActionCatalog(onRun = aiManager::run)
                is AiPanelState.Error -> Column {
                    ErrorBanner(s.error)
                    ActionCatalog(onRun = aiManager::run)
                }
                is AiPanelState.Loading -> LoadingView(onCancel = aiManager::cancel)
                is AiPanelState.Preview -> PreviewView(
                    text = s.result.text,
                    remaining = s.result.remaining,
                    onApply = aiManager::apply,
                    onDiscard = aiManager::reset,
                )
                is AiPanelState.Applied -> AppliedView(
                    onUndo = aiManager::undo,
                    onDone = {
                        aiManager.reset()
                        keyboardManager.activeState.imeUiMode = ImeUiMode.TEXT
                    },
                )
            }
        }
    }
}

@Composable
private fun HeaderRow(state: AiPanelState, onBack: () -> Unit) {
    SnyggRow(
        Ui.Header,
        modifier = Modifier
            .fillMaxWidth()
            .height(FlorisImeSizing.smartbarHeight),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SnyggIconButton(
            elementName = Ui.HeaderButton,
            onClick = onBack,
            modifier = Modifier
                .sizeIn(maxHeight = FlorisImeSizing.smartbarHeight)
                .aspectRatio(1f),
        ) {
            SnyggIcon(imageVector = Icons.AutoMirrored.Filled.ArrowBack)
        }
        SnyggText(
            elementName = Ui.HeaderText,
            modifier = Modifier.weight(1f),
            text = stringRes(R.string.ai__header_title),
        )
        if (state is AiPanelState.Loading) {
            CircularProgressIndicator(
                modifier = Modifier.padding(end = 12.dp).sizeIn(maxHeight = 20.dp, maxWidth = 20.dp),
                strokeWidth = 2.dp,
            )
        }
    }
}

@Composable
private fun ActionCatalog(onRun: (AiAction) -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        SectionTitle(R.string.ai__section_quick_fixes)
        ChipRow(AiActionCatalog.quickFixes, onRun)

        SectionTitle(R.string.ai__section_tone)
        ChipRow(AiActionCatalog.tones, onRun)

        SectionTitle(R.string.ai__section_reply)
        ChipRow(AiActionCatalog.replies, onRun)

        SectionTitle(R.string.ai__section_translate)
        Row(modifier = Modifier.florisHorizontalScroll(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            AiActionCatalog.translateLanguages.forEach { language ->
                SnyggChip(
                    elementName = Ui.Chip,
                    onClick = { onRun(AiAction.Translate(language)) },
                    imageVector = Icons.Default.Translate,
                    text = language,
                )
            }
        }

        // User-defined instructions, edited under Settings > AI writing tools.
        val prefs by FlorisPreferenceStore
        val customPrompts by prefs.ai.customPrompts.collectAsState()
        val prompts = customPrompts.lines().map { it.trim() }.filter { it.isNotEmpty() }
        SectionTitle(R.string.ai__section_my_prompts)
        if (prompts.isEmpty()) {
            SnyggText(elementName = Ui.Subheader, text = stringRes(R.string.ai__my_prompts_empty))
        } else {
            Row(modifier = Modifier.florisHorizontalScroll(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                prompts.forEach { prompt ->
                    SnyggChip(
                        elementName = Ui.Chip,
                        onClick = { onRun(AiAction.Custom(prompt)) },
                        imageVector = Icons.Default.AutoAwesome,
                        text = prompt.take(28) + if (prompt.length > 28) "…" else "",
                    )
                }
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun SectionTitle(res: Int) {
    SnyggText(
        elementName = Ui.Subheader,
        modifier = Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 2.dp),
        text = stringRes(res).uppercase(),
    )
}

@Composable
private fun ChipRow(chips: List<AiChip>, onRun: (AiAction) -> Unit) {
    Row(modifier = Modifier.florisHorizontalScroll(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        chips.forEach { chip ->
            SnyggChip(
                elementName = Ui.Chip,
                onClick = { onRun(chip.action) },
                imageVector = chip.icon,
                text = stringRes(chip.label),
            )
        }
    }
}

@Composable
private fun ErrorBanner(error: AiError) {
    val res = when (error) {
        AiError.NO_TEXT -> R.string.ai__error_no_text
        AiError.SENSITIVE_FIELD -> R.string.ai__error_sensitive_field
        AiError.TEXT_TOO_LONG -> R.string.ai__error_text_too_long
        AiError.NETWORK -> R.string.ai__error_network
        AiError.TIMEOUT -> R.string.ai__error_timeout
        AiError.UNAUTHORIZED -> R.string.ai__error_unauthorized
        AiError.QUOTA_EXCEEDED -> R.string.ai__error_quota
        AiError.INVALID_REQUEST -> R.string.ai__error_invalid
        AiError.UPSTREAM, AiError.UNKNOWN -> R.string.ai__error_upstream
    }
    SnyggBox(Ui.Card, modifier = Modifier.fillMaxWidth().padding(8.dp)) {
        SnyggText(elementName = Ui.CardText, modifier = Modifier.padding(8.dp), text = stringRes(res))
    }
}

@Composable
private fun LoadingView(onCancel: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        SnyggText(elementName = Ui.CardText, text = stringRes(R.string.ai__status_working))
        Spacer(Modifier.height(12.dp))
        ActionButton(R.string.ai__button_cancel, Icons.Default.Close, onCancel)
    }
}

@Composable
private fun PreviewView(text: String, remaining: Int?, onApply: () -> Unit, onDiscard: () -> Unit) {
    Column(modifier = Modifier.fillMaxSize().padding(8.dp)) {
        SnyggBox(
            Ui.Card,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
        ) {
            SnyggText(
                elementName = Ui.CardText,
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(10.dp),
                text = text,
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ActionButton(R.string.ai__button_replace, null, onApply)
            ActionButton(R.string.ai__button_discard, null, onDiscard)
            if (remaining != null) {
                Spacer(Modifier.weight(1f))
                SnyggText(elementName = Ui.Subheader, text = stringRes(R.string.ai__status_remaining, "count" to remaining))
            }
        }
    }
}

@Composable
private fun AppliedView(onUndo: () -> Unit, onDone: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        SnyggText(elementName = Ui.CardText, text = stringRes(R.string.ai__status_applied))
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ActionButton(R.string.ai__button_undo, null, onUndo)
            ActionButton(R.string.ai__button_done, null, onDone)
        }
    }
}

@Composable
private fun ActionButton(label: Int, icon: androidx.compose.ui.graphics.vector.ImageVector?, onClick: () -> Unit) {
    SnyggButton(elementName = Ui.Action, onClick = onClick) {
        if (icon != null) {
            SnyggIcon(imageVector = icon)
            Spacer(Modifier.padding(horizontal = 2.dp))
        }
        SnyggText(elementName = Ui.ActionText, text = stringRes(label))
    }
}
