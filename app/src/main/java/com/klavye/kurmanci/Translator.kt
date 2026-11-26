package com.klavye.kurmanci

/**
 * Rough, fully offline Turkish → Kurmanji sentence translation built on [Dictionary].
 *
 * Both languages are SOV, so word order is mostly kept. On top of word/phrase lookup it handles:
 *  - Turkish verb conjugation for the verbs in [VERBS] (progressive/aorist → Kurmanji present, past, future,
 *    imperative, negation), producing the subject pronoun Turkish drops, with ergative "min kir" for transitive past;
 *  - noun case suffixes → prepositions (evde → li mal, okuldan → ji dibistan) and 1st/2nd person possessives;
 *  - question particles (mi/mı/mu/mü, with person: "geliyor musun").
 * It does not know Kurmanji noun gender, so ezafe/oblique endings default to masculine. Output is a gist, not
 * a polished translation.
 */
class Translator(private val dict: Dictionary) {

    /** [goal]: for a Turkish dative ("eve"), its Kurmanji goal form ("malê"), used if a motion verb follows. */
    private class Piece(var text: String, val word: Boolean, var pronoun: Int? = null, val goal: String? = null)

    fun translate(sentence: String): String {
        val tokens = TOKEN.findAll(sentence).map { it.value }.toList()
        val out = ArrayList<Piece>()
        var clauseStart = 0
        var question = false
        val firstWord = tokens.indexOfFirst { isWord(it) }
        var i = 0
        while (i < tokens.size) {
            val tok = tokens[i]
            if (!isWord(tok)) {
                out.add(Piece(tok, word = false))
                if (tok.any { it == ',' || it == ';' || it == ':' }) clauseStart = out.size
                i++
                continue
            }
            val norm = Dictionary.normalize(tok)
            val keepCase = i != firstWord // sentence-initial capitalization is redone at the end

            // Greedy phrase match over words separated by single spaces.
            val phrase = matchPhrase(tokens, i)
            if (phrase != null) {
                out.add(Piece(phrase.first, word = true))
                i = phrase.second
                continue
            }

            val q = QUESTION.matchEntire(norm)
            if (q != null) {
                question = true
                i++
                continue
            }

            val pronoun = PRONOUNS[norm]
            if (pronoun != null) {
                out.add(Piece(NOM[pronoun], word = true, pronoun = pronoun))
                i++
                continue
            }

            // A following "musun/misiniz" carries the person for a 3sg-looking verb ("geliyor musun").
            val nextWord = tokens.getOrNull(i + 2)?.takeIf { tokens[i + 1] == " " }?.let { Dictionary.normalize(it) }
            val qPerson = nextWord?.let { QUESTION.matchEntire(it) }?.let { questionPerson(it.groupValues[1]) }

            val verb = analyzeVerb(norm)
            val exact = dict.translations(norm)
            if (verb != null && !(verb.weak && exact.isNotEmpty())) {
                emitVerb(out, clauseStart, verb.copy(person = qPerson ?: verb.person))
                i++
                continue
            }
            if (exact.isNotEmpty()) {
                out.add(Piece(if (keepCase) Dictionary.matchCase(tok, exact.first()) else exact.first(), word = true))
                // "ve/ama/çünkü" start a new clause, which gets its own subject: "min xwend û min hez kir".
                if (norm in CONJUNCTIONS) clauseStart = out.size
                i++
                continue
            }
            val noun = analyzeNoun(norm)
            if (noun != null) {
                out.add(Piece(if (keepCase) Dictionary.matchCase(tok, noun.text) else noun.text, word = true, goal = noun.goal))
                i++
                continue
            }
            val stem = Dictionary.stems(norm).firstNotNullOfOrNull { dict.translations(it).firstOrNull() }
            out.add(Piece(stem?.let { if (keepCase) Dictionary.matchCase(tok, it) else it } ?: tok, word = true))
            i++
        }

        var result = out.joinToString("") { it.text }.replace(MULTI_SPACE, " ").trim()
        result = result.replace(SPACE_BEFORE_PUNCT, "$1")
        if (question && !result.endsWith("?")) result = result.trimEnd('.', '!') + "?"
        if (sentence.trimStart().firstOrNull()?.isUpperCase() == true) {
            result = result.replaceFirstChar { it.uppercase(java.util.Locale.ROOT) }
        }
        return result
    }

