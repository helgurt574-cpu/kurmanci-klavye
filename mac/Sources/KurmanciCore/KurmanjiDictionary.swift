import Foundation

/// One candidate: [replaceLength] characters before the cursor get replaced by [kurmanji].
public struct Suggestion: Equatable {
    public enum Kind: Int { case phrase, exact, stem, prefix }

    public let turkish: String
    public let kurmanji: String
    /// Counted in Swift `Character`s.
    public let replaceLength: Int
    public let kind: Kind

    public init(turkish: String, kurmanji: String, replaceLength: Int, kind: Kind) {
        self.turkish = turkish
        self.kurmanji = kurmanji
        self.replaceLength = replaceLength
        self.kind = kind
    }
}

/// Offline Turkish → Kurmanji lookup, ported from the Android app's `Dictionary.kt`. Keys are normalized Turkish
/// words or multi-word phrases; values are one or more Kurmanji translations, best first. Entry order is the
/// priority used to rank prefix completions (the asset lists hand-curated, then most-confirmed entries first).
public final class KurmanjiDictionary {

    private let map: [String: [String]]
    /// Keys in priority order (index = rank, lower = better).
    private let keysByRank: [String]
    private let sortedIndex: SortedIndex
    /// Same keys indexed by their ASCII-folded form, so "guzel" finds "güzel".
    private let foldedIndex: SortedIndex
    /// Longest phrase length in words; [Translator] uses it for greedy phrase matching.
    public let longestPhrase: Int

    /// The user's pick counts (and own entries); they outrank bundled entries. Use from one thread only.
    public var user: UserWords?

    /// Keys sorted by UTF-8 bytes of their sort key, so every prefix is a contiguous run.
    private struct SortedIndex {
        let sortKeys: [String]
        let rank: [Int]

        init(_ keys: [String], _ sortKey: (String) -> String) {
            let k = keys.map(sortKey)
            let order = k.indices.sorted { a, b in
                k[a] == k[b] ? a < b : utf8Less(k[a], k[b])
            }
            sortKeys = order.map { k[$0] }
            rank = order
        }

        func lowerBound(_ s: String) -> Int {
            var lo = 0, hi = sortKeys.count
            while lo < hi {
                let mid = (lo + hi) / 2
                if utf8Less(sortKeys[mid], s) { lo = mid + 1 } else { hi = mid }
            }
            return lo
        }
    }

    /// Entries in priority order; duplicate keys must already be merged.
    public init(entries: [(String, [String])]) {
        var map = [String: [String]](minimumCapacity: entries.count)
        var keys: [String] = []
        keys.reserveCapacity(entries.count)
        for (k, v) in entries where map[k] == nil {
            map[k] = v
            keys.append(k)
        }
        self.map = map
        keysByRank = keys
        sortedIndex = SortedIndex(keys) { $0 }
        foldedIndex = SortedIndex(keys, KurmanjiDictionary.fold)
        longestPhrase = keys.map { $0.utf8.filter { $0 == 0x20 }.count + 1 }.max() ?? 1
    }

    public var size: Int { map.count }

    public func exact(_ turkish: String) -> [String] { map[Self.normalize(turkish)] ?? [] }

    /// Translations for an already-normalized key, falling back to its accent variants ("guzel" → "güzel").
    public func translations(_ key: String) -> [String] {
        let own = user?.lookup(key) ?? []
        let bundled = keysFor(key).first.flatMap { map[$0] } ?? []
        return own.isEmpty ? bundled : distinct(own + bundled)
    }

    public func isTurkishWord(_ normalized: String) -> Bool { !keysFor(normalized).isEmpty }

