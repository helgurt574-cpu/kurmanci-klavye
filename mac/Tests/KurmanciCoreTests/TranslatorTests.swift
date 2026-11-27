import XCTest
@testable import KurmanciCore

/// Port of the Android app's TranslatorTest.kt.
final class TranslatorTests: XCTestCase {

    private let t = Translator(KurmanjiDictionary.parse("""
        ev\tmal
        okul\tdibistan
        kitap\tpirtûk
        dün\tduh
        yarın\tsibê
        bugün\tîro
        hava\thewa
        çok\tgelek
        güzel\txweşik
        nasılsın\ttu çawa yî
        iyi geceler\tşev baş
        ve\tû
        yedi\theft
        et\tgoşt
        ekmek\tnan
        """))

    func testProgressiveAddsDroppedPronoun() { XCTAssertEqual("Ez diçim.", t.translate("Gidiyorum.")) }

    func testExplicitPronounIsReusedNotDuplicated() {
        XCTAssertEqual("Ez diçim malê.", t.translate("Ben eve gidiyorum."))
    }

    func testDestinationFollowsMotionVerb() {
        XCTAssertEqual("Ez duh çûm dibistanê", t.translate("Dün okula gittim"))
        XCTAssertEqual("Were malê", t.translate("Eve gel"))
        XCTAssertEqual("Em diçin malê me", t.translate("Evimize gidiyoruz"))
        XCTAssertEqual("Ez ê sibê bêm malê", t.translate("Yarın eve geleceğim"))
    }

    func testDativeWithoutMotionVerbKeepsPreposition() { XCTAssertEqual("bo mal", t.translate("eve")) }

    func testIntransitivePastAgreesWithSubject() { XCTAssertEqual("Ez duh hatim", t.translate("Dün geldim")) }

    func testTransitivePastIsErgative() { XCTAssertEqual("Min pirtûk xwend", t.translate("Kitabı okudum")) }

    func testErgativeReplacesExplicitPronoun() {
        XCTAssertEqual("Min pirtûk xwend", t.translate("Ben kitabı okudum"))
    }

    func testFuture() { XCTAssertEqual("Ez ê sibê bêm", t.translate("Yarın geleceğim")) }

    func testFutureSecondPersonUsesYe() { XCTAssertEqual("Tu yê biçî", t.translate("Gideceksin")) }

    func testThirdPersonFutureUsesDe() { XCTAssertEqual("Sibê dê bê", t.translate("Yarın gelecek")) }

    func testNegation() {
        XCTAssertEqual("Ez naçim", t.translate("Gitmiyorum"))
        XCTAssertEqual("Min nekir", t.translate("Yapmadım"))
        XCTAssertEqual("Ez nizanim", t.translate("Bilmiyorum"))
    }

    func testImperatives() {
        XCTAssertEqual("Were", t.translate("Gel"))
        XCTAssertEqual("Werin", t.translate("Gelin"))
        XCTAssertEqual("Neçe", t.translate("Gitme"))
    }

    func testQuestionParticleSetsPersonAndQuestionMark() {
        XCTAssertEqual("Tu têyî?", t.translate("Geliyor musun"))
    }

    func testCaseSuffixesBecomePrepositions() {
        XCTAssertEqual("ji dibistan", t.translate("okuldan"))
        XCTAssertEqual("li malê me", t.translate("evimizde"))
    }

    func testPhrasesAndPlainWords() {
        XCTAssertEqual("Şev baş û îro hewa gelek xweşik", t.translate("İyi geceler ve bugün hava çok güzel"))
    }

    func testDictionaryBeatsWeakVerbReading() {
        XCTAssertEqual("Heft nan", t.translate("Yedi ekmek"))
        XCTAssertEqual("goşt", t.translate("et"))
    }

    func testConjunctionStartsNewClauseWithOwnSubject() {
        XCTAssertEqual("Min pirtûk xwend û min gelek hez kir", t.translate("Kitabı okudum ve çok sevdim"))
    }

    func testSentenceStartFindsSentenceEndingAtCursor() {
        func tail(_ s: String) -> String? { Translator.sentenceStart(s).map { String(Array(s)[$0...]) } }
        XCTAssertEqual("Ben eve gidiyorum", tail("Merhaba. Nasılsın? Ben eve gidiyorum"))
        XCTAssertEqual("Nasılsın? ", tail("Merhaba. Nasılsın? "))
        XCTAssertNil(Translator.sentenceStart("   "))
        XCTAssertEqual(0, Translator.sentenceStart("tek cümle"))
    }

    func testAccentlessInput() { XCTAssertEqual("Ez duh hatim", t.translate("Dun geldim")) }

    func testRealDictionarySamples() {
        let real = Translator(realDictionary)
        XCTAssertEqual("Ez diçim malê.", real.translate("Ben eve gidiyorum."))
        for s in [
            "Merhaba, nasılsın?",
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
        ] {
            print("\(s)  →  \(real.translate(s))")
        }
    }
}
