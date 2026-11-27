import XCTest
@testable import KurmanciCore

/// The Mac input method's typing logic (no Android counterpart: Android reads the text before the cursor).
final class ComposerTests: XCTestCase {

    private func composer() -> Composer {
        Composer(dictionary: KurmanjiDictionary.parse("""
            merhaba\tsilav|merheba
            ev\tmal
            okul\tdibistan
            dün\tduh
            """))
    }

    func testWordCandidatesReplaceOnlyTheLastWord() {
        let c = composer()
        XCTAssertEqual("", c.type("Merhaba"))
        XCTAssertEqual(["Silav", "Merheba"], c.candidates.map(\.kurmanji))
        XCTAssertEqual("Silav ", c.choose(0))
        XCTAssertEqual("", c.buffer)
        XCTAssertTrue(c.candidates.isEmpty)
    }

    func testSentenceCandidateComesFirst() {
        let c = composer()
        _ = c.type("Ben eve gidiyorum")
        XCTAssertTrue(c.candidates[0].isSentence)
        XCTAssertEqual("Ez diçim malê", c.candidates[0].kurmanji)
        XCTAssertEqual("Ez diçim malê ", c.choose(0))
    }

    func testSentenceCandidateSurvivesEndPunctuation() {
        let c = composer()
        _ = c.type("Dün okula gittim.")
        XCTAssertEqual("Ez duh çûm dibistanê.", c.candidates.first?.kurmanji)
        _ = c.type(" ")
        XCTAssertEqual("Ez duh çûm dibistanê.", c.candidates.first?.kurmanji)
        XCTAssertFalse(c.digitsSelect) // after a space, digits are typed
    }

    func testNewSentenceCommitsThePreviousOne() {
        let c = composer()
        _ = c.type("Merhaba. ")
        XCTAssertEqual("Merhaba. ", c.type("e"))
        XCTAssertEqual("e", c.buffer)
    }

    func testChoosingAPhraseKeepsEarlierText() {
        let c = composer()
        _ = c.type("Merhaba ev")
        let i = c.candidates.firstIndex { $0.kurmanji == "mal" }!
        XCTAssertEqual("Merhaba mal ", c.choose(i))
    }

    func testBackspaceAndCommit() {
        let c = composer()
        XCTAssertFalse(c.backspace())
        _ = c.type("evv")
        XCTAssertTrue(c.backspace())
        XCTAssertEqual("ev", c.buffer)
        XCTAssertEqual("mal", c.candidates.first?.kurmanji)
        XCTAssertTrue(c.digitsSelect)
        XCTAssertEqual("ev", c.commitAll())
        XCTAssertTrue(c.candidates.isEmpty)
    }

    func testHighlightStaysInRange() {
        let c = composer()
        _ = c.type("merhaba")
        XCTAssertNil(c.highlighted)
        c.moveHighlight(by: 1)
        XCTAssertEqual(0, c.highlighted)
        c.moveHighlight(by: 5)
        XCTAssertEqual(1, c.highlighted)
        c.moveHighlight(by: -5)
        XCTAssertEqual(0, c.highlighted)
    }

    func testPickRecordsUsage() {
        let c = composer()
        let u = UserWords()
        c.dictionary?.user = u
        _ = c.type("merhaba")
        _ = c.choose(1)
        XCTAssertEqual(1, u.usage("merhaba", "merheba"))
        _ = c.type("merhaba")
        XCTAssertEqual("merheba", c.candidates.first?.kurmanji)
    }

    func testWithoutDictionaryTextPassesThrough() {
        let c = Composer()
        _ = c.type("merhaba")
        XCTAssertTrue(c.candidates.isEmpty)
        XCTAssertEqual("merhaba", c.commitAll())
    }
}