    /** Longest dictionary phrase starting at token [start]; returns (translation, next token index). */
    private fun matchPhrase(tokens: List<String>, start: Int): Pair<String, Int>? {
        val words = ArrayList<String>()
        var j = start
        while (words.size < dict.longestPhrase && j < tokens.size && isWord(tokens[j])) {
            words.add(Dictionary.normalize(tokens[j]))
            if (j + 2 < tokens.size && tokens[j + 1] == " " && isWord(tokens[j + 2])) j += 2 else break
        }
        for (n in words.size downTo 2) {
            dict.translations(words.take(n).joinToString(" ")).firstOrNull()?.let {
                return it to (start + 2 * (n - 1) + 1)
            }
        }
        return null
    }

    // --- verbs ---

    enum class Tense { PRESENT, PAST, FUTURE, IMPERATIVE }

    data class VerbForm(val verb: Verb, val tense: Tense, val negative: Boolean, val person: Int, val weak: Boolean)

    /**
     * A Kurmanji verb. [pre] is a preverb or light-verb part ("ve", "hez "), [pres]/[past] are the bare stems.
     * Irregular full forms override the regular di-/na-/bi- prefixing.
     */
    data class Verb(
        val tr: String,
        val pre: String = "",
        val pres: String,
        val past: String,
        val trans: Boolean,
        val alts: List<String> = emptyList(),
        /** Takes a destination ("eve gidiyorum"), which Kurmanji puts after the verb: "diçim malê". */
        val motion: Boolean = false,
        val presFull: String? = null,
        val negPresFull: String? = null,
        val subjFull: String? = null,
        val negSubjFull: String? = null,
        val imp: String? = null,
    ) {
        fun present(neg: Boolean) = if (neg) negPresFull ?: "${pre}na$pres" else presFull ?: "${pre}di$pres"
        fun subjunctive(neg: Boolean) = if (neg) negSubjFull ?: "${pre}ne$pres" else subjFull ?: "${pre}bi$pres"
        fun pastStem(neg: Boolean) = if (neg) "${pre}ne$past" else "$pre$past"
        fun imperative(neg: Boolean, plural: Boolean): String {
            val sg = if (neg) withE("${pre}ne$pres") else imp ?: withE(subjunctive(false))
            return if (plural) sg.removeSuffix("e") + "in" else sg
        }

        private fun withE(s: String) = if (s.last() in VOWELS) s else s + "e"
    }

    /**
     * Parses a conjugated verb. 3sg forms and bare imperatives are "weak": they collide with ordinary words
     * ("yedi" = seven / ate, "et" = meat / do!), so an exact dictionary entry wins over them.
     */
    private fun analyzeVerb(word: String): VerbForm? {
        for ((ss, verb, progOnly) in SURFACE_STEMS) {
            if (!word.startsWith(ss)) continue
            val rest = word.substring(ss.length)
            fun form(t: Tense, neg: Boolean, p: Int, weak: Boolean = false) = VerbForm(verb, t, neg, p, weak || p == 2)
            PROG.matchEntire(rest)?.let { return form(Tense.PRESENT, it.groupValues[1].isNotEmpty(), progPerson(it.groupValues[2])) }
            if (progOnly) continue
            PAST.matchEntire(rest)?.let { return form(Tense.PAST, it.groupValues[1].isNotEmpty(), pastPerson(it.groupValues[2])) }
            FUTURE.matchEntire(rest)?.let { return form(Tense.FUTURE, it.groupValues[1].isNotEmpty(), suffixPerson(it.groupValues[2])) }
            AORIST.matchEntire(rest)?.let { return form(Tense.PRESENT, false, suffixPerson(it.groupValues[2])) }
            NEG_AORIST.matchEntire(rest)?.let { return form(Tense.PRESENT, true, negAoristPerson(it.groupValues[1])) }
            when {
                rest.isEmpty() -> return form(Tense.IMPERATIVE, false, 1, weak = true)
                IMP_PLURAL.matches(rest) -> return form(Tense.IMPERATIVE, false, 4)
                NEG_IMP.matches(rest) -> return form(Tense.IMPERATIVE, true, 1, weak = true)
                NEG_IMP_PLURAL.matches(rest) -> return form(Tense.IMPERATIVE, true, 4)
            }
        }
        return null
    }

