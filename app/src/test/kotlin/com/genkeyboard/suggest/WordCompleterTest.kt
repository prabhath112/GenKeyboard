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
