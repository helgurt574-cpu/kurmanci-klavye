package com.klavye.kurmanci

import android.content.Context
import android.os.SystemClock
import android.util.Log
import java.io.InputStreamReader
import java.util.concurrent.Executors

/** Process-wide dictionary, loaded once from assets on a background thread. */
object DictionaryHolder {
    const val ASSET = "tr_ku.tsv"

    @Volatile
    var dictionary: Dictionary? = null
        private set

    private val executor = Executors.newSingleThreadExecutor()
    private val listeners = ArrayList<(Dictionary) -> Unit>()

    fun load(context: Context, onReady: (Dictionary) -> Unit) {
        dictionary?.let { onReady(it); return }
        val app = context.applicationContext
        synchronized(listeners) {
            listeners.add(onReady)
            if (listeners.size > 1) return
        }
        executor.execute {
            val start = SystemClock.elapsedRealtime()
            val dict = app.assets.open(ASSET).use { Dictionary.parse(InputStreamReader(it, Charsets.UTF_8)) }
            Log.i("Klavye", "dictionary: ${dict.size} entries in ${SystemClock.elapsedRealtime() - start} ms")
            dictionary = dict
            val pending = synchronized(listeners) { listeners.toList().also { listeners.clear() } }
            pending.forEach { it(dict) }
        }
    }
}
