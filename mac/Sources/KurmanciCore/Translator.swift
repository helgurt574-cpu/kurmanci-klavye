import Foundation

/// Rough, fully offline Turkish → Kurmanji sentence translation built on [KurmanjiDictionary], ported from the
/// Android app's `Translator.kt`.
///
/// Both languages are SOV, so word order is mostly kept. On top of word/phrase lookup it handles:
///  - Turkish verb conjugation for the verbs in [verbs] (progressive/aorist → Kurmanji present, past, future,
///    imperative, negation), producing the subject pronoun Turkish drops, with ergative "min kir" for transitive past;
///  - noun case suffixes → prepositions (evde → li mal, okuldan → ji dibistan) and 1st/2nd person possessives;
///  - motion verbs put a dative destination after the verb: "eve gidiyorum" → "diçim malê";
///  - question particles (mi/mı/mu/mü, with person: "geliyor musun").
/// It does not know Kurmanji noun gender, so ezafe/oblique endings default to masculine. Output is a gist.
public final class Translator {

    private let dict: KurmanjiDictionary

    public init(_ dict: KurmanjiDictionary) { self.dict = dict }

    /// [goal]: for a Turkish dative ("eve"), its Kurmanji goal form ("malê"), used if a motion verb follows.
    private final class Piece {
        var text: String
        let word: Bool
        var pronoun: Int?
        let goal: String?

        init(_ text: String, word: Bool, pronoun: Int? = nil, goal: String? = nil) {
            self.text = text
            self.word = word
            self.pronoun = pronoun
            self.goal = goal
        }
    }

    public func translate(_ sentence: String) -> String {
        typealias D = KurmanjiDictionary
        let tokens = Self.tokenize(sentence)
        var out: [Piece] = []
        var clauseStart = 0
        var question = false
        let firstWord = tokens.firstIndex(where: Self.isWord) ?? -1
        var i = 0
        while i < tokens.count {
            let tok = tokens[i]
            if !Self.isWord(tok) {
                out.append(Piece(tok, word: false))
                if tok.contains(where: { $0 == "," || $0 == ";" || $0 == ":" }) { clauseStart = out.count }
                i += 1
                continue
            }
            let norm = D.normalize(tok)
            let keepCase = i != firstWord // sentence-initial capitalization is redone at the end

            // Greedy phrase match over words separated by single spaces.
            if let phrase = matchPhrase(tokens, i) {
                out.append(Piece(phrase.0, word: true))
                i = phrase.1
                continue
            }

            if Self.groups(Self.question, norm) != nil {
                question = true
                i += 1
                continue
            }

            if let pronoun = Self.pronouns[norm] {
                out.append(Piece(Self.nom[pronoun], word: true, pronoun: pronoun))
                i += 1
                continue
            }

            // A following "musun/misiniz" carries the person for a 3sg-looking verb ("geliyor musun").
            var qPerson: Int?
            if i + 2 < tokens.count && tokens[i + 1] == " ", let g = Self.groups(Self.question, D.normalize(tokens[i + 2])) {
                qPerson = Self.questionPerson(g[1])
            }

            let verb = analyzeVerb(norm)
            let exact = dict.translations(norm)
            if let verb = verb, !(verb.weak && !exact.isEmpty) {
                var f = verb
                f.person = qPerson ?? verb.person
                emitVerb(&out, clauseStart, f)
                i += 1
                continue
            }
            if let first = exact.first {
                out.append(Piece(keepCase ? D.matchCase(tok, first) : first, word: true))
                // "ve/ama/çünkü" start a new clause, which gets its own subject: "min xwend û min hez kir".
                if Self.conjunctions.contains(norm) { clauseStart = out.count }
                i += 1
                continue
            }
            if let noun = analyzeNoun(norm) {
                out.append(Piece(keepCase ? D.matchCase(tok, noun.text) : noun.text, word: true, goal: noun.goal))
                i += 1
                continue
            }
            let stem = D.stems(norm).lazy.compactMap { self.dict.translations($0).first }.first
            out.append(Piece(stem.map { keepCase ? D.matchCase(tok, $0) : $0 } ?? tok, word: true))
            i += 1
        }

        var result = Self.collapseSpaces(out.map { $0.text }.joined())
        result = result.trimmingCharacters(in: .whitespacesAndNewlines)
        result = Self.removeSpaceBeforePunct(result)
        if question && !result.hasSuffix("?") {
            while let last = result.last, last == "." || last == "!" { result.removeLast() }
            result += "?"
        }
        let trimmedStart = sentence.drop { $0.isWhitespace }
        if trimmedStart.first?.isUppercase == true { result = D.capitalizeFirst(result) }
        return result
    }