    /// Suggestions for the text right before the cursor. Phrase matches (spanning the last few words) come
    /// first, then exact word, then suffix-stripped stem, then prefix completions.
    public func suggest(_ textBeforeCursor: String, limit: Int = 12) -> [Suggestion] {
        let chars = Array(textBeforeCursor)
        let words = Self.trailingWords(chars, max: longestPhrase)
        guard let current = words.last else { return [] }
        var out: [Suggestion] = []
        var seen = Set<String>()

        func add(_ s: Suggestion) {
            guard out.count < limit else { return }
            if seen.insert(s.kurmanji.lowercased()).inserted { out.append(s) }
        }

        // Longest phrase first: "iyi akşamlar" beats "akşamlar".
        if words.count >= 2 {
            for n in stride(from: words.count, through: 2, by: -1) {
                let span = words.suffix(n)
                let first = span.first!
                let key = span.map { Self.normalize($0.text) }.joined(separator: " ")
                let replaceLen = chars.count - first.start
                user?.lookup(key).forEach {
                    add(Suggestion(turkish: key, kurmanji: Self.matchCase(first.text, $0), replaceLength: replaceLen, kind: .phrase))
                }
                for k in keysFor(key) {
                    map[k]!.forEach {
                        add(Suggestion(turkish: k, kurmanji: Self.matchCase(first.text, $0), replaceLength: replaceLen, kind: .phrase))
                    }
                }
            }
        }

        let norm = Self.normalize(current.text)
        let len = current.text.count
        user?.lookup(norm).forEach {
            add(Suggestion(turkish: norm, kurmanji: Self.matchCase(current.text, $0), replaceLength: len, kind: .exact))
        }
        for (i, k) in keysFor(norm).enumerated() {
            // The literal key keeps all its translations; accent-variants ("sac" → "saç") add their best few.
            let values = map[k]!
            for v in (k == norm || i == 0 ? values : Array(values.prefix(2))) {
                add(Suggestion(turkish: k, kurmanji: Self.matchCase(current.text, v), replaceLength: len, kind: .exact))
            }
        }

        // Only the longest stem that is a real entry; shorter ones are mostly accidental (geceler → geç).
        if let stem = Self.stems(norm).lazy.compactMap({ self.keysFor($0).first }).first {
            for v in map[stem]!.prefix(3) {
                add(Suggestion(turkish: stem, kurmanji: Self.matchCase(current.text, v), replaceLength: len, kind: .stem))
            }
        }

        if norm.count >= 2 {
            for key in prefixMatches(norm, limit: limit) where key != norm {
                if let v = map[key]?.first {
                    add(Suggestion(turkish: key, kurmanji: Self.matchCase(current.text, v), replaceLength: len, kind: .prefix))
                }
            }
        }
        // Learned preference: within each kind, the translations the user picks most come first (stable sort).
        guard let u = user else { return out }
        return stableSorted(out) { a, b in
            if a.kind != b.kind { return a.kind.rawValue < b.kind.rawValue }
            return u.usage(a.turkish, a.kurmanji) > u.usage(b.turkish, b.kurmanji)
        }
    }

    /// Dictionary keys for a normalized Turkish [key]: the key itself if present, plus — when the input has no
    /// Turkish-specific letters — every key that folds to it ("sac" → "sac", "saç"), best-ranked first.
    private func keysFor(_ key: String) -> [String] {
        var res: [String] = []
        if map[key] != nil { res.append(key) }
        if Self.fold(key) != key { return res }
        var i = foldedIndex.lowerBound(key)
        var ranks: [Int] = []
        while i < foldedIndex.sortKeys.count && foldedIndex.sortKeys[i] == key {
            ranks.append(foldedIndex.rank[i])
            i += 1
        }
        for r in ranks.sorted() where keysByRank[r] != key { res.append(keysByRank[r]) }
        return res
    }

    /// Single-word keys starting with [prefix], best-ranked first. ASCII-only input also matches accented keys
    /// ("guz" → "güzel"). Scans at most [maxPrefixScan] keys.
    private func prefixMatches(_ prefix: String, limit: Int) -> [String] {
        let index = Self.fold(prefix) == prefix ? foldedIndex : sortedIndex
        var i = index.lowerBound(prefix)
        var ranks: [Int] = []
        while i < index.sortKeys.count && ranks.count < Self.maxPrefixScan && index.sortKeys[i].utf8.starts(with: prefix.utf8) {
            if !index.sortKeys[i].contains(" ") { ranks.append(index.rank[i]) }
            i += 1
        }
        return ranks.sorted().prefix(limit).map { keysByRank[$0] }
    }

    public struct Word: Equatable {
        public let text: String
        /// Character offset in the text.
        public let start: Int
    }

