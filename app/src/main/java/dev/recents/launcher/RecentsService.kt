package dev.recents.launcher

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class RecentsService : AccessibilityService() {

    private val timeFmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())
    private val handler = Handler(Looper.getMainLooper())
    private val bgThread = HandlerThread("recents-check").apply { start() }
    private val bg = Handler(bgThread.looper)

    @Volatile private var querying = false
    private var again = false
    private var settleLeft = 0
    private var holdUntil = 0L
    private var blockShowUntil = 0L
    private var pendingPkg: String? = null

    private var overlay: View? = null
    private var dismissed = false
    private var lastState = ""

    private var cachedView: View? = null
    private var cachedW = 0
    private var cachedH = 0
    internal var cachedVersion = -1
    internal var listVersion = 0
    internal var scrollRef: HorizontalScrollView? = null
    internal var rowRef: LinearLayout? = null
    internal var contentRef: View? = null
    internal var needsScroll = true

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
        blockShowUntil = System.currentTimeMillis() + 500
    }

    companion object {
        const val LAUNCHER = "com.motorola.launcher3"
        const val PANEL_ID = "com.motorola.launcher3:id/overview_panel"
        const val CLEAR_ID = "com.motorola.launcher3:id/action_clear_all"
        const val ANY = "*"
        const val SHOW_DELAY = 20L
        const val SHOW_FADE = 70L
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
            if (waiting != ANY && pkg != waiting) return
            pendingPkg = null
            log("[saida] $pkg")
            handler.postDelayed({ fadeOutOverlay() }, 60)
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

    internal fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    internal fun isLaunchable(pkg: String): Boolean =
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
            if (pendingPkg != null) return
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
        if (System.currentTimeMillis() < blockShowUntil) {
            log("[overlay] bloqueado")
            return
        }
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val bounds = wm.maximumWindowMetrics.bounds

        val view = ensureView(bounds.width(), bounds.height())
        view.animate().cancel()
        view.alpha = 0f
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
            view.animate()
                .alpha(1f)
                .setStartDelay(SHOW_DELAY)
                .setDuration(SHOW_FADE)
                .start()
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
            .setStartDelay(0)
            .setDuration(120)
            .withEndAction {
                if (overlay === v) {
                    hideOverlay()
                    dismissed = false
                    blockShowUntil = System.currentTimeMillis() + 500
                }
                v.alpha = 1f
            }
            .start()
    }

    internal fun goHome() {
        dismissed = true
        pendingPkg = ANY
        blockShowUntil = System.currentTimeMillis() + 900
        contentRef?.visibility = View.INVISIBLE
        performGlobalAction(GLOBAL_ACTION_HOME)
        handler.removeCallbacks(forceHideRunnable)
        handler.postDelayed(forceHideRunnable, 1500)
        settleLeft = 20
        handler.removeCallbacks(settleRunnable)
        handler.postDelayed(settleRunnable, 150)
    }

    internal fun openApp(pkg: String) {
        val intent = packageManager.getLaunchIntentForPackage(pkg) ?: return
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        dismissed = true
        pendingPkg = pkg
        blockShowUntil = System.currentTimeMillis() + 900
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

    internal fun labelOf(pkg: String): String = labelCache.getOrPut(pkg) {
        try {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
        } catch (e: Exception) {
            pkg
        }
    }

    internal fun iconOf(pkg: String): Drawable {
        val d = iconCache.getOrPut(pkg) {
            try {
                packageManager.getApplicationIcon(pkg)
            } catch (e: Exception) {
                packageManager.defaultActivityIcon
            }
        }
        return d.constantState?.newDrawable(resources)?.mutate() ?: d
    }

    internal fun readList(): List<String> {
        val prefs = getSharedPreferences("recents", Context.MODE_PRIVATE)
        return (prefs.getString("list", "") ?: "")
            .split("\n")
            .filter { it.isNotEmpty() }
    }

    internal fun saveList(list: List<String>) {
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
