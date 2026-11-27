import Foundation

/// One row in the candidate window.
public struct Candidate: Equatable {
    public let suggestion: Suggestion
    /// The whole-sentence "⇄" translation, as opposed to a word or phrase.
    public let isSentence: Bool

    public var kurmanji: String { suggestion.kurmanji }
}

/// The input method's state, independent of InputMethodKit so it can be unit-tested.
///
/// The Turkish being typed is kept in [buffer], which the app shows as marked (underlined) text, up to the end of
/// the current sentence. [candidates] are recomputed on every change: first the "⇄" translation of the sentence
/// (2+ words), then the word/phrase suggestions for the text before the cursor, as on Android. Choosing one
/// replaces its Turkish with Kurmanji and hands the whole buffer to the app ("commits" it).
public final class Composer {

    public private(set) var buffer = ""
    public private(set) var candidates: [Candidate] = []
    /// Highlighted candidate, set only once the user moves through the list with the arrow keys.
    public private(set) var highlighted: Int?

    public var dictionary: KurmanjiDictionary? {
        didSet {
            translator = dictionary.map(Translator.init)
            refresh()
        }
    }
    private var translator: Translator?

    /// Candidate rows are numbered 1–9.
    public static let maxCandidates = 9
    /// Very long marked text behaves badly in some apps; past this, the buffer is handed over as it is.
    static let maxBuffer = 400

    public init(dictionary: KurmanjiDictionary? = nil) {
        self.dictionary = dictionary
        translator = dictionary.map(Translator.init)
    }

    /// Appends typed text. Returns text to commit first: the previous sentence, once a new one starts after
    /// ". " (so the "⇄" candidate stays available until the user moves on).
    public func type(_ text: String) -> String {
        var commit = ""
        if text.contains(where: KurmanjiDictionary.isWordChar), startsNewSentence() || buffer.count > Self.maxBuffer {
            commit = takeBuffer()
        }
        buffer += text
        refresh()
        return commit
    }

    /// Deletes the last typed character. Returns false if there was nothing to delete (the app should handle it).
    public func backspace() -> Bool {
        guard !buffer.isEmpty else { return false }
        buffer.removeLast()
        refresh()
        return true
    }

    /// Replaces the Turkish of candidate [index] with its Kurmanji and returns the text to commit.
    public func choose(_ index: Int) -> String? {
        guard candidates.indices.contains(index) else { return nil }
        let c = candidates[index]
        let s = c.suggestion
        buffer = String(buffer.dropLast(s.replaceLength)) + s.kurmanji + " "
        // Learn the preference: next time this translation ranks first among its kind.
        if !c.isSentence { dictionary?.user?.recordUse(s.turkish, s.kurmanji) }
        return takeBuffer()
    }

    /// Hands over the buffer unchanged (Turkish), e.g. on Return, Escape or a click elsewhere.
    public func commitAll() -> String { takeBuffer() }

    public func moveHighlight(by delta: Int) {
        guard !candidates.isEmpty else { return }
        let next = (highlighted ?? -1) + delta
        highlighted = min(max(next, 0), candidates.count - 1)
    }

    /// Digits pick a candidate only right after a word; after a space or punctuation they are typed.
    public var digitsSelect: Bool {
        !candidates.isEmpty && (highlighted != nil || buffer.last.map(KurmanjiDictionary.isWordChar) == true)
    }

    private func takeBuffer() -> String {
        let b = buffer
        buffer = ""
        refresh()
        return b
    }

    private func startsNewSentence() -> Bool {
        guard let last = buffer.last, last.isWhitespace else { return false }
        let trimmed = buffer.trimmingCharacters(in: .whitespacesAndNewlines)
        return trimmed.last.map { ".!?…".contains($0) } == true
    }

    private func refresh() {
        highlighted = nil
        guard let dict = dictionary, !buffer.isEmpty else {
            candidates = []
            return
        }
        var list: [Candidate] = []
        if let s = sentenceSuggestion() { list.append(Candidate(suggestion: s, isSentence: true)) }
        for s in dict.suggest(buffer) where !list.contains(where: { $0.kurmanji.lowercased() == s.kurmanji.lowercased() }) {
            list.append(Candidate(suggestion: s, isSentence: false))
        }
        candidates = Array(list.prefix(Self.maxCandidates))
    }

    /// Whole-sentence translation for the sentence ending at the cursor, if it has 2+ words.
    private func sentenceSuggestion() -> Suggestion? {
        guard let translator = translator, let start = Translator.sentenceStart(buffer) else { return nil }
        let chars = Array(buffer)
        var original = String(chars[start...])
        while let l = original.last, l.isWhitespace { original.removeLast() }
        guard Translator.wordCount(original) >= 2 else { return nil }
        let translated = translator.translate(original)
        if translated.lowercased() == original.lowercased() { return nil }
        return Suggestion(turkish: original, kurmanji: translated, replaceLength: chars.count - start, kind: .phrase)
    }
}