    /// Longest dictionary phrase starting at token [start]; returns (translation, next token index).
    private func matchPhrase(_ tokens: [String], _ start: Int) -> (String, Int)? {
        var words: [String] = []
        var j = start
        while words.count < dict.longestPhrase && j < tokens.count && Self.isWord(tokens[j]) {
            words.append(KurmanjiDictionary.normalize(tokens[j]))
            if j + 2 < tokens.count && tokens[j + 1] == " " && Self.isWord(tokens[j + 2]) { j += 2 } else { break }
        }
        guard words.count >= 2 else { return nil }
        for n in stride(from: words.count, through: 2, by: -1) {
            if let t = dict.translations(words.prefix(n).joined(separator: " ")).first {
                return (t, start + 2 * (n - 1) + 1)
            }
        }
        return nil
    }

    // MARK: - Verbs

    public enum Tense { case present, past, future, imperative }

    struct VerbForm {
        let verb: Verb
        let tense: Tense
        let negative: Bool
        var person: Int
        let weak: Bool
    }

    /// A Kurmanji verb. [pre] is a preverb or light-verb part ("ve", "hez "), [pres]/[past] are the bare stems.
    /// Irregular full forms override the regular di-/na-/bi- prefixing.
    public struct Verb {
        public let tr: String
        public let pre: String
        public let pres: String
        public let past: String
        public let trans: Bool
        public let alts: [String]
        /// Takes a destination ("eve gidiyorum"), which Kurmanji puts after the verb: "diçim malê".
        public let motion: Bool
        let presFull: String?
        let negPresFull: String?
        let subjFull: String?
        let negSubjFull: String?
        let imp: String?

        init(_ tr: String, pre: String = "", pres: String, past: String, trans: Bool, alts: [String] = [],
             motion: Bool = false, presFull: String? = nil, negPresFull: String? = nil, subjFull: String? = nil,
             negSubjFull: String? = nil, imp: String? = nil) {
            self.tr = tr
            self.pre = pre
            self.pres = pres
            self.past = past
            self.trans = trans
            self.alts = alts
            self.motion = motion
            self.presFull = presFull
            self.negPresFull = negPresFull
            self.subjFull = subjFull
            self.negSubjFull = negSubjFull
            self.imp = imp
        }

        func present(_ neg: Bool) -> String { neg ? negPresFull ?? "\(pre)na\(pres)" : presFull ?? "\(pre)di\(pres)" }
        func subjunctive(_ neg: Bool) -> String { neg ? negSubjFull ?? "\(pre)ne\(pres)" : subjFull ?? "\(pre)bi\(pres)" }
        func pastStem(_ neg: Bool) -> String { neg ? "\(pre)ne\(past)" : "\(pre)\(past)" }
        func imperative(_ neg: Bool, plural: Bool) -> String {
            let sg = neg ? withE("\(pre)ne\(pres)") : imp ?? withE(subjunctive(false))
            return plural ? (sg.hasSuffix("e") ? String(sg.dropLast()) : sg) + "in" : sg
        }

        private func withE(_ s: String) -> String { Translator.vowels.contains(s.last!) ? s : s + "e" }
    }

