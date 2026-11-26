package com.klavye.kurmanci

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.inputmethodservice.InputMethodService
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.text.SpannableStringBuilder
import android.text.TextUtils
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * The keyboard service. The user types Turkish; the strip above the keys shows Kurmanji translations
 * of the word (or phrase) before the cursor, and tapping one replaces the Turkish text with it.
 */
class KurmanciIME : InputMethodService(), KeyboardView.Listener {

    private lateinit var keyboard: KeyboardView
    private var inputRoot: LinearLayout? = null
    private var emojiButton: TextView? = null
    private lateinit var strip: LinearLayout
    private var dictionary: Dictionary? = null
    private var translator: Pair<Dictionary, Translator>? = null
    private var lastInserted: String? = null
    private var suggestionsEnabled = true
    private lateinit var prefs: Prefs
    private lateinit var theme: Theme
    private var rowHeight = KeyHeight.DEFAULT
    private var numberRow = true
    private val mainHandler = Handler(Looper.getMainLooper())

    private lateinit var userWords: UserWords
    private var userWordsText = ""
    private var reverse: ReverseTranslator? = null
    /** Kurmanji/reverse indexes are built in the background; until then those features stay quiet. */
    @Volatile private var kurmanjiReady = false

    /** Recent clipboard texts, newest first. Memory only: clips can be private, so they are never written to disk. */
    private val clips = ArrayList<String>()
    private var lastClipAt = 0L
    private var clipboardMode = false
    private lateinit var panel: LinearLayout
    private val clipListener = ClipboardManager.OnPrimaryClipChangedListener { readClip(); refreshSuggestions() }

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        loadUserWords()
        (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).addPrimaryClipChangedListener(clipListener)
        DictionaryHolder.load(this) { dict ->
            dict.user = userWords
            mainHandler.post { dictionary = dict; refreshSuggestions() }
            Thread {
                dict.warmUpKurmanji()
                kurmanjiReady = true
                mainHandler.post { reverse = ReverseTranslator(dict); refreshSuggestions() }
            }.apply { priority = Thread.MIN_PRIORITY }.start()
        }
    }

    override fun onDestroy() {
        (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).removePrimaryClipChangedListener(clipListener)
        super.onDestroy()
    }

    /** Re-reads "Kelimelerim" if SettingsActivity changed it. */
    private fun loadUserWords() {
        val text = prefs.userWordsText
        if (::userWords.isInitialized && text == userWordsText) return
        userWordsText = text
        userWords = UserWords(text, prefs.usageText)
        dictionary?.user = userWords
    }

    private fun readClip() {
        val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        val clip = cm.primaryClip ?: return
        val sensitive = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            clip.description.extras?.getBoolean(ClipDescription.EXTRA_IS_SENSITIVE) == true
        if (sensitive || clip.itemCount == 0) return
        val text = clip.getItemAt(0).coerceToText(this)?.toString()?.trim().orEmpty()
        if (text.isEmpty() || text.length > 2000) return
        if (clips.firstOrNull() != text) lastClipAt = System.currentTimeMillis()
        clips.remove(text)
        clips.add(0, text)
        while (clips.size > MAX_CLIPS) clips.removeAt(clips.size - 1)
    }

    /**
     * The root is created once and only its contents are rebuilt when settings change: a fresh root passed to
     * setInputView doesn't get window insets re-dispatched, so it lost its nav-bar padding and the bottom row
     * slid under the navigation bar.
     */
    override fun onCreateInputView(): View {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        // Edge-to-edge (Android 15+) draws the IME under the navigation bar; keep the bottom row clear of it.
        root.setOnApplyWindowInsetsListener { v, insets ->
            val bottom = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                insets.getInsets(WindowInsets.Type.navigationBars()).bottom
            } else {
                @Suppress("DEPRECATION") insets.systemWindowInsetBottom
            }
            v.setPadding(0, 0, 0, bottom)
            insets
        }
        inputRoot = root
        buildInputView()
        return root
    }

    override fun onWindowShown() {
        super.onWindowShown()
        styleNavigationBar()
    }

    /**
     * Let the keyboard background run behind the navigation bar, as Samsung's keyboard does, instead of leaving
     * a separate grey band under the keys (3-button navigation). The root's inset padding keeps keys above it.
     */
    private fun styleNavigationBar() {
        val w = window?.window ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            w.setDecorFitsSystemWindows(false)
            val light = Color.luminance(theme.bgEnd ?: theme.bg) > 0.5f
            w.insetsController?.setSystemBarsAppearance(
                if (light) WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS else 0,
                WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS,
            )
        }
        @Suppress("DEPRECATION")
        w.navigationBarColor = Color.TRANSPARENT
        // No translucent contrast scrim over the keyboard background.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) w.isNavigationBarContrastEnforced = false
        inputRoot?.requestApplyInsets()
    }

    private fun buildInputView() {
        val root = inputRoot ?: return
        theme = prefs.theme
        rowHeight = prefs.rowHeightDp
        numberRow = prefs.numberRow
        root.removeAllViews()
        root.background = theme.background()
        strip = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), 0, dp(4), 0)
        }
        val scroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(strip)
        }
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        // Emoji toggle at the start of the strip, where Samsung's keyboard has it.
        emojiButton = TextView(this).apply {
            gravity = Gravity.CENTER
            setTextColor(theme.text)
            setOnClickListener {
                keyboard.toggleEmoji()
                updateEmojiButton()
            }
        }
        bar.addView(emojiButton, LinearLayout.LayoutParams(dp(48), -1))
        bar.addView(scroll, LinearLayout.LayoutParams(0, -1, 1f))
        // Clipboard history toggle.
        bar.addView(TextView(this).apply {
            text = "📋"
            gravity = Gravity.CENTER
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
            alpha = 0.8f
            contentDescription = "Pano"
            setOnClickListener {
                clipboardMode = !clipboardMode
                if (clipboardMode) readClip()
                refreshSuggestions()
            }
        }, LinearLayout.LayoutParams(dp(40), -1))
        // Settings gear at the end of the strip, like Samsung's keyboard toolbar.
        bar.addView(TextView(this).apply {
            text = "⚙"
            gravity = Gravity.CENTER
            setTextColor(theme.text)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
            alpha = 0.75f
            contentDescription = "Ayarlar"
            setOnClickListener { openSettings() }
        }, LinearLayout.LayoutParams(dp(44), -1))
        root.addView(bar, LinearLayout.LayoutParams(-1, dp(44)))
        keyboard = KeyboardView(this, this, theme, rowHeight, numberRow, prefs)
        keyboard.kurmanji = prefs.kurmanjiMode
        root.addView(keyboard, LinearLayout.LayoutParams(-1, -2))
        // Kurmanji → Turkish reading panel; takes the keyboard's place at the same height when shown.
        panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            setPadding(dp(14), dp(6), dp(14), dp(8))
        }
        root.addView(panel, LinearLayout.LayoutParams(-1, 0))
        updateEmojiButton()
        styleNavigationBar()
    }

    override fun onStartInputView(info: EditorInfo, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        // Settings may have changed in SettingsActivity since the view was built.
        if (prefs.theme != theme || prefs.rowHeightDp != rowHeight || prefs.numberRow != numberRow) buildInputView()
        loadUserWords()
        keyboard.kurmanji = prefs.kurmanjiMode
        clipboardMode = false
        hidePanel()
        val variation = info.inputType and InputType.TYPE_MASK_VARIATION
        suggestionsEnabled = (info.inputType and InputType.TYPE_MASK_CLASS) == InputType.TYPE_CLASS_TEXT &&
            variation != InputType.TYPE_TEXT_VARIATION_PASSWORD &&
            variation != InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD &&
            variation != InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD
        // Android 10+ puts a keyboard switcher in the navigation bar; a globe key would just duplicate it.
        keyboard.showGlobe = when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q -> false
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.P -> shouldOfferSwitchingToNextInputMethod()
            else -> imeToken()?.let { imm().shouldOfferSwitchingToNextInputMethod(it) } ?: false
        }
        keyboard.enterLabel = enterLabel(info)
        keyboard.shift = KeyboardView.Shift.OFF
        keyboard.resetMode()
        updateEmojiButton()
        updateAutoCaps()
        refreshSuggestions()
    }

    override fun onUpdateSelection(
        oldSelStart: Int, oldSelEnd: Int, newSelStart: Int, newSelEnd: Int,
        candidatesStart: Int, candidatesEnd: Int,
    ) {
        super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd)
        updateAutoCaps()
        refreshSuggestions()
    }

    // --- KeyboardView.Listener ---

    override fun onText(text: String) {
        currentInputConnection?.commitText(text, 1)
    }

    override fun onBackspace() {
        val ic = currentInputConnection ?: return
        if (!ic.getSelectedText(0).isNullOrEmpty()) ic.commitText("", 1)
        else sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL)
    }

    override fun onEnter() {
        val info = currentInputEditorInfo
        val action = info?.imeOptions?.and(EditorInfo.IME_MASK_ACTION) ?: EditorInfo.IME_ACTION_NONE
        val noAction = info == null || (info.imeOptions and EditorInfo.IME_FLAG_NO_ENTER_ACTION) != 0 ||
            action == EditorInfo.IME_ACTION_NONE || action == EditorInfo.IME_ACTION_UNSPECIFIED
        if (noAction) sendKeyChar('\n') else currentInputConnection?.performEditorAction(action)
    }

    override fun onModeChanged() = updateEmojiButton()

    override fun onToggleLanguage() {
        prefs.kurmanjiMode = !prefs.kurmanjiMode
        keyboard.kurmanji = prefs.kurmanjiMode
        refreshSuggestions()
    }

    override fun onCursorMove(delta: Int) {
        val code = if (delta < 0) KeyEvent.KEYCODE_DPAD_LEFT else KeyEvent.KEYCODE_DPAD_RIGHT
        repeat(kotlin.math.abs(delta)) { sendDownUpKeyEvents(code) }
    }

    /** 😊 on the letters, a keyboard icon on the emoji panel (Samsung's way back). */
    private fun updateEmojiButton() {
        val b = emojiButton ?: return
        if (keyboard.isEmoji) {
            b.text = ""
            b.contentDescription = "Klavye"
            b.background = getDrawable(R.drawable.ic_keyboard)?.mutate()?.apply { setTint(theme.text) }?.let {
                InsetDrawable(it, dp(12), dp(10), dp(12), dp(10))
            }
        } else {
            b.background = null
            b.text = "😊"
            b.contentDescription = "Emoji"
            b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
        }
    }

    /** Opens settings; with [addTurkish] it opens the "Kelimelerim" editor prefilled for that word. */
    private fun openSettings(addTurkish: String? = null, addKurmanji: String? = null) {
        requestHideSelf(0)
        // CLEAR_TASK: one settings screen at a time, never a stack of them to back out of one by one.
        startActivity(
            Intent(this, SettingsActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                .apply {
                    addTurkish?.let { putExtra(SettingsActivity.EXTRA_TURKISH, it) }
                    addKurmanji?.let { putExtra(SettingsActivity.EXTRA_KURMANJI, it) }
                },
        )
    }

    override fun onImePicker() {
        imm().showInputMethodPicker()
    }

    private fun imm() = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager

    /** Pre-Android 9 the switching APIs live on InputMethodManager and need the IME window's token. */
    private fun imeToken() = window?.window?.attributes?.token

    override fun onSwitchIme() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            switchToNextInputMethod(false)
        } else {
            @Suppress("DEPRECATION")
            imeToken()?.let { imm().switchToNextInputMethod(it, false) }
        }
    }

    // --- suggestions ---

    private fun refreshSuggestions() {
        if (!::strip.isInitialized) return
        strip.removeAllViews()
        if (clipboardMode) { showClipboard(); return }
        val dict = dictionary
        if (!suggestionsEnabled) return
        if (dict == null) { strip.addView(hint("Sözlük yükleniyor…")); return }
        val before = currentInputConnection?.getTextBeforeCursor(MAX_CONTEXT, 0)?.toString().orEmpty()
        val word = Dictionary.trailingWords(before, 1).firstOrNull()
        if (word == null) copiedKurmanjiChip()?.let { strip.addView(it) }

        if (prefs.kurmanjiMode) {
            if (word == null || !kurmanjiReady) return
            dict.completeKurmanji(word.text).forEach { w ->
                strip.addView(plainChip(Dictionary.matchCase(word.text, w)) { replaceWord(word.text.length, it) })
            }
            return
        }

        val sentence = sentenceSuggestion(dict, before)
        sentence?.let { strip.addView(sentenceChip(it)) }
        val suggestions = dict.suggest(before)
        if (suggestions.isEmpty() && sentence == null) {
            if (word != null) {
                // Nothing found: offer to teach it ("Kelimelerim").
                strip.addView(hint("Karşılık yok · eklemek için dokun").apply {
                    setOnClickListener { openSettings(addTurkish = Dictionary.normalize(word.text)) }
                })
            }
            return
        }
        suggestions.forEach { strip.addView(chip(it)) }
    }

    private fun replaceWord(length: Int, with: String) {
        val ic = currentInputConnection ?: return
        ic.beginBatchEdit()
        ic.deleteSurroundingText(length, 0)
        ic.commitText("$with ", 1)
        ic.endBatchEdit()
    }

    /** Clipboard history in the strip; Kurmanji clips get a "⇄ TR" chip to read them in Turkish. */
    private fun showClipboard() {
        if (clips.isEmpty()) { strip.addView(hint("Pano boş")); return }
        val dict = dictionary
        for (c in clips) {
            strip.addView(plainChip(c.replace('\n', ' '), maxWidth = resources.displayMetrics.widthPixels / 2) {
                currentInputConnection?.commitText(c, 1)
                clipboardMode = false
                refreshSuggestions()
            })
            if (dict != null && kurmanjiReady && ReverseTranslator.looksKurmanji(dict, c)) {
                strip.addView(plainChip("⇄ TR", accent = true) { showTranslation(c) })
            }
        }
    }

    /** Right after copying a Kurmanji message, offer its Turkish gist while nothing is being typed. */
    private fun copiedKurmanjiChip(): View? {
        val dict = dictionary ?: return null
        val c = clips.firstOrNull() ?: return null
        if (!kurmanjiReady || System.currentTimeMillis() - lastClipAt > CLIP_FRESH_MS) return null
        if (::panel.isInitialized && panel.visibility == View.VISIBLE) return null
        if (!ReverseTranslator.looksKurmanji(dict, c)) return null
        return plainChip("⇄ Kopyalananın Türkçesi", accent = true) { showTranslation(c) }
    }

    private fun showTranslation(kurmanji: String) {
        val r = reverse ?: return
        val turkish = r.translate(kurmanji)
        panel.removeAllViews()
        val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        header.addView(TextView(this).apply {
            text = "Türkçesi (yaklaşık)"
            setTextColor(theme.hint)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        }, LinearLayout.LayoutParams(0, -2, 1f))
        header.addView(plainChip("Kopyala") {
            (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("Türkçe", turkish))
        })
        header.addView(plainChip("✕") { hidePanel() })
        panel.addView(header)
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        body.addView(TextView(this).apply {
            text = turkish
            setTextColor(theme.text)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            setPadding(0, dp(6), 0, dp(8))
        })
        body.addView(TextView(this).apply {
            text = kurmanji
            setTextColor(theme.hint)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        })
        panel.addView(ScrollView(this).apply { addView(body) }, LinearLayout.LayoutParams(-1, 0, 1f))
        // Same height as the keys so the window doesn't resize.
        panel.layoutParams = panel.layoutParams.apply { height = keyboard.height }
        keyboard.visibility = View.GONE
        panel.visibility = View.VISIBLE
        clipboardMode = false
        refreshSuggestions()
    }

    private fun hidePanel() {
        if (!::panel.isInitialized || panel.visibility != View.VISIBLE) return
        panel.visibility = View.GONE
        keyboard.visibility = View.VISIBLE
        refreshSuggestions()
    }

    private fun plainChip(label: String, accent: Boolean = false, maxWidth: Int = 0, onTap: (String) -> Unit) =
        TextView(this).apply {
            text = label
            gravity = Gravity.CENTER
            isSingleLine = true
            ellipsize = TextUtils.TruncateAt.END
            if (maxWidth > 0) this.maxWidth = maxWidth
            setTextColor(if (accent) theme.chipText else theme.text)
            setTypeface(typeface, if (accent) Typeface.BOLD else Typeface.NORMAL)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setPadding(dp(14), 0, dp(14), 0)
            background = GradientDrawable().apply {
                setColor(if (accent) theme.chip else theme.chipPrefix)
                cornerRadius = dp(20).toFloat()
            }
            layoutParams = LinearLayout.LayoutParams(-2, dp(38)).apply { setMargins(dp(3), 0, dp(3), 0) }
            setOnClickListener { onTap(label) }
        }

    /** Whole-sentence translation for the sentence ending at the cursor, if it has 2+ words. */
    private fun sentenceSuggestion(dict: Dictionary, before: String): Suggestion? {
        val start = Translator.sentenceStart(before) ?: return null
        val original = before.substring(start).trimEnd()
        if (Translator.wordCount(original) < 2) return null
        // Don't offer to "translate" the Kurmanji sentence we just inserted.
        if (lastInserted?.let { original.endsWith(it) } == true) return null
        val translator = translator?.takeIf { it.first === dict }?.second ?: Translator(dict).also { translator = dict to it }
        val translated = translator.translate(original)
        if (translated.equals(original, ignoreCase = true)) return null
        return Suggestion(original, translated, before.length - start, Suggestion.Kind.PHRASE)
    }

    private fun sentenceChip(s: Suggestion): View {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), 0, dp(12), 0)
            background = GradientDrawable().apply {
                setColor(theme.chip)
                setStroke(dp(2), theme.accent)
                cornerRadius = dp(20).toFloat()
            }
            setOnClickListener { accept(s, learn = false) }
        }
        val maxW = resources.displayMetrics.widthPixels * 7 / 10
        col.addView(TextView(this).apply {
            text = "⇄ ${s.kurmanji}"
            includeFontPadding = false
            setTextColor(theme.chipText)
            setTypeface(typeface, Typeface.BOLD)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            isSingleLine = true
            ellipsize = TextUtils.TruncateAt.START // keep the end visible: that's what was just typed
            maxWidth = maxW
        })
        col.addView(TextView(this).apply {
            text = "cümle çevirisi"
            includeFontPadding = false
            setTextColor(theme.chipText and 0x00FFFFFF or 0xB3000000.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
        })
        col.layoutParams = LinearLayout.LayoutParams(-2, dp(38)).apply { setMargins(dp(3), 0, dp(3), 0) }
        return col
    }

    private fun accept(s: Suggestion, learn: Boolean = true) {
        val ic = currentInputConnection ?: return
        ic.beginBatchEdit()
        ic.deleteSurroundingText(s.replaceLength, 0)
        ic.commitText(s.kurmanji + " ", 1)
        ic.endBatchEdit()
        lastInserted = s.kurmanji
        if (learn) {
            // Learn the preference: next time this translation ranks first among its kind.
            userWords.recordUse(s.turkish, s.kurmanji)
            prefs.usageText = userWords.usageText()
        }
    }

    private fun chip(s: Suggestion): View {
        val sub = when (s.kind) {
            Suggestion.Kind.EXACT -> s.turkish
            Suggestion.Kind.PHRASE -> "“${s.turkish}”"
            Suggestion.Kind.STEM -> "kök: ${s.turkish}"
            Suggestion.Kind.PREFIX -> "${s.turkish}…"
        }
        val prefix = s.kind == Suggestion.Kind.PREFIX
        val fg = if (prefix) theme.text else theme.chipText
        val text = SpannableStringBuilder(s.kurmanji).apply {
            setSpan(StyleSpan(Typeface.BOLD), 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            val start = length
            append("\n").append(sub)
            setSpan(RelativeSizeSpan(0.7f), start, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            setSpan(ForegroundColorSpan(fg and 0x00FFFFFF or 0xB3000000.toInt()), start, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        return TextView(this).apply {
            this.text = text
            gravity = Gravity.CENTER
            setTextColor(fg)
            includeFontPadding = false
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setPadding(dp(12), 0, dp(12), 0)
            background = GradientDrawable().apply {
                setColor(if (prefix) theme.chipPrefix else theme.chip)
                cornerRadius = dp(20).toFloat()
            }
            layoutParams = LinearLayout.LayoutParams(-2, dp(38)).apply { setMargins(dp(3), 0, dp(3), 0) }
            setOnClickListener { accept(s) }
            // Long press: correct this translation or add your own ("Kelimelerim").
            setOnLongClickListener { openSettings(addTurkish = s.turkish, addKurmanji = s.kurmanji); true }
        }
    }

    // Pill background keeps the message readable on gradient themes.
    private fun hint(msg: String) = TextView(this).apply {
        text = msg
        setTextColor(theme.text)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        setPadding(dp(12), dp(6), dp(12), dp(6))
        background = GradientDrawable().apply {
            setColor(theme.chipPrefix)
            cornerRadius = dp(16).toFloat()
        }
        layoutParams = LinearLayout.LayoutParams(-2, -2).apply { setMargins(dp(3), 0, dp(3), 0) }
    }

    /** Turns one-shot shift on at sentence starts and back off elsewhere; never touches caps lock. */
    private fun updateAutoCaps() {
        if (!::keyboard.isInitialized || keyboard.shift == KeyboardView.Shift.LOCKED) return
        val info = currentInputEditorInfo ?: return
        if (info.inputType == InputType.TYPE_NULL) return
        val caps = currentInputConnection?.getCursorCapsMode(info.inputType) ?: 0
        val want = if (caps != 0) KeyboardView.Shift.ONCE else KeyboardView.Shift.OFF
        if (keyboard.shift != want) keyboard.shift = want
    }

    private fun enterLabel(info: EditorInfo): String {
        if ((info.imeOptions and EditorInfo.IME_FLAG_NO_ENTER_ACTION) != 0) return "↵"
        return when (info.imeOptions and EditorInfo.IME_MASK_ACTION) {
            // Kurmanji, like the rest of the keys.
            EditorInfo.IME_ACTION_SEND -> "Bişîne"
            EditorInfo.IME_ACTION_GO -> "Here"
            EditorInfo.IME_ACTION_SEARCH -> "Bigere"
            EditorInfo.IME_ACTION_NEXT -> "Pêşve"
            EditorInfo.IME_ACTION_DONE -> "Temam"
            else -> "↵"
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    companion object {
        /** Enough for a long sentence; the sentence translation needs all of it. */
        private const val MAX_CONTEXT = 400
        private const val MAX_CLIPS = 15
        private const val CLIP_FRESH_MS = 2 * 60 * 1000L
    }
}
