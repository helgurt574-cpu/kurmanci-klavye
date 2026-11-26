package com.klavye.kurmanci

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.StringReader

class DictionaryTest {

    private val dict = Dictionary.parse(
        StringReader(
            """
            # comment
            merhaba	silav|merheba
            ev	mal
            kitap	pirtûk
            iyi akşamlar	êvar baş
            akşamlar	êvar
            İstanbul	Stenbol
            """.trimIndent()
        )
    )

    @Test fun exactMatchKeepsOrder() {
        val s = dict.suggest("merhaba")
        assertEquals(listOf("silav", "merheba"), s.map { it.kurmanji })
        assertEquals(7, s.first().replaceLength)
    }

    @Test fun capitalizationFollowsInput() {
        assertEquals("Silav", dict.suggest("Merhaba").first().kurmanji)
        assertEquals("SILAV", dict.suggest("MERHABA").first().kurmanji)
    }

    @Test fun turkishDottedIUsesTurkishLowercase() {
        assertEquals("Stenbol", dict.suggest("İstanbul").first().kurmanji)
    }

    @Test fun onlyLastWordIsReplaced() {
        val s = dict.suggest("bugün ev").first()
        assertEquals("mal", s.kurmanji)
        assertEquals(2, s.replaceLength)
    }

    @Test fun phraseBeatsSingleWord() {
        val text = "Size iyi akşamlar"
        val s = dict.suggest(text)
        assertEquals("êvar baş", s[0].kurmanji)
        assertEquals("iyi akşamlar".length, s[0].replaceLength)
        assertEquals(Suggestion.Kind.PHRASE, s[0].kind)
        assertEquals("êvar", s[1].kurmanji)
    }

    @Test fun stemmingStripsSuffixes() {
        assertEquals("mal", dict.suggest("evlerde").first().kurmanji)
        assertEquals("mal", dict.suggest("evimiz").first().kurmanji)
    }

    @Test fun stemmingUndoesConsonantSoftening() {
        val s = dict.suggest("kitabı").first()
        assertEquals("pirtûk", s.kurmanji)
        assertEquals(Suggestion.Kind.STEM, s.kind)
    }

    @Test fun prefixCompletion() {
        val s = dict.suggest("mer")
        assertEquals("silav", s.first().kurmanji)
        assertEquals(Suggestion.Kind.PREFIX, s.first().kind)
    }

    @Test fun noSuggestionsAfterSpaceOrEmpty() {
        assertTrue(dict.suggest("merhaba ").isEmpty())
        assertTrue(dict.suggest("").isEmpty())
    }

    @Test fun prefixCompletionsFollowFileOrderNotAlphabet() {
        val d = Dictionary.parse(StringReader("gelmek\thatin\ngazete\trojname\ngarip\tecêb\n"))
        assertEquals(listOf("hatin"), d.suggest("ge").map { it.kurmanji })
        assertEquals(listOf("rojname", "ecêb"), d.suggest("ga").map { it.kurmanji })
    }

    @Test fun onlyLongestRealStemIsUsed() {
        val d = Dictionary.parse(StringReader("gece\tşev\ngeç\tdereng\n"))
        assertEquals(listOf("şev"), d.suggest("geceler").map { it.kurmanji })
    }

    private val accents = Dictionary.parse(
        StringReader("güzel\txweşik\nsaç\tpor\nsac\tsêl\nçocuk\tzarok\nağaç\tdar\niyi geceler\tşev baş\n")
    )

    @Test fun asciiInputFindsAccentedWord() {
        val s = accents.suggest("guzel").first()
        assertEquals("xweşik", s.kurmanji)
        assertEquals("güzel", s.turkish)
        assertEquals(5, s.replaceLength)
    }

    @Test fun asciiInputShowsLiteralMatchFirstThenAccentVariant() {
        assertEquals(listOf("sêl", "por"), accents.suggest("sac").map { it.kurmanji })
    }

    @Test fun accentedInputDoesNotFold() {
        assertEquals(listOf("por"), accents.suggest("saç").map { it.kurmanji })
    }

    @Test fun asciiStemmingAndSoftening() {
        assertEquals("zarok", accents.suggest("cocuklari").first().kurmanji)
        assertEquals("dar", accents.suggest("agaci").first().kurmanji)
    }

    @Test fun asciiPrefixAndPhrase() {
        assertEquals("xweşik", accents.suggest("guz").first().kurmanji)
        assertEquals("şev baş", accents.suggest("iyi geceler").first().kurmanji)
    }

    @Test fun foldStripsTurkishLetters() {
        assertEquals("cocuk agaci isik guzel", Dictionary.fold("çocuk ağacı ışık güzel"))
    }

    private val real by lazy {
        File("src/main/assets/tr_ku.tsv").reader(Charsets.UTF_8).use { Dictionary.parse(it) }
    }

    @Test fun bundledAssetParsesAndHasCoreWords() {
        assertTrue(real.size > 50_000)
        assertEquals("silav", real.exact("merhaba").first())
        assertEquals("spas", real.suggest("teşekkürler").first().kurmanji)
        assertEquals("mal", real.suggest("evde").first().kurmanji)
        assertEquals("pirtûk", real.suggest("kitabı").first().kurmanji)
        assertEquals("xweşik", real.suggest("guzel").first().kurmanji)
        assertEquals("spas", real.suggest("tesekkurler").first().kurmanji)
    }

    @Test fun bundledAssetLoadsQuickly() {
        val start = System.nanoTime()
        File("src/main/assets/tr_ku.tsv").reader(Charsets.UTF_8).use { Dictionary.parse(it) }
        val ms = (System.nanoTime() - start) / 1_000_000
        println("parse ms=$ms")
        assertTrue("parse took ${ms}ms", ms < 3000)
    }
}
