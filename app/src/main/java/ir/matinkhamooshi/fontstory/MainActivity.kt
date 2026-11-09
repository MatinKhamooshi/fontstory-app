package ir.matinkhamooshi.fontstory

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.InputFilter
import android.text.SpannableStringBuilder
import android.text.TextWatcher
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.AdapterView
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import ir.matinkhamooshi.fontstory.font.FontFamily
import ir.matinkhamooshi.fontstory.font.FontLibrary
import ir.matinkhamooshi.fontstory.model.Align
import ir.matinkhamooshi.fontstory.model.ColorMark
import ir.matinkhamooshi.fontstory.model.Document
import ir.matinkhamooshi.fontstory.model.Exporter
import ir.matinkhamooshi.fontstory.model.FontMark
import ir.matinkhamooshi.fontstory.model.Marks
import ir.matinkhamooshi.fontstory.model.ProjectStore
import ir.matinkhamooshi.fontstory.model.SizeMark
import ir.matinkhamooshi.fontstory.model.TextLayer
import ir.matinkhamooshi.fontstory.ui.ArtboardView
import ir.matinkhamooshi.fontstory.ui.ColorPicker
import ir.matinkhamooshi.fontstory.ui.FamilyAdapter
import ir.matinkhamooshi.fontstory.ui.StyledEditText
import ir.matinkhamooshi.fontstory.ui.UiKit
import ir.matinkhamooshi.fontstory.ui.WeightAdapter
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

class MainActivity : Activity(), ArtboardView.Listener {

    private lateinit var artboard: ArtboardView
    private lateinit var etText: StyledEditText
    private lateinit var spFamily: Spinner
    private lateinit var spWeight: Spinner
    private lateinit var etSize: EditText
    private lateinit var vColor: View
    private lateinit var btnAlign: Button
    private lateinit var editorGroup: View
    private lateinit var tvHint: TextView

    private val handler = Handler(Looper.getMainLooper())
    private val io: ExecutorService = Executors.newSingleThreadExecutor()
    private val prefs by lazy { getSharedPreferences("app", MODE_PRIVATE) }
    private val doc: Document get() = artboard.doc

    private var current: TextLayer? = null
    private var binding = false
    private var selStart = 0
    private var selEnd = 0
    private var families: List<FontFamily> = emptyList()
    private var shownFamily: String? = null
    private var shownStyle: String? = null
    private var shownSize = 110f
    private var shownColor = Color.WHITE
    private var libVersion = -1
    private var backCallback: Any? = null

    private val saveTask = Runnable { saveNow() }

    private val textWatcher = object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        override fun afterTextChanged(s: Editable?) {
            current?.invalidate()
            artboard.invalidate()
            scheduleSave()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        FontLibrary.init(this)
        setContentView(R.layout.activity_main)
        UiKit.applyInsets(this, findViewById(R.id.root))

        artboard = findViewById(R.id.artboard)
        etText = findViewById(R.id.etText)
        spFamily = findViewById(R.id.spFamily)
        spWeight = findViewById(R.id.spWeight)
        etSize = findViewById(R.id.etSize)
        vColor = findViewById(R.id.vColor)
        btnAlign = findViewById(R.id.btnAlign)
        editorGroup = findViewById(R.id.editorGroup)
        tvHint = findViewById(R.id.tvHint)

        artboard.doc = ProjectStore.load(filesDir)
        artboard.listener = this
        setupControls()
        setupBack()
        showEditor(null)
    }

    override fun onResume() {
        super.onResume()
        if (FontLibrary.version != libVersion) {
            libVersion = FontLibrary.version
            families = FontLibrary.families()
            spFamily.adapter = FamilyAdapter(this, families)
            spWeight.adapter = null
            doc.layers.forEach { it.invalidate() }
            current?.let { etText.typeface = FontLibrary.typeface(it.baseStyle) }
            artboard.invalidate()
            syncControls()
        }
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(saveTask)
        saveNow()
    }

    override fun onDestroy() {
        io.shutdown()
        super.onDestroy()
    }

    // ---------------- controls ----------------