    private fun emitVerb(out: ArrayList<Piece>, clauseStart: Int, f: VerbForm) {
        val v = f.verb
        val p = f.person
        // Destination right before a motion verb moves behind it with the -ê ending: "bo mal diçim" → "diçim malê".
        var goal = ""
        if (v.motion) {
            val last = (clauseStart until out.size).lastOrNull { out[it].word }
            out.getOrNull(last ?: -1)?.goal?.let { g ->
                goal = " $g"
                out.removeAt(last!!)
                if (last - 1 >= 0 && !out[last - 1].word && out[last - 1].text.isBlank()) out.removeAt(last - 1)
            }
        }
        val ergative = f.tense == Tense.PAST && v.trans
        val (verbText, futureMarker) = when (f.tense) {
            Tense.PRESENT -> v.present(f.negative).let { it + presentEnding(it, p) } to false
            Tense.FUTURE -> v.subjunctive(f.negative).let { it + presentEnding(it, p) } to true
            Tense.PAST -> v.pastStem(f.negative).let { if (ergative) it else it + pastEnding(it, p) } to false
            Tense.IMPERATIVE -> {
                out.add(Piece(v.imperative(f.negative, plural = p == 4) + goal, word = true))
                return
            }
        }
        val subject = if (ergative) OBL[p] else NOM[p]
        val marker = if (!futureMarker) "" else if (p == 1) " yê" else " ê"

        // An explicit Turkish pronoun in this clause becomes the subject (in oblique case for ergative past).
        val explicit = (clauseStart until out.size).lastOrNull { out[it].pronoun == p }
        when {
            explicit != null -> {
                out[explicit].text = subject + marker
                out[explicit].pronoun = null
                out.add(Piece(verbText + goal, word = true))
            }
            p == 2 || p == 5 -> {
                // 3rd person: the subject is probably a noun already in the sentence.
                out.add(Piece((if (futureMarker) "dê $verbText" else verbText) + goal, word = true))
            }
            else -> {
                val at = (clauseStart until out.size).firstOrNull { out[it].word } ?: out.size
                out.add(at, Piece(" ", word = false))
                out.add(at, Piece(subject + marker, word = true))
                out.add(Piece(verbText + goal, word = true))
            }
        }
    }

    // --- nouns ---

    private class NounForm(val text: String, val goal: String?)

    /**
     * "evlerimizde" → "li malê me": dictionary stem + possessive + case preposition. Datives also carry their
     * goal form ("eve" → "malê", "evimize" → "malê me") for a following motion verb.
     */
    private fun analyzeNoun(word: String): NounForm? {
        for (cut in word.length - 1 downTo 2) {
            val m = NOUN_SUFFIX.matchEntire(word.substring(cut)) ?: continue
            val stem = word.substring(0, cut)
            val base = dict.translations(stem).firstOrNull()
                ?: SOFTENED[stem.last()]?.let { dict.translations(stem.dropLast(1) + it).firstOrNull() }
                ?: continue
            val poss = m.groupValues[2]
            val case = m.groupValues[3]
            val possPerson = when {
                poss.isEmpty() -> null
                poss.endsWith("mız") || poss.endsWith("miz") || poss.endsWith("muz") || poss.endsWith("müz") -> 3
                poss.endsWith("nız") || poss.endsWith("niz") || poss.endsWith("nuz") || poss.endsWith("nüz") -> 4
                poss.endsWith("m") -> 0
                else -> null // 2sg -(ı)n and 3sg -(s)ı are too ambiguous with genitive/accusative
            }
            var text = base
            if (possPerson != null) text += (if (base.last() in VOWELS) "yê " else "ê ") + OBL[possPerson]
            val prep = when {
                case.endsWith("n") && (case.contains("d") || case.contains("t")) -> "ji "
                case.endsWith("de") || case.endsWith("da") || case.endsWith("te") || case.endsWith("ta") -> "li "
                case.endsWith("le") || case.endsWith("la") -> "bi "
                case.endsWith("e") || case.endsWith("a") -> "bo "
                else -> ""
            }
            val goal = if (prep != "bo ") null else if (possPerson != null) text else text + (if (base.last() in VOWELS) "yê" else "ê")
            return NounForm(prep + text, goal)
        }
        return null
    }

