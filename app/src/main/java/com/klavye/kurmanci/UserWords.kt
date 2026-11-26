package com.klavye.kurmanci

/**
 * The user's own Turkish → Kurmanji entries ("Kelimelerim") and how often each suggestion was picked.
 * Pure Kotlin; [Prefs] persists the two text forms. User entries always rank above bundled ones, and among
 * suggestions of the same kind, more-picked translations come first.
 */
class UserWords(entriesText: String = "", usageText: String = "") {

    /** Normalized Turkish → Kurmanji translations, most recently added first. */
    private val entries = LinkedHashMap<String, MutableList<String>>()
    private val usage = HashMap<String, Int>()

    init {
        for (line in entriesText.lineSequence()) {
            val parts = line.split('\t')
            if (parts.size == 2 && parts[0].isNotBlank() && parts[1].isNotBlank()) {
                entries.getOrPut(parts[0]) { ArrayList() }.add(parts[1])
            }
        }
        for (line in usageText.lineSequence()) {
            val parts = line.split('\t')
            if (parts.size == 3) parts[2].toIntOrNull()?.let { usage[usageKey(parts[0], parts[1])] = it }
        }
    }

    val isEmpty: Boolean get() = entries.isEmpty()

    fun lookup(normalizedTurkish: String): List<String> = entries[normalizedTurkish].orEmpty()

    /** All entries as (Turkish, Kurmanji) pairs, for the settings list. */
    fun all(): List<Pair<String, String>> = entries.flatMap { (tr, kus) -> kus.map { tr to it } }

    /** Every Kurmanji word the user taught, for Kurmanji completion. */
    fun kurmanjiWords(): List<String> = entries.values.flatten()

    fun add(turkish: String, kurmanji: String) {
        val tr = Dictionary.normalize(turkish).replace(Regex("\\s+"), " ")
        val ku = kurmanji.trim().replace(Regex("\\s+"), " ")
        if (tr.isEmpty() || ku.isEmpty()) return
        val list = entries.getOrPut(tr) { ArrayList() }
        list.removeAll { it.equals(ku, ignoreCase = true) }
        list.add(0, ku)
    }

    fun remove(turkish: String, kurmanji: String) {
        val tr = Dictionary.normalize(turkish)
        entries[tr]?.let { list ->
            list.removeAll { it == kurmanji }
            if (list.isEmpty()) entries.remove(tr)
        }
    }

    fun recordUse(turkish: String, kurmanji: String) {
        val k = usageKey(turkish, kurmanji)
        usage[k] = (usage[k] ?: 0) + 1
    }

    fun usage(turkish: String, kurmanji: String): Int = usage[usageKey(turkish, kurmanji)] ?: 0

    fun entriesText(): String = all().joinToString("\n") { (tr, ku) -> "$tr\t$ku" }

    /** Keeps the [MAX_USAGE] most-picked pairs so the stored string stays small. */
    fun usageText(): String = usage.entries.sortedByDescending { it.value }.take(MAX_USAGE)
        .joinToString("\n") { (k, n) -> "$k\t$n" }

    private fun usageKey(turkish: String, kurmanji: String) =
        Dictionary.normalize(turkish) + "\t" + kurmanji.lowercase(java.util.Locale.ROOT)

    companion object {
        private const val MAX_USAGE = 500
    }
}