    private fun setupControls() {
        findViewById<Button>(R.id.btnAdd).setOnClickListener { addLayer() }
        findViewById<Button>(R.id.btnFonts).setOnClickListener { startActivity(Intent(this, FontsActivity::class.java)) }
        findViewById<Button>(R.id.btnExport).setOnClickListener { exportDialog() }
        findViewById<Button>(R.id.btnMore).setOnClickListener { showMenu(it) }

        etText.filters = arrayOf(InputFilter.LengthFilter(TextLayer.MAX_TEXT))
        etText.onSelection = { s, e ->
            if (!binding && current != null && etText.hasFocus()) {
                selStart = min(s, e); selEnd = max(s, e)
                syncControls()
            }
        }

        spFamily.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) = onFamilyPicked(pos)
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
        spWeight.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) = onWeightPicked(pos)
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }

        findViewById<Button>(R.id.btnMinus).setOnClickListener { stepSize(-1) }
        findViewById<Button>(R.id.btnPlus).setOnClickListener { stepSize(+1) }
        etSize.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (binding) return
                val v = s.toString().toIntOrNull() ?: return
                if (v >= TextLayer.MIN_SIZE) applySize(v.toFloat())
            }
        })

        vColor.setOnClickListener { ColorPicker.show(this, shownColor) { applyColor(it) } }
        btnAlign.setOnClickListener {
            val l = current ?: return@setOnClickListener
            l.changeAlign(when (l.align) { Align.RIGHT -> Align.CENTER; Align.CENTER -> Align.LEFT; Align.LEFT -> Align.RIGHT })
            changed(l)
        }
        findViewById<Button>(R.id.btnSpacing).setOnClickListener { spacingDialog() }
        findViewById<Button>(R.id.btnDup).setOnClickListener {
            val l = current ?: return@setOnClickListener
            val c = l.copy()
            c.anchorX += 40f; c.top += 40f
            doc.layers.add(doc.layers.indexOf(l) + 1, c)
            artboard.select(c)
            scheduleSave()
        }
        findViewById<Button>(R.id.btnDelete).setOnClickListener {
            val l = current ?: return@setOnClickListener
            doc.layers.remove(l)
            artboard.select(null)
            scheduleSave()
        }
    }

    private fun setupBack() {
        if (Build.VERSION.SDK_INT >= 33) {
            val cb = OnBackInvokedCallback { handleBack() }
            onBackInvokedDispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT, cb)
            backCallback = cb
        }
    }

    @Deprecated("Pre-33 back handling")
    override fun onBackPressed() = handleBack()

    private fun handleBack() {
        if (current != null) artboard.select(null) else finish()
    }

    // ---------------- layers ----------------

    private fun addLayer() {
        val styleId = prefs.getString(KEY_LAST_STYLE, null)?.takeIf { FontLibrary.style(it) != null }
            ?: FontLibrary.defaultStyleId()
        val l = TextLayer(SpannableStringBuilder(getString(R.string.default_text)), styleId).apply {
            baseColor = prefs.getInt(KEY_LAST_COLOR, Color.WHITE)
            baseSize = prefs.getFloat(KEY_LAST_SIZE, 110f).coerceIn(TextLayer.MIN_SIZE, TextLayer.MAX_SIZE)
            anchorX = doc.artW / 2f
            top = doc.artH * 0.38f + (doc.layers.size % 5) * baseSize * 1.4f
        }
        doc.layers += l
        artboard.select(l)
        etText.requestFocus()
        etText.selectAll()
        selStart = 0; selEnd = etText.length()
        showKeyboard()
        scheduleSave()
    }

    override fun onLayerSelected(layer: TextLayer?) = showEditor(layer)

    override fun onLayerChanged(layer: TextLayer) {
        if (layer === current) syncControls()
        scheduleSave()
    }

    override fun onLayerDoubleTap(layer: TextLayer) {
        etText.requestFocus()
        showKeyboard()
    }

    private fun showEditor(layer: TextLayer?) {
        current?.let { old ->
            etText.removeTextChangedListener(textWatcher)
            old.text = Marks.cleanCopy(etText.text)
        }
        current = layer
        binding = true
        if (layer == null) {
            etText.setText("")
            binding = false
            editorGroup.visibility = View.GONE
            tvHint.setText(R.string.hint_no_selection)
            etText.clearFocus()
            hideKeyboard()
            return
        }
        etText.setText(layer.text)
        layer.text = etText.text // edits now go straight into the layer
        etText.typeface = FontLibrary.typeface(layer.baseStyle)
        etText.setSelection(etText.length())
        selStart = etText.length(); selEnd = selStart
        binding = false
        etText.addTextChangedListener(textWatcher)
        editorGroup.visibility = View.VISIBLE
        tvHint.setText(R.string.hint_partial)
        syncControls()
    }

    /** Reflects the style under the cursor/selection in the controls. */
    private fun syncControls() {
        val l = current ?: return
        if (families.isEmpty()) return
        binding = true
        try {
            val sp = l.text
            val idx = if (selStart == selEnd) selStart - 1 else selStart
            val styleId = Marks.at(sp, FontMark::class.java, idx)?.styleId ?: l.baseStyle
            val style = FontLibrary.style(styleId)
            var famIdx = families.indexOfFirst { it.name == style?.family }
            if (famIdx < 0) famIdx = families.lastIndex // deleted font -> system
            val fam = families[famIdx]
            shownFamily = fam.name
            if (spFamily.selectedItemPosition != famIdx) spFamily.setSelection(famIdx)

            val wa = spWeight.adapter as? WeightAdapter
            if (wa == null || wa.family != fam.name) spWeight.adapter = WeightAdapter(this, fam.name, fam.styles)
            var wIdx = fam.styles.indexOfFirst { it.id == styleId }
            if (wIdx < 0) wIdx = fam.styles.indexOf(FontLibrary.closest(fam, 400, false))
            shownStyle = fam.styles[wIdx].id
            if (spWeight.selectedItemPosition != wIdx) spWeight.setSelection(wIdx)

            shownSize = Marks.at(sp, SizeMark::class.java, idx)?.px ?: l.baseSize
            val sizeText = shownSize.roundToInt().toString()
            if (!(etSize.hasFocus() && etSize.text.toString().toIntOrNull() == shownSize.roundToInt())) {
                etSize.setText(sizeText)
            }

            shownColor = Marks.at(sp, ColorMark::class.java, idx)?.color ?: l.baseColor
            vColor.background = GradientDrawable().apply {
                cornerRadius = UiKit.dp(this@MainActivity, 10).toFloat()
                setColor(shownColor)
                setStroke(UiKit.dp(this@MainActivity, 2), 0x66FFFFFF)
            }
            btnAlign.setText(
                when (l.align) {
                    Align.RIGHT -> R.string.align_right
                    Align.CENTER -> R.string.align_center
                    Align.LEFT -> R.string.align_left
                }
            )
        } finally {
            binding = false
        }
    }

    private fun onFamilyPicked(pos: Int) {
        if (binding || pos !in families.indices) return
        val fam = families[pos]
        if (fam.name == shownFamily) return
        val cur = shownStyle?.let { FontLibrary.style(it) }
        val target = FontLibrary.closest(fam, cur?.weight ?: 400, cur?.italic ?: false)
        applyStyle(target.id)
    }

    private fun onWeightPicked(pos: Int) {
        if (binding) return
        val wa = spWeight.adapter as? WeightAdapter ?: return
        if (pos !in wa.styles.indices) return
        val s = wa.styles[pos]
        if (s.id == shownStyle) return
        applyStyle(s.id)
    }

    /** (start, end, whole) — whole when nothing or everything is selected. */
    private fun range(l: TextLayer): Triple<Int, Int, Boolean> {
        val len = l.text.length
        val s = selStart.coerceIn(0, len)
        val e = selEnd.coerceIn(0, len)
        return Triple(s, e, s == e || (s == 0 && e == len))
    }

    private fun applyStyle(id: String) {
        val l = current ?: return
        val (s, e, whole) = range(l)
        if (whole) {
            l.baseStyle = id
            Marks.clear(l.text, FontMark::class.java)
            etText.typeface = FontLibrary.typeface(id)
        } else {
            Marks.setRange(l.text, FontMark::class.java, s, e, FontMark(id)) { FontMark(it.styleId) }
        }
        prefs.edit().putString(KEY_LAST_STYLE, id).apply()
        changed(l)
    }

    private fun applySize(px: Float) {
        val l = current ?: return
        val v = px.coerceIn(TextLayer.MIN_SIZE, TextLayer.MAX_SIZE)
        val (s, e, whole) = range(l)
        if (whole) {
            l.baseSize = v
            Marks.clear(l.text, SizeMark::class.java)
            prefs.edit().putFloat(KEY_LAST_SIZE, v).apply()
        } else {
            Marks.setRange(l.text, SizeMark::class.java, s, e, SizeMark(v)) { SizeMark(it.px) }
        }
        changed(l)
    }

    private fun stepSize(dir: Int) {
        val cur = shownSize
        val step = when {
            cur < 40 -> 2f
            cur < 200 -> 4f
            else -> 10f
        }
        applySize(cur + dir * step)
    }

    private fun applyColor(c: Int) {
        val l = current ?: return
        val (s, e, whole) = range(l)
        if (whole) {
            l.baseColor = c
            Marks.clear(l.text, ColorMark::class.java)
            prefs.edit().putInt(KEY_LAST_COLOR, c).apply()
        } else {
            Marks.setRange(l.text, ColorMark::class.java, s, e, ColorMark(c)) { ColorMark(it.color) }
        }
        changed(l)
    }

    private fun changed(l: TextLayer) {
        l.invalidate()
        artboard.invalidate()
        scheduleSave()
        syncControls()
    }

    private fun spacingDialog() {
        val l = current ?: return
        val label = TextView(this).apply { textSize = 16f; setPadding(0, 0, 0, UiKit.dp(this@MainActivity, 8)) }
        val bar = SeekBar(this).apply { max = 250; progress = (l.lineSpacing * 100).roundToInt() - 50 }
        fun show() { label.text = getString(R.string.line_spacing) + ": " + "%.2f".format(l.lineSpacing) }
        show()
        bar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar, p: Int, user: Boolean) {
                l.lineSpacing = (p + 50) / 100f
                show(); changed(l)
            }
            override fun onStartTrackingTouch(s: SeekBar) {}
            override fun onStopTrackingTouch(s: SeekBar) {}
        })
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val p = UiKit.dp(this@MainActivity, 20)
            setPadding(p, p, p, 0)
            addView(label); addView(bar)
        }
        AlertDialog.Builder(this).setView(box).setPositiveButton(R.string.close, null).show()
    }

    // ---------------- menu ----------------

    private fun showMenu(anchor: View) {
        PopupMenu(this, anchor).apply {
            menu.add(0, 1, 0, R.string.menu_artboard)
            menu.add(0, 2, 1, R.string.menu_bg)
            menu.add(0, 3, 2, R.string.menu_clear)
            setOnMenuItemClickListener {
                when (it.itemId) {
                    1 -> artboardDialog()
                    2 -> ColorPicker.show(this@MainActivity, doc.previewBg, allowTransparent = true) { c ->
                        doc.previewBg = c; artboard.invalidate(); scheduleSave()
                    }
                    3 -> AlertDialog.Builder(this@MainActivity)
                        .setMessage(R.string.confirm_clear)
                        .setPositiveButton(R.string.delete) { _, _ ->
                            artboard.select(null); doc.layers.clear(); artboard.invalidate(); scheduleSave()
                        }
                        .setNegativeButton(R.string.cancel, null).show()
                }
                true
            }
        }.show()
    }

    private fun artboardDialog() {
        val sizes = arrayOf(1080 to 1920, 1080 to 1350, 1080 to 1080)
        val names = arrayOf(getString(R.string.art_story), getString(R.string.art_portrait), getString(R.string.art_square))
        AlertDialog.Builder(this).setTitle(R.string.menu_artboard).setItems(names) { _, w ->
            doc.artW = sizes[w].first; doc.artH = sizes[w].second
            artboard.invalidate(); scheduleSave()
        }.show()
    }

    // ---------------- export ----------------

    private fun exportDialog() {
        if (doc.layers.all { it.isBlank() }) {
            toast(R.string.export_empty); return
        }
        val opts = arrayOf(getString(R.string.export_1x), getString(R.string.export_2x), getString(R.string.export_3x))
        var choice = (prefs.getInt(KEY_EXPORT_SCALE, 1) - 1).coerceIn(0, 2)
        AlertDialog.Builder(this)
            .setTitle(R.string.export_title)
            .setSingleChoiceItems(opts, choice) { _, w -> choice = w }
            .setPositiveButton(R.string.export_save) { _, _ ->
                prefs.edit().putInt(KEY_EXPORT_SCALE, choice + 1).apply()
                doExport((choice + 1).toFloat())
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun doExport(scale: Float) {
        val snapshot = doc.layers.map { it.copy() }
        val wait = AlertDialog.Builder(this).setMessage(R.string.export_working).setCancelable(false).show()
        val resolver = contentResolver
        io.execute {
            val result = runCatching {
                Exporter.render(snapshot, scale)?.let { out ->
                    try {
                        Triple(Exporter.savePng(resolver, out.bitmap), out.width, out.height)
                    } finally {
                        out.bitmap.recycle()
                    }
                }
            }
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                wait.dismiss()
                val r = result.getOrNull()
                when {
                    result.isFailure -> toast(R.string.export_failed)
                    r == null -> toast(R.string.export_empty)
                    else -> AlertDialog.Builder(this)
                        .setMessage(getString(R.string.export_done, r.second, r.third))
                        .setPositiveButton(R.string.share) { _, _ -> share(r.first) }
                        .setNegativeButton(R.string.close, null)
                        .show()
                }
            }
        }
    }

    private fun share(uri: Uri) {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = ClipData.newRawUri("", uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(send, getString(R.string.share)))
    }

    // ---------------- misc ----------------

    private fun scheduleSave() {
        handler.removeCallbacks(saveTask)
        handler.postDelayed(saveTask, 700)
    }

    private fun saveNow() {
        val json = doc.toJson().toString()
        val dir = filesDir
        if (!io.isShutdown) io.execute { ProjectStore.save(dir, json) } else ProjectStore.save(dir, json)
    }

    private fun showKeyboard() {
        etText.post {
            getSystemService(InputMethodManager::class.java)?.showSoftInput(etText, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    private fun hideKeyboard() {
        getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(etText.windowToken, 0)
    }

    private fun toast(res: Int) = Toast.makeText(this, res, Toast.LENGTH_SHORT).show()

    companion object {
        private const val KEY_LAST_STYLE = "lastStyle"
        private const val KEY_LAST_SIZE = "lastSize"
        private const val KEY_LAST_COLOR = "lastColor"
        private const val KEY_EXPORT_SCALE = "exportScale"
    }
}