    companion object {
        private const val TERMINATORS = ".!?…\n"

        /**
         * Start index of the sentence that ends at the cursor (end of [text]), skipping trailing spaces and
         * end punctuation so "Nasılsın? " still targets "Nasılsın?". Returns null if there is no sentence.
         */
        fun sentenceStart(text: String): Int? {
            var i = text.length
            while (i > 0 && text[i - 1].isWhitespace()) i--
            while (i > 0 && text[i - 1] in TERMINATORS) i--
            while (i > 0 && text[i - 1] !in TERMINATORS) i--
            while (i < text.length && text[i].isWhitespace()) i++
            return if (i < text.length) i else null
        }

        fun wordCount(s: String) = TOKEN.findAll(s).count { isWord(it.value) }

        private val TOKEN = Regex("[\\p{L}'’]+|[^\\p{L}'’]+")
        private val MULTI_SPACE = Regex(" {2,}")
        private val SPACE_BEFORE_PUNCT = Regex(" ([.,!?;:])")
        private const val VOWELS = "aeêiîouûıöü"

        private fun isWord(t: String) = t.first().isLetter()

        private val NOM = listOf("ez", "tu", "ew", "em", "hûn", "ew")
        private val OBL = listOf("min", "te", "wî", "me", "we", "wan")
        private val CONJUNCTIONS = setOf("ve", "ama", "fakat", "ancak", "çünkü", "cunku", "sonra", "veya", "ya da")
        private val PRONOUNS = mapOf("ben" to 0, "sen" to 1, "o" to 2, "biz" to 3, "siz" to 4, "onlar" to 5)

        private fun presentEnding(stem: String, p: Int) =
            if (stem.last() in VOWELS) listOf("m", "yî", "", "n", "n", "n")[p] else listOf("im", "î", "e", "in", "in", "in")[p]

        private fun pastEnding(stem: String, p: Int) =
            if (stem.last() in VOWELS) listOf("m", "yî", "", "n", "n", "n")[p] else listOf("im", "î", "", "in", "in", "in")[p]

        // Suffix patterns after the verb stem. Group 1 = negation, group 2 = person suffix.
        private val PROG = Regex("(m[ıiuü])?[ıiuü]?yor(um|sun|uz|sunuz|lar)?")
        private val PAST = Regex("(m[ae])?[dt][ıiuü](m|n|k|n[ıiuü]z|l[ae]r)?")
        private val FUTURE = Regex("(m[ae])?y?(?:acak|acağ|ecek|eceğ)([ıi]m|s[ıi]n|[ıi]z|s[ıi]n[ıi]z|l[ae]r)?")
        private val AORIST = Regex("([ıiuüae]?r)([ıiuü]m|s[ıiuü]n|[ıiuü]z|s[ıiuü]n[ıiuü]z|l[ae]r)?")
        private val NEG_AORIST = Regex("m[ae](m|z|zs[ıi]n|y[ıi]z|zs[ıi]n[ıi]z|zl[ae]r)")
        private val IMP_PLURAL = Regex("y?[ıiuü]n([ıiuü]z)?")
        private val NEG_IMP = Regex("m[ae]")
        private val NEG_IMP_PLURAL = Regex("m[ae]y[ıiuü]n([ıiuü]z)?")
        private val QUESTION = Regex("m[ıiuü](y[ıiuü]m|s[ıiuü]n|y[ıiuü]z|s[ıiuü]n[ıiuü]z)?")

        private val NOUN_SUFFIX = Regex(
            "(l[ae]r)?" +
                "([ıiuü]?m[ıiuü]z|[ıiuü]?n[ıiuü]z|[ıiuü]?m|s?[ıiuü])?" +
                "(n?d[ae]n|n?t[ae]n|n?d[ae]|n?t[ae]|y?l[ae]|[yn]?[ae]|[yn]?[ıiuü]|n?[ıiuü]n)?"
        )
        private val SOFTENED = mapOf('b' to 'p', 'c' to 'ç', 'd' to 't', 'ğ' to 'k', 'g' to 'k')

        private fun progPerson(s: String) = when (s) {
            "um" -> 0; "sun" -> 1; "uz" -> 3; "sunuz" -> 4; "lar" -> 5; else -> 2
        }

        private fun pastPerson(s: String) = when {
            s.isEmpty() -> 2
            s == "m" -> 0
            s == "n" -> 1
            s == "k" -> 3
            s.startsWith("n") -> 4
            else -> 5
        }

        private fun suffixPerson(s: String) = when {
            s.isEmpty() -> 2
            s.startsWith("l") -> 5
            s.startsWith("s") -> if (s.length > 3) 4 else 1
            s.endsWith("m") -> 0
            else -> 3
        }

        private fun negAoristPerson(s: String) = when {
            s == "m" -> 0
            s == "z" -> 2
            s.startsWith("y") -> 3
            s.startsWith("zl") -> 5
            s.length > 4 -> 4
            else -> 1
        }

        private fun questionPerson(s: String): Int? = when {
            s.isEmpty() -> null
            s.startsWith("y") -> if (s.endsWith("m")) 0 else 3
            s.length > 3 -> 4
            else -> 1
        }

        /** Common verbs, keyed by Turkish stem. Kurmanji stems are bare (no di-/bi- prefix). */
        val VERBS = listOf(
            Verb("gel", pres = "ê", past = "hat", trans = false, motion = true, presFull = "tê", negPresFull = "nayê", subjFull = "bê", negSubjFull = "neyê", imp = "were"),
            Verb("git", pres = "ç", past = "çû", trans = false, motion = true, alts = listOf("gid"), imp = "here"),
            Verb("yap", pres = "k", past = "kir", trans = true, imp = "bike"),
            Verb("et", pres = "k", past = "kir", trans = true, alts = listOf("ed"), imp = "bike"),
            Verb("ye", pres = "xw", past = "xwar", trans = true, alts = listOf("yi")),
            Verb("iç", pre = "ve", pres = "xw", past = "xwar", trans = true, subjFull = "vexw"),
            Verb("gör", pres = "bîn", past = "dît", trans = true),
            Verb("bul", pres = "bîn", past = "dît", trans = true),
            Verb("bil", pres = "zan", past = "zanî", trans = true, negPresFull = "nizan"),
            Verb("iste", pres = "xwaz", past = "xwest", trans = true),
            Verb("söyle", pres = "bêj", past = "got", trans = true),
            Verb("de", pres = "bêj", past = "got", trans = true, alts = listOf("di")),
            Verb("konuş", pres = "axiv", past = "axivî", trans = false),
            Verb("oku", pres = "xwîn", past = "xwend", trans = true),
            Verb("yaz", pres = "nivîs", past = "nivîsî", trans = true),
            Verb("otur", pre = "rû", pres = "n", past = "nişt", trans = false, presFull = "rûdin", subjFull = "rûn", imp = "rûne"),
            Verb("kalk", pre = "ra", pres = "b", past = "bû", trans = false, subjFull = "rab", imp = "rabe"),
            Verb("al", pres = "gir", past = "girt", trans = true),
            Verb("ver", pres = "d", past = "da", trans = true, imp = "bide"),
            Verb("sev", pre = "hez ", pres = "k", past = "kir", trans = true),
            Verb("çalış", pres = "xebit", past = "xebitî", trans = false),
            Verb("anla", pre = "fêm ", pres = "k", past = "kir", trans = true),
            Verb("düşün", pres = "fikir", past = "fikirî", trans = false),
            Verb("sor", pres = "pirs", past = "pirsî", trans = true),
            Verb("aç", pre = "ve", pres = "k", past = "kir", trans = true, subjFull = "vek", imp = "veke"),
            Verb("kapat", pres = "gir", past = "girt", trans = true),
            Verb("gül", pres = "ken", past = "kenî", trans = false),
            Verb("öğren", pre = "hîn ", pres = "b", past = "bû", trans = false),
            Verb("yaşa", pres = "jî", past = "jiya", trans = false),
            Verb("öl", pres = "mir", past = "mir", trans = false),
            Verb("getir", pres = "în", past = "anî", trans = true, motion = true, presFull = "tîn", negPresFull = "nayn", subjFull = "bîn", imp = "bîne"),
            Verb("götür", pres = "b", past = "bir", trans = true, motion = true),
            Verb("dinle", pre = "guhdarî ", pres = "k", past = "kir", trans = true),
            Verb("duy", pres = "bihîz", past = "bihîst", trans = true),
            Verb("koş", pres = "bez", past = "bezî", trans = false, motion = true),
            Verb("yürü", pres = "meş", past = "meşî", trans = false, motion = true),
            Verb("oyna", pres = "lîz", past = "lîst", trans = true),
            Verb("ol", pres = "b", past = "bû", trans = false),
            Verb("başla", pre = "dest pê ", pres = "k", past = "kir", trans = true),
            Verb("bitir", pres = "qedîn", past = "qedand", trans = true),
            Verb("sat", pres = "firoş", past = "firot", trans = true),
            Verb("gönder", pres = "şîn", past = "şand", trans = true, motion = true),
            Verb("ağla", pres = "girî", past = "girî", trans = false),
        )

        /** (surface stem, verb, progressive-only). a/e-final stems lose the vowel before -(i)yor: anla → anlıyor. */
        private val SURFACE_STEMS: List<Triple<String, Verb, Boolean>> = VERBS.flatMap { v ->
            val full = (listOf(v.tr) + v.alts).map { Triple(it, v, false) }
            val narrowed = if (v.tr.last() in "ae") listOf(Triple(v.tr.dropLast(1), v, true)) else emptyList()
            full + narrowed
        }.sortedByDescending { it.first.length }
    }
}
