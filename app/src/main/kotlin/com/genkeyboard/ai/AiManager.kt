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

package com.genkeyboard.ai

import android.content.Context
import com.genkeyboard.ai.data.AiBackendConfig
import com.genkeyboard.ai.data.RemoteAiRepository
import com.genkeyboard.ai.data.UrlConnectionTransport
import com.genkeyboard.ai.domain.AiAction
import com.genkeyboard.ai.domain.AiError
import com.genkeyboard.ai.domain.AiResult
import com.genkeyboard.ai.domain.TextEditorGateway
import com.genkeyboard.ai.domain.TextTarget
import com.genkeyboard.ai.domain.TransformTextUseCase
import com.genkeyboard.ai.editor.InputConnectionTextGateway
import dev.patrickgold.florisboard.BuildConfig
import dev.patrickgold.florisboard.app.FlorisPreferenceStore
import dev.patrickgold.florisboard.editorInstance
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

/** What the AI panel shows. One state at a time; transitions happen only inside [AiManager]. */
sealed interface AiPanelState {
    data object Idle : AiPanelState
    data class Loading(val action: AiAction) : AiPanelState
    data class Preview(val action: AiAction, val original: TextTarget, val result: AiResult.Success) : AiPanelState
    data class Applied(val action: AiAction, val original: TextTarget, val applied: TextTarget) : AiPanelState
    data class Error(val error: AiError) : AiPanelState
}

/**
 * Single owner of AI panel state. UI observes [state] and calls the intent methods below.
 * Wired like every other FlorisBoard manager: lazy singleton in FlorisApplication, reached via Context.aiManager().
 */
class AiManager(
    context: Context,
    private val gateway: TextEditorGateway = InputConnectionTextGateway(context),
    private val useCase: TransformTextUseCase = TransformTextUseCase(
        RemoteAiRepository(
            transport = UrlConnectionTransport(allowCleartext = BuildConfig.DEBUG),
            config = { backendConfig() },
        ),
    ),
) {
    private val prefs by FlorisPreferenceStore
    private val editorInstance by context.editorInstance()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var inFlight: Job? = null

    private val _state = MutableStateFlow<AiPanelState>(AiPanelState.Idle)
    val state: StateFlow<AiPanelState> = _state.asStateFlow()

    init {
        // A new text field means whatever we previewed no longer applies.
        editorInstance.activeInfoFlow
            .distinctUntilChangedBy { it.base.fieldId to it.base.packageName }
            .onEach { reset() }
            .launchIn(scope)
    }

    fun run(action: AiAction) {
        inFlight?.cancel()
        val target = gateway.readTarget()
        val sensitive = gateway.isSensitiveField
        _state.value = AiPanelState.Loading(action)
        inFlight = scope.launch {
            _state.value = when (val result = useCase.execute(target, sensitive, action)) {
                is AiResult.Success -> AiPanelState.Preview(action, target!!, result)
                is AiResult.Failure -> AiPanelState.Error(result.error)
            }
        }
    }

    fun apply() {
        val preview = _state.value as? AiPanelState.Preview ?: return
        val applied = gateway.replace(preview.original, preview.result.text)
        _state.value = if (applied != null) {
            AiPanelState.Applied(preview.action, preview.original, applied)
        } else {
            AiPanelState.Error(AiError.UNKNOWN)
        }
    }

    fun undo() {
        val applied = _state.value as? AiPanelState.Applied ?: return
        gateway.replace(applied.applied, applied.original.text)
        _state.value = AiPanelState.Idle
    }

    fun cancel() {
        inFlight?.cancel()
        reset()
    }

    fun reset() {
        _state.value = AiPanelState.Idle
    }

    companion object {
        /** Device id doubles as the quota key. Generated once, never leaves the device except in our own requests. */
        suspend fun backendConfig(): AiBackendConfig {
            val prefs by FlorisPreferenceStore
            var id = prefs.ai.deviceId.get()
            if (id.isBlank()) {
                id = UUID.randomUUID().toString()
                prefs.ai.deviceId.set(id)
            }
            return AiBackendConfig(baseUrl = prefs.ai.backendUrl.get(), deviceId = id)
        }
    }
}
