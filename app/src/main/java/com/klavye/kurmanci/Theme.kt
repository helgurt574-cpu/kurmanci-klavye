package com.klavye.kurmanci

import android.content.Context
import android.content.SharedPreferences
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.view.HapticFeedbackConstants

/**
 * Keyboard colors. [bgMid]/[bgEnd] make the background a vertical gradient. [keyStroke] outlines every key.
 * [rowKeys] colors letter keys row by row (top to bottom, cycling) instead of using [key].
 */
data class Theme(
    val id: String,
    val name: String,
    val bg: Int,
    val bgEnd: Int? = null,
    val bgMid: Int? = null,
    val keyStroke: Int? = null,
    val rowKeys: List<Int>? = null,
    val key: Int,
    val keySpecial: Int,
    val keyPressed: Int,
    val text: Int,
    val hint: Int,
    val accent: Int,
    val chip: Int,
    val chipText: Int,
    val chipPrefix: Int,
) {
    fun background(): Drawable = when {
        bgEnd == null -> GradientDrawable().apply { setColor(bg) }
        bgMid == null -> GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(bg, bgEnd))
        else -> GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(bg, bgMid, bgEnd))
    }

    /** Color of a letter key in [row] (0 = top row on screen). */
    fun keyColor(row: Int): Int = rowKeys?.let { it[row % it.size] } ?: key

    companion object {
        private fun c(argb: Long) = argb.toInt()

        val ALL = listOf(
            Theme(
                "dark", "Koyu",
                bg = c(0xFF1E1F22), key = c(0xFF3A3B3F), keySpecial = c(0xFF2B2C30), keyPressed = c(0xFF5A5C62),
                text = c(0xFFFFFFFF), hint = c(0xFF9AA0A6), accent = c(0xFF8BC34A),
                chip = c(0xFF2E5D32), chipText = c(0xFFFFFFFF), chipPrefix = c(0xFF2B2C30),
            ),
            Theme(
                "light", "Açık",
                bg = c(0xFFEDEFF3), key = c(0xFFFFFFFF), keySpecial = c(0xFFD5D9E0), keyPressed = c(0xFFBFC5CF),
                text = c(0xFF1B1B1F), hint = c(0xFF7A808A), accent = c(0xFF2E7D32),
                chip = c(0xFFC8E6C9), chipText = c(0xFF1B5E20), chipPrefix = c(0xFFDDE1E7),
            ),
            Theme(
                "newroz", "Newroz",
                bg = c(0xFFC62828), bgEnd = c(0xFF2E7D32),
                key = c(0xF2FFFFFF), keySpecial = c(0xFFFFD54F), keyPressed = c(0xFFFFB300),
                text = c(0xFF212121), hint = c(0xFFC62828), accent = c(0xFFC62828),
                chip = c(0xFFFFD54F), chipText = c(0xFF3E2723), chipPrefix = c(0xD9FFFFFF),
            ),
            Theme(
                "ocean", "Okyanus",
                bg = c(0xFF0D47A1), bgEnd = c(0xFF00838F),
                key = c(0x33FFFFFF), keySpecial = c(0x1AFFFFFF), keyPressed = c(0x66FFFFFF),
                text = c(0xFFFFFFFF), hint = c(0xFFB3E5FC), accent = c(0xFF80DEEA),
                chip = c(0xFF00ACC1), chipText = c(0xFFFFFFFF), chipPrefix = c(0x33FFFFFF),
            ),
            Theme(
                "sunset", "Gün batımı",
                bg = c(0xFFFF6F00), bgEnd = c(0xFFAD1457),
                key = c(0x33FFFFFF), keySpecial = c(0x1AFFFFFF), keyPressed = c(0x66FFFFFF),
                text = c(0xFFFFFFFF), hint = c(0xFFFFE0B2), accent = c(0xFFFFF176),
                chip = c(0xFFFFF176), chipText = c(0xFF6A1B9A), chipPrefix = c(0x33FFFFFF),
            ),
            Theme(
                "forest", "Orman",
                bg = c(0xFF1B5E20), bgEnd = c(0xFF33691E),
                key = c(0xFF2E7D32), keySpecial = c(0xFF255D29), keyPressed = c(0xFF43A047),
                text = c(0xFFFFFFFF), hint = c(0xFFC5E1A5), accent = c(0xFFFFEB3B),
                chip = c(0xFFFFEB3B), chipText = c(0xFF1B5E20), chipPrefix = c(0xFF255D29),
            ),
            Theme(
                "lavender", "Lavanta",
                bg = c(0xFFF3E5F5), bgEnd = c(0xFFE1BEE7),
                key = c(0xFFFFFFFF), keySpecial = c(0xFFCE93D8), keyPressed = c(0xFFBA68C8),
                text = c(0xFF4A148C), hint = c(0xFF9C27B0), accent = c(0xFF6A1B9A),
                chip = c(0xFF8E24AA), chipText = c(0xFFFFFFFF), chipPrefix = c(0xFFEFD8F3),
            ),
            Theme(
                "zagros", "Zagros",
                bg = c(0xFF263238), bgEnd = c(0xFF546E7A),
                key = c(0xFF607D8B), keySpecial = c(0xFF37474F), keyPressed = c(0xFF90A4AE),
                text = c(0xFFFFFFFF), hint = c(0xFFE0F7FA), accent = c(0xFFE0F7FA),
                chip = c(0xFFECEFF1), chipText = c(0xFF263238), chipPrefix = c(0xFF37474F),
            ),
            Theme(
                "kilim", "Kilim",
                bg = c(0xFF1A237E), bgEnd = c(0xFF283593), keyStroke = c(0x80FFE082),
                key = c(0xFFB71C1C), keySpecial = c(0xFFE09A1B), keyPressed = c(0xFFE53935),
                text = c(0xFFFFF8E1), hint = c(0xFFFFE082), accent = c(0xFFFFD54F),
                chip = c(0xFFE09A1B), chipText = c(0xFF1A237E), chipPrefix = c(0xFF3949AB),
            ),
            Theme(
                "nar", "Nar",
                bg = c(0xFF3B0A18), bgEnd = c(0xFF880E4F),
                key = c(0xFFC2185B), keySpecial = c(0xFF5E1230), keyPressed = c(0xFFE91E63),
                text = c(0xFFFFFFFF), hint = c(0xFFF8BBD0), accent = c(0xFFFF80AB),
                chip = c(0xFFFF80AB), chipText = c(0xFF3B0A18), chipPrefix = c(0xFF5E1230),
            ),
            Theme(
                "cay", "Çay",
                bg = c(0xFF3E2723), bgEnd = c(0xFF6D4C41),
                key = c(0xFFB86F2B), keySpecial = c(0xFF5D4037), keyPressed = c(0xFFD9893B),
                text = c(0xFFFFF3E0), hint = c(0xFFFFE0B2), accent = c(0xFFFFB74D),
                chip = c(0xFFFFB74D), chipText = c(0xFF3E2723), chipPrefix = c(0xFF5D4037),
            ),
            Theme(
                "aurora", "Kuzey ışıkları",
                bg = c(0xFF0B1D3A), bgMid = c(0xFF0E5E5A), bgEnd = c(0xFF4A2C6E), keyStroke = c(0x33FFFFFF),
                key = c(0x26FFFFFF), keySpecial = c(0x14FFFFFF), keyPressed = c(0x55FFFFFF),
                text = c(0xFFFFFFFF), hint = c(0xFFA7FFEB), accent = c(0xFF69F0AE),
                chip = c(0xFF69F0AE), chipText = c(0xFF0B1D3A), chipPrefix = c(0x26FFFFFF),
            ),
            Theme(
                "neon", "Neon",
                bg = c(0xFF07070D), keyStroke = c(0xFF00E5FF),
                key = c(0xFF10101C), keySpecial = c(0xFF10101C), keyPressed = c(0xFF1F1F3A),
                text = c(0xFF00E5FF), hint = c(0xFFFF4081), accent = c(0xFFFF4081),
                chip = c(0xFFFF4081), chipText = c(0xFF07070D), chipPrefix = c(0xFF10101C),
            ),
            Theme(
                "gold", "Altın",
                bg = c(0xFF0D0D0D), bgEnd = c(0xFF1F1B12), keyStroke = c(0xFFC9A646),
                key = c(0xFF1A1A1A), keySpecial = c(0xFF2A2516), keyPressed = c(0xFF3A3220),
                text = c(0xFFE6C766), hint = c(0xFF9C8443), accent = c(0xFFFFD54F),
                chip = c(0xFFC9A646), chipText = c(0xFF0D0D0D), chipPrefix = c(0xFF2A2516),
            ),
            Theme(
                "rainbow", "Gökkuşağı",
                bg = c(0xFF1B1B1F),
                rowKeys = listOf(c(0xFFE53935), c(0xFFF57C00), c(0xFFF9A825), c(0xFF43A047), c(0xFF1E88E5), c(0xFF8E24AA)),
                key = c(0xFF3A3B3F), keySpecial = c(0xFF2E2E33), keyPressed = c(0xFF5A5C62),
                text = c(0xFFFFFFFF), hint = c(0xDDFFFFFF), accent = c(0xFFFFEB3B),
                chip = c(0xFFFFFFFF), chipText = c(0xFF1B1B1F), chipPrefix = c(0xFF2E2E33),
            ),
            Theme(
                "candy", "Şeker",
                bg = c(0xFFFFE4EC), bgMid = c(0xFFE3F2FD), bgEnd = c(0xFFE8F5E9),
                key = c(0xFFFFFFFF), keySpecial = c(0xFFF8BBD0), keyPressed = c(0xFFE1BEE7),
                text = c(0xFF5D4E75), hint = c(0xFFB39DDB), accent = c(0xFFEC407A),
                chip = c(0xFFF8BBD0), chipText = c(0xFF5D4E75), chipPrefix = c(0xFFFFFFFF),
            ),
            Theme(
                "snow", "Kar",
                bg = c(0xFFE3F2FD), bgEnd = c(0xFFFFFFFF), keyStroke = c(0xFFBBDEFB),
                key = c(0xFFFFFFFF), keySpecial = c(0xFFD6EAF8), keyPressed = c(0xFFBBDEFB),
                text = c(0xFF0D47A1), hint = c(0xFF64B5F6), accent = c(0xFF1565C0),
                chip = c(0xFF1565C0), chipText = c(0xFFFFFFFF), chipPrefix = c(0xFFD6EAF8),
            ),
        )

        fun byId(id: String?) = ALL.firstOrNull { it.id == id } ?: ALL.first()
    }
}

