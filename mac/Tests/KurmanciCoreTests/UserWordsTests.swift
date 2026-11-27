import XCTest
@testable import KurmanciCore

/// Port of the user-word part of the Android app's KurmanjiSideTest.kt. (Kurmanji completion and Kurmanji →
/// Turkish are not part of the Mac version.)
final class UserWordsTests: XCTestCase {

    private func dict() -> KurmanjiDictionary {
        KurmanjiDictionary.parse("""
            merhaba\tsilav|merheba
            ev\tmal
            güzel\txweşik|delal
            çok\tgelek|pir
            iyi geceler\tşev baş
            kitap\tpirtûk|kitêb
            """)
    }

    func testUserEntryComesFirst() {
        let d = dict()
        let u = UserWords()
        u.add("Merhaba", "rojbaş")
        d.user = u
        XCTAssertEqual(["rojbaş", "silav", "merheba"], d.suggest("merhaba").map(\.kurmanji))
        XCTAssertEqual("rojbaş", d.translations("merhaba").first)
    }

    func testUserPhraseEntry() {
        let d = dict()
        let u = UserWords()
        u.add("nasıl gidiyor", "çawa diçe")
        d.user = u
        XCTAssertEqual("çawa diçe", d.suggest("nasıl gidiyor").first?.kurmanji)
    }

    func testPickedTranslationRisesWithinItsKind() {
        let d = dict()
        let u = UserWords()
        d.user = u
        XCTAssertEqual("xweşik", d.suggest("güzel").first?.kurmanji)
        u.recordUse("güzel", "delal")
        XCTAssertEqual(["delal", "xweşik"], d.suggest("güzel").map(\.kurmanji))
    }

    func testUserWordsRoundTripThroughText() {
        let u = UserWords()
        u.add("Merhaba", "rojbaş")
        u.add("çay", "çay")
        u.recordUse("merhaba", "rojbaş")
        u.recordUse("merhaba", "rojbaş")
        let back = UserWords(entriesText: u.entriesText(), usageText: u.usageText())
        XCTAssertEqual(u.all().map { "\($0.0)=\($0.1)" }, back.all().map { "\($0.0)=\($0.1)" })
        XCTAssertEqual(2, back.usage("Merhaba", "Rojbaş"))
        back.remove("merhaba", "rojbaş")
        XCTAssertEqual(["çay=çay"], back.all().map { "\($0.0)=\($0.1)" })
    }
}