    /// Parses a conjugated verb. 3sg forms and bare imperatives are "weak": they collide with ordinary words
    /// ("yedi" = seven / ate, "et" = meat / do!), so an exact dictionary entry wins over them.
    private func analyzeVerb(_ word: String) -> VerbForm? {
        for (ss, verb, progOnly) in Self.surfaceStems {
            if !word.hasPrefix(ss) { continue }
            let rest = String(word.dropFirst(ss.count))
            func form(_ t: Tense, _ neg: Bool, _ p: Int, weak: Bool = false) -> VerbForm {
                VerbForm(verb: verb, tense: t, negative: neg, person: p, weak: weak || p == 2)
            }
            if let g = Self.groups(Self.prog, rest) { return form(.present, !g[1].isEmpty, Self.progPerson(g[2])) }
            if progOnly { continue }
            if let g = Self.groups(Self.pastRe, rest) { return form(.past, !g[1].isEmpty, Self.pastPerson(g[2])) }
            if let g = Self.groups(Self.future, rest) { return form(.future, !g[1].isEmpty, Self.suffixPerson(g[2])) }
            if let g = Self.groups(Self.aorist, rest) { return form(.present, false, Self.suffixPerson(g[2])) }
            if let g = Self.groups(Self.negAorist, rest) { return form(.present, true, Self.negAoristPerson(g[1])) }
            if rest.isEmpty { return form(.imperative, false, 1, weak: true) }
            if Self.groups(Self.impPlural, rest) != nil { return form(.imperative, false, 4) }
            if Self.groups(Self.negImp, rest) != nil { return form(.imperative, true, 1, weak: true) }
            if Self.groups(Self.negImpPlural, rest) != nil { return form(.imperative, true, 4) }
        }
        return nil
    }

    private func emitVerb(_ out: inout [Piece], _ clauseStart: Int, _ f: VerbForm) {
        let v = f.verb
        let p = f.person
        func clause() -> [Int] { Array(stride(from: clauseStart, to: out.count, by: 1)) }
        // Destination right before a motion verb moves behind it with the -ê ending: "bo mal diçim" → "diçim malê".
        var goal = ""
        if v.motion, let last = clause().last(where: { out[$0].word }), let g = out[last].goal {
            goal = " " + g
            out.remove(at: last)
            if last - 1 >= 0 && !out[last - 1].word && out[last - 1].text.allSatisfy({ $0.isWhitespace }) {
                out.remove(at: last - 1)
            }
        }
        let ergative = f.tense == .past && v.trans
        let verbText: String
        let futureMarker: Bool
        switch f.tense {
        case .present:
            let s = v.present(f.negative)
            (verbText, futureMarker) = (s + Self.presentEnding(s, p), false)
        case .future:
            let s = v.subjunctive(f.negative)
            (verbText, futureMarker) = (s + Self.presentEnding(s, p), true)
        case .past:
            let s = v.pastStem(f.negative)
            (verbText, futureMarker) = (ergative ? s : s + Self.pastEnding(s, p), false)
        case .imperative:
            out.append(Piece(v.imperative(f.negative, plural: p == 4) + goal, word: true))
            return
        }
        let subject = ergative ? Self.obl[p] : Self.nom[p]
        let marker = !futureMarker ? "" : p == 1 ? " yê" : " ê"

        // An explicit Turkish pronoun in this clause becomes the subject (in oblique case for ergative past).
        if let explicit = clause().last(where: { out[$0].pronoun == p }) {
            out[explicit].text = subject + marker
            out[explicit].pronoun = nil
            out.append(Piece(verbText + goal, word: true))
        } else if p == 2 || p == 5 {
            // 3rd person: the subject is probably a noun already in the sentence.
            out.append(Piece((futureMarker ? "dê \(verbText)" : verbText) + goal, word: true))
        } else {
            let at = clause().first(where: { out[$0].word }) ?? out.count
            out.insert(Piece(" ", word: false), at: at)
            out.insert(Piece(subject + marker, word: true), at: at)
            out.append(Piece(verbText + goal, word: true))
        }
    }

    // MARK: - Nouns

    private struct NounForm {
        let text: String
        let goal: String?
    }

