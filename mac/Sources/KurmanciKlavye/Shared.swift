import Cocoa
import Carbon
import KurmanciCore

/// Process-wide state shared by all input controllers (one controller per text field client). Main thread only.
enum Shared {
    /// Nil until the bundled dictionary has been parsed on a background thread (well under a second).
    static var dictionary: KurmanjiDictionary?
    static let panel = CandidatePanel()

    private static let usageKey = "usage"

    static func loadDictionary() {
        DispatchQueue.global(qos: .userInitiated).async {
            guard let url = Bundle.main.url(forResource: "tr_ku", withExtension: "tsv"),
                  let data = try? Data(contentsOf: url) else {
                NSLog("Kurmanci: tr_ku.tsv missing from the bundle")
                return
            }
            let start = Date()
            let dict = KurmanjiDictionary.parse(data)
            NSLog("Kurmanci: dictionary %d entries in %.0f ms", dict.size, Date().timeIntervalSince(start) * 1000)
            DispatchQueue.main.async {
                dict.user = UserWords(usageText: UserDefaults.standard.string(forKey: usageKey) ?? "")
                dictionary = dict
            }
        }
    }

    /// Persists which translations the user picks, so they rank first next time.
    static func saveUsage() {
        guard let user = dictionary?.user else { return }
        UserDefaults.standard.set(user.usageText(), forKey: usageKey)
    }
}

/// The keys are read through a Turkish keyboard layout the user has enabled, so ç ğ ı ö ş ü come out right.
enum KeyboardLayout {
    /// `defaults write com.klavye.inputmethod.Kurmanci keyboardLayout <ID>` picks one explicitly.
    static var turkishLayoutID: String? {
        if let id = UserDefaults.standard.string(forKey: "keyboardLayout"), !id.isEmpty { return id }
        let filter = [kTISPropertyInputSourceType as String: kTISTypeKeyboardLayout as String] as CFDictionary
        guard let list = TISCreateInputSourceList(filter, false)?.takeRetainedValue() as? [TISInputSource] else {
            return nil
        }
        for source in list {
            let langs = property(source, kTISPropertyInputSourceLanguages) as? [String] ?? []
            if langs.first == "tr", let id = property(source, kTISPropertyInputSourceID) as? String { return id }
        }
        return nil
    }

    static func property(_ source: TISInputSource, _ key: CFString) -> AnyObject? {
        guard let p = TISGetInputSourceProperty(source, key) else { return nil }
        return Unmanaged<AnyObject>.fromOpaque(p).takeUnretainedValue()
    }
}

enum InputSourceRegistration {
    static func registerAndEnable() {
        let url = Bundle.main.bundleURL as CFURL
        let status = TISRegisterInputSource(url)
        print("TISRegisterInputSource: \(status)")
        guard let bundleID = Bundle.main.bundleIdentifier else { return }
        let filter = [kTISPropertyBundleID as String: bundleID] as CFDictionary
        guard let list = TISCreateInputSourceList(filter, true)?.takeRetainedValue() as? [TISInputSource] else {
            print("Girdi kaynağı bulunamadı; oturumu kapatıp açın.")
            return
        }
        for source in list {
            let id = KeyboardLayout.property(source, kTISPropertyInputSourceID) as? String ?? "?"
            let capable = (KeyboardLayout.property(source, kTISPropertyInputSourceIsEnableCapable) as? Bool) ?? false
            if capable { print("Etkinleştirildi: \(id) (\(TISEnableInputSource(source)))") }
        }
    }
}
