package eu.kanade.tachiyomi.ui.reader.mokuro

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class JmdictHelperTest {

    @Test
    fun `JmdictEntry definitions list is parsed from tab-separated string`() {
        val raw = "to eat\tto consume\tto bite"
        val defs = raw.split("\t")
        defs.size shouldBe 3
        defs[0] shouldBe "to eat"
    }

    @Test
    fun `JmdictEntry top 3 definitions are taken`() {
        val entry = JmdictEntry(
            word = "食べる",
            reading = "たべる",
            partsOfSpeech = "v1",
            definitions = listOf("to eat", "to consume", "to bite", "to have a meal"),
        )
        entry.definitions.take(3).size shouldBe 3
    }
}
