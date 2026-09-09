/*
 * Copyright (C) 2022-2025 The FlorisBoard Contributors
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

package dev.patrickgold.florisboard.ime.nlp.latin

import android.content.Context
import com.genkeyboard.suggest.NextWordModel
import com.genkeyboard.suggest.WordCompleter
import java.io.File
import dev.patrickgold.florisboard.app.FlorisPreferenceStore
import dev.patrickgold.florisboard.appContext
import dev.patrickgold.florisboard.ime.core.Subtype
import dev.patrickgold.florisboard.ime.dictionary.DictionaryManager
import dev.patrickgold.florisboard.ime.dictionary.UserDictionaryEntry
import dev.patrickgold.florisboard.ime.editor.EditorContent
import dev.patrickgold.florisboard.ime.nlp.SpellingProvider
import dev.patrickgold.florisboard.ime.nlp.SpellingResult
import dev.patrickgold.florisboard.ime.nlp.SuggestionCandidate
import dev.patrickgold.florisboard.ime.nlp.SuggestionProvider
import dev.patrickgold.florisboard.ime.nlp.WordSuggestionCandidate
import dev.patrickgold.florisboard.lib.devtools.flogDebug
import dev.patrickgold.florisboard.lib.devtools.flogWarning
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import org.florisboard.lib.android.readText
import org.florisboard.lib.kotlin.guardedByLock

class LatinLanguageProvider(context: Context) : SpellingProvider, SuggestionProvider {
    companion object {
        // Default user ID used for all subtypes, unless otherwise specified.
        // See `ime/core/Subtype.kt` Line 210 and 211 for the default usage
        const val ProviderId = "org.florisboard.nlp.providers.latin"
        private val SENTENCE_END = setOf('.', '!', '?', '\n')
    }

    private val appContext by context.appContext()

    private val wordData = guardedByLock { mutableMapOf<String, Int>() }
    private val wordDataSerializer = MapSerializer(String.serializer(), Int.serializer())

    override val providerId = ProviderId

    override suspend fun create() {
        // Here we initialize our provider, set up all things which are not language dependent.
    }

    override suspend fun preload(subtype: Subtype) = withContext(Dispatchers.IO) {
        // Here we have the chance to preload dictionaries and prepare a neural network for a specific language.
        // Is kept in sync with the active keyboard subtype of the user, however a new preload does not necessary mean
        // the previous language is not needed anymore (e.g. if the user constantly switches between two subtypes)

        // To read a file from the APK assets the following methods can be used:
        // appContext.assets.open()
        // appContext.assets.reader()
        // appContext.assets.bufferedReader()
        // appContext.assets.readText()
        // To copy an APK file/dir to the file system cache (appContext.cacheDir), the following methods are available:
        // appContext.assets.copy()
        // appContext.assets.copyRecursively()

        // The subtype we get here contains a lot of data, however we are only interested in subtype.primaryLocale and
        // subtype.secondaryLocales.

        if (!nextWordsLoaded) {
            nextWordsLoaded = true
            runCatching { nextWords.load(nextWordsFile) }.onFailure { flogWarning { "nextword load failed: ${it.message}" } }
        }
        wordData.withLock { wordData ->
            if (wordData.isEmpty()) {
                // Here we use readText() because the test dictionary is a json dictionary
                val rawData = appContext.assets.readText("ime/dict/data.json")
                val jsonData = Json.decodeFromString(wordDataSerializer, rawData)
                wordData.putAll(jsonData)
            }
        }
    }

    override suspend fun spell(
        subtype: Subtype,
        word: String,
        precedingWords: List<String>,
        followingWords: List<String>,
        maxSuggestionCount: Int,
        allowPossiblyOffensive: Boolean,
        isPrivateSession: Boolean,
    ): SpellingResult {
        return when (word.lowercase()) {
            // Use typo for typing errors
            "typo" -> SpellingResult.typo(arrayOf("typo1", "typo2", "typo3"))
            // Use grammar error if the algorithm can detect this. On Android 11 and lower grammar errors are visually
            // marked as typos due to a lack of support
            "gerror" -> SpellingResult.grammarError(arrayOf("grammar1", "grammar2", "grammar3"))
            // Use valid word for valid input
            else -> SpellingResult.validWord()
        }
    }

    override suspend fun suggest(
        subtype: Subtype,
        content: EditorContent,
        maxCandidateCount: Int,
        allowPossiblyOffensive: Boolean,
        isPrivateSession: Boolean,
    ): List<SuggestionCandidate> {
        preload(subtype)
        val prefix = content.composingText.trim()

        // Word boundary: the word we were completing is gone from the composing region and sits committed
        // before the cursor. Learn it (and the bigram from the word before it), unless this is a private session.
        val previous = lastComposing
        lastComposing = prefix
        if (prefix.isEmpty()) {
            val before = content.textBeforeSelection
            val committed = before.trimEnd { !it.isLetterOrDigit() && it != '\'' }
            if (previous.isNotEmpty() && committed.endsWith(previous)) {
                if (!isPrivateSession) {
                    // A brand-new word must be typed twice before it is learned, so a one-off typo (or a word the
                    // user let autocorrect skip once) does not enter the personal dictionary. Tapping the word in
                    // the suggestion row learns it immediately (notifySuggestionAccepted).
                    val key = previous.lowercase()
                    if (seenOnce.remove(key) || isKnown(previous)) learn(subtype, previous) else seenOnce.add(key)
                    lastCommitted?.let { nextWords.learn(it, previous) }
                    persistNextWords()
                }
                lastCommitted = previous
            }
            // Sentence end resets the context; a next-word guess after "." is noise.
            if (before.trimEnd().lastOrNull() in SENTENCE_END) lastCommitted = null
            val prev = lastCommitted ?: return emptyList()
            return nextWords.predict(prev, minOf(3, maxCandidateCount)).map {
                WordSuggestionCandidate(text = it, confidence = 0.5, isEligibleForAutoCommit = false, sourceProvider = this@LatinLanguageProvider)
            }
        }

        val bundled = wordData.withLock { it.toMap() }
        val learned = withContext(Dispatchers.IO) {
            runCatching {
                val manager = DictionaryManager.default()
                manager.loadUserDictionariesIfNecessary() // no-op once open; without it the DAO is null in a fresh process
                manager.florisUserDictionaryDao()
                    ?.query(prefix, subtype.primaryLocale)
                    ?.associate { it.word to it.freq }
            }.getOrNull() ?: emptyMap()
        }
        val completions = WordCompleter.complete(prefix, bundled, learned, maxCandidateCount).map {
            WordSuggestionCandidate(
                text = it.word,
                confidence = it.score,
                isEligibleForAutoCommit = false,
                sourceProvider = this@LatinLanguageProvider,
            )
        }
        // Autocorrect: an unknown word one edit away from a common word gets a candidate that the space key
        // commits automatically. Backspace right after reverts it (FlorisBoard handles the revert).
        val fix = if (prefs.correction.autoCorrect.get()) WordCompleter.correct(prefix, bundled, learned) else null
        if (fix == null) return completions
        val auto = WordSuggestionCandidate(text = fix, confidence = 1.0, isEligibleForAutoCommit = true, sourceProvider = this@LatinLanguageProvider)
        // Keep the word as typed one tap away, so an intentional spelling survives the autocorrect.
        val typed = WordSuggestionCandidate(text = prefix, confidence = 0.9, isEligibleForAutoCommit = false, isEligibleForUserRemoval = false, sourceProvider = this@LatinLanguageProvider)
        return listOf(auto, typed) + completions.filterNot { it.text.toString().equals(fix, ignoreCase = true) }.take(maxOf(0, maxCandidateCount - 2))
    }

    private val prefs by FlorisPreferenceStore

    /** Last non-empty composing prefix, used to detect that a word just got committed. */
    private var lastComposing: String = ""

    /** Last committed word, the left context for next-word prediction. Null after sentence end. */
    private var lastCommitted: String? = null

    /** Unknown words typed once this session; typing one again learns it. ponytail: in-memory, resets on restart. */
    private val seenOnce = LinkedHashSet<String>()

    /** Already in the personal dictionary with a positive frequency (so a repeat use should bump it). */
    private suspend fun isKnown(word: String): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val manager = DictionaryManager.default()
            manager.loadUserDictionariesIfNecessary()
            manager.florisUserDictionaryDao()?.queryExact(word, null)?.any { it.freq > WordCompleter.BLOCKED } == true
        }.getOrDefault(false)
    }

    private val nextWords = NextWordModel()
    private var nextWordsLoaded = false
    private var nextWordsDirty = 0
    private val nextWordsFile get() = File(appContext.filesDir, "genkeyboard/nextword.json")

    private fun persistNextWords() {
        // ponytail: write every 5th learn; the file is a few KB. A lost handful of bigrams is harmless.
        if (++nextWordsDirty % 5 != 0) return
        runCatching { nextWords.save(nextWordsFile) }.onFailure { flogWarning { "nextword save failed: ${it.message}" } }
    }

    /**
     * Add or bump a word in the user's personal dictionary so it is suggested next time. Words the bundled
     * dictionary already knows are skipped, so the personal list only holds the user's own vocabulary.
     */
    private suspend fun learn(subtype: Subtype, word: String) = withContext(Dispatchers.IO) {
        val bundled = wordData.withLock { it }
        if (!WordCompleter.shouldLearn(word, bundled)) return@withContext
        runCatching {
            val manager = DictionaryManager.default()
            manager.loadUserDictionariesIfNecessary()
            val dao = manager.florisUserDictionaryDao() ?: return@withContext
            // No locale: names and personal words are language-independent, and a null locale matches every
            // subtype in the DAO's LOCALE_MATCHES clause. (Room stores locales as "en_US"; do not hand-format.)
            val existing = dao.queryExact(word, null).firstOrNull()
            if (existing == null) {
                dao.insert(UserDictionaryEntry(0, word, WordCompleter.nextFrequency(null), null, null))
            } else if (existing.freq <= WordCompleter.BLOCKED) {
                // User removed this word on purpose; typing it again must not resurrect it.
                return@withContext
            } else {
                dao.update(existing.copy(freq = WordCompleter.nextFrequency(existing.freq)))
            }
        }.onFailure { flogWarning { "learn '$word' failed: ${it.message}" } }
    }

    override suspend fun notifySuggestionAccepted(subtype: Subtype, candidate: SuggestionCandidate) {
        // Picking a suggestion counts as using the word; bump it so it ranks higher next time.
        lastComposing = ""
        learn(subtype, candidate.text.toString())
    }

    override suspend fun notifySuggestionReverted(subtype: Subtype, candidate: SuggestionCandidate) {
        flogDebug { candidate.toString() }
    }

    /**
     * Long-press on a suggestion. Blocks the word: it gets frequency 0 in the personal dictionary, so it is
     * neither suggested nor re-learned. Unblock by deleting the row in Settings > Typing > Dictionary.
     */
    override suspend fun removeSuggestion(subtype: Subtype, candidate: SuggestionCandidate): Boolean =
        withContext(Dispatchers.IO) {
            val word = candidate.text.toString().trim()
            if (word.isEmpty()) return@withContext false
            nextWords.forget(word)
            runCatching { nextWords.save(nextWordsFile) }
            runCatching {
                val manager = DictionaryManager.default()
                manager.loadUserDictionariesIfNecessary()
                val dao = manager.florisUserDictionaryDao() ?: return@withContext false
                val rows = dao.queryExact(word, null) + dao.queryExact(word.lowercase(), null)
                if (rows.isEmpty()) {
                    dao.insert(UserDictionaryEntry(0, word.lowercase(), WordCompleter.BLOCKED, null, null))
                } else {
                    for (row in rows.distinctBy { it.id }) dao.update(row.copy(freq = WordCompleter.BLOCKED))
                }
                true
            }.getOrElse {
                flogWarning { "block '$word' failed: ${it.message}" }
                false
            }
        }

    override suspend fun getListOfWords(subtype: Subtype): List<String> {
        return wordData.withLock { it.keys.toList() }
    }

    override suspend fun getFrequencyForWord(subtype: Subtype, word: String): Double {
        return wordData.withLock { it.getOrDefault(word, 0) / 255.0 }
    }

    override suspend fun destroy() {
        // Here we have the chance to de-allocate memory and finish our work. However this might never be called if
        // the app process is killed (which will most likely always be the case).
    }
}
