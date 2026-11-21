package com.antonio.inicio

import android.app.Activity
import android.app.AlertDialog
import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.media.ExifInterface
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.view.DragEvent
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.text.Normalizer
import java.util.UUID
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
private const val REQ_PORT = 1
private const val REQ_LAND = 2
private const val REQ_EXPORT = 3
private const val REQ_IMPORT = 4
private const val REQ_BIND = 5
private const val REQ_CONFIG = 6
private const val ACCENT = 0xFF4CC2FF.toInt()

class AppEntry(val key: String, val pkg: String, val label: String, val icon: Drawable)

class WRow(val info: AppWidgetProviderInfo, val label: String, val app: String, val size: String)

sealed class Row {
    object Pinned : Row()
    class Letter(val c: String) : Row()
    class App(val e: AppEntry) : Row()
}

fun norm(s: String): String =
    Normalizer.normalize(s, Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "").lowercase()

class StartDrawable : Drawable() {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ACCENT }

    override fun draw(c: Canvas) {
        val b = bounds
        val s = min(b.width(), b.height()) * 0.46f
        val gap = s * 0.12f
        val cell = (s - gap) / 2f
        val x0 = b.exactCenterX() - s / 2f
        val y0 = b.exactCenterY() - s / 2f
        for (r in 0..1) {
            for (col in 0..1) {
                val l = x0 + col * (cell + gap)
                val t = y0 + r * (cell + gap)
                c.drawRoundRect(l, t, l + cell, t + cell, cell * 0.2f, cell * 0.2f, p)
            }
        }
    }

    override fun setAlpha(alpha: Int) {
        p.alpha = alpha
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        p.colorFilter = colorFilter
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}

class MainActivity : Activity() {

    private lateinit var store: Store
    private var apps: List<AppEntry> = emptyList()
    private val byKey = HashMap<String, AppEntry>()

    private lateinit var root: FrameLayout
    private lateinit var bg: ImageView
    private lateinit var content: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var grid: GridLayout
    private lateinit var barRow: LinearLayout
    private lateinit var widgetBox: LinearLayout
    private val widgetMgr by lazy { AppWidgetManager.getInstance(this) }
    private val widgetHost by lazy { AppWidgetHost(this, 1024) }
    private var pendingWidgetId = -1
    private lateinit var emptyHint: TextView
    private lateinit var dropBar: LinearLayout
    private lateinit var dropRemove: TextView
    private lateinit var dropInfo: TextView

    private lateinit var panelLayer: FrameLayout
    private lateinit var panel: LinearLayout
    private lateinit var search: EditText
    private lateinit var list: ListView
    private val rowAdapter = RowAdapter()
    private var panelOpen = false
    private var panelW = 0

    private lateinit var folderLayer: FrameLayout
    private lateinit var folderPanel: LinearLayout
    private lateinit var folderTitle: TextView
    private lateinit var folderGrid: GridLayout
    private lateinit var folderEmpty: TextView
    private var openFolderId: String? = null

    private var insL = 0
    private var insT = 0
    private var insR = 0
    private var insB = 0
    private var homeW = 0
    private var dragView: View? = null
    private var bgKey = ""
    private var bgBmp: Bitmap? = null

    private var touching = false
    private var downX = 0f
    private var downY = 0f
    private var lpRun: Runnable? = null