    /// "evlerimizde" → "li malê me": dictionary stem + possessive + case preposition. Datives also carry their
    /// goal form ("eve" → "malê", "evimize" → "malê me") for a following motion verb.
    private func analyzeNoun(_ word: String) -> NounForm? {
        let w = Array(word)
        guard w.count >= 3 else { return nil }
        for cut in stride(from: w.count - 1, through: 2, by: -1) {
            guard let m = Self.groups(Self.nounSuffix, String(w[cut...])) else { continue }
            let stem = String(w[..<cut])
            var base = dict.translations(stem).first
            if base == nil, let s = KurmanjiDictionary.softened[stem.last!] {
                base = dict.translations(String(stem.dropLast()) + String(s)).first
            }
            guard let base = base else { continue }
            let poss = m[2]
            let kase = m[3]
            let possPerson: Int?
            if poss.isEmpty {
                possPerson = nil
            } else if ["mız", "miz", "muz", "müz"].contains(where: poss.hasSuffix) {
                possPerson = 3
            } else if ["nız", "niz", "nuz", "nüz"].contains(where: poss.hasSuffix) {
                possPerson = 4
            } else if poss.hasSuffix("m") {
                possPerson = 0
            } else {
                possPerson = nil // 2sg -(ı)n and 3sg -(s)ı are too ambiguous with genitive/accusative
            }
            var text = base
            let baseVowel = Self.vowels.contains(base.last!)
            if let pp = possPerson { text += (baseVowel ? "yê " : "ê ") + Self.obl[pp] }
            let prep: String
            if kase.hasSuffix("n") && (kase.contains("d") || kase.contains("t")) {
                prep = "ji "
            } else if ["de", "da", "te", "ta"].contains(where: kase.hasSuffix) {
                prep = "li "
            } else if kase.hasSuffix("le") || kase.hasSuffix("la") {
                prep = "bi "
            } else if kase.hasSuffix("e") || kase.hasSuffix("a") {
                prep = "bo "
            } else {
                prep = ""
            }
            let goal: String? = prep != "bo " ? nil : possPerson != nil ? text : text + (baseVowel ? "yê" : "ê")
            return NounForm(text: prep + text, goal: goal)
        }
        return nil
    }

    // MARK: - Static data

    private static let terminators: Set<Character> = [".", "!", "?", "…", "\n"]

    /// Character offset where the sentence that ends at the end of [text] starts, skipping trailing spaces and
    /// end punctuation so "Nasılsın? " still targets "Nasılsın?". Nil if there is no sentence.
    public static func sentenceStart(_ text: String) -> Int? {
        let t = Array(text)
        var i = t.count
        while i > 0 && t[i - 1].isWhitespace { i -= 1 }
        while i > 0 && terminators.contains(t[i - 1]) { i -= 1 }
        while i > 0 && !terminators.contains(t[i - 1]) { i -= 1 }
        while i < t.count && t[i].isWhitespace { i += 1 }
        return i < t.count ? i : nil
    }

    public static func wordCount(_ s: String) -> Int { tokenize(s).filter(isWord).count }

    /// Splits into runs of word characters (letters and apostrophes) and runs of everything else.
    static func tokenize(_ s: String) -> [String] {
        var tokens: [String] = []
        var cur = ""
        var curIsWord = false
        for ch in s {
            let w = ch.isLetter || ch == "'" || ch == "’"
            if !cur.isEmpty && w != curIsWord {
                tokens.append(cur)
                cur = ""
            }
            cur.append(ch)
            curIsWord = w
        }
        if !cur.isEmpty { tokens.append(cur) }
        return tokens
    }

    private static func isWord(_ t: String) -> Bool { t.first?.isLetter == true }

    private static func collapseSpaces(_ s: String) -> String {
        var r = ""
        var prevSpace = false
        for ch in s {
            if ch == " " && prevSpace { continue }
            prevSpace = ch == " "
            r.append(ch)
        }
        return r
    }

    private static func removeSpaceBeforePunct(_ s: String) -> String {
        let c = Array(s)
        var r = ""
        for (i, ch) in c.enumerated() {
            if ch == " " && i + 1 < c.count && ".,!?;:".contains(c[i + 1]) { continue }
            r.append(ch)
        }
        return r
    }

    static let vowels: Set<Character> = Set("aeêiîouûıöü")

    private static let nom = ["ez", "tu", "ew", "em", "hûn", "ew"]
    private static let obl = ["min", "te", "wî", "me", "we", "wan"]
    private static let conjunctions: Set<String> = ["ve", "ama", "fakat", "ancak", "çünkü", "cunku", "sonra", "veya", "ya da"]
    private static let pronouns: [String: Int] = ["ben": 0, "sen": 1, "o": 2, "biz": 3, "siz": 4, "onlar": 5]

    private static func presentEnding(_ stem: String, _ p: Int) -> String {
        vowels.contains(stem.last!) ? ["m", "yî", "", "n", "n", "n"][p] : ["im", "î", "e", "in", "in", "in"][p]
    }

