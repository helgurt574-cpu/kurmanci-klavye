package com.klavye.kurmanci

import java.io.BufferedReader
import java.io.Reader
import java.util.Locale

/** One entry in the suggestion strip: [replaceLength] chars before the cursor get replaced by [kurmanji]. */
data class Suggestion(
    val turkish: String,
    val kurmanji: String,
    val replaceLength: Int,
    val kind: Kind,
) {
    enum class Kind { PHRASE, EXACT, STEM, PREFIX }
}

/**
 * Offline Turkish → Kurmanji lookup. Keys are normalized Turkish words or multi-word phrases;
 * values are one or more Kurmanji translations, best first. The iteration order of [entries] is the
 * priority used to rank prefix completions (the asset lists hand-curated, then most-confirmed entries first).
 */
class Dictionary(entries: Map<String, List<String>>) {

    private val map: Map<String, List<String>> = entries
    /** Keys in priority order (index = rank, lower = better). */
    private val keysByRank: Array<String> = entries.keys.toTypedArray()
    private val sorted: SortedIndex = SortedIndex(keysByRank) { it }
    /** Same keys indexed by their ASCII-folded form, so "guzel" finds "güzel". */
    private val folded: SortedIndex = SortedIndex(keysByRank, ::fold)

    /** Keys sorted by [sortKey]; rank[i] is the rank of the key at sorted position i. */
    private class SortedIndex(keys: Array<String>, sortKey: (String) -> String) {
        val sortKeys: Array<String>
        val rank: IntArray

        init {
            val k = Array(keys.size) { sortKey(keys[it]) }
            val order = k.indices.sortedBy { k[it] }
            sortKeys = Array(order.size) { k[order[it]] }
            rank = IntArray(order.size) { order[it] }
        }

        fun lowerBound(s: String): Int = sortKeys.binarySearch(s).let { if (it < 0) -it - 1 else it }
    }
    private val maxPhraseWords: Int = entries.keys.maxOfOrNull { it.count { c -> c == ' ' } + 1 } ?: 1

    val size: Int get() = map.size

    fun exact(turkish: String): List<String> = map[normalize(turkish)].orEmpty()

    /** Longest phrase length in words; [Translator] uses it for greedy phrase matching. */
    val longestPhrase: Int get() = maxPhraseWords

    /** The user's own entries and pick counts; they outrank bundled entries. Set from the main thread. */
    @Volatile
    var user: UserWords? = null

    /** Translations for an already-normalized key, falling back to its accent variants ("guzel" → "güzel"). */
    fun translations(key: String): List<String> {
        val own = user?.lookup(key).orEmpty()
        val bundled = keysFor(key).firstOrNull()?.let { map[it] }.orEmpty()
        return if (own.isEmpty()) bundled else (own + bundled).distinct()
    }

    // --- Kurmanji side: completion while typing Kurmanji, and Kurmanji → Turkish lookup ---

    /** Kurmanji single words from all translations, ranked by how many entries use them. Built on first use. */
    private val kurmanjiIndex: KurmanjiIndex by lazy { KurmanjiIndex(map) }

    private class KurmanjiIndex(map: Map<String, List<String>>) {
        val words: Array<String>
        val score: IntArray
        val foldedSorted: Array<String>
        val foldedOrder: IntArray

        init {
            val counts = HashMap<String, Int>()
            // The asset lists hand-curated everyday words first: their Kurmanji gets a big head start over
            // words that are merely frequent across Wiktionary's many technical entries ("spas" before "spartin").
            map.values.forEachIndexed { i, values ->
                val weight = if (i < CURATED_HEAD) 1000 else 1
                for (v in values) for (w in v.split(' ')) {
                    val word = w.lowercase(Locale.ROOT)
                    if (word.length >= 2 && KU_WORD.matches(word)) counts[word] = (counts[word] ?: 0) + weight
                }
            }
            val ranked = counts.entries.sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            words = Array(ranked.size) { ranked[it].key }
            score = IntArray(ranked.size) { ranked[it].value }
            val folded = Array(words.size) { foldKurmanji(words[it]) }
            val order = folded.indices.sortedBy { folded[it] }
            foldedSorted = Array(order.size) { folded[order[it]] }
            foldedOrder = IntArray(order.size) { order[it] }
        }
    }