    private val pkgReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) {
            if (i == null) return
            if (i.action == Intent.ACTION_PACKAGE_REMOVED && !i.getBooleanExtra(Intent.EXTRA_REPLACING, false)) {
                val p = i.data?.schemeSpecificPart
                if (p != null) purge(p)
            }
            loadApps()
        }
    }

    // ---------------------------------------------------------------- ciclo de vida

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = Store(this)
        buildUi()
        applyRotation()
        val f = IntentFilter()
        f.addAction(Intent.ACTION_PACKAGE_ADDED)
        f.addAction(Intent.ACTION_PACKAGE_REMOVED)
        f.addAction(Intent.ACTION_PACKAGE_CHANGED)
        f.addDataScheme("package")
        registerReceiver(pkgReceiver, f)
        loadApps()
    }

    override fun onStart() {
        super.onStart()
        try {
            widgetHost.startListening()
        } catch (e: Exception) {
        }
    }

    override fun onStop() {
        try {
            widgetHost.stopListening()
        } catch (e: Exception) {
        }
        super.onStop()
    }

    override fun onDestroy() {
        try {
            unregisterReceiver(pkgReceiver)
        } catch (e: Exception) {
        }
        super.onDestroy()
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        if (intent != null && intent.action == Intent.ACTION_MAIN && intent.hasCategory(Intent.CATEGORY_HOME)) {
            closePanel()
            closeFolder()
            scroll.smoothScrollTo(0, 0)
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        applyWallpaper()
        layoutPanel()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        when {
            openFolderId != null -> closeFolder()
            panelOpen -> closePanel()
            else -> {
            }
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_BIND || requestCode == REQ_CONFIG) {
            val id = pendingWidgetId
            if (id < 0) return
            if (resultCode == RESULT_OK) {
                if (requestCode == REQ_BIND) configureWidget(id) else finishAddWidget(id)
            } else {
                widgetHost.deleteAppWidgetId(id)
                pendingWidgetId = -1
            }
            return
        }
        val uri = data?.data
        if (resultCode != RESULT_OK || uri == null) return
        when (requestCode) {
            REQ_PORT -> importImage(uri, "bg_port.jpg")
            REQ_LAND -> importImage(uri, "bg_land.jpg")
            REQ_EXPORT -> exportTo(uri)
            REQ_IMPORT -> importFrom(uri)
            else -> {
            }
        }
    }

    // ---------------------------------------------------------------- utilidades

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()

    private fun toast(s: String) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
    }

    private fun rounded(color: Int, r: Float): GradientDrawable {
        val g = GradientDrawable()
        g.setColor(color)
        g.cornerRadius = r
        return g
    }

    private fun dlg(): AlertDialog.Builder =
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)

    private fun comp(key: String) = android.content.ComponentName(key.substringBefore('/'), key.substringAfter('/'))

    private fun isHidden(key: String): Boolean = key.substringBefore('/') in store.hidden

    private fun copyIcon(e: AppEntry): Drawable = e.icon.constantState?.newDrawable() ?: e.icon

    private fun label(text: String, size: Float, shadow: Boolean): TextView {
        val t = TextView(this)
        t.text = text
        t.setTextColor(Color.WHITE)
        t.textSize = size
        t.maxLines = 1
        t.ellipsize = TextUtils.TruncateAt.END
        t.gravity = Gravity.CENTER
        if (shadow) t.setShadowLayer(3f, 0f, 1f, 0xCC000000.toInt())
        t.setPadding(dp(2), dp(4), dp(2), 0)
        return t
    }

    private fun hideKeyboard() {
        val m = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        m.hideSoftInputFromWindow(search.windowToken, 0)
        search.clearFocus()
    }

    // ---------------------------------------------------------------- apps

    private fun loadApps() {
        val pm = packageManager
        val self = packageName
        Thread {
            val i = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            val res = pm.queryIntentActivities(i, 0)
            val out = ArrayList<AppEntry>()
            for (r in res) {
                val ai = r.activityInfo
                if (ai.packageName == self) continue
                out.add(AppEntry(ai.packageName + "/" + ai.name, ai.packageName, r.loadLabel(pm).toString(), r.loadIcon(pm)))
            }
            out.sortBy { norm(it.label) }
            runOnUiThread {
                apps = out
                byKey.clear()
                for (a in out) byKey[a.key] = a
                refreshAll()
            }
        }.start()
    }

    private fun purge(pkg: String) {
        val prefix = "$pkg/"
        store.items.removeAll { !it.folder && it.key.startsWith(prefix) }
        for (item in store.items) item.kids.removeAll { k -> k.startsWith(prefix) }
        store.pinned.removeAll { it.startsWith(prefix) }
        store.hidden.remove(pkg)
        store.save()
    }

    private fun launch(key: String) {
        try {
            val i = Intent(Intent.ACTION_MAIN)
            i.addCategory(Intent.CATEGORY_LAUNCHER)
            i.component = comp(key)
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
            startActivity(i)
        } catch (e: Exception) {
            toast("Não foi possível abrir o app")
        }
    }

    private fun appInfo(pkg: String) {
        try {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$pkg")))
        } catch (e: Exception) {
            toast("Não foi possível abrir as informações")
        }
    }

    private fun uninstall(pkg: String) {
        try {
            startActivity(Intent(Intent.ACTION_DELETE, Uri.parse("package:$pkg")))
        } catch (e: Exception) {
            toast("Não foi possível desinstalar")
        }
    }

    override fun onResume() {
        super.onResume()
        renderBar()
    }

    // ---------------------------------------------------------------- barra de tarefas

    private fun renderBar() {
        if (!::barRow.isInitialized) return
        while (barRow.childCount > 1) barRow.removeViewAt(1)
        for (k in store.bar) {
            val e = byKey[k] ?: continue
            if (isHidden(k)) continue
            barRow.addView(barItem(e))
        }
    }

    private fun barItem(e: AppEntry): View {
        val c = LinearLayout(this)
        c.orientation = LinearLayout.VERTICAL
        c.gravity = Gravity.CENTER_HORIZONTAL
        c.setPadding(dp(4), dp(4), dp(4), dp(4))
        val iv = ImageView(this)
        iv.setImageDrawable(copyIcon(e))
        c.addView(iv, LinearLayout.LayoutParams(dp(38), dp(38)))
        c.setOnClickListener { launch(e.key) }
        c.setOnLongClickListener {
            showBarMenu(e)
            true
        }
        return c
    }

    private fun showBarMenu(e: AppEntry) {
        val names = arrayOf("Mover para a esquerda", "Mover para a direita", "Desafixar da barra", "Informações do app")
        dlg().setTitle(e.label).setItems(names) { _, w ->
            val idx = store.bar.indexOf(e.key)
            when (w) {
                0 -> if (idx > 0) {
                    store.bar.removeAt(idx)
                    store.bar.add(idx - 1, e.key)
                }
                1 -> if (idx >= 0 && idx < store.bar.size - 1) {
                    store.bar.removeAt(idx)
                    store.bar.add(idx + 1, e.key)
                }
                2 -> store.bar.remove(e.key)
                3 -> appInfo(e.pkg)
                else -> {
                }
            }
            store.save()
            renderBar()
        }.show()
    }

    private fun refreshAll() {
        renderBar()
        renderWidgets()
        renderHome()
        refreshRows()
        if (openFolderId != null) renderFolder()
    }

    // ---------------------------------------------------------------- construção da interface

    private fun buildUi() {
        root = FrameLayout(this)

        bg = ImageView(this)
        bg.scaleType = ImageView.ScaleType.CENTER_CROP
        bg.visibility = View.GONE
        root.addView(bg, FrameLayout.LayoutParams(MATCH, MATCH))

        content = LinearLayout(this)
        content.orientation = LinearLayout.VERTICAL

        val homeArea = FrameLayout(this)
        scroll = ScrollView(this)
        scroll.isVerticalScrollBarEnabled = false
        scroll.overScrollMode = View.OVER_SCROLL_NEVER
        scroll.isFillViewport = true
        grid = GridLayout(this)
        widgetBox = LinearLayout(this)
        widgetBox.orientation = LinearLayout.VERTICAL
        val inner = LinearLayout(this)
        inner.orientation = LinearLayout.VERTICAL
        inner.addView(widgetBox, LinearLayout.LayoutParams(MATCH, WRAP))
        inner.addView(grid, LinearLayout.LayoutParams(MATCH, WRAP))
        scroll.addView(inner, FrameLayout.LayoutParams(MATCH, WRAP))
        homeArea.addView(scroll, FrameLayout.LayoutParams(MATCH, MATCH))

        emptyHint = TextView(this)
        emptyHint.text = "Sua tela inicial está vazia.\n\nToque no botão Iniciar (embaixo), segure um app e escolha “Adicionar à tela inicial”."
        emptyHint.setTextColor(0xCCFFFFFF.toInt())
        emptyHint.textSize = 15f
        emptyHint.gravity = Gravity.CENTER
        emptyHint.setPadding(dp(32), 0, dp(32), 0)
        homeArea.addView(emptyHint, FrameLayout.LayoutParams(MATCH, WRAP, Gravity.CENTER))

        content.addView(homeArea, LinearLayout.LayoutParams(MATCH, 0, 1f))

        val taskbar = FrameLayout(this)
        val startBtn = ImageView(this)
        startBtn.setImageDrawable(StartDrawable())
        startBtn.background = rounded(0x22FFFFFF, dp(14).toFloat())
        startBtn.setOnClickListener { if (panelOpen) closePanel() else openPanel() }
        barRow = LinearLayout(this)
        barRow.orientation = LinearLayout.HORIZONTAL
        barRow.gravity = Gravity.CENTER_VERTICAL
        barRow.background = rounded(0xB3101014.toInt(), dp(20).toFloat())
        barRow.setPadding(dp(6), dp(4), dp(6), dp(4))
        barRow.addView(startBtn, LinearLayout.LayoutParams(dp(46), dp(46)))
        val barScroll = HorizontalScrollView(this)
        barScroll.isHorizontalScrollBarEnabled = false
        barScroll.overScrollMode = View.OVER_SCROLL_NEVER
        barScroll.addView(barRow)
        val blp = FrameLayout.LayoutParams(WRAP, WRAP, Gravity.CENTER)
        blp.setMargins(dp(64), 0, dp(64), 0)
        taskbar.addView(barScroll, blp)

        val gear = ImageView(this)
        gear.setImageResource(android.R.drawable.ic_menu_preferences)
        gear.setColorFilter(Color.WHITE)
        gear.setPadding(dp(10), dp(10), dp(10), dp(10))
        gear.background = rounded(0xB3101014.toInt(), dp(14).toFloat())
        gear.setOnClickListener { showSettings() }
        val glp = FrameLayout.LayoutParams(dp(44), dp(44), Gravity.END or Gravity.CENTER_VERTICAL)
        glp.marginEnd = dp(12)
        taskbar.addView(gear, glp)

        content.addView(taskbar, LinearLayout.LayoutParams(MATCH, dp(64)))
        root.addView(content, FrameLayout.LayoutParams(MATCH, MATCH))

        dropBar = LinearLayout(this)
        dropBar.orientation = LinearLayout.HORIZONTAL
        dropBar.visibility = View.GONE
        dropRemove = dropTarget("Remover", 0xE6C62828.toInt())
        dropInfo = dropTarget("Informações", 0xE61565C0.toInt())
        val l1 = LinearLayout.LayoutParams(0, dp(56), 1f)
        l1.marginEnd = dp(6)
        val l2 = LinearLayout.LayoutParams(0, dp(56), 1f)
        l2.marginStart = dp(6)
        dropBar.addView(dropRemove, l1)
        dropBar.addView(dropInfo, l2)
        root.addView(dropBar, FrameLayout.LayoutParams(MATCH, WRAP, Gravity.TOP))

        buildPanel()
        buildFolder()

        scroll.addOnLayoutChangeListener { _, l, _, r, _, _, _, _, _ ->
            val w = r - l
            if (w != homeW) {
                homeW = w
                scroll.post {
                    renderWidgets()
                    renderHome()
                }
            }
        }
        root.addOnLayoutChangeListener { _, l, t, r, b, ol, ot, oRight, oBottom ->
            if (r - l != oRight - ol || b - t != oBottom - ot) {
                root.post {
                    applyWallpaper()
                    layoutPanel()
                    if (openFolderId != null) renderFolder()
                }
            }
        }

        scroll.setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    touching = true
                    downX = e.x
                    downY = e.y
                    cancelLongPress(v)
                    val run = Runnable {
                        lpRun = null
                        if (touching) {
                            touching = false
                            showSettings()
                        }
                    }
                    lpRun = run
                    v.postDelayed(run, ViewConfiguration.getLongPressTimeout().toLong())
                }
                MotionEvent.ACTION_MOVE -> {
                    val slop = ViewConfiguration.get(this).scaledTouchSlop
                    if (abs(e.x - downX) > slop || abs(e.y - downY) > slop) cancelLongPress(v)
                }
                MotionEvent.ACTION_UP -> {
                    cancelLongPress(v)
                    if (touching && e.x - downX > dp(110) && abs(e.y - downY) < dp(70)) openPanel()
                    touching = false
                }
                MotionEvent.ACTION_CANCEL -> {
                    cancelLongPress(v)
                    touching = false
                }
                else -> {
                }
            }
            false
        }

        root.setOnApplyWindowInsetsListener { _, ins ->
            insL = ins.systemWindowInsetLeft
            insT = ins.systemWindowInsetTop
            insR = ins.systemWindowInsetRight
            insB = ins.systemWindowInsetBottom
            applyInsets()
            ins
        }

        root.setOnDragListener { _, e ->
            when (e.action) {
                DragEvent.ACTION_DRAG_STARTED -> e.localState is String
                DragEvent.ACTION_DRAG_LOCATION -> {
                    autoScroll(e.y)
                    true
                }
                DragEvent.ACTION_DROP -> {
                    handleDrop(e.localState as? String, e.x, e.y)
                    true
                }
                DragEvent.ACTION_DRAG_ENDED -> {
                    dropBar.visibility = View.GONE
                    dragView?.alpha = 1f
                    dragView = null
                    true
                }
                else -> true
            }
        }

        setContentView(root)

        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
            View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        val wlp = window.attributes
        wlp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        window.attributes = wlp

        layoutPanel()
    }

    private fun cancelLongPress(v: View) {
        val r = lpRun
        if (r != null) {
            v.removeCallbacks(r)
            lpRun = null
        }
    }

    private fun dropTarget(text: String, color: Int): TextView {
        val t = TextView(this)
        t.text = text
        t.setTextColor(Color.WHITE)
        t.textSize = 15f
        t.typeface = Typeface.DEFAULT_BOLD
        t.gravity = Gravity.CENTER
        t.background = rounded(color, dp(16).toFloat())
        return t
    }

    private fun buildPanel() {
        panelLayer = FrameLayout(this)
        panelLayer.visibility = View.GONE
        val dim = View(this)
        dim.setBackgroundColor(0x80000000.toInt())
        dim.setOnClickListener { closePanel() }
        panelLayer.addView(dim, FrameLayout.LayoutParams(MATCH, MATCH))

        panel = LinearLayout(this)
        panel.orientation = LinearLayout.VERTICAL
        panel.background = rounded(0xF0202026.toInt(), dp(24).toFloat())
        panel.setPadding(dp(16), dp(16), dp(16), dp(8))
        panel.isClickable = true

        search = EditText(this)
        search.hint = "Pesquisar apps"
        search.setHintTextColor(0x99FFFFFF.toInt())
        search.setTextColor(Color.WHITE)
        search.textSize = 16f
        search.isSingleLine = true
        search.imeOptions = EditorInfo.IME_ACTION_SEARCH or EditorInfo.IME_FLAG_NO_EXTRACT_UI
        search.background = rounded(0x33FFFFFF, dp(20).toFloat())
        search.setPadding(dp(16), dp(10), dp(16), dp(10))
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {
            }

            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
            }

            override fun afterTextChanged(s: Editable?) {
                refreshRows()
            }
        })
        panel.addView(search, LinearLayout.LayoutParams(MATCH, WRAP))

        list = ListView(this)
        list.divider = null
        list.setSelector(ColorDrawable(Color.TRANSPARENT))
        list.overScrollMode = View.OVER_SCROLL_NEVER
        list.isVerticalScrollBarEnabled = false
        list.adapter = rowAdapter
        list.setOnItemClickListener { _, _, pos, _ ->
            val r = rowAdapter.rows[pos]
            if (r is Row.App) {
                launch(r.e.key)
                closePanel()
            }
        }
        list.setOnItemLongClickListener { _, _, pos, _ ->
            val r = rowAdapter.rows[pos]
            if (r is Row.App) {
                showAppMenu(r.e)
                true
            } else {
                false
            }
        }
        panel.addView(list, LinearLayout.LayoutParams(MATCH, 0, 1f))

        val settingsLink = TextView(this)
        settingsLink.text = "Ajustes do início"
        settingsLink.setTextColor(ACCENT)
        settingsLink.textSize = 14f
        settingsLink.gravity = Gravity.CENTER
        settingsLink.setPadding(0, dp(12), 0, dp(8))
        settingsLink.setOnClickListener {
            closePanel()
            showSettings()
        }
        panel.addView(settingsLink, LinearLayout.LayoutParams(MATCH, WRAP))

        panelLayer.addView(panel, FrameLayout.LayoutParams(dp(360), MATCH))
        root.addView(panelLayer, FrameLayout.LayoutParams(MATCH, MATCH))
    }

    private fun buildFolder() {
        folderLayer = FrameLayout(this)
        folderLayer.visibility = View.GONE
        folderLayer.setBackgroundColor(0x99000000.toInt())
        folderLayer.setOnClickListener { closeFolder() }

        folderPanel = LinearLayout(this)
        folderPanel.orientation = LinearLayout.VERTICAL
        folderPanel.background = rounded(0xF0202026.toInt(), dp(24).toFloat())
        folderPanel.setPadding(dp(16), dp(16), dp(16), dp(16))
        folderPanel.isClickable = true

        folderTitle = TextView(this)
        folderTitle.setTextColor(Color.WHITE)
        folderTitle.textSize = 18f
        folderTitle.typeface = Typeface.DEFAULT_BOLD
        folderTitle.gravity = Gravity.CENTER
        folderTitle.setPadding(0, 0, 0, dp(12))
        folderTitle.setOnClickListener { renameOpenFolder() }
        folderPanel.addView(folderTitle, LinearLayout.LayoutParams(MATCH, WRAP))

        folderEmpty = TextView(this)
        folderEmpty.text = "Pasta vazia.\nArraste apps da tela inicial até o ícone da pasta, ou use “Adicionar à pasta” no menu do app."
        folderEmpty.setTextColor(0xCCFFFFFF.toInt())
        folderEmpty.textSize = 14f
        folderEmpty.gravity = Gravity.CENTER
        folderEmpty.setPadding(0, dp(8), 0, dp(8))
        folderPanel.addView(folderEmpty, LinearLayout.LayoutParams(MATCH, WRAP))

        folderGrid = GridLayout(this)
        val fs = ScrollView(this)
        fs.addView(folderGrid)
        folderPanel.addView(fs, LinearLayout.LayoutParams(MATCH, WRAP))

        folderLayer.addView(folderPanel, FrameLayout.LayoutParams(dp(320), WRAP, Gravity.CENTER))
        root.addView(folderLayer, FrameLayout.LayoutParams(MATCH, MATCH))
    }

    private fun applyInsets() {
        content.setPadding(insL + dp(8), insT, insR + dp(8), insB)
        val lp = dropBar.layoutParams as FrameLayout.LayoutParams
        lp.setMargins(insL + dp(12), insT + dp(8), insR + dp(12), 0)
        dropBar.layoutParams = lp
        layoutPanel()
    }

    private fun layoutPanel() {
        val rw = if (root.width > 0) root.width else resources.displayMetrics.widthPixels
        val pw = max(dp(240), min(dp(380), rw - insL - insR - dp(24)))
        panelW = pw
        val lp = FrameLayout.LayoutParams(pw, MATCH)
        lp.setMargins(insL + dp(12), insT + dp(12), 0, insB + dp(12))
        panel.layoutParams = lp
        val fw = max(dp(240), min(dp(400), rw - insL - insR - dp(32)))
        folderPanel.layoutParams = FrameLayout.LayoutParams(fw, WRAP, Gravity.CENTER)
        rowAdapter.notifyDataSetChanged()
    }

    // ---------------------------------------------------------------- papel de parede e rotação

    private fun applyRotation() {
        requestedOrientation = when (store.rotation) {
            1 -> ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR
            2 -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            3 -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            else -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    private fun applyWallpaper() {
        if (store.wallMode == 1) {
            val land = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
            val order = if (land) listOf("bg_land.jpg", "bg_port.jpg") else listOf("bg_port.jpg", "bg_land.jpg")
            var f: File? = null
            for (n in order) {
                val c = File(filesDir, n)
                if (c.exists()) {
                    f = c
                    break
                }
            }
            if (f != null) {
                val k = f.name + f.lastModified()
                if (k != bgKey || bgBmp == null) {
                    bgKey = k
                    val dm = resources.displayMetrics
                    bgBmp = decodeSampled(f.path, max(dm.widthPixels, dm.heightPixels))
                }
                bg.setImageBitmap(bgBmp)
                bg.visibility = View.VISIBLE
                return
            }
        }
        bg.setImageDrawable(null)
        bg.visibility = View.GONE
    }

    private fun decodeSampled(path: String, target: Int): Bitmap? {
        val b = BitmapFactory.Options()
        b.inJustDecodeBounds = true
        BitmapFactory.decodeFile(path, b)
        var s = 1
        while (max(b.outWidth, b.outHeight) / (s * 2) >= target) s *= 2
        val o = BitmapFactory.Options()
        o.inSampleSize = s
        return BitmapFactory.decodeFile(path, o)
    }

    private fun pickImage(req: Int) {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT)
        i.addCategory(Intent.CATEGORY_OPENABLE)
        i.type = "image/*"
        try {
            startActivityForResult(i, req)
        } catch (e: Exception) {
            toast("Não foi possível abrir o seletor de imagens")
        }
    }

    private fun importImage(uri: Uri, name: String) {
        Thread {
            var ok = false
            try {
                val b = BitmapFactory.Options()
                b.inJustDecodeBounds = true
                contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, b) }
                var s = 1
                while (max(b.outWidth, b.outHeight) / s > 2600) s *= 2
                val o = BitmapFactory.Options()
                o.inSampleSize = s
                val src = contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, o) }
                val orient = try {
                    contentResolver.openInputStream(uri)?.use {
                        ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, 1)
                    } ?: 1
                } catch (e: Exception) {
                    1
                }
                if (src != null) {
                    val deg = when (orient) {
                        6 -> 90f
                        3 -> 180f
                        8 -> 270f
                        else -> 0f
                    }
                    val fin = if (deg != 0f) {
                        val m = Matrix()
                        m.postRotate(deg)
                        Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true)
                    } else {
                        src
                    }
                    File(filesDir, name).outputStream().use { fin.compress(Bitmap.CompressFormat.JPEG, 92, it) }
                    ok = true
                }
            } catch (e: Exception) {
                ok = false
            }
            runOnUiThread {
                if (ok) {
                    store.wallMode = 1
                    store.save()
                    bgKey = ""
                    applyWallpaper()
                    toast("Imagem aplicada")
                } else {
                    toast("Não foi possível usar essa imagem")
                }
            }
        }.start()
    }

    // ---------------------------------------------------------------- tela inicial

    private fun renderHome() {
        if (!::grid.isInitialized) return
        grid.removeAllViews()
        val avail = if (homeW > 0) homeW else resources.displayMetrics.widthPixels
        val size = store.iconDp
        val cols = max(3, avail / dp(size + 26))
        val cw = avail / cols
        grid.columnCount = cols
        var count = 0
        for (item in store.items) {
            val v: View = if (item.folder) {
                folderCell(item, size)
            } else {
                if (isHidden(item.key)) continue
                val e = byKey[item.key] ?: continue
                appCell(e, size)
            }
            v.tag = item.id
            v.setOnClickListener {
                if (item.folder) openFolder(item.id) else launch(item.key)
            }
            v.setOnLongClickListener {
                beginDrag(v, item)
                true
            }
            val lp = GridLayout.LayoutParams()
            lp.width = cw
            lp.height = WRAP
            grid.addView(v, lp)
            count++
        }
        emptyHint.visibility = if (count == 0 && store.widgets.isEmpty()) View.VISIBLE else View.GONE
    }

    // ---------------------------------------------------------------- widgets

    private fun renderWidgets() {
        if (!::widgetBox.isInitialized) return
        widgetBox.removeAllViews()
        val dm = resources.displayMetrics
        val avail = if (homeW > 0) homeW else dm.widthPixels
        val wDp = (avail / dm.density).toInt()
        for (w in store.widgets) {
            val info = widgetMgr.getAppWidgetInfo(w.id) ?: continue
            try {
                val hv = widgetHost.createView(this, w.id, info)
                hv.updateAppWidgetSize(null, wDp, w.h, wDp, w.h)
                val lp = LinearLayout.LayoutParams(MATCH, dp(w.h))
                lp.setMargins(0, dp(4), 0, dp(4))
                widgetBox.addView(hv, lp)
            } catch (e: Exception) {
            }
        }
    }

    private fun widgetsDialog() {
        val items = arrayOf("Adicionar widget…", "Gerenciar widgets…")
        dlg().setTitle("Widgets").setItems(items) { _, w ->
            when (w) {
                0 -> addWidgetDialog()
                1 -> manageWidgets()
                else -> {
                }
            }
        }.show()
    }

    private fun loadWidgetImage(p: AppWidgetProviderInfo): Drawable? {
        val d = resources.displayMetrics.densityDpi
        try {
            val pv = p.loadPreviewImage(this, d)
            if (pv != null) return pv
        } catch (e: Exception) {
        }
        try {
            return p.loadIcon(this, d)
        } catch (e: Exception) {
        }
        return null
    }

    private fun widgetPickRow(): LinearLayout {
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.setPadding(dp(4), dp(8), dp(4), dp(8))
        val iv = ImageView(this)
        iv.scaleType = ImageView.ScaleType.FIT_CENTER
        row.addView(iv, LinearLayout.LayoutParams(dp(84), dp(64)))
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        val t1 = TextView(this)
        t1.setTextColor(Color.WHITE)
        t1.textSize = 16f
        t1.typeface = Typeface.DEFAULT_BOLD
        t1.maxLines = 2
        t1.ellipsize = TextUtils.TruncateAt.END
        val t2 = TextView(this)
        t2.setTextColor(0xB3FFFFFF.toInt())
        t2.textSize = 12f
        t2.maxLines = 2
        t2.ellipsize = TextUtils.TruncateAt.END
        col.addView(t1)
        col.addView(t2)
        val clp = LinearLayout.LayoutParams(0, WRAP, 1f)
        clp.marginStart = dp(12)
        row.addView(col, clp)
        return row
    }

    private fun addWidgetDialog() {
        val pm = packageManager
        val dm = resources.displayMetrics
        val all = ArrayList<WRow>()
        for (p in widgetMgr.installedProviders) {
            val pkg = p.provider.packageName
            val app = try {
                pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
            } catch (e: Exception) {
                pkg
            }
            val lbl = p.loadLabel(pm) ?: "Widget"
            val wd = (p.minWidth / dm.density).toInt()
            val hd = (p.minHeight / dm.density).toInt()
            all.add(WRow(p, lbl, app, "$wd×$hd dp"))
        }
        if (all.isEmpty()) {
            toast("Nenhum widget disponível")
            return
        }
        all.sortBy { norm(it.app + " " + it.label) }
        val shown = ArrayList<WRow>(all)
        val images = HashMap<String, Drawable?>()

        val adapter = object : BaseAdapter() {
            override fun getCount(): Int = shown.size

            override fun getItem(position: Int): Any = shown[position]

            override fun getItemId(position: Int): Long = position.toLong()

            override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
                val row = (convertView as? LinearLayout) ?: widgetPickRow()
                val r = shown[position]
                val iv = row.getChildAt(0) as ImageView
                val col = row.getChildAt(1) as LinearLayout
                (col.getChildAt(0) as TextView).text = r.label
                (col.getChildAt(1) as TextView).text = r.app + "  ·  " + r.size
                val key = r.info.provider.flattenToString()
                if (!images.containsKey(key)) images[key] = loadWidgetImage(r.info)
                val img = images[key]
                iv.setImageDrawable(img?.constantState?.newDrawable() ?: img)
                return row
            }
        }

        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(dp(16), dp(8), dp(16), 0)
        val q = EditText(this)
        q.hint = "Pesquisar widget ou app"
        q.setHintTextColor(0x99FFFFFF.toInt())
        q.setTextColor(Color.WHITE)
        q.isSingleLine = true
        q.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {
            }

            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
            }

            override fun afterTextChanged(s: Editable?) {
                val nq = norm(q.text.toString().trim())
                shown.clear()
                for (r in all) {
                    if (nq.isEmpty() || norm(r.app + " " + r.label).contains(nq)) shown.add(r)
                }
                adapter.notifyDataSetChanged()
            }
        })
        box.addView(q, LinearLayout.LayoutParams(MATCH, WRAP))
        val lv = ListView(this)
        lv.adapter = adapter
        box.addView(lv, LinearLayout.LayoutParams(MATCH, dm.heightPixels * 6 / 10))

        val dialog = dlg().setTitle("Escolher widget").setView(box).setNegativeButton("Cancelar", null).create()
        lv.setOnItemClickListener { _, _, pos, _ ->
            val r = shown[pos]
            dialog.dismiss()
            startAddWidget(r.info)
        }
        dialog.show()
    }

    private fun startAddWidget(info: AppWidgetProviderInfo) {
        val id = widgetHost.allocateAppWidgetId()
        pendingWidgetId = id
        if (widgetMgr.bindAppWidgetIdIfAllowed(id, info.provider)) {
            configureWidget(id)
        } else {
            val i = Intent(AppWidgetManager.ACTION_APPWIDGET_BIND)
            i.putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
            i.putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER, info.provider)
            try {
                startActivityForResult(i, REQ_BIND)
            } catch (e: Exception) {
                widgetHost.deleteAppWidgetId(id)
                pendingWidgetId = -1
                toast("Não foi possível adicionar o widget")
            }
        }
    }

    private fun configureWidget(id: Int) {
        val info = widgetMgr.getAppWidgetInfo(id)
        if (info != null && info.configure != null) {
            try {
                widgetHost.startAppWidgetConfigureActivityForResult(this, id, 0, REQ_CONFIG, null)
                return
            } catch (e: Exception) {
            }
        }
        finishAddWidget(id)
    }

    private fun finishAddWidget(id: Int) {
        val info = widgetMgr.getAppWidgetInfo(id)
        val h = if (info != null) (info.minHeight / resources.displayMetrics.density).toInt().coerceIn(60, 400) else 100
        store.widgets.add(WItem(id, h))
        store.save()
        pendingWidgetId = -1
        renderWidgets()
        renderHome()
        scroll.smoothScrollTo(0, 0)
    }

    private fun manageWidgets() {
        if (store.widgets.isEmpty()) {
            toast("Nenhum widget adicionado")
            return
        }
        val pm = packageManager
        val names = store.widgets.map { w ->
            (widgetMgr.getAppWidgetInfo(w.id)?.loadLabel(pm) ?: "Widget indisponível") + "  (" + w.h + "dp)"
        }.toTypedArray()
        dlg().setTitle("Seus widgets").setItems(names) { _, i -> widgetOptions(store.widgets[i]) }.show()
    }

    private fun widgetOptions(w: WItem) {
        val items = arrayOf("Aumentar altura", "Diminuir altura", "Mover para cima", "Mover para baixo", "Remover")
        dlg().setTitle("Widget").setItems(items) { _, k ->
            val idx = store.widgets.indexOf(w)
            when (k) {
                0 -> w.h = min(w.h + 20, 500)
                1 -> w.h = max(w.h - 20, 40)
                2 -> if (idx > 0) {
                    store.widgets.removeAt(idx)
                    store.widgets.add(idx - 1, w)
                }
                3 -> if (idx >= 0 && idx < store.widgets.size - 1) {
                    store.widgets.removeAt(idx)
                    store.widgets.add(idx + 1, w)
                }
                4 -> {
                    widgetHost.deleteAppWidgetId(w.id)
                    store.widgets.remove(w)
                }
                else -> {
                }
            }
            store.save()
            renderWidgets()
        }.show()
    }

    private fun appCell(e: AppEntry, size: Int): View {
        val c = LinearLayout(this)
        c.orientation = LinearLayout.VERTICAL
        c.gravity = Gravity.CENTER_HORIZONTAL
        c.setPadding(0, dp(8), 0, dp(8))
        val iv = ImageView(this)
        iv.setImageDrawable(copyIcon(e))
        c.addView(iv, LinearLayout.LayoutParams(dp(size), dp(size)))
        if (store.labels) c.addView(label(e.label, 12f, true))
        return c
    }

    private fun folderCell(item: Item, size: Int): View {
        val c = LinearLayout(this)
        c.orientation = LinearLayout.VERTICAL
        c.gravity = Gravity.CENTER_HORIZONTAL
        c.setPadding(0, dp(8), 0, dp(8))
        c.addView(folderIcon(item, size), LinearLayout.LayoutParams(dp(size), dp(size)))
        if (store.labels) c.addView(label(if (item.name.isBlank()) "Pasta" else item.name, 12f, true))
        return c
    }

    private fun folderIcon(item: Item, size: Int): View {
        val total = dp(size)
        val f = FrameLayout(this)
        f.background = rounded(0x55FFFFFF, dp(14).toFloat())
        val g = GridLayout(this)
        g.columnCount = 2
        g.rowCount = 2
        val pad = dp(6)
        val mg = dp(1)
        val mini = (total - 2 * pad) / 2 - 2 * mg
        var n = 0
        for (k in item.kids) {
            if (n >= 4) break
            val e = byKey[k] ?: continue
            if (isHidden(k)) continue
            val iv = ImageView(this)
            iv.setImageDrawable(copyIcon(e))
            val lp = GridLayout.LayoutParams()
            lp.width = mini
            lp.height = mini
            lp.setMargins(mg, mg, mg, mg)
            g.addView(iv, lp)
            n++
        }
        f.addView(g, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.CENTER))
        return f
    }

    private fun addToHome(key: String) {
        store.items.add(Item(UUID.randomUUID().toString(), false, key, ""))
        store.save()
        renderHome()
        toast("Adicionado à tela inicial")
    }

    // ---------------------------------------------------------------- arrastar e soltar

    private fun beginDrag(v: View, item: Item) {
        dropInfo.text = if (item.folder) "Renomear" else "Informações"
        dropBar.visibility = View.VISIBLE
        dragView = v
        v.alpha = 0.35f
        val data = ClipData.newPlainText("id", item.id)
        v.startDragAndDrop(data, View.DragShadowBuilder(v), item.id, 0)
    }

    private fun hit(v: View, x: Float, y: Float): Boolean {
        if (v.visibility != View.VISIBLE) return false
        val l = IntArray(2)
        v.getLocationInWindow(l)
        val r = IntArray(2)
        root.getLocationInWindow(r)
        val px = x + r[0]
        val py = y + r[1]
        return px >= l[0] && px <= l[0] + v.width && py >= l[1] && py <= l[1] + v.height
    }

    private fun autoScroll(y: Float) {
        val l = IntArray(2)
        scroll.getLocationInWindow(l)
        val r = IntArray(2)
        root.getLocationInWindow(r)
        val py = y + r[1]
        if (py < l[1] + dp(90)) {
            scroll.scrollBy(0, -dp(14))
        } else if (py > l[1] + scroll.height - dp(90)) {
            scroll.scrollBy(0, dp(14))
        }
    }

    private fun handleDrop(id: String?, x: Float, y: Float) {
        val item = store.items.firstOrNull { it.id == id } ?: return
        if (hit(dropRemove, x, y)) {
            store.items.remove(item)
            store.save()
            renderHome()
            toast("Removido da tela inicial")
            return
        }
        if (hit(dropInfo, x, y)) {
            if (item.folder) renameFolder(item) else appInfo(item.key.substringBefore('/'))
            return
        }
        var found: Item? = null
        for (i in 0 until grid.childCount) {
            val c = grid.getChildAt(i)
            if (hit(c, x, y)) {
                val tid = c.tag
                found = store.items.firstOrNull { it.id == tid }
                break
            }
        }
        val t = found
        if (t != null) {
            if (t.id != item.id) {
                if (t.folder && !item.folder) {
                    if (item.key !in t.kids) t.kids.add(item.key)
                    store.items.remove(item)
                } else {
                    val ti = store.items.indexOf(t)
                    store.items.remove(item)
                    store.items.add(min(ti, store.items.size), item)
                }
            }
        } else {
            store.items.remove(item)
            store.items.add(item)
        }
        store.save()
        renderHome()
    }

    // ---------------------------------------------------------------- pastas

    private fun openFolder(id: String) {
        closePanel()
        openFolderId = id
        renderFolder()
        folderLayer.visibility = View.VISIBLE
    }

    private fun closeFolder() {
        openFolderId = null
        folderLayer.visibility = View.GONE
    }

    private fun renderFolder() {
        val f = store.items.firstOrNull { it.id == openFolderId && it.folder }
        if (f == null) {
            closeFolder()
            return
        }
        folderTitle.text = if (f.name.isBlank()) "Pasta" else f.name
        folderGrid.removeAllViews()
        val cols = 4
        val cw = (folderPanel.layoutParams.width - dp(32)) / cols
        folderGrid.columnCount = cols
        var n = 0
        for (k in f.kids) {
            val e = byKey[k] ?: continue
            if (isHidden(k)) continue
            val c = LinearLayout(this)
            c.orientation = LinearLayout.VERTICAL
            c.gravity = Gravity.CENTER_HORIZONTAL
            c.setPadding(0, dp(8), 0, dp(8))
            val iv = ImageView(this)
            iv.setImageDrawable(copyIcon(e))
            c.addView(iv, LinearLayout.LayoutParams(dp(48), dp(48)))
            c.addView(label(e.label, 11f, false))
            c.setOnClickListener {
                launch(k)
                closeFolder()
            }
            c.setOnLongClickListener {
                folderKidMenu(f, k)
                true
            }
            val lp = GridLayout.LayoutParams()
            lp.width = cw
            lp.height = WRAP
            folderGrid.addView(c, lp)
            n++
        }
        folderEmpty.visibility = if (n == 0) View.VISIBLE else View.GONE
    }

    private fun folderKidMenu(f: Item, key: String) {
        val e = byKey[key] ?: return
        val names = arrayOf("Mover para a tela inicial", "Remover da pasta", "Informações do app")
        dlg().setTitle(e.label).setItems(names) { _, w ->
            when (w) {
                0 -> {
                    f.kids.remove(key)
                    store.items.add(Item(UUID.randomUUID().toString(), false, key, ""))
                    store.save()
                    renderHome()
                    renderFolder()
                }
                1 -> {
                    f.kids.remove(key)
                    store.save()
                    renderHome()
                    renderFolder()
                }
                2 -> appInfo(e.pkg)
                else -> {
                }
            }
        }.show()
    }

    private fun promptName(title: String, initial: String, ok: (String) -> Unit) {
        val et = EditText(this)
        et.setText(initial)
        et.setSelection(et.text.length)
        et.setTextColor(Color.WHITE)
        et.isSingleLine = true
        dlg().setTitle(title).setView(et)
            .setPositiveButton("OK") { _, _ -> ok(et.text.toString().trim()) }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun newFolder() {
        promptName("Nova pasta", "") { n ->
            store.items.add(Item(UUID.randomUUID().toString(), true, "", if (n.isBlank()) "Pasta" else n))
            store.save()
            renderHome()
        }
    }

    private fun renameFolder(item: Item) {
        promptName("Renomear pasta", item.name) { n ->
            item.name = if (n.isBlank()) "Pasta" else n
            store.save()
            renderHome()
            if (openFolderId != null) renderFolder()
        }
    }

    private fun renameOpenFolder() {
        val f = store.items.firstOrNull { it.id == openFolderId && it.folder }
        if (f != null) renameFolder(f)
    }

    private fun chooseFolder(key: String) {
        val folders = store.items.filter { it.folder }
        val names = folders.map { if (it.name.isBlank()) "Pasta" else it.name }.toTypedArray()
        dlg().setTitle("Adicionar à pasta").setItems(names) { _, w ->
            val f = folders[w]
            if (key !in f.kids) f.kids.add(key)
            store.save()
            renderHome()
            toast("Adicionado à pasta")
        }.show()
    }

    // ---------------------------------------------------------------- menu lateral (estilo Iniciar)

    private fun openPanel() {
        if (panelOpen) return
        closeFolder()
        panelOpen = true
        search.setText("")
        refreshRows()
        list.setSelection(0)
        panelLayer.visibility = View.VISIBLE
        panelLayer.alpha = 0f
        panel.translationX = -(panelW + dp(24)).toFloat()
        panelLayer.animate().alpha(1f).setDuration(160).start()
        panel.animate().translationX(0f).setDuration(200).start()
    }

    private fun closePanel() {
        if (!panelOpen) return
        panelOpen = false
        hideKeyboard()
        panelLayer.animate().alpha(0f).setDuration(160).withEndAction {
            if (!panelOpen) panelLayer.visibility = View.GONE
        }.start()
        panel.animate().translationX(-(panelW + dp(24)).toFloat()).setDuration(180).start()
    }

    private fun refreshRows() {
        if (!::search.isInitialized) return
        val q = norm(search.text.toString().trim())
        val vis = apps.filter { it.pkg !in store.hidden }
        val rows = ArrayList<Row>()
        if (q.isEmpty()) {
            if (store.pinned.any { byKey.containsKey(it) && !isHidden(it) }) rows.add(Row.Pinned)
            var last = ""
            for (a in vis) {
                val ch = norm(a.label).firstOrNull()
                val l = if (ch != null && ch in 'a'..'z') ch.uppercaseChar().toString() else "#"
                if (l != last) {
                    rows.add(Row.Letter(l))
                    last = l
                }
                rows.add(Row.App(a))
            }
        } else {
            for (a in vis) {
                if (norm(a.label).contains(q)) rows.add(Row.App(a))
            }
        }
        rowAdapter.rows = rows
        rowAdapter.notifyDataSetChanged()
    }

    private fun sectionLabel(t: String): TextView {
        val tv = TextView(this)
        tv.text = t
        tv.setTextColor(0xB3FFFFFF.toInt())
        tv.textSize = 13f
        tv.typeface = Typeface.DEFAULT_BOLD
        tv.setPadding(dp(4), dp(10), dp(4), dp(6))
        return tv
    }

    private fun pinnedView(): View {
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.addView(sectionLabel("Fixados"))
        val g = GridLayout(this)
        g.columnCount = 4
        val cw = (panelW - dp(32)) / 4
        for (k in store.pinned) {
            val e = byKey[k] ?: continue
            if (isHidden(k)) continue
            val c = LinearLayout(this)
            c.orientation = LinearLayout.VERTICAL
            c.gravity = Gravity.CENTER_HORIZONTAL
            c.setPadding(0, dp(6), 0, dp(6))
            val iv = ImageView(this)
            iv.setImageDrawable(copyIcon(e))
            c.addView(iv, LinearLayout.LayoutParams(dp(44), dp(44)))
            c.addView(label(e.label, 11f, false))
            c.setOnClickListener {
                launch(k)
                closePanel()
            }
            c.setOnLongClickListener {
                showAppMenu(e)
                true
            }
            val lp = GridLayout.LayoutParams()
            lp.width = cw
            lp.height = WRAP
            g.addView(c, lp)
        }
        box.addView(g)
        box.addView(sectionLabel("Todos os apps"))
        return box
    }

    private fun appRow(): LinearLayout {
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.setPadding(dp(8), dp(7), dp(8), dp(7))
        row.addView(ImageView(this), LinearLayout.LayoutParams(dp(40), dp(40)))
        val tv = TextView(this)
        tv.setTextColor(Color.WHITE)
        tv.textSize = 16f
        tv.maxLines = 1
        tv.ellipsize = TextUtils.TruncateAt.END
        val lp = LinearLayout.LayoutParams(0, WRAP, 1f)
        lp.marginStart = dp(14)
        row.addView(tv, lp)
        return row
    }

    private inner class RowAdapter : BaseAdapter() {
        var rows: List<Row> = emptyList()

        override fun getCount(): Int = rows.size

        override fun getItem(position: Int): Any = rows[position]

        override fun getItemId(position: Int): Long = position.toLong()

        override fun getViewTypeCount(): Int = 3

        override fun getItemViewType(position: Int): Int = when (rows[position]) {
            is Row.Pinned -> 0
            is Row.Letter -> 1
            is Row.App -> 2
        }

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            return when (val r = rows[position]) {
                is Row.Pinned -> pinnedView()
                is Row.Letter -> {
                    val tv = (convertView as? TextView) ?: TextView(this@MainActivity).also {
                        it.setTextColor(ACCENT)
                        it.textSize = 14f
                        it.typeface = Typeface.DEFAULT_BOLD
                        it.setPadding(dp(8), dp(12), dp(8), dp(4))
                    }
                    tv.text = r.c
                    tv
                }
                is Row.App -> {
                    val row = (convertView as? LinearLayout) ?: appRow()
                    val ic = row.getChildAt(0) as ImageView
                    val tv = row.getChildAt(1) as TextView
                    ic.setImageDrawable(copyIcon(r.e))
                    tv.text = r.e.label
                    row
                }
            }
        }
    }

    private fun showAppMenu(e: AppEntry) {
        val names = ArrayList<String>()
        val acts = ArrayList<() -> Unit>()
        fun add(n: String, a: () -> Unit) {
            names.add(n)
            acts.add(a)
        }
        val onHome = store.items.any { !it.folder && it.key == e.key }
        if (!onHome) {
            add("Adicionar à tela inicial") {
                addToHome(e.key)
                closePanel()
            }
        }
        if (store.items.any { it.folder }) {
            add("Adicionar à pasta…") { chooseFolder(e.key) }
        }
        if (e.key in store.pinned) {
            add("Desafixar do menu") {
                store.pinned.remove(e.key)
                store.save()
                refreshRows()
            }
        } else {
            add("Fixar no menu") {
                store.pinned.add(e.key)
                store.save()
                refreshRows()
            }
        }
        if (e.key in store.bar) {
            add("Desafixar da barra de tarefas") {
                store.bar.remove(e.key)
                store.save()
                renderBar()
            }
        } else {
            add("Fixar na barra de tarefas") {
                store.bar.add(e.key)
                store.save()
                renderBar()
            }
        }
        add("Ocultar app") {
            store.hidden.add(e.pkg)
            store.save()
            refreshAll()
        }
        add("Informações do app") { appInfo(e.pkg) }
        add("Desinstalar") { uninstall(e.pkg) }
        dlg().setTitle(e.label).setItems(names.toTypedArray()) { _, w -> acts[w]() }.show()
    }

    // ---------------------------------------------------------------- ajustes

    private fun showSettings() {
        val items = arrayOf(
            "Widgets (relógio, clima…)",
            "Papel de parede…",
            "Rotação da tela…",
            "Tamanho dos ícones…",
            "Nomes dos apps: " + (if (store.labels) "ligado" else "desligado"),
            "Apps ocultos…",
            "Nova pasta…",
            "Exportar backup (arquivo)",
            "Importar backup (arquivo)",
            "Definir como launcher padrão"
        )
        dlg().setTitle("Ajustes do início").setItems(items) { _, w ->
            when (w) {
                0 -> widgetsDialog()
                1 -> wallpaperDialog()
                2 -> rotationDialog()
                3 -> sizeDialog()
                4 -> {
                    store.labels = !store.labels
                    store.save()
                    renderHome()
                }
                5 -> hiddenDialog()
                6 -> newFolder()
                7 -> exportBackup()
                8 -> importBackup()
                9 -> {
                    try {
                        startActivity(Intent(Settings.ACTION_HOME_SETTINGS))
                    } catch (e: Exception) {
                        toast("Abra Configurações > Apps > Apps padrão > App de início")
                    }
                }
                else -> {
                }
            }
        }.show()
    }

    private fun wallpaperDialog() {
        val items = arrayOf(
            "Usar papel de parede do sistema",
            "Escolher imagem (retrato)",
            "Escolher imagem (paisagem)",
            "Apagar minhas imagens"
        )
        dlg().setTitle("Papel de parede").setItems(items) { _, w ->
            when (w) {
                0 -> {
                    store.wallMode = 0
                    store.save()
                    applyWallpaper()
                }
                1 -> pickImage(REQ_PORT)
                2 -> pickImage(REQ_LAND)
                3 -> {
                    File(filesDir, "bg_port.jpg").delete()
                    File(filesDir, "bg_land.jpg").delete()
                    store.wallMode = 0
                    store.save()
                    bgKey = ""
                    bgBmp = null
                    applyWallpaper()
                }
                else -> {
                }
            }
        }.show()
    }

    private fun rotationDialog() {
        val items = arrayOf(
            "Seguir o sistema",
            "Sempre girar com o sensor",
            "Travar em retrato",
            "Travar em paisagem"
        )
        dlg().setTitle("Rotação da tela").setItems(items) { _, w ->
            store.rotation = w
            store.save()
            applyRotation()
        }.show()
    }

    private fun sizeDialog() {
        val items = arrayOf("Pequeno", "Médio", "Grande", "Extra grande")
        val sizes = intArrayOf(44, 52, 60, 72)
        dlg().setTitle("Tamanho dos ícones").setItems(items) { _, w ->
            store.iconDp = sizes[w]
            store.save()
            renderHome()
        }.show()
    }

    private fun hiddenDialog() {
        val all = apps
        val labels = all.map { it.label }.toTypedArray()
        val checked = BooleanArray(all.size) { all[it].pkg in store.hidden }
        dlg().setTitle("Apps ocultos")
            .setMultiChoiceItems(labels, checked) { _, which, isChecked -> checked[which] = isChecked }
            .setPositiveButton("OK") { _, _ ->
                val s = mutableSetOf<String>()
                for (i in all.indices) {
                    if (checked[i]) s.add(all[i].pkg)
                }
                store.hidden = s
                store.save()
                refreshAll()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun exportBackup() {
        val i = Intent(Intent.ACTION_CREATE_DOCUMENT)
        i.addCategory(Intent.CATEGORY_OPENABLE)
        i.type = "application/json"
        i.putExtra(Intent.EXTRA_TITLE, "inicio-backup.json")
        try {
            startActivityForResult(i, REQ_EXPORT)
        } catch (e: Exception) {
            toast("Não foi possível abrir o seletor de arquivos")
        }
    }

    private fun importBackup() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT)
        i.addCategory(Intent.CATEGORY_OPENABLE)
        i.type = "*/*"
        try {
            startActivityForResult(i, REQ_IMPORT)
        } catch (e: Exception) {
            toast("Não foi possível abrir o seletor de arquivos")
        }
    }

    private fun exportTo(uri: Uri) {
        try {
            contentResolver.openOutputStream(uri)?.use { it.write(store.toJson().toByteArray()) }
            toast("Backup salvo")
        } catch (e: Exception) {
            toast("Falha ao salvar o backup")
        }
    }

    private fun importFrom(uri: Uri) {
        try {
            val t = contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
            if (t != null && store.fromJson(t)) {
                store.save()
                applyRotation()
                bgKey = ""
                applyWallpaper()
                refreshAll()
                toast("Backup restaurado")
            } else {
                toast("Arquivo de backup inválido")
            }
        } catch (e: Exception) {
            toast("Falha ao ler o backup")
        }
    }
}