    private static func pastEnding(_ stem: String, _ p: Int) -> String {
        vowels.contains(stem.last!) ? ["m", "yî", "", "n", "n", "n"][p] : ["im", "î", "", "in", "in", "in"][p]
    }

    private static func re(_ pattern: String) -> Regex<AnyRegexOutput> { try! Regex(pattern) }

    /// Whole-match groups (index 0 = whole match, missing groups = ""), or nil if [s] doesn't match.
    private static func groups(_ r: Regex<AnyRegexOutput>, _ s: String) -> [String]? {
        guard let m = try? r.wholeMatch(in: s) else { return nil }
        return (0..<m.output.count).map { m.output[$0].substring.map(String.init) ?? "" }
    }

    // Suffix patterns after the verb stem. Group 1 = negation, group 2 = person suffix.
    private static let prog = re("(m[ıiuü])?[ıiuü]?yor(um|sun|uz|sunuz|lar)?")
    private static let pastRe = re("(m[ae])?[dt][ıiuü](m|n|k|n[ıiuü]z|l[ae]r)?")
    private static let future = re("(m[ae])?y?(?:acak|acağ|ecek|eceğ)([ıi]m|s[ıi]n|[ıi]z|s[ıi]n[ıi]z|l[ae]r)?")
    private static let aorist = re("([ıiuüae]?r)([ıiuü]m|s[ıiuü]n|[ıiuü]z|s[ıiuü]n[ıiuü]z|l[ae]r)?")
    private static let negAorist = re("m[ae](m|z|zs[ıi]n|y[ıi]z|zs[ıi]n[ıi]z|zl[ae]r)")
    private static let impPlural = re("y?[ıiuü]n([ıiuü]z)?")
    private static let negImp = re("m[ae]")
    private static let negImpPlural = re("m[ae]y[ıiuü]n([ıiuü]z)?")
    private static let question = re("m[ıiuü](y[ıiuü]m|s[ıiuü]n|y[ıiuü]z|s[ıiuü]n[ıiuü]z)?")

    private static let nounSuffix = re(
        "(l[ae]r)?" +
            "([ıiuü]?m[ıiuü]z|[ıiuü]?n[ıiuü]z|[ıiuü]?m|s?[ıiuü])?" +
            "(n?d[ae]n|n?t[ae]n|n?d[ae]|n?t[ae]|y?l[ae]|[yn]?[ae]|[yn]?[ıiuü]|n?[ıiuü]n)?"
    )

    private static func progPerson(_ s: String) -> Int {
        switch s {
        case "um": return 0
        case "sun": return 1
        case "uz": return 3
        case "sunuz": return 4
        case "lar": return 5
        default: return 2
        }
    }

    private static func pastPerson(_ s: String) -> Int {
        if s.isEmpty { return 2 }
        if s == "m" { return 0 }
        if s == "n" { return 1 }
        if s == "k" { return 3 }
        if s.hasPrefix("n") { return 4 }
        return 5
    }

    private static func suffixPerson(_ s: String) -> Int {
        if s.isEmpty { return 2 }
        if s.hasPrefix("l") { return 5 }
        if s.hasPrefix("s") { return s.count > 3 ? 4 : 1 }
        if s.hasSuffix("m") { return 0 }
        return 3
    }

    private static func negAoristPerson(_ s: String) -> Int {
        if s == "m" { return 0 }
        if s == "z" { return 2 }
        if s.hasPrefix("y") { return 3 }
        if s.hasPrefix("zl") { return 5 }
        if s.count > 4 { return 4 }
        return 1
    }

    private static func questionPerson(_ s: String) -> Int? {
        if s.isEmpty { return nil }
        if s.hasPrefix("y") { return s.hasSuffix("m") ? 0 : 3 }
        if s.count > 3 { return 4 }
        return 1
    }

