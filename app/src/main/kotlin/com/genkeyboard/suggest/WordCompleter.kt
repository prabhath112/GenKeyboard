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

/**
 * Pure word-completion logic, no Android types, so it is unit-testable.
 *
 * ponytail: linear prefix scan over the bundled word map on every keystroke. ~50k words, sub-millisecond
 * on a phone. Upgrade path: sorted array + binary search if a much larger dictionary is bundled.
 */
object WordCompleter {

    /** How much a word from the user's own dictionary outranks a bundled word of equal frequency. */
    private const val LEARNED_BOOST = 1.0

    /** Personal-dictionary frequency that marks a word as blocked from suggestions. */
    const val BLOCKED = 0

    /**
     * @param prefix what the user has typed so far in the current word
     * @param bundled word -> frequency 0..255 from the shipped dictionary
     * @param learned word -> frequency 0..255 from the user's personal dictionary
     * @return completions, best first, capitalised like the prefix, never equal to the prefix itself
     */
    fun complete(
        prefix: String,
        bundled: Map<String, Int>,
        learned: Map<String, Int>,
        max: Int,
    ): List<Scored> {
        if (prefix.isBlank() || max <= 0) return emptyList()
        val key = prefix.lowercase()
        val out = LinkedHashMap<String, Double>()
        // Frequency 0 in the personal dictionary means "blocked": user long-pressed it away. Never suggest.
        val blocked = learned.filterValues { it <= BLOCKED }.keys.map { it.lowercase() }.toSet()

        for ((word, freq) in learned) {
            val w = word.lowercase()
            if (freq > BLOCKED && w !in blocked && word.length > key.length && w.startsWith(key)) {
                out[w] = maxOf(out[w] ?: 0.0, freq / 255.0 + LEARNED_BOOST)
            }
        }
        for ((word, freq) in bundled) {
            if (word.length > key.length && word.startsWith(key)) {
                val w = word.lowercase()
                if (w !in out && w !in blocked) out[w] = freq / 255.0
            }
        }

        return out.entries
            .sortedByDescending { it.value }
            .take(max)
            .map { Scored(matchCase(prefix, it.key), it.value) }
    }

    /**
     * Should a committed word go into the personal dictionary? Skip noise (short, numeric, symbols)
     * and words the bundled dictionary already knows, so the personal list stays "your" words.
     */
    fun shouldLearn(word: String, bundled: Map<String, Int>): Boolean {
        if (word.length < 3 || word.length > 32) return false
        if (!word.any { it.isLetter() }) return false
        if (word.any { !(it.isLetterOrDigit() || it == '\'' || it == '-') }) return false
        return word.lowercase() !in bundled
    }

    /** Frequency bump for a learned word: new words start mid-range and climb with use. */
    fun nextFrequency(current: Int?): Int = if (current == null) 96 else minOf(255, current + 16)

    private fun matchCase(prefix: String, word: String): String = when {
        prefix.length > 1 && prefix.all { !it.isLetter() || it.isUpperCase() } -> word.uppercase()
        prefix.first().isUpperCase() -> word.replaceFirstChar { it.uppercase() }
        else -> word
    }

    data class Scored(val word: String, val score: Double)
}
