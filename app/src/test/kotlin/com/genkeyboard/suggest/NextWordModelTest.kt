package com.genkeyboard.suggest

import io.kotest.matchers.shouldBe
import java.io.File
import kotlin.test.Test

class NextWordModelTest {
    @Test
    fun `predicts the most frequent follower first and survives a save-load round trip`() {
        val m = NextWordModel()
        repeat(3) { m.learn("see", "you") }
        m.learn("see", "them")
        m.learn("See", "tomorrow")
        val p = m.predict("see")
        p.first() shouldBe "you"
        p.drop(1).toSet() shouldBe setOf("them", "tomorrow") // equal counts, order not guaranteed
        m.predict("unknown") shouldBe emptyList()

        val f = File.createTempFile("nextword", ".json")
        try {
            m.save(f)
            val loaded = NextWordModel()
            loaded.load(f) shouldBe true
            loaded.predict("see", 1) shouldBe listOf("you")
        } finally {
            f.delete()
        }
    }

    @Test
    fun `ignores junk, forgets blocked words, respects caps`() {
        val m = NextWordModel(maxPrev = 2, maxNext = 2)
        m.learn("a", "b") // too short, ignored
        m.learn("hello", "123") // no letters, ignored
        m.predict("hello") shouldBe emptyList()

        m.learn("good", "morning"); m.learn("good", "morning"); m.learn("good", "night"); m.learn("good", "luck")
        m.predict("good").size shouldBe 2 // maxNext
        m.forget("morning")
        m.predict("good").contains("morning") shouldBe false

        m.learn("thank", "you"); m.learn("see", "you")
        m.size shouldBe 2 // maxPrev
    }
}
