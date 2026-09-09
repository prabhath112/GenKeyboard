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

package com.genkeyboard.suggest

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Next-word prediction learned from the user's own typing: a bigram table "previous word -> next word -> count".
 * Pure Kotlin, persisted as one small JSON file, all on device.
 *
 * ponytail: bigrams only, capped at [maxPrev] previous words x [maxNext] followers. Good enough to finish
 * "see you" -> "tomorrow" after a few uses. Upgrade path: trigrams or a tiny n-gram LM if users want more.
 */
class NextWordModel(
    private val maxPrev: Int = 3000,
    private val maxNext: Int = 12,
) {
    @Serializable
    private data class Snapshot(val table: Map<String, Map<String, Int>>)

    private val table = HashMap<String, HashMap<String, Int>>()

    /** Record that [next] followed [prev]. Both are normalised to lowercase. */
    fun learn(prev: String, next: String) {
        val p = norm(prev) ?: return
        val n = norm(next) ?: return
        if (p == n) return
        val followers = table.getOrPut(p) { HashMap() }
        followers[n] = (followers[n] ?: 0) + 1
        if (followers.size > maxNext) {
            followers.entries.sortedBy { it.value }.take(followers.size - maxNext).forEach { followers.remove(it.key) }
        }
        if (table.size > maxPrev) {
            // Drop the previous-words with the least total evidence.
            table.entries.sortedBy { e -> e.value.values.sum() }.take(table.size - maxPrev).forEach { table.remove(it.key) }
        }
    }

    /** Most likely words after [prev], best first. Empty when nothing was learned yet. */
    fun predict(prev: String, max: Int = 3): List<String> {
        val p = norm(prev) ?: return emptyList()
        val followers = table[p] ?: return emptyList()
        return followers.entries.sortedByDescending { it.value }.take(max).map { it.key }
    }

    /** Forget every prediction of [word] (user blocked it). */
    fun forget(word: String) {
        val w = norm(word) ?: return
        table.remove(w)
        table.values.forEach { it.remove(w) }
    }

    val size: Int get() = table.size

    fun save(file: File) {
        file.parentFile?.mkdirs()
        file.writeText(Json.encodeToString(Snapshot(table)))
    }

    fun load(file: File): Boolean {
        if (!file.exists()) return false
        return runCatching {
            val snap = Json.decodeFromString<Snapshot>(file.readText())
            table.clear()
            snap.table.forEach { (k, v) -> table[k] = HashMap(v) }
        }.isSuccess
    }

    private fun norm(word: String): String? {
        val w = word.trim().lowercase()
        if (w.length < 2 || w.length > 32 || !w.any { it.isLetter() }) return null
        if (w.any { !(it.isLetterOrDigit() || it == '\'' || it == '-') }) return null
        return w
    }
}
