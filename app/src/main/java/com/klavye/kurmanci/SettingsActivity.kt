package com.klavye.kurmanci

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView

/**
 * Keyboard settings, styled like a system settings page (grouped rounded cards on a grey background). There is no
 * launcher icon: it opens from the system keyboard list or the gear in the keyboard's suggestion strip.
 */
class SettingsActivity : Activity() {

    companion object {
        /** Open the "Kelimelerim" editor prefilled with this Turkish word (and optionally a Kurmanji draft). */
        const val EXTRA_TURKISH = "turkish"
        const val EXTRA_KURMANJI = "kurmanji"
    }

    private lateinit var prefs: Prefs
    private lateinit var setupCard: LinearLayout
    private lateinit var themeStrip: LinearLayout
    private lateinit var themeScroll: HorizontalScrollView
    private lateinit var testField: EditText
    private lateinit var dictInfo: TextView
    private lateinit var myWords: LinearLayout

    private val night get() = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
    private val pageBg get() = if (night) 0xFF000000.toInt() else 0xFFF2F2F7.toInt()
    private val cardBg get() = if (night) 0xFF1C1C1E.toInt() else 0xFFFFFFFF.toInt()
    private val secondary = 0xFF8E8E93.toInt()
    private val accent = 0xFF2E7D32.toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(24))
            // Take initial focus so the page opens at the top instead of jumping to the test field.
            isFocusableInTouchMode = true
        }

        col.addView(TextView(this).apply {
            text = "←"
            gravity = Gravity.CENTER
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 24f)
            contentDescription = "Geri"
            setOnClickListener { finish() }
        }, LinearLayout.LayoutParams(dp(48), dp(48)))
        col.addView(TextView(this).apply {
            text = getString(R.string.ime_name)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 30f)
            setPadding(dp(16), dp(8), dp(16), dp(20))
        })

        setupCard = card()
        col.addView(setupCard)

        // At the top so the field stays above the keyboard: the page gets no IME insets to scroll by.
        col.addView(header("Dene"))
        val tryCard = card()
        // Behaves like a chat box so the enter key shows its "Bişîne" (send) label.
        testField = EditText(this).apply {
            hint = "Türkçe yazın: merhaba nasılsın"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            imeOptions = EditorInfo.IME_ACTION_SEND
            background = null
            setPadding(dp(16), dp(14), dp(16), dp(14))
            setOnEditorActionListener { v, action, _ ->
                if (action == EditorInfo.IME_ACTION_SEND) { (v as EditText).text.clear(); true } else false
            }
        }
        tryCard.addView(testField)
        col.addView(tryCard)


        col.addView(header("Görünüm"))
        val look = card()
        look.addView(rowTitle("Tema"))
        themeStrip = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(12), 0, dp(12), dp(12))
        }
        themeScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(themeStrip)
        }
        look.addView(themeScroll)
        look.addView(divider())
        // Keyboard height, like Samsung's "Size": a slider over the letter-row height, 100% = default.
        val heightValue = TextView(this).apply { setTextColor(secondary); setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f) }
        fun showHeight(dpValue: Int) { heightValue.text = "%${dpValue * 100 / KeyHeight.DEFAULT}" }
        look.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, dp(16), 0)
            addView(rowTitle("Klavye yüksekliği"), LinearLayout.LayoutParams(0, -2, 1f))
            addView(heightValue)
        })
        val slider = SeekBar(this).apply {
            max = KeyHeight.MAX - KeyHeight.MIN
            progress = prefs.rowHeightDp - KeyHeight.MIN
            setPadding(dp(20), dp(4), dp(20), dp(4))
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) = showHeight(KeyHeight.MIN + p)
                override fun onStartTrackingTouch(sb: SeekBar) {}
                override fun onStopTrackingTouch(sb: SeekBar) {
                    prefs.rowHeightDp = KeyHeight.MIN + sb.progress
                    applied()
                }
            })
        }
        look.addView(slider)
        look.addView(TextView(this).apply {
            text = "Varsayılan"
            setTextColor(accent)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setPadding(dp(16), dp(2), dp(16), dp(12))
            setOnClickListener {
                prefs.rowHeightDp = KeyHeight.DEFAULT
                slider.progress = KeyHeight.DEFAULT - KeyHeight.MIN
                applied()
            }
        })
        showHeight(prefs.rowHeightDp)
        look.addView(divider())
        look.addView(switchRow("Sayı satırı", "Harflerin üstünde 1–0", prefs.numberRow) {
            prefs.numberRow = it
            applied()
        })
        col.addView(look)

        col.addView(header("Yazma"))
        val typing = card()
        typing.addView(rowTitle("Titreşim"))
        typing.addView(RadioGroup(this).apply {
            orientation = RadioGroup.HORIZONTAL
            setPadding(dp(12), 0, dp(8), dp(12))
            Vibration.entries.forEach { vib ->
                addView(RadioButton(this@SettingsActivity).apply {
                    id = View.generateViewId()
                    text = vib.label
                    isChecked = vib == prefs.vibration
                    setOnCheckedChangeListener { b, on ->
                        if (!on) return@setOnCheckedChangeListener
                        prefs.vibration = vib
                        // Let the user feel the new strength right away.
                        vib.haptic?.let { b.performHapticFeedback(it, HapticFeedbackConstants.FLAG_IGNORE_VIEW_SETTING) }
                    }
                }, RadioGroup.LayoutParams(0, -2, 1f))
            }
        })
        typing.addView(divider())
        typing.addView(switchRow("Tuş önizlemesi", "Basılan harf tuşun üstünde büyük görünür", prefs.keyPreview) {
            prefs.keyPreview = it
        })
        col.addView(typing)

        col.addView(header("Kelimelerim"))
        myWords = card()
        col.addView(myWords)

        col.addView(header("Bilgi"))
        val info = card()
        dictInfo = TextView(this).apply { setTextColor(secondary) }
        info.addView(textRow("Sözlük", dictInfo))
        info.addView(divider())
        info.addView(textRow("Hakkında", null).apply { setOnClickListener { showAbout() } })
        col.addView(info)

        val page = ScrollView(this).apply {
            setBackgroundColor(pageBg)
            addView(col)
            // Edge-to-edge (Android 15+): keep content clear of the status bar, nav bar and keyboard.
            setOnApplyWindowInsetsListener { v, insets ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.ime())
                    v.setPadding(0, bars.top, 0, bars.bottom)
                } else {
                    @Suppress("DEPRECATION")
                    v.setPadding(0, insets.systemWindowInsetTop, 0, insets.systemWindowInsetBottom)
                }
                insets
            }
        }
        setContentView(page)
        // Edge-to-edge leaves the status/nav bar icons white; on the light page they'd be invisible.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bits = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
            window.insetsController?.setSystemBarsAppearance(if (night) 0 else bits, bits)
        }
        renderThemes()
        renderMyWords()
        // Opened from a long press on a suggestion (or "Karşılık yok"): go straight to the editor.
        intent.getStringExtra(EXTRA_TURKISH)?.let { tr ->
            page.post { wordDialog(tr, intent.getStringExtra(EXTRA_KURMANJI).orEmpty(), editing = null) }
        }
        col.requestFocus()
        page.post { page.scrollTo(0, 0) }
        // Bring the selected theme into view.
        themeScroll.post {
            themeStrip.getChildAt(Theme.ALL.indexOf(prefs.theme))?.let { themeScroll.scrollTo(maxOf(0, it.left - dp(24)), 0) }
        }
        DictionaryHolder.load(this) { dict ->
            runOnUiThread { dictInfo.text = "%,d kayıt · çevrimdışı".format(dict.size).replace(',', '.') }
        }
    }

    override fun onResume() {
        super.onResume()
        renderSetupCard()
    }

    /** Only shown when the keyboard still needs enabling or selecting. */
    private fun renderSetupCard() {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        val enabled = imm.enabledInputMethodList.any { it.packageName == packageName }
        val current = Settings.Secure.getString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
            ?.startsWith("$packageName/") == true
        setupCard.removeAllViews()
        if (enabled && current) {
            setupCard.visibility = View.GONE
            return
        }
        setupCard.visibility = View.VISIBLE
        setupCard.addView(TextView(this).apply {
            text = if (!enabled) "Kurmancî Klavye henüz etkin değil." else "Şu an başka bir klavye seçili."
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setPadding(dp(16), dp(14), dp(16), dp(4))
        })
        setupCard.addView(Button(this).apply {
            text = if (!enabled) "Etkinleştir" else "Kurmancî Klavye'yi seç"
            isAllCaps = false
            setOnClickListener {
                if (!enabled) startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
                else imm.showInputMethodPicker()
            }
        }, LinearLayout.LayoutParams(-2, -2).apply { setMargins(dp(12), 0, dp(12), dp(8)) })
    }

    private fun renderThemes() {
        val current = prefs.theme
        themeStrip.removeAllViews()
        Theme.ALL.forEach { t -> themeStrip.addView(themeCard(t, t == current), LinearLayout.LayoutParams(dp(92), -2)) }
    }

    /** A miniature keyboard in the theme's colors. */
    private fun themeCard(t: Theme, selected: Boolean): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(4), dp(4), dp(4), dp(4))
            setOnClickListener { prefs.theme = t; applied() }
        }
        val preview = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(5), dp(5), dp(5), dp(5))
            background = (t.background() as GradientDrawable).apply {
                cornerRadius = dp(10).toFloat()
                if (selected) setStroke(dp(3), accent) else setStroke(dp(1), 0x33888888)
            }
        }
        preview.addView(TextView(this).apply {
            text = "silav"
            setTextColor(t.chipText)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
            setPadding(dp(5), 0, dp(5), 0)
            background = GradientDrawable().apply { setColor(t.chip); cornerRadius = dp(8).toFloat() }
        }, LinearLayout.LayoutParams(-2, -2).apply { bottomMargin = dp(3) })
        repeat(3) { r ->
            val keys = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            repeat(4) { i ->
                keys.addView(View(this).apply {
                    background = GradientDrawable().apply {
                        setColor(if (r == 2 && i == 3) t.keySpecial else t.keyColor(r))
                        t.keyStroke?.let { setStroke(1, it) }
                        cornerRadius = dp(3).toFloat()
                    }
                }, LinearLayout.LayoutParams(0, dp(11), 1f).apply { setMargins(dp(1), dp(1), dp(1), dp(1)) })
            }
            preview.addView(keys)
        }
        card.addView(preview, LinearLayout.LayoutParams(-1, -2))
        card.addView(TextView(this).apply {
            text = t.name
            gravity = Gravity.CENTER
            isSingleLine = true
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            if (selected) { setTextColor(accent); setTypeface(typeface, Typeface.BOLD) }
            setPadding(0, dp(4), 0, 0)
        })
        return card
    }

    // --- "Kelimelerim": the user's own translations ---

    private fun renderMyWords() {
        myWords.removeAllViews()
        myWords.addView(textRow("＋  Kelime ekle", null).apply { setOnClickListener { wordDialog("", "", editing = null) } })
        val words = UserWords(prefs.userWordsText).all()
        if (words.isEmpty()) {
            myWords.addView(divider())
            myWords.addView(TextView(this).apply {
                text = "Klavyede bir öneriye uzun basarak da çeviriyi düzeltebilir ya da kendi kelimenizi ekleyebilirsiniz. " +
                    "Eklediğiniz karşılıklar her zaman ilk sırada çıkar."
                setTextColor(secondary)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                setPadding(dp(16), dp(10), dp(16), dp(14))
            })
        }
        for ((tr, ku) in words) {
            myWords.addView(divider())
            myWords.addView(textRow(tr, TextView(this).apply {
                text = ku
                setTextColor(accent)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            }).apply { setOnClickListener { wordDialog(tr, ku, editing = tr to ku) } })
        }
    }

    /** Add or edit a pair; [editing] is the pair being replaced (null when adding). */
    private fun wordDialog(turkish: String, kurmanji: String, editing: Pair<String, String>?) {
        val trField = EditText(this).apply { hint = "Türkçe"; setText(turkish); setSingleLine() }
        val kuField = EditText(this).apply { hint = "Kurmancî"; setText(kurmanji); setSingleLine() }
        val form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
            addView(trField)
            addView(kuField)
        }
        val b = AlertDialog.Builder(this)
            .setTitle(if (editing == null) "Kelime ekle" else "Kelimeyi düzenle")
            .setView(form)
            .setPositiveButton("Kaydet") { _, _ ->
                val u = UserWords(prefs.userWordsText)
                editing?.let { (t, k) -> u.remove(t, k) }
                u.add(trField.text.toString(), kuField.text.toString())
                prefs.userWordsText = u.entriesText()
                renderMyWords()
            }
            .setNegativeButton("İptal", null)
        if (editing != null) {
            b.setNeutralButton("Sil") { _, _ ->
                val u = UserWords(prefs.userWordsText)
                u.remove(editing.first, editing.second)
                prefs.userWordsText = u.entriesText()
                renderMyWords()
            }
        }
        // Turkish known (from the keyboard), Kurmanji to fill in: start there, with the keyboard already up
        // (the soft-input mode must be set before the dialog shows).
        val dialog = b.create()
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)
        dialog.show()
        (if (turkish.isNotEmpty()) kuField else trField).requestFocus()
    }

    private fun showAbout() {
        AlertDialog.Builder(this)
            .setTitle("Hakkında")
            .setMessage(
                "Kurmancî Klavye — Türkçe yazın, Kurmancî gönderin. İnternet kullanmaz.\n\n" +
                    "Sözlük verisinin çoğu Wiktionary katkıcılarının çalışmasıdır ve CC BY-SA 4.0 lisansıyla " +
                    "kullanılmıştır (kaikki.org üzerinden)."
            )
            .setPositiveButton("Tamam", null)
            .show()
    }

    /**
     * Re-render the theme strip and, if the test field has the keyboard open, show it again with the new look.
     * Always a hide/show cycle: a view swapped in while the IME is showing isn't re-measured reliably, which
     * clipped the bottom row.
     */
    private fun applied() {
        renderThemes()
        if (!testField.hasFocus()) return
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(testField.windowToken, 0)
        testField.postDelayed({ imm.showSoftInput(testField, 0) }, 250)
    }

    // --- system-settings-like building blocks ---

    private fun card() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = GradientDrawable().apply { setColor(cardBg); cornerRadius = dp(18).toFloat() }
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) }
    }

    private fun header(t: String) = TextView(this).apply {
        text = t
        setTextColor(secondary)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        setPadding(dp(16), dp(20), dp(16), dp(4))
    }

    private fun rowTitle(t: String) = TextView(this).apply {
        text = t
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        setPadding(dp(16), dp(14), dp(16), dp(8))
    }

    private fun divider() = View(this).apply {
        setBackgroundColor(0x22888888)
        layoutParams = LinearLayout.LayoutParams(-1, 1).apply { setMargins(dp(16), 0, dp(16), 0) }
    }

    private fun switchRow(title: String, summary: String, checked: Boolean, onChange: (Boolean) -> Unit) =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(12))
            val texts = LinearLayout(this@SettingsActivity).apply { orientation = LinearLayout.VERTICAL }
            texts.addView(TextView(this@SettingsActivity).apply { text = title; setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f) })
            texts.addView(TextView(this@SettingsActivity).apply {
                text = summary
                setTextColor(secondary)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            })
            addView(texts, LinearLayout.LayoutParams(0, -2, 1f))
            val sw = Switch(this@SettingsActivity).apply {
                isChecked = checked
                setOnCheckedChangeListener { _, on -> onChange(on) }
            }
            addView(sw)
            setOnClickListener { sw.toggle() }
        }

    private fun textRow(title: String, value: TextView?) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(16), dp(14), dp(16), dp(14))
        addView(TextView(this@SettingsActivity).apply {
            text = title
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        }, LinearLayout.LayoutParams(0, -2, 1f))
        value?.let { addView(it) }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