/** Letter-row height in dp, adjustable with the settings slider (like Samsung's keyboard size setting). */
object KeyHeight {
    const val MIN = 42
    const val MAX = 66
    /** About the size of Samsung's keyboard at its default setting. */
    const val DEFAULT = 54
}

/**
 * Key-press vibration, via View haptics (no VIBRATE permission: requesting one would make Android show a
 * synthetic launcher icon for this icon-less app).
 */
enum class Vibration(val label: String, val haptic: Int?) {
    OFF("Kapalı", null),
    LIGHT("Hafif", HapticFeedbackConstants.CLOCK_TICK),
    MEDIUM("Orta", HapticFeedbackConstants.VIRTUAL_KEY),
    STRONG("Güçlü", HapticFeedbackConstants.LONG_PRESS);

    companion object {
        fun byName(name: String?) = entries.firstOrNull { it.name == name } ?: MEDIUM
    }
}

/**
 * User settings. Stored in device-protected storage because the keyboard is directBootAware and must be
 * able to read them before the first unlock after a reboot.
 */
class Prefs(context: Context) {
    private val sp: SharedPreferences = context.createDeviceProtectedStorageContext()
        .getSharedPreferences("klavye", Context.MODE_PRIVATE)

    var theme: Theme
        get() = Theme.byId(sp.getString("theme", null))
        set(value) = sp.edit().putString("theme", value.id).apply()

