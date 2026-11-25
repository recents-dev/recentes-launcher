package dev.recents.launcher

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.text.TextUtils
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

class RecentsService : AccessibilityService() {

    private val timeFmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())
    private val handler = Handler(Looper.getMainLooper())
    private val bgThread = HandlerThread("recents-check").apply { start() }
    private val bg = Handler(bgThread.looper)

    @Volatile private var querying = false
    private var again = false
    private var settleLeft = 0
    private var holdUntil = 0L
    private var pendingPkg: String? = null

    private var overlay: View? = null
    private var dismissed = false
    private var lastState = ""

    private var cachedView: View? = null
    private var cachedVersion = -1
    private var cachedW = 0
    private var cachedH = 0
    private var listVersion = 0
    private var scrollRef: HorizontalScrollView? = null
    private var rowRef: LinearLayout? = null
    private var contentRef: View? = null
    private var needsScroll = true

    private val iconCache = HashMap<String, Drawable>()
    private val labelCache = HashMap<String, String>()
    private val launchableCache = HashMap<String, Boolean>()

    private val homePkgs: Set<String> by lazy {
        try {
            val i = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
            packageManager.queryIntentActivities(i, 0)
                .map { it.activityInfo.packageName }
                .toSet()
        } catch (ex: Exception) {
            emptySet()
        }
    }

    private val prebuildRunnable = Runnable { prebuild() }
    private val settleRunnable = Runnable { scheduleCheck() }
    private val forceHideRunnable = Runnable {
        log("[overlay] forcado")
        pendingPkg = null
        hideOverlay()
        dismissed = false
    }

    companion object {
        const val LAUNCHER = "com.motorola.launcher3"
        const val PANEL_ID = "com.motorola.launcher3:id/overview_panel"
        const val CLEAR_ID = "com.motorola.launcher3:id/action_clear_all"
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        handler.postDelayed(prebuildRunnable, 500)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val e = event ?: return
        val pkg = e.packageName?.toString() ?: return
        if (pkg == packageName) return

        if (pkg == LAUNCHER) {
            if (e.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
                val cls = e.className?.toString() ?: ""
                log("[launcher-janela] ${cls.substringAfterLast('.')}")
                if (cls.contains("RecentsActivity")) {
                    holdUntil = System.currentTimeMillis() + 1200
                    applyState(true, false)
                }
            }
            settleLeft = 20
            scheduleCheck()
            return
        }

        if (e.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        if (pkg == "com.android.systemui") return

        val waiting = pendingPkg
        if (waiting != null) {
            if (pkg != waiting) return
            pendingPkg = null
            handler.postDelayed({ fadeOutOverlay() }, 120)
        } else {
            dismissed = false
            hideOverlay()
        }

        log("$pkg\n    ${e.className ?: "?"}")

        if (!isLaunchable(pkg)) return
        addRecent(pkg)
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        hideOverlay()
        bgThread.quitSafely()
        super.onDestroy()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun isLaunchable(pkg: String): Boolean =
        launchableCache.getOrPut(pkg) {
            pkg !in homePkgs && packageManager.getLaunchIntentForPackage(pkg) != null
        }

    private fun scheduleCheck() {
        if (querying) {
            again = true
            return
        }
        querying = true
        bg.post { runQuery() }
    }

    private fun runQuery() {
        var ok = false
        var panelVis = false
        var clearVis = false
        try {
            val root = rootInActiveWindow
            if (root != null && root.packageName?.toString() == LAUNCHER) {
                ok = true
                panelVis = root.findAccessibilityNodeInfosByViewId(PANEL_ID)
                    .any { it.isVisibleToUser }
                clearVis = root.findAccessibilityNodeInfosByViewId(CLEAR_ID)
                    .any { it.isVisibleToUser }
            }
        } catch (ex: Exception) {
            ok = false
        }
        handler.post {
            querying = false
            if (ok) applyState(panelVis, clearVis)
            if (again) {
                again = false
                scheduleCheck()
            } else if (settleLeft > 0) {
                settleLeft--
                handler.removeCallbacks(settleRunnable)
                handler.postDelayed(settleRunnable, 120)
            }
        }
    }

    private fun applyState(panelVis: Boolean, clearVis: Boolean) {
        val open = panelVis || clearVis
        val state = "recentes=$open panel=$panelVis clear=$clearVis"
        if (state != lastState) {
            lastState = state
            log("[launcher] $state")
        }
        if (open) {
            showOverlay()
        } else {
            if (System.currentTimeMillis() < holdUntil) return
            dismissed = false
            hideOverlay()
        }
    }

    private fun ensureView(w: Int, h: Int): View {
        val c = cachedView
        if (c != null && cachedVersion == listVersion && cachedW == w && cachedH == h) return c
        val v = buildOverlayView(w, h)
        cachedView = v
        cachedVersion = listVersion
        cachedW = w
        cachedH = h
        return v
    }

    private fun prebuild() {
        if (overlay != null) return
        try {
            val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val b = wm.maximumWindowMetrics.bounds
            ensureView(b.width(), b.height())
        } catch (ex: Exception) {
            log("[prebuild] erro: ${ex.javaClass.simpleName}")
        }
    }

    private fun showOverlay() {
        if (overlay != null || dismissed) return
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val bounds = wm.maximumWindowMetrics.bounds

        val view = ensureView(bounds.width(), bounds.height())
        view.animate().cancel()
        view.alpha = 1f
        contentRef?.visibility = View.VISIBLE
        needsScroll = true

        val lp = WindowManager.LayoutParams(
            bounds.width(),
            bounds.height(),
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT
        )
        lp.gravity = Gravity.TOP or Gravity.START
        lp.x = 0
        lp.y = 0
        lp.setFitInsetsTypes(0)
        lp.layoutInDisplayCutoutMode =
            WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS

        try {
            wm.addView(view, lp)
            overlay = view
            scrollRef?.post { scrollRef?.scrollTo(rowRef?.width ?: 0, 0) }
            log("[overlay] mostrado ${bounds.width()}x${bounds.height()}")
        } catch (ex: Exception) {
            log("[overlay] erro: ${ex.javaClass.simpleName}")
        }
    }

    private fun hideOverlay() {
        handler.removeCallbacks(forceHideRunnable)
        val v = overlay ?: return
        overlay = null
        v.animate().cancel()
        try {
            (getSystemService(Context.WINDOW_SERVICE) as WindowManager).removeView(v)
        } catch (ex: Exception) {
        }
        log("[overlay] escondido")
    }

    private fun fadeOutOverlay() {
        val v = overlay
        if (v == null) {
            dismissed = false
            return
        }
        v.animate()
            .alpha(0f)
            .setDuration(150)
            .withEndAction {
                if (overlay === v) {
                    hideOverlay()
                    dismissed = false
                }
                v.alpha = 1f
            }
            .start()
    }

    private fun goHome() {
        dismissed = true
        contentRef?.visibility = View.INVISIBLE
        performGlobalAction(GLOBAL_ACTION_HOME)
        handler.removeCallbacks(forceHideRunnable)
        handler.postDelayed(forceHideRunnable, 1500)
        settleLeft = 20
        handler.removeCallbacks(settleRunnable)
        handler.postDelayed(settleRunnable, 150)
    }

    private fun buildOverlayView(screenW: Int, screenH: Int): View {
        val matchP = FrameLayout.LayoutParams.MATCH_PARENT
        val wrapP = FrameLayout.LayoutParams.WRAP_CONTENT

        val cardW = (screenW * 0.65f).toInt()
        val cardH = (screenH * 0.65f).toInt()
        val headerH = dp(52)
        val margin = dp(8)
        val sidePad = (screenW - cardW) / 2 - margin

        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.parseColor("#FF111114"))
            clipChildren = false
            setOnClickListener { goHome() }
        }
        val content = FrameLayout(this).apply { clipChildren = false }
        root.addView(content, FrameLayout.LayoutParams(matchP, matchP))

        val empty = TextView(this).apply {
            text = "Nenhum app recente"
            setTextColor(Color.parseColor("#B0B0B5"))
            textSize = 18f
            gravity = Gravity.CENTER
            visibility = View.GONE
        }

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            clipChildren = false
            clipToPadding = false
            setPadding(sidePad, 0, sidePad, 0)
            setOnClickListener { goHome() }
        }

        val scroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            clipChildren = false
            clipToPadding = false
            setOnClickListener { goHome() }
            addView(row, FrameLayout.LayoutParams(wrapP, matchP))
        }

        fun updateEmpty() {
            empty.visibility = if (row.childCount == 0) View.VISIBLE else View.GONE
        }

        val pkgs = readList()
            .filter { isLaunchable(it) }
            .reversed()
        for (pkg in pkgs) {
            row.addView(
                buildCard(pkg, cardW, cardH, headerH, margin) { v ->
                    row.removeView(v)
                    updateEmpty()
                }
            )
        }
        updateEmpty()

        scroll.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            if (needsScroll && row.width > 0) {
                needsScroll = false
                scroll.scrollTo(row.width, 0)
            }
        }
        scrollRef = scroll
        rowRef = row
        contentRef = content

        val clearBtn = TextView(this).apply {
            text = "Fechar tudo"
            setTextColor(Color.WHITE)
            textSize = 18f
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                cornerRadius = dp(26).toFloat()
                setColor(Color.parseColor("#3A3A3F"))
            }
            setOnClickListener {
                saveList(emptyList())
                goHome()
            }
        }

        content.addView(empty, FrameLayout.LayoutParams(wrapP, wrapP, Gravity.CENTER))
        content.addView(
            scroll,
            FrameLayout.LayoutParams(matchP, headerH + cardH).apply {
                topMargin = (screenH * 0.065f).toInt()
            }
        )
        content.addView(
            clearBtn,
            FrameLayout.LayoutParams(matchP, dp(52), Gravity.BOTTOM).apply {
                setMargins(dp(30), 0, dp(30), (screenH * 0.06f).toInt())
            }
        )
        return root
    }

    private fun buildCard(
        pkg: String,
        cardW: Int,
        cardH: Int,
        headerH: Int,
        margin: Int,
        onRemoved: (View) -> Unit
    ): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(cardW, headerH + cardH).apply {
                setMargins(margin, 0, margin, 0)
            }
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        header.addView(
            ImageView(this).apply { setImageDrawable(iconOf(pkg)) },
            LinearLayout.LayoutParams(dp(36), dp(36))
        )
        header.addView(
            TextView(this).apply {
                text = labelOf(pkg)
                setTextColor(Color.WHITE)
                textSize = 15f
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                setPadding(dp(10), 0, 0, 0)
            },
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )

        val body = FrameLayout(this).apply {
            background = GradientDrawable().apply {
                cornerRadius = dp(28).toFloat()
                setColor(Color.parseColor("#2C2C30"))
            }
        }
        body.addView(
            ImageView(this).apply { setImageDrawable(iconOf(pkg)) },
            FrameLayout.LayoutParams(dp(96), dp(96), Gravity.CENTER)
        )

        card.addView(
            header,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, headerH)
        )
        card.addView(
            body,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, cardH)
        )

        attachGestures(card, pkg, onRemoved)
        return card
    }

    private fun attachGestures(card: View, pkg: String, onRemoved: (View) -> Unit) {
        var downX = 0f
        var downY = 0f
        var dragging = false
        val slop = ViewConfiguration.get(this).scaledTouchSlop

        card.setOnTouchListener { v, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = ev.rawX
                    downY = ev.rawY
                    dragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = ev.rawX - downX
                    val dy = ev.rawY - downY
                    if (!dragging && dy < -slop && abs(dy) > abs(dx)) {
                        dragging = true
                        v.parent.requestDisallowInterceptTouchEvent(true)
                    }
                    if (dragging) {
                        v.translationY = minOf(dy, 0f)
                        v.alpha = (1f + v.translationY / v.height).coerceIn(0f, 1f)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (dragging) {
                        if (v.translationY < -v.height * 0.2f) {
                            v.animate()
                                .translationY(-v.height.toFloat())
                                .alpha(0f)
                                .setDuration(150)
                                .withEndAction {
                                    saveList(readList().filter { it != pkg })
                                    cachedVersion = listVersion
                                    onRemoved(v)
                                }
                                .start()
                        } else {
                            v.animate().translationY(0f).alpha(1f).setDuration(150).start()
                        }
                    } else if (abs(ev.rawX - downX) < slop && abs(ev.rawY - downY) < slop) {
                        openApp(pkg)
                    }
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    v.animate().translationY(0f).alpha(1f).setDuration(150).start()
                    true
                }
                else -> true
            }
        }
    }

    private fun openApp(pkg: String) {
        val intent = packageManager.getLaunchIntentForPackage(pkg) ?: return
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        dismissed = true
        pendingPkg = pkg
        contentRef?.visibility = View.INVISIBLE
        try {
            startActivity(intent)
        } catch (ex: Exception) {
            pendingPkg = null
            log("[abrir] erro: ${ex.javaClass.simpleName}")
        }
        handler.removeCallbacks(forceHideRunnable)
        handler.postDelayed(forceHideRunnable, 1500)
    }

    private fun labelOf(pkg: String): String = labelCache.getOrPut(pkg) {
        try {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
        } catch (e: Exception) {
            pkg
        }
    }

    private fun iconOf(pkg: String): Drawable {
        val d = iconCache.getOrPut(pkg) {
            try {
                packageManager.getApplicationIcon(pkg)
            } catch (e: Exception) {
                packageManager.defaultActivityIcon
            }
        }
        return d.constantState?.newDrawable(resources)?.mutate() ?: d
    }

    private fun readList(): List<String> {
        val prefs = getSharedPreferences("recents", Context.MODE_PRIVATE)
        return (prefs.getString("list", "") ?: "")
            .split("\n")
            .filter { it.isNotEmpty() }
    }

    private fun saveList(list: List<String>) {
        listVersion++
        getSharedPreferences("recents", Context.MODE_PRIVATE)
            .edit()
            .putString("list", list.joinToString("\n"))
            .apply()
    }

    private fun log(body: String) {
        val prefs = getSharedPreferences("debug", Context.MODE_PRIVATE)
        val old = (prefs.getString("log", "") ?: "")
            .split("\n\n")
            .filter { it.isNotEmpty() }
        if (old.firstOrNull()?.substringAfter("  ") == body) return
        val list = (listOf("${timeFmt.format(Date())}  $body") + old).take(60)
        prefs.edit().putString("log", list.joinToString("\n\n")).apply()
    }

    private fun addRecent(pkg: String) {
        val cur = readList()
        if (cur.firstOrNull() == pkg) return
        val list = cur.filter { it != pkg }.toMutableList()
        list.add(0, pkg)
        saveList(list.take(20))
        handler.removeCallbacks(prebuildRunnable)
        handler.postDelayed(prebuildRunnable, 400)
    }
}
