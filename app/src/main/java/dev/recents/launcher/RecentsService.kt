package dev.recents.launcher

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.FrameLayout
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class RecentsService : AccessibilityService() {

    private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    private var lastDump = 0L
    private var overlay: View? = null
    private var dismissed = false
    private var lastState = ""

    companion object {
        const val LAUNCHER = "com.motorola.launcher3"
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val e = event ?: return
        val pkg = e.packageName?.toString() ?: return
        if (pkg == packageName) return

        if (pkg == LAUNCHER) {
            val now = System.currentTimeMillis()
            if (now - lastDump > 300) {
                lastDump = now
                checkLauncher()
            }
            return
        }

        if (e.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        if (pkg == "com.android.systemui") return

        dismissed = false
        hideOverlay()
        log("$pkg\n    ${e.className ?: "?"}")

        if (packageManager.getLaunchIntentForPackage(pkg) == null) return
        addRecent(pkg)
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        hideOverlay()
        super.onDestroy()
    }

    private fun checkLauncher() {
        try {
            val root = rootInActiveWindow ?: return
            if (root.packageName?.toString() != LAUNCHER) return

            var panelVis = false
            var clearVis = false
            var taskVis = false
            var count = 0

            val queue = ArrayDeque<Pair<AccessibilityNodeInfo, Int>>()
            queue.add(root to 0)
            while (queue.isNotEmpty() && count < 600) {
                val (n, depth) = queue.removeFirst()
                count++
                val id = n.viewIdResourceName?.substringAfter(":id/") ?: ""
                val txt = n.text?.toString() ?: ""
                val vis = n.isVisibleToUser
                if (id == "overview_panel" && vis) panelVis = true
                if (vis && (id.contains("clear_all") || txt == "Remover tudo" || txt == "Clear all")) {
                    clearVis = true
                }
                if (vis && id == "task") taskVis = true
                if (depth < 8) {
                    for (i in 0 until n.childCount) {
                        n.getChild(i)?.let { queue.add(it to depth + 1) }
                    }
                }
            }

            val open = panelVis || clearVis
            val state = "recentes=$open panel=$panelVis clear=$clearVis task=$taskVis"
            if (state != lastState) {
                lastState = state
                log("[launcher] $state nos=$count")
            }

            if (open) {
                showOverlay()
            } else {
                dismissed = false
                hideOverlay()
            }
        } catch (ex: Exception) {
            log("[launcher] erro: ${ex.javaClass.simpleName}")
        }
    }

    private fun showOverlay() {
        if (overlay != null || dismissed) return
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager

        val view = FrameLayout(this).apply {
            setBackgroundColor(Color.parseColor("#CC1B3A57"))
            addView(
                TextView(this@RecentsService).apply {
                    text = "Tela de recentes (teste)\n\nToque para fechar"
                    setTextColor(Color.WHITE)
                    textSize = 22f
                    gravity = Gravity.CENTER
                },
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
            )
            setOnClickListener {
                dismissed = true
                hideOverlay()
            }
        }

        val bounds = wm.maximumWindowMetrics.bounds
        val lp = WindowManager.LayoutParams(
            bounds.width(),
            bounds.height(),
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
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
            log("[overlay] mostrado ${bounds.width()}x${bounds.height()}")
        } catch (ex: Exception) {
            log("[overlay] erro: ${ex.javaClass.simpleName}")
        }
    }

    private fun hideOverlay() {
        val v = overlay ?: return
        overlay = null
        try {
            (getSystemService(Context.WINDOW_SERVICE) as WindowManager).removeView(v)
        } catch (ex: Exception) {
        }
        log("[overlay] escondido")
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
        val prefs = getSharedPreferences("recents", Context.MODE_PRIVATE)
        val list = (prefs.getString("list", "") ?: "")
            .split("\n")
            .filter { it.isNotEmpty() && it != pkg }
            .toMutableList()
        list.add(0, pkg)
        prefs.edit().putString("list", list.take(20).joinToString("\n")).apply()
    }
}
