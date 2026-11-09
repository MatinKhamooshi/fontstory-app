package ir.matinkhamooshi.fontstory

import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseExpandableListAdapter
import android.widget.Button
import android.widget.ExpandableListView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import ir.matinkhamooshi.fontstory.font.FontFamily
import ir.matinkhamooshi.fontstory.font.FontImporter
import ir.matinkhamooshi.fontstory.font.FontLibrary
import ir.matinkhamooshi.fontstory.ui.UiKit
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class FontsActivity : Activity() {

    private val io: ExecutorService = Executors.newSingleThreadExecutor()
    private lateinit var list: ExpandableListView
    private lateinit var tvEmpty: TextView
    private lateinit var tvHelp: TextView
    private var families: List<FontFamily> = emptyList()
    private val adapter = Adapter()
    private var importing = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        FontLibrary.init(this)
        setContentView(R.layout.activity_fonts)
        UiKit.applyInsets(this, findViewById(R.id.root))

        list = findViewById(R.id.list)
        tvEmpty = findViewById(R.id.tvEmpty)
        tvHelp = findViewById(R.id.tvHelp)
        list.setAdapter(adapter)
        list.setOnItemLongClickListener { _, _, position, _ ->
            onLongPress(list.getExpandableListPosition(position)); true
        }
        findViewById<Button>(R.id.btnFiles).setOnClickListener { pickFiles() }
        findViewById<Button>(R.id.btnFolder).setOnClickListener { pickFolder() }
        refresh()
        if (savedInstanceState == null) handleIncoming(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIncoming(intent)
    }

    override fun onDestroy() {
        io.shutdown()
        super.onDestroy()
    }

    private fun refresh() {
        families = FontLibrary.userFamilies()
        adapter.notifyDataSetChanged()
        val empty = families.isEmpty()
        tvEmpty.visibility = if (empty) View.VISIBLE else View.GONE
        list.visibility = if (empty) View.GONE else View.VISIBLE
        tvHelp.visibility = if (empty) View.GONE else View.VISIBLE
    }

    // ---------------- picking ----------------

    private fun pickFiles() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, MIME_TYPES)
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        }
        launch(i, REQ_FILES)
    }

    private fun pickFolder() = launch(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), REQ_TREE)

    private fun launch(i: Intent, req: Int) {
        try {
            @Suppress("DEPRECATION")
            startActivityForResult(i, req)
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, "File picker not available", Toast.LENGTH_LONG).show()
        }
    }

    @Deprecated("Platform activity result API")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK || data == null) return
        when (requestCode) {
            REQ_FILES -> {
                val uris = ArrayList<Uri>()
                data.clipData?.let { c -> for (k in 0 until c.itemCount) c.getItemAt(k).uri?.let { uris += it } }
                if (uris.isEmpty()) data.data?.let { uris += it }
                startImport(uris, emptyList())
            }
            REQ_TREE -> data.data?.let { startImport(emptyList(), listOf(it)) }
        }
    }

    private fun handleIncoming(intent: Intent?) {
        intent ?: return
        val uris = ArrayList<Uri>()
        when (intent.action) {
            Intent.ACTION_SEND -> streamExtra(intent)?.let { uris += it }
            Intent.ACTION_SEND_MULTIPLE -> streamListExtra(intent)?.let { uris += it }
            Intent.ACTION_VIEW -> intent.data?.let { uris += it }
        }
        // Only content:// URIs are accepted (never file:// paths from other apps).
        val safe = uris.filter { it.scheme == "content" }
        if (safe.isNotEmpty()) startImport(safe, emptyList())
        setIntent(Intent(this, FontsActivity::class.java))
    }

    @Suppress("DEPRECATION")
    private fun streamExtra(i: Intent): Uri? =
        if (Build.VERSION.SDK_INT >= 33) i.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        else i.getParcelableExtra(Intent.EXTRA_STREAM)

    @Suppress("DEPRECATION")
    private fun streamListExtra(i: Intent): List<Uri>? =
        if (Build.VERSION.SDK_INT >= 33) i.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
        else i.getParcelableArrayListExtra(Intent.EXTRA_STREAM)

    // ---------------- importing ----------------

    private fun startImport(uris: List<Uri>, trees: List<Uri>) {
        if (importing || (uris.isEmpty() && trees.isEmpty())) return
        importing = true
        val pad = UiKit.dp(this, 24)
        val bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { isIndeterminate = true }
        val label = TextView(this).apply { setPadding(0, UiKit.dp(this@FontsActivity, 8), 0, 0) }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad / 2)
            addView(bar); addView(label)
        }
        val dlg = AlertDialog.Builder(this).setTitle(R.string.importing).setView(box).setCancelable(false).show()
        val resolver = contentResolver

        io.execute {
            val res = FontImporter(resolver).run(uris, trees) { done, total ->
                runOnUiThread {
                    bar.isIndeterminate = false
                    bar.max = total.coerceAtLeast(1)
                    bar.progress = done
                    label.text = getString(R.string.import_progress, done, total)
                }
            }
            runOnUiThread {
                importing = false
                if (isDestroyed) return@runOnUiThread
                dlg.dismiss()
                val added = FontLibrary.addStyles(res.styles)
                refresh()
                val famCount = added.map { it.family }.distinct().size
                val dupes = res.styles.size - added.size
                val msg = StringBuilder(getString(R.string.import_result, added.size, famCount))
                if (dupes > 0) msg.append(getString(R.string.import_dupes, dupes))
                if (res.invalid > 0) msg.append(getString(R.string.import_invalid, res.invalid))
                AlertDialog.Builder(this).setMessage(msg).setPositiveButton(R.string.ok, null).show()
            }
        }
    }

    // ---------------- deleting ----------------

    private fun onLongPress(packed: Long) {
        val g = ExpandableListView.getPackedPositionGroup(packed)
        if (g !in families.indices) return
        val fam = families[g]
        when (ExpandableListView.getPackedPositionType(packed)) {
            ExpandableListView.PACKED_POSITION_TYPE_GROUP -> confirm(getString(R.string.delete_family, fam.name)) {
                FontLibrary.removeStyles(fam.styles.map { it.id }); refresh()
            }
            ExpandableListView.PACKED_POSITION_TYPE_CHILD -> {
                val c = ExpandableListView.getPackedPositionChild(packed)
                val s = fam.styles.getOrNull(c) ?: return
                confirm(getString(R.string.delete_style, s.label)) {
                    FontLibrary.removeStyles(listOf(s.id)); refresh()
                }
            }
        }
    }

    private fun confirm(msg: String, action: () -> Unit) {
        AlertDialog.Builder(this).setMessage(msg)
            .setPositiveButton(R.string.delete) { _, _ -> action() }
            .setNegativeButton(R.string.cancel, null).show()
    }

    // ---------------- list ----------------

    private inner class Adapter : BaseExpandableListAdapter() {
        override fun getGroupCount() = families.size
        override fun getChildrenCount(g: Int) = families[g].styles.size
        override fun getGroup(g: Int) = families[g]
        override fun getChild(g: Int, c: Int) = families[g].styles[c]
        override fun getGroupId(g: Int) = g.toLong()
        override fun getChildId(g: Int, c: Int) = c.toLong()
        override fun hasStableIds() = false
        override fun isChildSelectable(g: Int, c: Int) = true

        override fun getGroupView(g: Int, expanded: Boolean, convert: View?, parent: ViewGroup?): View {
            val fam = families[g]
            val row = (convert as? LinearLayout) ?: twoLineRow(true)
            val title = row.getChildAt(0) as TextView
            val sub = row.getChildAt(1) as TextView
            title.text = (if (expanded) "▾  " else "▸  ") + fam.name
            title.typeface = FontLibrary.typeface(FontLibrary.closest(fam, 400, false).id)
            sub.text = getString(R.string.weights_count, fam.styles.size)
            return row
        }

        override fun getChildView(g: Int, c: Int, last: Boolean, convert: View?, parent: ViewGroup?): View {
            val s = families[g].styles[c]
            val row = (convert as? LinearLayout) ?: twoLineRow(false)
            val title = row.getChildAt(0) as TextView
            val preview = row.getChildAt(1) as TextView
            val tf = FontLibrary.typeface(s.id)
            title.text = s.label
            preview.text = getString(R.string.preview_text)
            preview.typeface = tf
            return row
        }

        private fun twoLineRow(group: Boolean): LinearLayout {
            val ctx = this@FontsActivity
            val p = UiKit.dp(ctx, 16)
            return LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(if (group) p else p * 2, p * 3 / 4, p, p * 3 / 4)
                addView(TextView(ctx).apply {
                    setTextColor(0xFFFFFFFF.toInt())
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, if (group) 19f else 13f)
                    alpha = if (group) 1f else 0.7f
                    gravity = Gravity.START
                    textDirection = View.TEXT_DIRECTION_FIRST_STRONG
                })
                addView(TextView(ctx).apply {
                    setTextColor(0xFFFFFFFF.toInt())
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, if (group) 12f else 22f)
                    alpha = if (group) 0.6f else 1f
                    gravity = Gravity.START
                })
            }
        }
    }

    companion object {
        private const val REQ_FILES = 11
        private const val REQ_TREE = 12
        private val MIME_TYPES = arrayOf(
            "font/ttf", "font/otf", "font/sfnt", "font/collection",
            "application/x-font-ttf", "application/x-font-otf", "application/x-font-truetype",
            "application/font-sfnt", "application/vnd.ms-opentype",
            "application/zip", "application/x-zip-compressed", "application/octet-stream",
        )
    }
}
