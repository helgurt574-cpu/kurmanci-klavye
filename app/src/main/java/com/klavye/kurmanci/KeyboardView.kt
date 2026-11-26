package com.klavye.kurmanci

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.ViewGroup
import android.widget.AbsListView
import android.widget.BaseAdapter
import android.widget.FrameLayout
import android.widget.GridView
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.TextView

/** Key layouts and touch handling. Draws its own rows; no deprecated android.inputmethodservice.KeyboardView. */
@SuppressLint("ViewConstructor")
class KeyboardView(
    context: Context,
    private val listener: Listener,
    private val theme: Theme,
    rowDp: Int,
    private val numberRow: Boolean,
    private val prefs: Prefs,
) : LinearLayout(context) {

    interface Listener {
        fun onText(text: String)
        fun onBackspace()
        fun onEnter()
        fun onSwitchIme()
        /** Long press on space: pick another keyboard, as on Samsung's keyboard. */
        fun onImePicker()
        /** Letters / symbols / emoji switched from inside the keyboard. */
        fun onModeChanged() {}
        /** The TR/KU key: Turkish-with-translation ↔ typing Kurmanji directly. */
        fun onToggleLanguage() {}
        /** Sliding on the space bar moves the cursor by [delta] characters. */
        fun onCursorMove(delta: Int) {}
    }

    enum class Special { SHIFT, BACKSPACE, SYMBOLS, LETTERS, SPACE, ENTER, GLOBE, EMOJI, LANG }

    enum class Mode { LETTERS, SYMBOLS, EMOJI }

    /** [text] is what gets typed; [alt] is typed on long press (Kurmanji letters ê î û live here). */
    data class Key(
        val text: String = "",
        val alt: String? = null,
        val special: Special? = null,
        val weight: Float = 1f,
    )

    enum class Shift { OFF, ONCE, LOCKED }

    // Re-rendering the emoji panel would reset its scroll position after every emoji, so shift changes
    // (auto-caps after each commit) only redraw the key layouts.
    var shift = Shift.OFF
        set(value) {
            if (field == value) return
            field = value
            if (mode != Mode.EMOJI) render()
        }

    var showGlobe = true
        set(value) { field = value; render() }

    var enterLabel = "↵"
        set(value) { field = value; render() }

    /** Direct Kurmanji typing: Kurmanji letter layout and Kurmanji (not Turkish) upper-casing. */
    var kurmanji = false
        set(value) { if (field != value) { field = value; if (mode != Mode.EMOJI) render() } }

    private var mode = Mode.LETTERS
    private var emojiCategory = -1 // -1 = recents
    private val handler = Handler(Looper.getMainLooper())
    private val keyHeight = dp(rowDp)
    /** Letter size grows with the row height so big keys don't look empty. */
    private val letterSp = 22f * rowDp / 52f + 1f

    init {
        orientation = VERTICAL
        // Background comes from the parent so the gradient also spans the suggestion strip.
        setPadding(dp(3), dp(2), dp(3), dp(4))
        render()
    }

    private fun layout(): List<List<Key>> = if (mode == Mode.SYMBOLS) SYMBOL_ROWS else letterRows()

    /** The 😊 / ABC toggle in the top strip, where Samsung keeps it. */
    fun toggleEmoji() {
        mode = if (mode == Mode.EMOJI) Mode.LETTERS else Mode.EMOJI
        render()
    }

    val isEmoji: Boolean get() = mode == Mode.EMOJI

    /** Read on every press so a change in settings applies without rebuilding the keyboard. */
    private fun haptic(v: View) {
        prefs.vibration.haptic?.let { v.performHapticFeedback(it, HapticFeedbackConstants.FLAG_IGNORE_VIEW_SETTING) }
    }

    /** Back to letters, e.g. when a new text field gets focus. */
    fun resetMode() {
        if (mode != Mode.LETTERS) { mode = Mode.LETTERS; render() }
    }

    private fun letterRows(): List<List<Key>> {
        val bottom = mutableListOf(Key("?123", special = Special.SYMBOLS, weight = 1.5f))
        if (showGlobe) bottom.add(Key("🌐", special = Special.GLOBE))
        bottom += listOf(
            Key(if (kurmanji) "KU" else "TR", special = Special.LANG),
            Key(","),
            Key(if (kurmanji) "Kurmancî" else "Türkçe → Kurmancî", special = Special.SPACE, weight = if (showGlobe) 3.5f else 4.5f),
            Key("."),
            Key(special = Special.ENTER, weight = 1.5f),
        )
        return (if (numberRow) listOf(NUMBER_ROW) else emptyList()) + (if (kurmanji) KU_ROWS else LETTER_ROWS) + listOf(bottom)
    }

    /** The number row is a bit shorter, like Samsung's, so it doesn't crowd the letters. */
    private fun rowHeight(row: List<Key>) = if (row === NUMBER_ROW) keyHeight * 3 / 4 else keyHeight

    private fun render() {
        preview?.dismiss()
        removeAllViews()
        if (mode == Mode.EMOJI) { renderEmoji(); return }
        for ((rowIndex, row) in layout().withIndex()) {
            val rowView = LinearLayout(context).apply { orientation = HORIZONTAL }
            for (key in row) rowView.addView(keyView(key, rowIndex), LayoutParams(0, rowHeight(row), key.weight))
            addView(rowView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        }
    }

    /**
     * Emoji panel with the same height as the letter layout so the keyboard doesn't jump. Like Samsung's: category
     * tabs with a backspace at their end, and the grid below; the way back to letters is the keyboard icon in the
     * top strip.
     */
    private fun renderEmoji() {
        val total = letterRows().sumOf { rowHeight(it) }
        val tabsH = dp(38)
        val recents = prefs.recentEmojis
        if (emojiCategory == -1 && recents.isEmpty()) emojiCategory = 0

        val tabs = LinearLayout(context).apply { orientation = HORIZONTAL }
        val tabIcons = listOf("🕘") + EmojiData.CATEGORIES.map { it.icon }
        tabIcons.forEachIndexed { i, icon ->
            val cat = i - 1
            tabs.addView(TextView(context).apply {
                text = icon
                gravity = Gravity.CENTER
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
                alpha = if (cat == emojiCategory) 1f else 0.55f
                if (cat == emojiCategory) background = rounded(theme.keySpecial)
                setOnClickListener { emojiCategory = cat; render() }
            }, LayoutParams(0, tabsH, 1f))
        }
        tabs.addView(keyView(Key(special = Special.BACKSPACE), 0), LayoutParams(0, tabsH, 1.4f))
        addView(tabs, LayoutParams(LayoutParams.MATCH_PARENT, tabsH))

        val emojis = if (emojiCategory == -1) recents else EmojiData.CATEGORIES[emojiCategory].emojis
        val cell = dp(44)
        val grid = GridView(context).apply {
            numColumns = 8
            stretchMode = GridView.STRETCH_COLUMN_WIDTH
            selector = ColorDrawable(Color.TRANSPARENT)
            isVerticalScrollBarEnabled = false
            adapter = object : BaseAdapter() {
                override fun getCount() = emojis.size
                override fun getItem(position: Int) = emojis[position]
                override fun getItemId(position: Int) = position.toLong()
                override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View =
                    ((convertView as? TextView) ?: TextView(context).apply {
                        gravity = Gravity.CENTER
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 26f)
                        layoutParams = AbsListView.LayoutParams(-1, cell)
                    }).apply { text = emojis[position] }
            }
            setOnItemClickListener { view, _, position, _ ->
                haptic(view)
                val e = emojis[position]
                listener.onText(e)
                prefs.recentEmojis = listOf(e) + prefs.recentEmojis.filter { it != e }
            }
        }
        addView(grid, LayoutParams(LayoutParams.MATCH_PARENT, total - tabsH))
    }

    // Turkish upper-cases i → İ; Kurmanji uses the plain Latin pair (i → I).
    private fun display(s: String): String =
        if (shift == Shift.OFF) s else s.uppercase(if (kurmanji) java.util.Locale.ROOT else Dictionary.TR)

    // --- key preview bubble (Samsung-style), drawn in a popup so it can rise above the top row ---

    private var preview: PopupWindow? = null

    private fun showPreview(anchor: View, label: String) {
        if (!prefs.keyPreview || label.isBlank()) return
        val w = maxOf(anchor.width * 5 / 4, dp(40))
        val h = keyHeight * 5 / 4
        val p = preview ?: PopupWindow(
            TextView(context).apply {
                gravity = Gravity.CENTER
                setTextColor(theme.text)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, letterSp * 1.3f)
                background = rounded(theme.keyPressed)
            },
            w, h, false,
        ).apply {
            isClippingEnabled = false
            isTouchable = false
            preview = this
        }
        (p.contentView as TextView).text = label
        val loc = IntArray(2)
        anchor.getLocationInWindow(loc)
        val x = loc[0] + anchor.width / 2 - w / 2
        val y = loc[1] - h + dp(4)
        if (p.isShowing) p.update(x, y, w, h) else { p.width = w; p.height = h; p.showAtLocation(this, Gravity.NO_GRAVITY, x, y) }
    }

    private fun hidePreview() = preview?.dismiss()

    override fun onDetachedFromWindow() {
        hidePreview()
        super.onDetachedFromWindow()
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun keyView(key: Key, row: Int): View {
        val frame = FrameLayout(context)
        val isSpecial = key.special != null && key.special != Special.SPACE
        val normalBg = rounded(if (isSpecial) theme.keySpecial else theme.keyColor(row))
        val pressedBg = rounded(theme.keyPressed)
        val inner = FrameLayout(context).apply { background = normalBg }
        // Narrow gaps like Samsung's keyboard: more of the width goes to the keys themselves.
        frame.addView(inner, FrameLayout.LayoutParams(-1, -1).apply { setMargins(dp(2), dp(3), dp(2), dp(3)) })

        val label = when (key.special) {
            Special.SHIFT -> if (shift == Shift.LOCKED) "⇪" else "⇧"
            Special.BACKSPACE -> "⌫"
            Special.ENTER -> enterLabel
            null -> display(key.text)
            else -> key.text
        }
        val main = TextView(context).apply {
            text = label
            isSingleLine = true
            gravity = Gravity.CENTER
            setTextColor(if (key.special == Special.SHIFT && shift != Shift.OFF) theme.accent else theme.text)
            setTextSize(
                TypedValue.COMPLEX_UNIT_SP,
                when {
                    key.special == Special.SPACE -> 14f
                    key.special == Special.LANG -> 14f
                    label.length > 4 -> 13f // "Bişîne", "Bigere": must fit the enter key on one line
                    label.length > 2 -> 15f
                    // Digits sit on the shorter number row next to a symbol hint: keep them compact.
                    else -> if (key.text.firstOrNull()?.isDigit() == true) letterSp * 0.72f else letterSp
                },
            )
            // With a corner hint, nudge the main label down a little so the two never touch.
            if (key.alt != null) {
                includeFontPadding = false
                setPadding(0, dp(if (key.text.firstOrNull()?.isDigit() == true) 7 else 3), 0, 0)
            }
        }
        inner.addView(main, FrameLayout.LayoutParams(-1, -1))
        if (key.special == Special.LANG) {
            // Active language small and bold; the other one as a tiny corner hint, like the long-press hints.
            main.setTypeface(main.typeface, android.graphics.Typeface.BOLD)
            main.letterSpacing = 0.06f
            inner.addView(TextView(context).apply {
                text = if (kurmanji) "tr" else "ku"
                setTextColor(theme.hint)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
                setPadding(0, dp(2), dp(5), 0)
            }, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.END))
        }
        key.alt?.let {
            val digit = key.text.firstOrNull()?.isDigit() == true
            inner.addView(TextView(context).apply {
                text = display(it)
                setTextColor(theme.hint)
                includeFontPadding = false
                setTextSize(TypedValue.COMPLEX_UNIT_SP, if (digit) 8.5f else 10f)
                setPadding(0, dp(if (digit) 1 else 2), dp(if (digit) 3 else 5), 0)
            }, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.END))
        }

        var longPressed = false
        // Space-bar cursor slide.
        var downX = 0f
        var stepX = 0f
        var sliding = false
        val longPress = Runnable {
            longPressed = true
            if (prefs.vibration != Vibration.OFF) frame.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            if (key.special == Special.SPACE) listener.onImePicker()
            // Long press on shift locks caps, like Samsung's keyboard (double tap still works too).
            if (key.special == Special.SHIFT) shift = Shift.LOCKED
            key.alt?.let { listener.onText(display(it)); afterChar() }
        }
        val repeat = object : Runnable {
            override fun run() {
                longPressed = true
                listener.onBackspace()
                handler.postDelayed(this, 60)
            }
        }

        frame.setOnTouchListener { v, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    inner.background = pressedBg
                    haptic(v)
                    longPressed = false
                    sliding = false
                    downX = ev.x
                    stepX = ev.x
                    if (key.special == null) showPreview(inner, label)
                    if (key.special == Special.BACKSPACE) handler.postDelayed(repeat, 400)
                    else if (key.alt != null) handler.postDelayed(longPress, 350)
                    else if (key.special == Special.SPACE) handler.postDelayed(longPress, 500)
                    else if (key.special == Special.SHIFT) handler.postDelayed(longPress, 400)
                }
                MotionEvent.ACTION_MOVE -> if (key.special == Special.SPACE) {
                    if (!sliding && kotlin.math.abs(ev.x - downX) > dp(12)) {
                        sliding = true
                        handler.removeCallbacks(longPress)
                    }
                    if (sliding) {
                        val steps = ((ev.x - stepX) / dp(10)).toInt()
                        if (steps != 0) {
                            listener.onCursorMove(steps)
                            stepX += steps * dp(10)
                        }
                    }
                }
                MotionEvent.ACTION_UP -> {
                    inner.background = normalBg
                    hidePreview()
                    handler.removeCallbacks(longPress)
                    handler.removeCallbacks(repeat)
                    if (!longPressed && !sliding) onKey(key)
                }
                MotionEvent.ACTION_CANCEL -> {
                    inner.background = normalBg
                    hidePreview()
                    handler.removeCallbacks(longPress)
                    handler.removeCallbacks(repeat)
                }
            }
            true
        }
        return frame
    }

    private var lastShiftTap = 0L

    private fun onKey(key: Key) {
        when (key.special) {
            null -> { listener.onText(display(key.text)); afterChar() }
            Special.SPACE -> listener.onText(" ")
            Special.BACKSPACE -> listener.onBackspace()
            Special.ENTER -> listener.onEnter()
            Special.GLOBE -> listener.onSwitchIme()
            Special.SYMBOLS -> { mode = Mode.SYMBOLS; render(); listener.onModeChanged() }
            Special.LETTERS -> { mode = Mode.LETTERS; render(); listener.onModeChanged() }
            Special.EMOJI -> { mode = Mode.EMOJI; render(); listener.onModeChanged() }
            Special.LANG -> listener.onToggleLanguage()
            Special.SHIFT -> {
                val now = System.currentTimeMillis()
                shift = when {
                    shift == Shift.LOCKED -> Shift.OFF
                    shift == Shift.ONCE && now - lastShiftTap < 400 -> Shift.LOCKED
                    shift == Shift.ONCE -> Shift.OFF
                    else -> Shift.ONCE
                }
                lastShiftTap = now
            }
        }
    }

    private fun afterChar() {
        if (shift == Shift.ONCE) shift = Shift.OFF
    }

    private fun rounded(color: Int) = GradientDrawable().apply {
        setColor(color)
        theme.keyStroke?.let { setStroke(dp(1), it) }
        cornerRadius = dp(8).toFloat()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    companion object {
        private fun row(chars: String, alts: Map<Char, String> = KURMANJI_ALTS) =
            chars.map { Key(it.toString(), alts[it]) }

        /** Long-press alternates: the Kurmanji letters missing from the Turkish layout, plus a few conveniences. */
        val KURMANJI_ALTS = mapOf('e' to "ê", 'i' to "î", 'ı' to "î", 'u' to "û", 'ü' to "û", 'a' to "â", 'o' to "ô")

        /** Optional row above the letters; long press gives the symbol Samsung puts there. */
        val NUMBER_ROW = "1234567890".zip("!@#₺%^&*()").map { (d, sym) -> Key(d.toString(), sym.toString()) }

        val LETTER_ROWS = listOf(
            row("qwertyuıopğü"),
            row("asdfghjklşi"),
            listOf(Key(special = Special.SHIFT, weight = 1.5f)) + row("zxcvbnmöç") +
                Key(special = Special.BACKSPACE, weight = 1.5f),
        )

        /** Kurmanji alphabet: ê î û get their own keys; Turkish-only ı ğ ö ü are gone. */
        val KU_ROWS = listOf(
            row("qweêrtyuûiîop", emptyMap()),
            row("asdfghjklş", emptyMap()),
            listOf(Key(special = Special.SHIFT, weight = 1.5f)) + row("zxcçvbnm", emptyMap()) +
                Key(special = Special.BACKSPACE, weight = 1.5f),
        )

        val SYMBOL_ROWS = listOf(
            row("1234567890"),
            row("@#₺_&-+()/"),
            row("*\"':;!?%=") + Key(special = Special.BACKSPACE, weight = 1.5f),
            listOf(
                Key("ABC", special = Special.LETTERS, weight = 1.5f),
                Key(","),
                Key("Kurmancî", special = Special.SPACE, weight = 5f),
                Key("."),
                Key(special = Special.ENTER, weight = 1.5f),
            ),
        )
    }
}
