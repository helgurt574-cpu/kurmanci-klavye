import Foundation
@testable import KurmanciCore

/// The Android app's bundled dictionary, which the Mac build copies into the input method.
let assetURL = URL(fileURLWithPath: #filePath)
    .deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent()
    .appendingPathComponent("app/src/main/assets/tr_ku.tsv")

let realDictionary: KurmanjiDictionary = KurmanjiDictionary.parse(try! Data(contentsOf: assetURL))
