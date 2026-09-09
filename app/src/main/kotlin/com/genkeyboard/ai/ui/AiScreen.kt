/*
 * Copyright (C) 2026 GenKeyboard
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.genkeyboard.ai.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.patrickgold.florisboard.R
import dev.patrickgold.florisboard.app.DEFAULT_CUSTOM_PROMPTS
import dev.patrickgold.florisboard.app.FlorisPreferenceStore
import dev.patrickgold.florisboard.lib.compose.FlorisScreen
import dev.patrickgold.jetpref.datastore.model.collectAsState
import dev.patrickgold.jetpref.datastore.ui.Preference
import dev.patrickgold.jetpref.datastore.ui.PreferenceGroup
import dev.patrickgold.jetpref.datastore.ui.SwitchPreference
import dev.patrickgold.jetpref.material.ui.JetPrefAlertDialog
import org.florisboard.lib.compose.stringRes

/**
 * Settings > AI writing tools. Toggle, and the editable "My prompts" list that shows up as chips in the panel.
 * Prompts live in one string preference, one per line. ponytail: no separate table; a few lines of text is all.
 */
@Composable
fun AiScreen() = FlorisScreen {
    title = stringRes(R.string.pref__ai__title)
    previewFieldVisible = false

    val prefs by FlorisPreferenceStore
    val scope = rememberCoroutineScope()
    val raw by prefs.ai.customPrompts.collectAsState()
    val prompts = raw.lines().map { it.trim() }.filter { it.isNotEmpty() }
    fun save(list: List<String>) {
        scope.launch { prefs.ai.customPrompts.set(list.joinToString("\n")) }
    }

    // -1 = closed, prompts.size = add new, else edit that index
    var editing by remember { mutableStateOf(-1) }
    var draft by remember { mutableStateOf("") }

    floatingActionButton {
        androidx.compose.material3.ExtendedFloatingActionButton(
            text = { Text(stringRes(R.string.pref__ai__custom_prompts__add)) },
            icon = { Icon(Icons.Default.Add, contentDescription = null) },
            onClick = { draft = ""; editing = prompts.size },
        )
    }

    content {
        SwitchPreference(
            prefs.ai.enabled,
            title = stringRes(R.string.pref__ai__enabled__label),
        )
        PreferenceGroup(title = stringRes(R.string.pref__ai__custom_prompts__title)) {
            Text(
                text = stringRes(R.string.pref__ai__custom_prompts__summary),
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
            if (prompts.isEmpty()) {
                Text(
                    text = stringRes(R.string.pref__ai__custom_prompts__none),
                    modifier = Modifier.padding(16.dp),
                )
            }
            prompts.forEachIndexed { index, prompt ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { draft = prompt; editing = index }
                        .padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(text = prompt, modifier = Modifier.weight(1f))
                    IconButton(onClick = { save(prompts.filterIndexed { i, _ -> i != index }) }) {
                        Icon(Icons.Default.Delete, contentDescription = stringRes(R.string.pref__ai__custom_prompts__delete))
                    }
                }
            }
            Preference(
                title = stringRes(R.string.pref__ai__custom_prompts__reset),
                onClick = { scope.launch { prefs.ai.customPrompts.set(DEFAULT_CUSTOM_PROMPTS) } },
            )
        }
    }

    if (editing >= 0) {
        val isNew = editing >= prompts.size
        JetPrefAlertDialog(
            title = stringRes(if (isNew) R.string.pref__ai__custom_prompts__add else R.string.pref__ai__custom_prompts__edit),
            confirmLabel = stringRes(R.string.action__apply),
            onConfirm = {
                val text = draft.trim().replace('\n', ' ')
                if (text.isNotEmpty()) {
                    save(if (isNew) prompts + text else prompts.mapIndexed { i, p -> if (i == editing) text else p })
                }
                editing = -1
            },
            dismissLabel = stringRes(R.string.action__cancel),
            onDismiss = { editing = -1 },
        ) {
            Column {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it.take(500) },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text(stringRes(R.string.pref__ai__custom_prompts__hint)) },
                    minLines = 2,
                )
                if (!isNew) {
                    TextButton(onClick = { save(prompts.filterIndexed { i, _ -> i != editing }); editing = -1 }) {
                        Text(stringRes(R.string.pref__ai__custom_prompts__delete))
                    }
                }
            }
        }
    }
}
