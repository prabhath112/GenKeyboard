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

package com.genkeyboard.diagnostics

import android.content.Context
import dev.patrickgold.florisboard.lib.devtools.flogDebug
import dev.patrickgold.florisboard.lib.devtools.flogError
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Small append-only, size-capped log for the suggestion pipeline (spelling, word completion,
 * autocorrect, next-word prediction). Survives process death and logcat buffer rollover, so an
 * intermittent "no suggestions" report can be diagnosed after the fact instead of only live.
 *
 * Records pipeline health only: provider ids, candidate counts, timings, exception types and
 * messages. Never the text being typed or the suggestion candidates themselves.
 *
 * Pull the file for inspection with:
 *   adb shell run-as <applicationId> cat files/genkeyboard/suggestion-diagnostics.log
 */
object SuggestionDiagnostics {
    private const val RELATIVE_PATH = "genkeyboard/suggestion-diagnostics.log"

    /** Oldest half of the file is dropped once it grows past this, so it never grows unbounded. */
    private const val MAX_BYTES = 200_000L

    private fun timestampFormat() = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)

    fun logOk(context: Context, event: String, details: String = "") {
        write(fileFor(context), "OK", event, details)
    }

    fun logError(context: Context, event: String, error: Throwable, details: String = "") {
        val message = formatError(details, error)
        write(fileFor(context), "ERROR", event, message)
        flogError { "$event failed: $message" }
    }

    /** Reads back the full log for export/inspection. Empty string if nothing was logged yet. */
    fun readAll(context: Context): String = readAll(fileFor(context))

    fun clear(context: Context) = clear(fileFor(context))

    internal fun formatError(details: String, error: Throwable): String {
        val summary = "${error::class.simpleName}: ${error.message}"
        return if (details.isEmpty()) summary else "$details | $summary"
    }

    internal fun write(file: File, level: String, event: String, details: String) {
        flogDebug { "$event $details" }
        val line = buildString {
            append(timestampFormat().format(Date()))
            append(' ').append(level)
            append(' ').append(event)
            if (details.isNotEmpty()) append(" - ").append(details)
            append('\n')
        }
        synchronized(this) {
            runCatching {
                file.parentFile?.mkdirs()
                if (file.exists() && file.length() > MAX_BYTES) {
                    // Drop the oldest half instead of growing forever.
                    val kept = file.readText().takeLast((MAX_BYTES / 2).toInt())
                    file.writeText(kept)
                }
                file.appendText(line)
            }
        }
    }

    internal fun readAll(file: File): String =
        runCatching { file.takeIf { it.exists() }?.readText() }.getOrNull() ?: ""

    internal fun clear(file: File) {
        runCatching { file.delete() }
    }

    private fun fileFor(context: Context): File = File(context.filesDir, RELATIVE_PATH)
}