    /** Builds the Kurmanji indexes ahead of time (they take ~a second on a phone). Call off the main thread. */
    fun warmUpKurmanji() {
        kurmanjiIndex
        reverseIndex
    }

    /**
     * Kurmanji words starting with [prefix], most common first, user-taught words before all. Plain letters also
     * match hatted ones ("ce" → "cêr"), since people often skip the circumflex.
     */
    fun completeKurmanji(prefix: String, limit: Int = 10): List<String> {
        val p = prefix.lowercase(Locale.ROOT)
        if (p.isEmpty()) return emptyList()
        val fp = foldKurmanji(p)
        val strict = fp != p // the user typed a special letter: honor it exactly
        val out = LinkedHashSet<String>()
        user?.kurmanjiWords()?.forEach { w ->
            val lw = w.lowercase(Locale.ROOT)
            if (if (strict) lw.startsWith(p) else foldKurmanji(lw).startsWith(fp)) out.add(lw)
        }
        val idx = kurmanjiIndex
        var i = idx.foldedSorted.binarySearch(fp).let { if (it < 0) -it - 1 else it }
        val hits = ArrayList<Int>()
        while (i < idx.foldedSorted.size && hits.size < MAX_PREFIX_SCAN && idx.foldedSorted[i].startsWith(fp)) {
            val r = idx.foldedOrder[i++]
            if (!strict || idx.words[r].startsWith(p)) hits.add(r)
        }
        hits.sorted().forEach { out.add(idx.words[it]) }
        return out.take(limit)
    }

    /** Kurmanji word or phrase (lowercase) → best Turkish key. Built on first use. */
    private val reverseIndex: Map<String, String> by lazy {
        val rev = HashMap<String, String>(map.size * 2)
        val pos = HashMap<String, Int>(map.size * 2)
        for ((tr, values) in map) values.forEachIndexed { i, ku ->
            val k = ku.lowercase(Locale.ROOT)
            // Prefer entries where this Kurmanji is the main translation; ties keep the better-ranked entry.
            if (i < (pos[k] ?: Int.MAX_VALUE)) { rev[k] = tr; pos[k] = i }
        }
        rev
    }

    /** Turkish for a lowercase Kurmanji word or phrase, user entries first. */
    fun turkishFor(kurmanji: String): String? {
        val k = kurmanji.lowercase(Locale.ROOT)
        user?.all()?.firstOrNull { it.second.lowercase(Locale.ROOT) == k }?.let { return it.first }
        return reverseIndex[k]
    }

    fun isTurkishWord(normalized: String): Boolean = keysFor(normalized).isNotEmpty()

    /**
     * Suggestions for the text right before the cursor. Phrase matches (spanning the last few words)
     * come first, then exact word, then suffix-stripped stem, then prefix completions.
     */
    fun suggest(textBeforeCursor: String, limit: Int = 12): List<Suggestion> {
        val words = trailingWords(textBeforeCursor, maxPhraseWords)
        if (words.isEmpty()) return emptyList()
        val current = words.last()
        val out = LinkedHashMap<String, Suggestion>()

        fun add(s: Suggestion) {
            if (out.size < limit) out.putIfAbsent(s.kurmanji.lowercase(Locale.ROOT), s)
        }

        // Longest phrase first: "iyi akşamlar" beats "akşamlar".
        for (n in words.size downTo 2) {
            val span = words.takeLast(n)
            val key = span.joinToString(" ") { normalize(it.text) }
            val replaceLen = textBeforeCursor.length - span.first().start
            user?.lookup(key)?.forEach { add(Suggestion(key, matchCase(span.first().text, it), replaceLen, Suggestion.Kind.PHRASE)) }
            for (k in keysFor(key)) {
                map[k]!!.forEach { add(Suggestion(k, matchCase(span.first().text, it), replaceLen, Suggestion.Kind.PHRASE)) }
            }
        }

        val norm = normalize(current.text)
        user?.lookup(norm)?.forEach { add(Suggestion(norm, matchCase(current.text, it), current.text.length, Suggestion.Kind.EXACT)) }
        keysFor(norm).forEachIndexed { i, k ->
            // The literal key keeps all its translations; accent-variants ("sac" → "saç") add their best few.
            map[k]!!.let { if (k == norm || i == 0) it else it.take(2) }.forEach {
                add(Suggestion(k, matchCase(current.text, it), current.text.length, Suggestion.Kind.EXACT))
            }
        }

        // Only the longest stem that is a real entry; shorter ones are mostly accidental (geceler → geç).
        stems(norm).firstNotNullOfOrNull { keysFor(it).firstOrNull() }?.let { stem ->
            map[stem]!!.take(3).forEach {
                add(Suggestion(stem, matchCase(current.text, it), current.text.length, Suggestion.Kind.STEM))
            }
        }

        if (norm.length >= 2) {
            for (key in prefixMatches(norm, limit)) {
                if (key == norm) continue
                map[key]?.firstOrNull()?.let {
                    add(Suggestion(key, matchCase(current.text, it), current.text.length, Suggestion.Kind.PREFIX))
                }
            }
        }
        // Learned preference: within each kind, the translations the user picks most come first (stable sort).
        val u = user ?: return out.values.toList()
        return out.values.sortedWith(compareBy<Suggestion> { it.kind.ordinal }.thenByDescending { u.usage(it.turkish, it.kurmanji) })
    }