    // MARK: - Static helpers

    static let maxPrefixScan = 3000

    public static let tr = Locale(identifier: "tr")

    public static func normalize<S: StringProtocol>(_ s: S) -> String {
        let t = trimmed(s)
        // Most input is already lowercase; Foundation's locale-aware lowercasing is comparatively slow.
        if !t.unicodeScalars.contains(where: { $0.properties.changesWhenLowercased }) { return String(t) }
        return t.lowercased(with: tr)
    }

    /// Strips leading and trailing whitespace without going through Foundation (hot in [parse]).
    static func trimmed<S: StringProtocol>(_ s: S) -> S.SubSequence {
        var start = s.startIndex, end = s.endIndex
        while start < end && s[start].isWhitespace { start = s.index(after: start) }
        while end > start && s[s.index(before: end)].isWhitespace { end = s.index(before: end) }
        return s[start..<end]
    }

    private static let foldMap: [Character: Character] = [
        "ç": "c", "ğ": "g", "ı": "i", "ö": "o", "ş": "s", "ü": "u", "â": "a", "î": "i", "û": "u",
    ]

    /// Strips Turkish-specific letters: "güzel" → "guzel". Input must already be lowercase.
    public static func fold(_ s: String) -> String {
        if s.utf8.allSatisfy({ $0 < 0x80 }) { return s }
        return String(s.map { foldMap[$0] ?? $0 })
    }

    public static func isWordChar(_ c: Character) -> Bool { c.isLetter || c == "'" || c == "’" }

    /// Up to [max] words ending exactly at the end of [text]; empty if the cursor isn't right after a word.
    public static func trailingWords(_ text: String, max: Int) -> [Word] { trailingWords(Array(text), max: max) }

    static func trailingWords(_ text: [Character], max: Int) -> [Word] {
        var res: [Word] = []
        var end = text.count
        while res.count < max {
            var start = end
            while start > 0 && isWordChar(text[start - 1]) { start -= 1 }
            if start == end { break }
            var word = String(text[start..<end])
            while let last = word.last, last == "'" || last == "’" { word.removeLast() }
            res.insert(Word(text: word, start: start), at: 0)
            // Only continue across a single plain space.
            if start >= 2 && text[start - 1] == " " && isWordChar(text[start - 2]) { end = start - 1 } else { break }
        }
        return res
    }

    /// "Merhaba" → "Silav", "MERHABA" → "SILAV". Kurmanji uses the standard Latin i/I pair.
    public static func matchCase(_ original: String, _ translation: String) -> String {
        if original.count > 1 && original.allSatisfy({ !$0.isLetter || $0.isUppercase }) {
            return translation.uppercased()
        }
        if original.first?.isUppercase == true { return capitalizeFirst(translation) }
        return translation
    }

    public static func capitalizeFirst(_ s: String) -> String {
        guard let f = s.first else { return s }
        return f.uppercased() + s.dropFirst()
    }

    // Includes ASCII-folded variants so "evlerinde" and "cocuklari" both stem.
    private static let suffixes: [String] = {
        let base = [
            "lerimiz", "larımız", "leriniz", "larınız", "lerinden", "larından",
            "imiz", "ımız", "umuz", "ümüz", "iniz", "ınız", "unuz", "ünüz",
            "lerde", "larda", "lerden", "lardan", "lere", "lara", "leri", "ları", "ler", "lar",
            "yorum", "yorsun", "yoruz", "yorlar", "iyor", "ıyor", "uyor", "üyor", "yor",
            "acak", "ecek", "mak", "mek", "dım", "dim", "dum", "düm", "tım", "tim", "tum", "tüm",
            "dı", "di", "du", "dü", "tı", "ti", "tu", "tü", "miş", "mış", "muş", "müş",
            "nin", "nın", "nun", "nün", "den", "dan", "ten", "tan", "de", "da", "te", "ta",
            "yi", "yı", "yu", "yü", "ye", "ya", "le", "la", "im", "ım", "um", "üm",
            "in", "ın", "un", "ün", "i", "ı", "u", "ü", "e", "a", "m", "n",
        ]
        return distinct(base + base.map(fold))
    }()

