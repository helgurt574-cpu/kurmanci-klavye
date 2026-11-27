import XCTest
@testable import KurmanciCore

/// Port of the Android app's DictionaryTest.kt.
final class DictionaryTests: XCTestCase {

    private let dict = KurmanjiDictionary.parse("""
        # comment
        merhaba\tsilav|merheba
        ev\tmal
        kitap\tpirtûk
        iyi akşamlar\têvar baş
        akşamlar\têvar
        İstanbul\tStenbol
        """)

    func testExactMatchKeepsOrder() {
        let s = dict.suggest("merhaba")
        XCTAssertEqual(["silav", "merheba"], s.map(\.kurmanji))
        XCTAssertEqual(7, s.first?.replaceLength)
    }

    func testCapitalizationFollowsInput() {
        XCTAssertEqual("Silav", dict.suggest("Merhaba").first?.kurmanji)
        XCTAssertEqual("SILAV", dict.suggest("MERHABA").first?.kurmanji)
    }

    func testTurkishDottedIUsesTurkishLowercase() {
        XCTAssertEqual("Stenbol", dict.suggest("İstanbul").first?.kurmanji)
    }

    func testOnlyLastWordIsReplaced() {
        let s = dict.suggest("bugün ev").first!
        XCTAssertEqual("mal", s.kurmanji)
        XCTAssertEqual(2, s.replaceLength)
    }

    func testPhraseBeatsSingleWord() {
        let s = dict.suggest("Size iyi akşamlar")
        XCTAssertEqual("êvar baş", s[0].kurmanji)
        XCTAssertEqual("iyi akşamlar".count, s[0].replaceLength)
        XCTAssertEqual(.phrase, s[0].kind)
        XCTAssertEqual("êvar", s[1].kurmanji)
    }

    func testStemmingStripsSuffixes() {
        XCTAssertEqual("mal", dict.suggest("evlerde").first?.kurmanji)
        XCTAssertEqual("mal", dict.suggest("evimiz").first?.kurmanji)
    }

    func testStemmingUndoesConsonantSoftening() {
        let s = dict.suggest("kitabı").first!
        XCTAssertEqual("pirtûk", s.kurmanji)
        XCTAssertEqual(.stem, s.kind)
    }

    func testPrefixCompletion() {
        let s = dict.suggest("mer")
        XCTAssertEqual("silav", s.first?.kurmanji)
        XCTAssertEqual(.prefix, s.first?.kind)
    }

    func testNoSuggestionsAfterSpaceOrEmpty() {
        XCTAssertTrue(dict.suggest("merhaba ").isEmpty)
        XCTAssertTrue(dict.suggest("").isEmpty)
    }

    func testPrefixCompletionsFollowFileOrderNotAlphabet() {
        let d = KurmanjiDictionary.parse("gelmek\thatin\ngazete\trojname\ngarip\tecêb\n")
        XCTAssertEqual(["hatin"], d.suggest("ge").map(\.kurmanji))
        XCTAssertEqual(["rojname", "ecêb"], d.suggest("ga").map(\.kurmanji))
    }

    func testOnlyLongestRealStemIsUsed() {
        let d = KurmanjiDictionary.parse("gece\tşev\ngeç\tdereng\n")
        XCTAssertEqual(["şev"], d.suggest("geceler").map(\.kurmanji))
    }

    private let accents = KurmanjiDictionary.parse(
        "güzel\txweşik\nsaç\tpor\nsac\tsêl\nçocuk\tzarok\nağaç\tdar\niyi geceler\tşev baş\n"
    )

    func testAsciiInputFindsAccentedWord() {
        let s = accents.suggest("guzel").first!
        XCTAssertEqual("xweşik", s.kurmanji)
        XCTAssertEqual("güzel", s.turkish)
        XCTAssertEqual(5, s.replaceLength)
    }

    func testAsciiInputShowsLiteralMatchFirstThenAccentVariant() {
        XCTAssertEqual(["sêl", "por"], accents.suggest("sac").map(\.kurmanji))
    }

    func testAccentedInputDoesNotFold() {
        XCTAssertEqual(["por"], accents.suggest("saç").map(\.kurmanji))
    }

    func testAsciiStemmingAndSoftening() {
        XCTAssertEqual("zarok", accents.suggest("cocuklari").first?.kurmanji)
        XCTAssertEqual("dar", accents.suggest("agaci").first?.kurmanji)
    }

    func testAsciiPrefixAndPhrase() {
        XCTAssertEqual("xweşik", accents.suggest("guz").first?.kurmanji)
        XCTAssertEqual("şev baş", accents.suggest("iyi geceler").first?.kurmanji)
    }

    func testFoldStripsTurkishLetters() {
        XCTAssertEqual("cocuk agaci isik guzel", KurmanjiDictionary.fold("çocuk ağacı ışık güzel"))
    }

    func testCrlfAndDuplicateKeysMerge() {
        let d = KurmanjiDictionary.parse("ev\tmal\r\nEv\tmal|xanî\r\n")
        XCTAssertEqual(["mal", "xanî"], d.exact("ev"))
    }

    func testBundledAssetParsesAndHasCoreWords() {
        let real = realDictionary
        XCTAssertGreaterThan(real.size, 50_000)
        XCTAssertEqual("silav", real.exact("merhaba").first)
        XCTAssertEqual("spas", real.suggest("teşekkürler").first?.kurmanji)
        XCTAssertEqual("mal", real.suggest("evde").first?.kurmanji)
        XCTAssertEqual("pirtûk", real.suggest("kitabı").first?.kurmanji)
        XCTAssertEqual("xweşik", real.suggest("guzel").first?.kurmanji)
        XCTAssertEqual("spas", real.suggest("tesekkurler").first?.kurmanji)
    }

    func testBundledAssetLoadsQuickly() throws {
        let data = try Data(contentsOf: assetURL)
        let start = Date()
        _ = KurmanjiDictionary.parse(data)
        let ms = Int(Date().timeIntervalSince(start) * 1000)
        print("parse ms=\(ms)")
        // Unoptimized builds are several times slower; CI checks the release bundle (KurmanciKlavye --selftest).
        #if !DEBUG
        XCTAssertLessThan(ms, 3000, "parse took \(ms)ms")
        #endif
    }
}