    /**
     * Dictionary keys for a normalized Turkish [key]: the key itself if present, plus — when the input has no
     * Turkish-specific letters — every key that folds to it ("sac" → "sac", "saç"), best-ranked first.
     */
    private fun keysFor(key: String): List<String> {
        val res = ArrayList<String>(2)
        if (key in map) res.add(key)
        if (fold(key) != key) return res
        var i = folded.lowerBound(key)
        val ranks = ArrayList<Int>()
        while (i < folded.sortKeys.size && folded.sortKeys[i] == key) ranks.add(folded.rank[i++])
        ranks.sorted().forEach { r -> keysByRank[r].let { if (it != key) res.add(it) } }
        return res
    }

    /**
     * Single-word keys starting with [prefix], best-ranked first. ASCII-only input also matches accented keys
     * ("guz" → "güzel"). Scans at most [MAX_PREFIX_SCAN] keys.
     */
    private fun prefixMatches(prefix: String, limit: Int): List<String> {
        val index = if (fold(prefix) == prefix) folded else sorted
        var i = index.lowerBound(prefix)
        val ranks = ArrayList<Int>()
        while (i < index.sortKeys.size && ranks.size < MAX_PREFIX_SCAN && index.sortKeys[i].startsWith(prefix)) {
            if (' ' !in index.sortKeys[i]) ranks.add(index.rank[i])
            i++
        }
        return ranks.sorted().take(limit).map { keysByRank[it] }
    }

    data class Word(val text: String, val start: Int)

