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

package com.genkeyboard.ai.editor

import android.content.Context
import android.view.inputmethod.ExtractedTextRequest
import android.view.inputmethod.InputConnection
import com.genkeyboard.ai.domain.TextEditorGateway
import com.genkeyboard.ai.domain.TextTarget
import dev.patrickgold.florisboard.FlorisImeService
import dev.patrickgold.florisboard.editorInstance
import dev.patrickgold.florisboard.ime.editor.InputAttributes

/**
 * Bridges the domain gateway to FlorisBoard's editor state and the live InputConnection.
 * Must be called on the main thread, like every other InputConnection user in the IME.
 */
class InputConnectionTextGateway(context: Context) : TextEditorGateway {
    private val editorInstance by context.editorInstance()

    override val isSensitiveField: Boolean
        get() {
            val info = editorInstance.activeInfo
            return when (info.inputAttributes.variation) {
                InputAttributes.Variation.PASSWORD,
                InputAttributes.Variation.VISIBLE_PASSWORD,
                InputAttributes.Variation.WEB_PASSWORD -> true
                else -> false
            }
        }

    override fun readTarget(): TextTarget? {
        val ic = FlorisImeService.currentInputConnection() ?: return null
        if (editorInstance.activeInfo.isRawInputEditor) return null

        val selected = ic.getSelectedText(0)?.toString()
        if (!selected.isNullOrBlank()) {
            val start = editorInstance.activeContent.selection.start.coerceAtLeast(0)
            return TextTarget(selected, start, isSelection = true)
        }

        val extracted = ic.getExtractedText(ExtractedTextRequest(), 0)?.text?.toString()
        if (extracted.isNullOrBlank()) return null
        return TextTarget(extracted, start = 0, isSelection = false)
    }

    override fun replace(target: TextTarget, newText: String): TextTarget? {
        val ic = FlorisImeService.currentInputConnection() ?: return null
        val ok = ic.batch {
            finishComposingText()
            setSelection(target.start, target.end) && commitText(newText, 1)
        }
        return if (ok) target.copy(text = newText) else null
    }

    private inline fun InputConnection.batch(block: InputConnection.() -> Boolean): Boolean {
        beginBatchEdit()
        return try {
            block()
        } finally {
            endBatchEdit()
        }
    }
}
