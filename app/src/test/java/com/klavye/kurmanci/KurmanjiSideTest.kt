package com.klavye.kurmanci

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.StringReader

class KurmanjiSideTest {

    private fun dict() = Dictionary.parse(
        StringReader(
            """
            merhaba	silav|merheba
            ev	mal
            güzel	xweşik|delal
            çok	gelek|pir
            iyi geceler	şev baş
            kitap	pirtûk|kitêb
            okumak	xwendin
            ben	ez
            gelmek	hatin
            kalem	pênûs
            """.trimIndent()
        )
    )

    // --- user words ---

    @Test fun userEntryComesFirst() {
        val d = dict()
        d.user = UserWords().apply { add("Merhaba", "rojbaş") }
        assertEquals(listOf("rojbaş", "silav", "merheba"), d.suggest("merhaba").map { it.kurmanji })
        assertEquals("rojbaş", d.translations("merhaba").first())
    }

    @Test fun userPhraseEntry() {
        val d = dict()
        d.user = UserWords().apply { add("nasıl gidiyor", "çawa diçe") }
        assertEquals("çawa diçe", d.suggest("nasıl gidiyor").first().kurmanji)
    }

    @Test fun pickedTranslationRisesWithinItsKind() {
        val d = dict()
        val u = UserWords()
        d.user = u
        assertEquals("xweşik", d.suggest("güzel").first().kurmanji)
        u.recordUse("güzel", "delal")
        assertEquals(listOf("delal", "xweşik"), d.suggest("güzel").map { it.kurmanji })
    }

    @Test fun userWordsRoundTripThroughText() {
        val u = UserWords().apply {
            add("Merhaba", "rojbaş")
            add("çay", "çay")
            recordUse("merhaba", "rojbaş")
            recordUse("merhaba", "rojbaş")
        }
        val back = UserWords(u.entriesText(), u.usageText())
        assertEquals(u.all(), back.all())
        assertEquals(2, back.usage("Merhaba", "Rojbaş"))
        back.remove("merhaba", "rojbaş")
        assertEquals(listOf("çay" to "çay"), back.all())
    }

    // --- Kurmanji completion ---

    @Test fun completesKurmanjiWords() {
        val d = dict()
        assertEquals(listOf("pirtûk", "pir"), d.completeKurmanji("pir").sortedByDescending { it.length })
        assertTrue("pênûs" in d.completeKurmanji("pe")) // plain e also finds ê
        assertFalse("pênûs" in d.completeKurmanji("pi"))
        assertTrue(d.completeKurmanji("pê").all { it.startsWith("pê") }) // typed ê is honored exactly
    }

    @Test fun userTaughtWordsCompleteFirst() {
        val d = dict()
        d.user = UserWords().apply { add("selam", "silavên germ") }
        assertEquals("silavên germ", d.completeKurmanji("sil").first())
    }

    // --- reverse translation ---

    @Test fun reverseWordsAndPhrases() {
        val r = ReverseTranslator(dict())
        assertEquals("İyi geceler", r.translate("Şev baş"))
        assertEquals("Merhaba, ev çok güzel", r.translate("Silav, mal gelek xweşik"))
    }

    @Test fun reverseStripsKurmanjiEndingsAndPrefixes() {
        val r = ReverseTranslator(dict())
        assertEquals("kitap", r.translate("pirtûka"))
        assertEquals("ev", r.translate("malê"))
    }

    @Test fun copulasAndPrepositionsDropped() =
        assertEquals("Ev çok güzel.", ReverseTranslator(dict()).translate("Mal gelek xweşik e."))

    @Test fun unknownWordsStay() = assertEquals("ev zzz", ReverseTranslator(dict()).translate("mal zzz"))

    @Test fun detectsKurmanji() {
        val d = dict()
        assertTrue(ReverseTranslator.looksKurmanji(d, "silav mal"))
        assertTrue(ReverseTranslator.looksKurmanji(d, "silav pirtûk"))
        assertFalse(ReverseTranslator.looksKurmanji(d, "merhaba ev"))
        assertFalse(ReverseTranslator.looksKurmanji(d, "123"))
    }

    @Test fun realDictionaryReverseSamples() {
        val d = File("src/main/assets/tr_ku.tsv").reader(Charsets.UTF_8).use { Dictionary.parse(it) }
        val start = System.nanoTime()
        d.warmUpKurmanji()
        println("kurmanji warm-up ms=${(System.nanoTime() - start) / 1_000_000}")
        val r = ReverseTranslator(d)
        listOf("Silav, tu çawa yî?", "Ez diçim malê.", "Spas, gelek xweş e.", "Şev baş hevalê min")
            .forEach { println("$it  →  ${r.translate(it)}") }
        println("complete 'hev' → ${d.completeKurmanji("hev")}")
        println("complete 'spa' → ${d.completeKurmanji("spa")}")
        assertTrue(d.completeKurmanji("spa").contains("spas"))
    }
}
