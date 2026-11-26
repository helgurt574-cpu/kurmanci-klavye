package com.klavye.kurmanci

import java.util.Locale

/**
 * Rough Kurmanji → Turkish gist for copied messages, built on [Dictionary]'s reverse index. Greedy phrase
 * lookup, then single words with Kurmanji endings (ezafe, oblique, plural) and verb prefixes stripped. Unknown
 * words are kept as they are. Word order is kept: both languages are SOV.
 */
class ReverseTranslator(private val dict: Dictionary) {

    fun translate(text: String): String {
        val tokens = TOKEN.findAll(text).map { it.value }.toList()
        val out = StringBuilder()
        var i = 0
        while (i < tokens.size) {
            val tok = tokens[i]
            if (!tok.first().isLetter()) { out.append(tok); i++; continue }
            val phrase = matchPhrase(tokens, i)
            if (phrase != null) { out.append(phrase.first); i = phrase.second; continue }
            out.append(word(tok))
            i++
        }
        val result = out.toString().replace(Regex(" {2,}"), " ").replace(Regex(" ([.,!?;:])"), "$1").trim()
        return if (text.trimStart().firstOrNull()?.isUpperCase() == true) {
            result.replaceFirstChar { it.uppercase(Dictionary.TR) }
        } else result
    }

    private fun matchPhrase(tokens: List<String>, start: Int): Pair<String, Int>? {
        val words = ArrayList<String>()
        var j = start
        while (words.size < MAX_PHRASE && j < tokens.size && tokens[j].first().isLetter()) {
            words.add(tokens[j].lowercase(Locale.ROOT))
            if (j + 2 < tokens.size && tokens[j + 1] == " " && tokens[j + 2].first().isLetter()) j += 2 else break
        }
        for (n in words.size downTo 2) {
            dict.turkishFor(words.take(n).joinToString(" "))?.let { return it to (start + 2 * (n - 1) + 1) }
        }
        return null
    }

    private fun word(tok: String): String {
        val w = tok.lowercase(Locale.ROOT)
        FUNCTION_WORDS[w]?.let { return it }
        dict.turkishFor(w)?.let { return it }
        for (c in candidates(w)) dict.turkishFor(c)?.let { return it }
        return tok
    }

    /** Bare forms to try for an inflected Kurmanji word, most likely first. */
    private fun candidates(w: String): List<String> {
        val res = LinkedHashSet<String>()
        for (suf in SUFFIXES) if (w.length - suf.length >= 2 && w.endsWith(suf)) res.add(w.dropLast(suf.length))
        for (pre in PREFIXES) if (w.length - pre.length >= 2 && w.startsWith(pre)) {
            val stem = w.drop(pre.length)
            res.add(stem)
            for (suf in SUFFIXES) if (stem.length - suf.length >= 2 && stem.endsWith(suf)) res.add(stem.dropLast(suf.length))
        }
        return res.toList()
    }

    companion object {
        private val TOKEN = Regex("[\\p{L}'’]+|[^\\p{L}'’]+")
        private const val MAX_PHRASE = 4
        private val SUFFIXES = listOf("yên", "ên", "yê", "ya", "an", "ê", "a", "î", "im", "in", "e")
        private val PREFIXES = listOf("di", "bi", "na", "ne", "ve")
        private val SPECIAL = Regex("[êîû]")

        /**
         * Small words the dictionary gets wrong or Turkish expresses with suffixes: copulas ("xweş e" = "güzel")
         * and prepositions (li/ji/bi become case endings in Turkish) are dropped.
         */
        private val FUNCTION_WORDS = mapOf(
            "e" to "", "ye" to "", "im" to "", "in" to "", "î" to "", "yî" to "", "me" to "",
            "li" to "", "ji" to "", "di" to "", "de" to "", "re" to "", "ve" to "", "bo" to "", "bi" to "",
            "û" to "ve", "jî" to "de", "ku" to "ki", "ez" to "ben", "tu" to "sen", "ew" to "o",
            "em" to "biz", "hûn" to "siz", "min" to "ben", "te" to "sen", "wî" to "onu", "wê" to "onu",
        )

        /**
         * Heuristic: Kurmanji-specific letters (ê î û), or most words found on the Kurmanji side but not as
         * Turkish words.
         */
        fun looksKurmanji(dict: Dictionary, text: String): Boolean {
            val words = TOKEN.findAll(text).map { it.value }.filter { it.first().isLetter() }.toList()
            if (words.isEmpty()) return false
            if (SPECIAL.containsMatchIn(text.lowercase(Locale.ROOT))) return true
            val kurmanjiOnly = words.count {
                dict.turkishFor(it.lowercase(Locale.ROOT)) != null && !dict.isTurkishWord(Dictionary.normalize(it))
            }
            return kurmanjiOnly * 2 > words.size
        }
    }
}
