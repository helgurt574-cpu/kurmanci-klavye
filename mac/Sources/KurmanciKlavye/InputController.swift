import Cocoa
import InputMethodKit
import KurmanciCore

/// One instance per text field the user types into. The Turkish being typed stays as marked (underlined) text
/// until the user picks a Kurmanji candidate (1–9, Tab, arrows + Return, or a click), or keeps the Turkish with
/// Return, Escape, or by moving the cursor.
@objc(KurmanciInputController)
final class KurmanciInputController: IMKInputController {

    private let composer = Composer()
    /// Whether the app currently shows marked text from us (inserting text replaces it).
    private var hasMarkedText = false

    override func activateServer(_ sender: Any!) {
        super.activateServer(sender)
        if let id = KeyboardLayout.turkishLayoutID, let client = sender as? IMKTextInput {
            client.overrideKeyboard(withKeyboardNamed: id)
        }
    }

    override func deactivateServer(_ sender: Any!) {
        commit(sender as? IMKTextInput)
        Shared.panel.hide()
        super.deactivateServer(sender)
    }

    /// The app asks for the marked text to be finished (click elsewhere, window switch...): keep the Turkish.
    override func commitComposition(_ sender: Any!) {
        commit(sender as? IMKTextInput)
    }

    override func handle(_ event: NSEvent!, client sender: Any!) -> Bool {
        guard let event = event, event.type == .keyDown, let client = sender as? IMKTextInput else { return false }
        if composer.dictionary == nil, let dict = Shared.dictionary { composer.dictionary = dict }

        let mods = event.modifierFlags.intersection(.deviceIndependentFlagsMask)
        if mods.contains(.command) || mods.contains(.control) {
            commit(client)
            return false
        }
        let hasCandidates = !composer.candidates.isEmpty
        switch event.keyCode {
        case 51: // delete (backspace)
            guard composer.backspace() else { return false }
            update(client)
            return true
        case 36, 76: // return, keypad enter
            if let h = composer.highlighted {
                choose(h, client)
                return true
            }
            commit(client)
            return false
        case 48: // tab: takes the highlighted or first candidate
            if hasCandidates {
                choose(composer.highlighted ?? 0, client)
                return true
            }
            commit(client)
            return false
        case 53: // escape: keep the Turkish
            guard !composer.buffer.isEmpty else { return false }
            commit(client)
            return true
        case 125, 126: // down, up
            if hasCandidates {
                composer.moveHighlight(by: event.keyCode == 125 ? 1 : -1)
                showCandidates(client)
                return true
            }
            commit(client)
            return false
        default:
            break
        }

        guard let chars = event.characters, !chars.isEmpty else {
            commit(client)
            return false
        }
        if chars.count == 1, let ch = chars.first, ch.isASCII, let n = ch.wholeNumberValue, (1...9).contains(n),
           composer.digitsSelect, n <= composer.candidates.count {
            choose(n - 1, client)
            return true
        }
        // Arrows, function keys, forward delete and other control keys: finish the word and let the app act.
        let control = chars.unicodeScalars.contains { $0.value < 0x20 || $0.value == 0x7F || (0xF700...0xF8FF).contains($0.value) }
        if control {
            commit(client)
            return false
        }
        // Spaces, digits and punctuation with nothing being composed go straight to the app.
        if composer.buffer.isEmpty && !chars.contains(where: KurmanjiDictionary.isWordChar) {
            insert(chars, client)
            return true
        }
        let finished = composer.type(chars)
        if !finished.isEmpty { insert(finished, client) }
        update(client)
        return true
    }

    override func menu() -> NSMenu! {
        let menu = NSMenu(title: "Kurmancî Klavye")
        menu.addItem(NSMenuItem(title: "Kurmancî Klavye hakkında…", action: #selector(showAbout(_:)), keyEquivalent: ""))
        return menu
    }

    @objc func showAbout(_ sender: Any?) {
        NSApp.activate(ignoringOtherApps: true)
        let alert = NSAlert()
        alert.messageText = "Kurmancî Klavye"
        alert.informativeText = """
            Türkçe yazın, Kurmancî gönderin. Tamamen çevrimdışı çalışır.

            1–9, Tab veya ok tuşları + Return: öneriyi seç
            Return / Esc: Türkçeyi olduğu gibi bırak

            Sözlük verisinin çoğu Vikisözlük'ten (Wiktionary) alınmıştır ve \
            CC BY-SA 4.0 lisansı altındadır: https://creativecommons.org/licenses/by-sa/4.0/
            Kaynak kod: https://github.com/helgurt574-cpu/kurmanci-klavye (MIT)
            """
        alert.runModal()
    }

    // MARK: - Helpers

    private func choose(_ index: Int, _ client: IMKTextInput) {
        guard let text = composer.choose(index) else { return }
        insert(text, client)
        Shared.saveUsage()
        update(client)
    }

    private func commit(_ client: IMKTextInput?) {
        guard let client = client, !composer.buffer.isEmpty else { return }
        insert(composer.commitAll(), client)
        update(client)
    }

    private func insert(_ text: String, _ client: IMKTextInput) {
        client.insertText(text, replacementRange: NSRange(location: NSNotFound, length: 0))
        hasMarkedText = false
    }

    /// Shows the buffer as marked text and refreshes the candidate window.
    private func update(_ client: IMKTextInput) {
        let buffer = composer.buffer
        if buffer.isEmpty && !hasMarkedText {
            showCandidates(client)
            return
        }
        hasMarkedText = !buffer.isEmpty
        let marked = NSAttributedString(string: buffer, attributes: [
            .underlineStyle: NSUnderlineStyle.single.rawValue,
            .markedClauseSegment: 0,
        ])
        client.setMarkedText(marked,
                             selectionRange: NSRange(location: (buffer as NSString).length, length: 0),
                             replacementRange: NSRange(location: NSNotFound, length: 0))
        showCandidates(client)
    }

    private func showCandidates(_ client: IMKTextInput) {
        guard !composer.candidates.isEmpty else {
            Shared.panel.hide()
            return
        }
        var rect = NSRect.zero
        _ = client.attributes(forCharacterIndex: 0, lineHeightRectangle: &rect)
        Shared.panel.onSelect = { [weak self] index in
            guard let self = self, let client = self.client() else { return }
            self.choose(index, client)
        }
        Shared.panel.show(composer.candidates, highlighted: composer.highlighted, below: rect)
    }
}
