import Foundation

/// The user's own Turkish → Kurmanji entries and how often each suggestion was picked, ported from
/// `UserWords.kt`. User entries always rank above bundled ones, and among suggestions of the same kind,
/// more-picked translations come first. The two text forms are what gets persisted.
public final class UserWords {

    /// Normalized Turkish keys in insertion order; each maps to its translations, most recently added first.
    private var keys: [String] = []
    private var entries: [String: [String]] = [:]
    private var usageCounts: [String: Int] = [:]

    static let maxUsage = 500

    public init(entriesText: String = "", usageText: String = "") {
        for line in entriesText.split(separator: "\n", omittingEmptySubsequences: false) {
            let parts = line.split(separator: "\t", omittingEmptySubsequences: false).map(String.init)
            if parts.count == 2 && !isBlank(parts[0]) && !isBlank(parts[1]) {
                if entries[parts[0]] == nil { keys.append(parts[0]) }
                entries[parts[0], default: []].append(parts[1])
            }
        }
        for line in usageText.split(separator: "\n", omittingEmptySubsequences: false) {
            let parts = line.split(separator: "\t", omittingEmptySubsequences: false).map(String.init)
            if parts.count == 3, let n = Int(parts[2]) { usageCounts[Self.usageKey(parts[0], parts[1])] = n }
        }
    }

    public var isEmpty: Bool { entries.isEmpty }

    public func lookup(_ normalizedTurkish: String) -> [String] { entries[normalizedTurkish] ?? [] }

    /// All entries as (Turkish, Kurmanji) pairs.
    public func all() -> [(String, String)] { keys.flatMap { tr in entries[tr]!.map { (tr, $0) } } }

    public func add(_ turkish: String, _ kurmanji: String) {
        let tr = collapseSpaces(KurmanjiDictionary.normalize(turkish))
        let ku = collapseSpaces(kurmanji.trimmingCharacters(in: .whitespacesAndNewlines))
        if tr.isEmpty || ku.isEmpty { return }
        if entries[tr] == nil { keys.append(tr) }
        var list = entries[tr] ?? []
        list.removeAll { $0.lowercased() == ku.lowercased() }
        list.insert(ku, at: 0)
        entries[tr] = list
    }

    public func remove(_ turkish: String, _ kurmanji: String) {
        let tr = KurmanjiDictionary.normalize(turkish)
        guard var list = entries[tr] else { return }
        list.removeAll { $0 == kurmanji }
        if list.isEmpty {
            entries[tr] = nil
            keys.removeAll { $0 == tr }
        } else {
            entries[tr] = list
        }
    }

    public func recordUse(_ turkish: String, _ kurmanji: String) {
        usageCounts[Self.usageKey(turkish, kurmanji), default: 0] += 1
    }

    public func usage(_ turkish: String, _ kurmanji: String) -> Int { usageCounts[Self.usageKey(turkish, kurmanji)] ?? 0 }

    public func entriesText() -> String { all().map { "\($0.0)\t\($0.1)" }.joined(separator: "\n") }

    /// Keeps the [maxUsage] most-picked pairs so the stored string stays small.
    public func usageText() -> String {
        usageCounts.sorted { $0.value != $1.value ? $0.value > $1.value : $0.key < $1.key }
            .prefix(Self.maxUsage)
            .map { "\($0.key)\t\($0.value)" }
            .joined(separator: "\n")
    }

    private static func usageKey(_ turkish: String, _ kurmanji: String) -> String {
        KurmanjiDictionary.normalize(turkish) + "\t" + kurmanji.lowercased()
    }

    private func isBlank(_ s: String) -> Bool { s.allSatisfy { $0.isWhitespace } }

    private func collapseSpaces(_ s: String) -> String {
        s.split(whereSeparator: { $0.isWhitespace }).joined(separator: " ")
    }
}