    /// Common verbs, keyed by Turkish stem. Kurmanji stems are bare (no di-/bi- prefix). Keep in sync with
    /// `Translator.VERBS` in the Android app.
    public static let verbs: [Verb] = [
        Verb("gel", pres: "ê", past: "hat", trans: false, motion: true, presFull: "tê", negPresFull: "nayê", subjFull: "bê", negSubjFull: "neyê", imp: "were"),
        Verb("git", pres: "ç", past: "çû", trans: false, alts: ["gid"], motion: true, imp: "here"),
        Verb("yap", pres: "k", past: "kir", trans: true, imp: "bike"),
        Verb("et", pres: "k", past: "kir", trans: true, alts: ["ed"], imp: "bike"),
        Verb("ye", pres: "xw", past: "xwar", trans: true, alts: ["yi"]),
        Verb("iç", pre: "ve", pres: "xw", past: "xwar", trans: true, subjFull: "vexw"),
        Verb("gör", pres: "bîn", past: "dît", trans: true),
        Verb("bul", pres: "bîn", past: "dît", trans: true),
        Verb("bil", pres: "zan", past: "zanî", trans: true, negPresFull: "nizan"),
        Verb("iste", pres: "xwaz", past: "xwest", trans: true),
        Verb("söyle", pres: "bêj", past: "got", trans: true),
        Verb("de", pres: "bêj", past: "got", trans: true, alts: ["di"]),
        Verb("konuş", pres: "axiv", past: "axivî", trans: false),
        Verb("oku", pres: "xwîn", past: "xwend", trans: true),
        Verb("yaz", pres: "nivîs", past: "nivîsî", trans: true),
        Verb("otur", pre: "rû", pres: "n", past: "nişt", trans: false, presFull: "rûdin", subjFull: "rûn", imp: "rûne"),
        Verb("kalk", pre: "ra", pres: "b", past: "bû", trans: false, subjFull: "rab", imp: "rabe"),
        Verb("al", pres: "gir", past: "girt", trans: true),
        Verb("ver", pres: "d", past: "da", trans: true, imp: "bide"),
        Verb("sev", pre: "hez ", pres: "k", past: "kir", trans: true),
        Verb("çalış", pres: "xebit", past: "xebitî", trans: false),
        Verb("anla", pre: "fêm ", pres: "k", past: "kir", trans: true),
        Verb("düşün", pres: "fikir", past: "fikirî", trans: false),
        Verb("sor", pres: "pirs", past: "pirsî", trans: true),
        Verb("aç", pre: "ve", pres: "k", past: "kir", trans: true, subjFull: "vek", imp: "veke"),
        Verb("kapat", pres: "gir", past: "girt", trans: true),
        Verb("gül", pres: "ken", past: "kenî", trans: false),
        Verb("öğren", pre: "hîn ", pres: "b", past: "bû", trans: false),
        Verb("yaşa", pres: "jî", past: "jiya", trans: false),
        Verb("öl", pres: "mir", past: "mir", trans: false),
        Verb("getir", pres: "în", past: "anî", trans: true, motion: true, presFull: "tîn", negPresFull: "nayn", subjFull: "bîn", imp: "bîne"),
        Verb("götür", pres: "b", past: "bir", trans: true, motion: true),
        Verb("dinle", pre: "guhdarî ", pres: "k", past: "kir", trans: true),
        Verb("duy", pres: "bihîz", past: "bihîst", trans: true),
        Verb("koş", pres: "bez", past: "bezî", trans: false, motion: true),
        Verb("yürü", pres: "meş", past: "meşî", trans: false, motion: true),
        Verb("oyna", pres: "lîz", past: "lîst", trans: true),
        Verb("ol", pres: "b", past: "bû", trans: false),
        Verb("başla", pre: "dest pê ", pres: "k", past: "kir", trans: true),
        Verb("bitir", pres: "qedîn", past: "qedand", trans: true),
        Verb("sat", pres: "firoş", past: "firot", trans: true),
        Verb("gönder", pres: "şîn", past: "şand", trans: true, motion: true),
        Verb("ağla", pres: "girî", past: "girî", trans: false),
    ]

    /// (surface stem, verb, progressive-only). a/e-final stems lose the vowel before -(i)yor: anla → anlıyor.
    private static let surfaceStems: [(String, Verb, Bool)] = {
        let all = verbs.flatMap { v -> [(String, Verb, Bool)] in
            let full = ([v.tr] + v.alts).map { ($0, v, false) }
            let narrowed = "ae".contains(v.tr.last!) ? [(String(v.tr.dropLast()), v, true)] : []
            return full + narrowed
        }
        return stableSorted(all) { $0.0.count > $1.0.count }
    }()
}
