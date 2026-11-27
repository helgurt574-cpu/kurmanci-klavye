import Cocoa
import InputMethodKit
import KurmanciCore

/// What can be checked about the installed bundle without a user typing: Info.plist wiring, the controller class
/// InputMethodKit will instantiate, and the bundled dictionary.
enum SelfTest {
    static func run() -> Bool {
        var ok = true
        func check(_ name: String, _ pass: Bool) {
            print((pass ? "ok   " : "FAIL ") + name)
            ok = ok && pass
        }
        let info = Bundle.main.infoDictionary ?? [:]
        check("bundle id contains .inputmethod.", Bundle.main.bundleIdentifier?.contains(".inputmethod.") == true)
        check("InputMethodConnectionName set", (info["InputMethodConnectionName"] as? String)?.isEmpty == false)
        let className = info["InputMethodServerControllerClass"] as? String ?? ""
        let cls: AnyClass? = NSClassFromString(className)
        check("controller class \(className) found", cls != nil)
        check("controller is an IMKInputController", cls?.isSubclass(of: IMKInputController.self) == true)
        let icon = info["tsInputMethodIconFileKey"] as? String ?? ""
        check("icon \(icon) present", Bundle.main.path(forResource: icon, ofType: nil) != nil)

        guard let url = Bundle.main.url(forResource: "tr_ku", withExtension: "tsv"),
              let data = try? Data(contentsOf: url) else {
            check("tr_ku.tsv present", false)
            return false
        }
        let start = Date()
        let dict = KurmanjiDictionary.parse(data)
        print(String(format: "     dictionary: %d entries, %.0f ms", dict.size, Date().timeIntervalSince(start) * 1000))
        check("dictionary loaded", dict.size > 50_000)
        let composer = Composer(dictionary: dict)
        _ = composer.type("Ben eve gidiyorum")
        let first = composer.candidates.first?.kurmanji ?? "-"
        print("     Ben eve gidiyorum → \(first)")
        check("sentence candidate", first == "Ez diçim malê")
        return ok
    }
}
