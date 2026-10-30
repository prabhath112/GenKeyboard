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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.genkeyboard.ai.AiPanelState
import com.genkeyboard.ai.PromptCapture
import com.genkeyboard.ai.domain.AiAction
import com.genkeyboard.ai.domain.AiError
import dev.patrickgold.florisboard.BuildConfig
import dev.patrickgold.florisboard.R
import dev.patrickgold.florisboard.aiManager
import dev.patrickgold.florisboard.app.FlorisPreferenceStore
import dev.patrickgold.jetpref.datastore.model.collectAsState
import dev.patrickgold.florisboard.ime.ImeUiMode
import dev.patrickgold.florisboard.ime.keyboard.FlorisImeSizing
import dev.patrickgold.florisboard.ime.theme.FlorisImeUi
import dev.patrickgold.florisboard.keyboardManager
import dev.patrickgold.florisboard.lib.util.launchUrl
import org.florisboard.lib.compose.florisHorizontalScroll
import kotlinx.coroutines.launch
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
    val prefs by FlorisPreferenceStore
    val consented by prefs.ai.consent.collectAsState()
    val scope = rememberCoroutineScope()
    // Back to the real keys with the prompt bar on top; PromptBar returns here on save or cancel.
    val onNewPrompt = {
        PromptCapture.start()
        keyboardManager.activeState.imeUiMode = ImeUiMode.TEXT
    }

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
            if (!consented) {
                ConsentView(
                    onAllow = { scope.launch { prefs.ai.consent.set(true) } },
                    onDecline = {
                        aiManager.cancel()
                        keyboardManager.activeState.imeUiMode = ImeUiMode.TEXT
                    },
                )
                return@SnyggBox
            }
            when (val s = state) {
                is AiPanelState.Idle -> ActionCatalog(onRun = aiManager::run, onNewPrompt = onNewPrompt)
                is AiPanelState.Error -> Column {
                    ErrorBanner(s.error)
                    ActionCatalog(onRun = aiManager::run, onNewPrompt = onNewPrompt)
                }
                is AiPanelState.Loading -> LoadingView(onCancel = aiManager::cancel)
                is AiPanelState.Preview -> PreviewView(
                    text = s.result.text,
                    remaining = s.result.remaining,
                    onApply = {
                        // Straight back to typing once the text is in the field.
                        if (aiManager.apply()) keyboardManager.activeState.imeUiMode = ImeUiMode.TEXT
                    },
                    onRefine = aiManager::refine,
                    onReport = { reportResult(context, s.action) },
                    onDiscard = aiManager::reset,
                )
                is AiPanelState.Refine -> Column {
                    RefineBanner(s.text)
                    ActionCatalog(onRun = aiManager::run, onNewPrompt = onNewPrompt)
                }
            }
        }
    }
}