    companion object {
        private const val MAX_PREFIX_SCAN = 3000
        private val SPACES = Regex("\\s+")

        val TR: Locale = Locale.forLanguageTag("tr")

        private val KU_WORD = Regex("[a-zçêîşû]+")
        /** Roughly the size of tools/tr_ku.txt, which leads the asset. */
        private const val CURATED_HEAD = 600
        private val KU_FOLD = mapOf('ç' to 'c', 'ê' to 'e', 'î' to 'i', 'ş' to 's', 'û' to 'u')

        /** Strips Kurmanji special letters: "kêfxweş" → "kefxwes". Input must be lowercase. */
        fun foldKurmanji(s: String): String =
            if (s.none { it in KU_FOLD }) s else buildString(s.length) { s.forEach { append(KU_FOLD[it] ?: it) } }

        fun normalize(s: String): String = s.trim().lowercase(TR)

        private val FOLD = mapOf(
            'ç' to 'c', 'ğ' to 'g', 'ı' to 'i', 'ö' to 'o', 'ş' to 's', 'ü' to 'u',
            'â' to 'a', 'î' to 'i', 'û' to 'u',
        )

        /** Strips Turkish-specific letters: "güzel" → "guzel". Input must already be lowercase. */
        fun fold(s: String): String {
            if (s.none { it in FOLD }) return s
            return buildString(s.length) { s.forEach { append(FOLD[it] ?: it) } }
        }

        fun isWordChar(c: Char): Boolean = c.isLetter() || c == '\'' || c == '’'

        /** Up to [max] words ending exactly at the end of [text]; empty if the cursor isn't right after a word. */
        fun trailingWords(text: String, max: Int): List<Word> {
            val res = ArrayList<Word>()
            var end = text.length
            while (res.size < max) {
                var start = end
                while (start > 0 && isWordChar(text[start - 1])) start--
                if (start == end) break
                res.add(0, Word(text.substring(start, end).trimEnd('\'', '’'), start))
                // Only continue across a single plain space.
                if (start >= 2 && text[start - 1] == ' ' && isWordChar(text[start - 2])) end = start - 1 else break
            }
            return res
        }

        /** "Merhaba" → "Silav", "MERHABA" → "SILAV". Kurmanji uses the standard Latin i/I pair. */
        fun matchCase(original: String, translation: String): String = when {
            original.length > 1 && original.all { !it.isLetter() || it.isUpperCase() } -> translation.uppercase(Locale.ROOT)
            original.firstOrNull()?.isUpperCase() == true ->
                translation.replaceFirstChar { it.uppercase(Locale.ROOT) }
            else -> translation
        }

        // Includes ASCII-folded variants so "evlerinde" and "cocuklari" both stem.
        private val SUFFIXES = listOf(
            "lerimiz", "larımız", "leriniz", "larınız", "lerinden", "larından",
            "imiz", "ımız", "umuz", "ümüz", "iniz", "ınız", "unuz", "ünüz",
            "lerde", "larda", "lerden", "lardan", "lere", "lara", "leri", "ları", "ler", "lar",
            "yorum", "yorsun", "yoruz", "yorlar", "iyor", "ıyor", "uyor", "üyor", "yor",
            "acak", "ecek", "mak", "mek", "dım", "dim", "dum", "düm", "tım", "tim", "tum", "tüm",
            "dı", "di", "du", "dü", "tı", "ti", "tu", "tü", "miş", "mış", "muş", "müş",
            "nin", "nın", "nun", "nün", "den", "dan", "ten", "tan", "de", "da", "te", "ta",
            "yi", "yı", "yu", "yü", "ye", "ya", "le", "la", "im", "ım", "um", "üm",
            "in", "ın", "un", "ün", "i", "ı", "u", "ü", "e", "a", "m", "n",
        ).let { base -> (base + base.map(::fold)).distinct() }

        private val SOFTENED = mapOf('b' to 'p', 'c' to 'ç', 'd' to 't', 'ğ' to 'k', 'g' to 'k')

        /** Naive Turkish stemming: strip up to two suffix layers, undo consonant softening (kitabı → kitap). */
        fun stems(word: String): List<String> {
            val res = LinkedHashSet<String>()
            fun strip(w: String, depth: Int) {
                for (suf in SUFFIXES) {
                    if (w.length - suf.length < 2 || !w.endsWith(suf)) continue
                    val stem = w.dropLast(suf.length)
                    res.add(stem)
                    SOFTENED[stem.last()]?.let { res.add(stem.dropLast(1) + it) }
                    if (depth < 2) strip(stem, depth + 1)
                }
            }
            strip(word, 1)
            res.remove(word)
            return res.sortedByDescending { it.length }
        }

        /**
         * TSV format, one entry per line: `turkish<TAB>kurmanji1|kurmanji2`. Lines starting with `#` are
         * comments. Duplicate Turkish keys are merged.
         */
        fun parse(reader: Reader): Dictionary {
            val map = LinkedHashMap<String, MutableList<String>>()
            BufferedReader(reader).useLines { lines ->
                for (raw in lines) {
                    val line = raw.trim()
                    if (line.isEmpty() || line.startsWith("#")) continue
                    val tab = line.indexOf('\t')
                    if (tab <= 0) continue
                    val key = normalize(line.substring(0, tab)).let { if ("  " in it) it.replace(SPACES, " ") else it }
                    val values = line.substring(tab + 1).split('|').map { it.trim() }.filter { it.isNotEmpty() }
                    if (values.isEmpty()) continue
                    val list = map.getOrPut(key) { ArrayList() }
                    values.forEach { if (it !in list) list.add(it) }
                }
            }
            return Dictionary(map)
        }
    }
}