    /** Letter-row height in dp. Migrates the old Küçük/Normal/Büyük presets. */
    var rowHeightDp: Int
        get() = sp.getInt("rowHeight", when (sp.getString("keySize", null)) {
            "SMALL" -> 48
            "LARGE" -> 60
            else -> KeyHeight.DEFAULT
        }).coerceIn(KeyHeight.MIN, KeyHeight.MAX)
        set(value) = sp.edit().putInt("rowHeight", value.coerceIn(KeyHeight.MIN, KeyHeight.MAX)).apply()

    /** Most recent first, space-separated. */
    var recentEmojis: List<String>
        get() = sp.getString("recentEmojis", "")!!.split(' ').filter { it.isNotEmpty() }
        set(value) = sp.edit().putString("recentEmojis", value.take(40).joinToString(" ")).apply()

    var vibration: Vibration
        get() = Vibration.byName(sp.getString("vibration", null))
        set(value) = sp.edit().putString("vibration", value.name).apply()

    var keyPreview: Boolean
        get() = sp.getBoolean("keyPreview", true)
        set(value) = sp.edit().putBoolean("keyPreview", value).apply()

    /** true = type Kurmanji directly; false = type Turkish and pick Kurmanji translations. */
    var kurmanjiMode: Boolean
        get() = sp.getBoolean("kurmanjiMode", false)
        set(value) = sp.edit().putBoolean("kurmanjiMode", value).apply()

    var userWordsText: String
        get() = sp.getString("userWords", "")!!
        set(value) = sp.edit().putString("userWords", value).apply()

    var usageText: String
        get() = sp.getString("usage", "")!!
        set(value) = sp.edit().putString("usage", value).apply()

    var numberRow: Boolean
        get() = sp.getBoolean("numberRow", true)
        set(value) = sp.edit().putBoolean("numberRow", value).apply()
}
