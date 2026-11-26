package com.klavye.kurmanci

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import java.io.StringReader

class TranslatorTest {

    private val t = Translator(
        Dictionary.parse(
            StringReader(
                """
                ev	mal
                okul	dibistan
                kitap	pirtûk
                dün	duh
                yarın	sibê
                bugün	îro
                hava	hewa
                çok	gelek
                güzel	xweşik
                nasılsın	tu çawa yî
                iyi geceler	şev baş
                ve	û
                yedi	heft
                et	goşt
                ekmek	nan
                """.trimIndent()
            )
        )
    )

    @Test fun progressiveAddsDroppedPronoun() = assertEquals("Ez diçim.", t.translate("Gidiyorum."))

    @Test fun explicitPronounIsReusedNotDuplicated() =
        assertEquals("Ez diçim malê.", t.translate("Ben eve gidiyorum."))

    @Test fun destinationFollowsMotionVerb() {
        assertEquals("Ez duh çûm dibistanê", t.translate("Dün okula gittim"))
        assertEquals("Were malê", t.translate("Eve gel"))
        assertEquals("Em diçin malê me", t.translate("Evimize gidiyoruz"))
        assertEquals("Ez ê sibê bêm malê", t.translate("Yarın eve geleceğim"))
    }

    @Test fun dativeWithoutMotionVerbKeepsPreposition() = assertEquals("bo mal", t.translate("eve"))

    @Test fun intransitivePastAgreesWithSubject() = assertEquals("Ez duh hatim", t.translate("Dün geldim"))

    @Test fun transitivePastIsErgative() = assertEquals("Min pirtûk xwend", t.translate("Kitabı okudum"))

    @Test fun ergativeReplacesExplicitPronoun() = assertEquals("Min pirtûk xwend", t.translate("Ben kitabı okudum"))

    @Test fun future() = assertEquals("Ez ê sibê bêm", t.translate("Yarın geleceğim"))

    @Test fun futureSecondPersonUsesYe() = assertEquals("Tu yê biçî", t.translate("Gideceksin"))

    @Test fun thirdPersonFutureUsesDe() = assertEquals("Sibê dê bê", t.translate("Yarın gelecek"))

    @Test fun negation() {
        assertEquals("Ez naçim", t.translate("Gitmiyorum"))
        assertEquals("Min nekir", t.translate("Yapmadım"))
        assertEquals("Ez nizanim", t.translate("Bilmiyorum"))
    }

    @Test fun imperatives() {
        assertEquals("Were", t.translate("Gel"))
        assertEquals("Werin", t.translate("Gelin"))
        assertEquals("Neçe", t.translate("Gitme"))
    }

    @Test fun questionParticleSetsPersonAndQuestionMark() =
        assertEquals("Tu têyî?", t.translate("Geliyor musun"))

    @Test fun caseSuffixesBecomePrepositions() {
        assertEquals("ji dibistan", t.translate("okuldan"))
        assertEquals("li malê me", t.translate("evimizde"))
    }

    @Test fun phrasesAndPlainWords() {
        assertEquals("Şev baş û îro hewa gelek xweşik", t.translate("İyi geceler ve bugün hava çok güzel"))
    }

    @Test fun dictionaryBeatsWeakVerbReading() {
        assertEquals("Heft nan", t.translate("Yedi ekmek"))
        assertEquals("goşt", t.translate("et"))
    }

    @Test fun conjunctionStartsNewClauseWithOwnSubject() =
        assertEquals("Min pirtûk xwend û min gelek hez kir", t.translate("Kitabı okudum ve çok sevdim"))

    @Test fun sentenceStartFindsSentenceEndingAtCursor() {
        val text = "Merhaba. Nasılsın? Ben eve gidiyorum"
        assertEquals("Ben eve gidiyorum", text.substring(Translator.sentenceStart(text)!!))
        assertEquals("Nasılsın? ", "Merhaba. Nasılsın? ".let { it.substring(Translator.sentenceStart(it)!!) })
        assertEquals(null, Translator.sentenceStart("   "))
        assertEquals(0, Translator.sentenceStart("tek cümle"))
    }

    @Test fun accentlessInput() = assertEquals("Ez duh hatim", t.translate("Dun geldim"))

    @Test fun realDictionarySamples() {
        val real = File("src/main/assets/tr_ku.tsv").reader(Charsets.UTF_8).use { Translator(Dictionary.parse(it)) }
        listOf(
            "Merhaba, nasılsın?",
            "Ben eve gidiyorum.",
            "Dün okula gittim.",
            "Bugün hava çok güzel.",
            "Yarın sana geleceğim.",
            "Kitabı okudum ve çok sevdim.",
            "Annem ekmek yapıyor.",
            "Su içmek istiyorum.",
            "Ne yapıyorsun?",
            "Seni çok özledim.",
            "Kardeşim okulda çalışıyor.",
            "Biz Kürtçe konuşuyoruz.",
        ).forEach { println("$it  →  ${real.translate(it)}") }
    }
}