/** Opens the user's mail app with a prefilled report. No user text or AI result is attached. */
private fun reportResult(context: android.content.Context, action: AiAction) {
    val body = context.getString(R.string.ai__report_body) + "\n\nAction: ${action::class.simpleName}\nApp: ${BuildConfig.VERSION_NAME}"
    val intent = android.content.Intent(android.content.Intent.ACTION_SENDTO).apply {
        data = android.net.Uri.parse("mailto:prabhath.kumar2000@gmail.com")
        putExtra(android.content.Intent.EXTRA_SUBJECT, context.getString(R.string.ai__report_subject))
        putExtra(android.content.Intent.EXTRA_TEXT, body)
        addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    runCatching { context.startActivity(intent) }
}

@Composable
private fun ConsentView(onAllow: () -> Unit, onDecline: () -> Unit) {
    val context = LocalContext.current
    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 8.dp)) {
        // Only the explanation scrolls; the decision buttons stay on screen whatever the panel height.
        Column(
            modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            SnyggText(elementName = Ui.HeaderText, text = stringRes(R.string.ai__consent_title))
            SnyggText(elementName = Ui.CardText, text = stringRes(R.string.ai__consent_body))
            TextButton(
                onClick = { context.launchUrl(R.string.florisboard__privacy_policy_url) },
                contentPadding = PreviewButtonPadding,
            ) {
                Text(text = stringRes(R.string.ai__consent_privacy), color = Color(0xFFD0BCFF))
            }
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
        ) {
            Button(onClick = onAllow, modifier = Modifier.weight(1f), contentPadding = PreviewButtonPadding) {
                PreviewButtonLabel(R.string.ai__consent_allow)
            }
            TextButton(
                onClick = onDecline,
                modifier = Modifier.weight(1f),
                contentPadding = PreviewButtonPadding,
                colors = ButtonDefaults.textButtonColors(contentColor = Color(0xFFD0BCFF)),
            ) { PreviewButtonLabel(R.string.ai__consent_decline) }
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
private fun ActionCatalog(onRun: (AiAction) -> Unit, onNewPrompt: () -> Unit) {
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
        }
        Row(modifier = Modifier.florisHorizontalScroll(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            SnyggChip(
                elementName = Ui.Chip,
                onClick = onNewPrompt,
                imageVector = Icons.Default.Add,
                text = stringRes(R.string.ai__button_new_prompt),
            )
            prompts.forEach { prompt ->
                SnyggChip(
                    elementName = Ui.Chip,
                    onClick = { onRun(AiAction.Custom(prompt)) },
                    imageVector = Icons.Default.AutoAwesome,
                    text = prompt.take(28) + if (prompt.length > 28) "…" else "",
                )
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
        AiError.CONSENT_REQUIRED -> R.string.ai__error_consent
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
private fun PreviewView(
    text: String,
    remaining: Int?,
    onApply: () -> Unit,
    onRefine: () -> Unit,
    onReport: () -> Unit,
    onDiscard: () -> Unit,
) {
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
        TextButton(onClick = onReport, contentPadding = PreviewButtonPadding) {
            Text(
                text = stringRes(R.string.ai__button_report),
                color = Color(0xFFD0BCFF),
                style = MaterialTheme.typography.labelMedium,
            )
        }
        if (remaining != null) {
            SnyggText(
                elementName = Ui.Subheader,
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                text = stringRes(R.string.ai__status_remaining, "count" to remaining),
            )
        }
        // One row, equal widths, single-line labels: primary / secondary / quiet, so the hierarchy is obvious.
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Button(onClick = onApply, modifier = Modifier.weight(1f), contentPadding = PreviewButtonPadding) {
                PreviewButtonLabel(R.string.ai__button_replace)
            }
            FilledTonalButton(onClick = onRefine, modifier = Modifier.weight(1f), contentPadding = PreviewButtonPadding) {
                PreviewButtonLabel(R.string.ai__button_add_filter)
            }
            TextButton(
                onClick = onDiscard,
                modifier = Modifier.weight(1f),
                contentPadding = PreviewButtonPadding,
                // Light enough to meet contrast on the dark panel; the default primary is too dim there.
                colors = ButtonDefaults.textButtonColors(contentColor = Color(0xFFD0BCFF)),
            ) {
                PreviewButtonLabel(R.string.ai__button_discard)
            }
        }
    }
}

@Composable
private fun RefineBanner(text: String) {
    SnyggBox(Ui.Card, modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)) {
        Column(modifier = Modifier.padding(8.dp)) {
            SnyggText(elementName = Ui.Subheader, text = stringRes(R.string.ai__status_refining))
            SnyggText(elementName = Ui.CardText, text = text.take(100) + if (text.length > 100) "…" else "")
        }
    }
}

private val PreviewButtonPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp)

@Composable
private fun PreviewButtonLabel(label: Int) {
    Text(text = stringRes(label), maxLines = 1, overflow = TextOverflow.Ellipsis)
}

@Composable
private fun ActionButton(
    label: Int,
    icon: androidx.compose.ui.graphics.vector.ImageVector?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    SnyggButton(elementName = Ui.Action, onClick = onClick, modifier = modifier) {
        if (icon != null) {
            SnyggIcon(imageVector = icon)
            Spacer(Modifier.padding(horizontal = 2.dp))
        }
        SnyggText(elementName = Ui.ActionText, text = stringRes(label))
    }
}