    static let softened: [Character: Character] = ["b": "p", "c": "ç", "d": "t", "ğ": "k", "g": "k"]

    /// Naive Turkish stemming: strip up to two suffix layers, undo consonant softening (kitabı → kitap).
    public static func stems(_ word: String) -> [String] {
        var res: [String] = []
        var seen = Set<String>()
        func put(_ s: String) { if seen.insert(s).inserted { res.append(s) } }
        func strip(_ w: String, _ depth: Int) {
            let wc = w.count
            for suf in suffixes {
                if wc - suf.count < 2 || !w.hasSuffix(suf) { continue }
                let stem = String(w.dropLast(suf.count))
                put(stem)
                if let s = softened[stem.last!] { put(String(stem.dropLast()) + String(s)) }
                if depth < 2 { strip(stem, depth + 1) }
            }
        }
        strip(word, 1)
        res.removeAll { $0 == word }
        return stableSorted(res) { $0.count > $1.count }
    }

    /// TSV, one entry per line: `turkish<TAB>kurmanji1|kurmanji2`. Lines starting with `#` are comments.
    /// Duplicate Turkish keys are merged.
    public static func parse(_ data: Data) -> KurmanjiDictionary {
        var order: [String] = []
        var map: [String: [String]] = [:]
        // Works on bytes: tab, '|', '#' and the whitespace trimmed (bytes <= 0x20, like Kotlin's trim) are all
        // ASCII, so they never occur inside a multi-byte UTF-8 letter. Character-level scanning is much slower.
        data.withUnsafeBytes { (raw: UnsafeRawBufferPointer) in
            let bytes = raw.bindMemory(to: UInt8.self)
            func text(_ from: Int, _ to: Int) -> String? {
                var a = from, b = to
                while a < b && bytes[a] <= 0x20 { a += 1 }
                while b > a && bytes[b - 1] <= 0x20 { b -= 1 }
                return a < b ? String(decoding: UnsafeBufferPointer(rebasing: bytes[a..<b]), as: UTF8.self) : nil
            }
            var lineStart = 0
            for i in 0...bytes.count {
                guard i == bytes.count || bytes[i] == 0x0A else { continue }
                defer { lineStart = i + 1 }
                var a = lineStart
                while a < i && bytes[a] <= 0x20 { a += 1 }
                guard a < i, bytes[a] != 0x23 /* # */ else { continue }
                var tab = a
                while tab < i && bytes[tab] != 0x09 { tab += 1 }
                guard tab > a, tab < i, let rawKey = text(a, tab) else { continue }
                var key = normalize(rawKey)
                if key.contains("  ") {
                    key = key.split(whereSeparator: { $0.isWhitespace }).joined(separator: " ")
                }
                var values: [String] = []
                var v = tab + 1
                for j in (tab + 1)...i where j == i || bytes[j] == 0x7C /* | */ {
                    if let value = text(v, j), !values.contains(value) { values.append(value) }
                    v = j + 1
                }
                if values.isEmpty { continue }
                if var list = map[key] {
                    for v in values where !list.contains(v) { list.append(v) }
                    map[key] = list
                } else {
                    order.append(key)
                    map[key] = values
                }
            }
        }
        return KurmanjiDictionary(entries: order.map { ($0, map[$0]!) })
    }

    public static func parse(_ text: String) -> KurmanjiDictionary { parse(Data(text.utf8)) }
}

// MARK: - Small utilities shared by the module

func utf8Less(_ a: String, _ b: String) -> Bool { a.utf8.lexicographicallyPrecedes(b.utf8) }

func distinct<T: Hashable>(_ xs: [T]) -> [T] {
    var seen = Set<T>()
    return xs.filter { seen.insert($0).inserted }
}

/// Stable sort (Kotlin's sortedBy is stable; Swift's sort makes no such promise).
func stableSorted<T>(_ xs: [T], by less: (T, T) -> Bool) -> [T] {
    xs.enumerated().sorted { a, b in
        if less(a.element, b.element) { return true }
        if less(b.element, a.element) { return false }
        return a.offset < b.offset
    }.map { $0.element }
}
