package com.genkeyboard.suggest

import io.kotest.matchers.shouldBe
import kotlin.test.Test

class WordCompleterTest {
    private val bundled = mapOf("hello" to 250, "help" to 200, "helmet" to 100, "world" to 240, "he" to 255)

    @Test
    fun `ranks learned words above bundled and keeps prefix casing`() {
        val learned = mapOf("helsinki" to 96)
        val out = WordCompleter.complete("Hel", bundled, learned, 3).map { it.word }
        out shouldBe listOf("Helsinki", "Hello", "Help")
    }

    @Test
    fun `blocked words (frequency 0) are hidden even if bundled`() {
        val learned = mapOf("hello" to 0, "junkword" to 0, "helsinki" to 96)
        WordCompleter.complete("hel", bundled, learned, 5).map { it.word } shouldBe listOf("helsinki", "help", "helmet")
    }

    @Test
    fun `never suggests the prefix itself and returns nothing for blank input`() {
        WordCompleter.complete("hello", bundled, emptyMap(), 5).map { it.word } shouldBe emptyList()
        WordCompleter.complete("", bundled, emptyMap(), 5) shouldBe emptyList()
    }

    @Test
    fun `autocorrects one-edit typos of common words, leaves known and unknown-looking words alone`() {
        val dict = mapOf("hello" to 200, "help" to 250, "world" to 240, "their" to 200, "there" to 220, "rare" to 20)
        WordCompleter.correct("helo", dict, emptyMap()) shouldBe "hello" // dropped double letter beats the more frequent "help"
        WordCompleter.correct("Wrold", dict, emptyMap()) shouldBe "World" // transposition, keeps case
        WordCompleter.correct("hellp", dict, emptyMap()) shouldBe "help" // one deletion, most frequent wins
        WordCompleter.correct("hello", dict, emptyMap()) shouldBe null // already correct
        WordCompleter.correct("Prabhath", dict, mapOf("Prabhath" to 96)) shouldBe null // learned
        WordCompleter.correct("helo", dict, mapOf("helo" to 0)) shouldBe "hello" // blocked is not known
        WordCompleter.correct("rae", dict, emptyMap()) shouldBe null // too short
        WordCompleter.correct("rar", dict, emptyMap()) shouldBe null // too short
        WordCompleter.correct("rarr", dict, emptyMap()) shouldBe null // candidate too rare
        WordCompleter.correct("xyzzy", dict, emptyMap()) shouldBe null // nothing close
    }

    @Test
    fun `autocorrects a wrong first letter, the most common real typing mistake`() {
        val dict = mapOf("world" to 240, "hello" to 200)
        // Adjacent-key miss on a QWERTY layout: 'q' next to 'w', 'e' next to 'w'.
        WordCompleter.correct("qorld", dict, emptyMap()) shouldBe "world" // substitution at position 0
        WordCompleter.correct("Qorld", dict, emptyMap()) shouldBe "World" // keeps case
        WordCompleter.correct("eworld", dict, emptyMap()) shouldBe "world" // extra leading letter
        WordCompleter.correct("orld", dict, emptyMap()) shouldBe "world" // missing first letter
    }

    @Test
    fun `suggests near-miss completions when the wrong first letter makes prefix matching useless`() {
        val dict = mapOf("world" to 240)
        // Nothing in the dictionary starts with "qorld", so plain prefix completion finds nothing;
        // this is exactly what a person sees after fat-fingering the first key of a finished word.
        WordCompleter.complete("qorld", dict, emptyMap(), 5).map { it.word } shouldBe listOf("world")

        // Two real words can be one substitution away from the same wrong first letter; both should
        // show up, most frequent first, same as multi-candidate autocorrect already behaves elsewhere.
        val twoCandidates = mapOf("word" to 200, "cord" to 100)
        WordCompleter.complete("qord", twoCandidates, emptyMap(), 5).map { it.word } shouldBe listOf("word", "cord")
    }

    @Test
    fun `fuzzy completion never resurrects a word the user blocked`() {
        val dict = mapOf("world" to 240)
        WordCompleter.complete("qorld", dict, mapOf("world" to 0), 5) shouldBe emptyList()
    }

    @Test
    fun `a real prefix match always wins over a fuzzy near-miss`() {
        val dict = mapOf("world" to 240, "worry" to 100)
        // "wor" is a genuine prefix of real words, so the fuzzy fallback must never even run here.
        WordCompleter.complete("wor", dict, emptyMap(), 5).map { it.word } shouldBe listOf("world", "worry")
    }

    @Test
    fun `learns only real personal words`() {
        WordCompleter.shouldLearn("Prabhath", bundled) shouldBe true
        WordCompleter.shouldLearn("hello", bundled) shouldBe false // already bundled
        WordCompleter.shouldLearn("ok", bundled) shouldBe false // too short
        WordCompleter.shouldLearn("12345", bundled) shouldBe false // no letters
        WordCompleter.shouldLearn("hi!!", bundled) shouldBe false // punctuation
        WordCompleter.nextFrequency(null) shouldBe 96
        WordCompleter.nextFrequency(250) shouldBe 255
    }
}
