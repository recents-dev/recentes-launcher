package dev.recents.launcher

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
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

    private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    private var lastDump = 0L
    private var overlay: View? = null
    private var dismissed = false
    private var lastState = ""

    companion object {
        const val LAUNCHER = "com.motorola.launcher3"
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

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

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
        val bounds = wm.maximumWindowMetrics.bounds

        val view = buildOverlayView(bounds.width(), bounds.height())

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

    private fun goHome() {
        dismissed = true
        performGlobalAction(GLOBAL_ACTION_HOME)
        hideOverlay()
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
            setBackgroundColor(Color.parseColor("#F2111114"))
            clipChildren = false
            setOnClickListener { goHome() }
        }

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
            .filter { packageManager.getLaunchIntentForPackage(it) != null }
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

        var positioned = false
        scroll.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            if (!positioned && row.width > 0) {
                positioned = true
                scroll.scrollTo(row.width, 0)
            }
        }

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

        root.addView(empty, FrameLayout.LayoutParams(wrapP, wrapP, Gravity.CENTER))
        root.addView(
            scroll,
            FrameLayout.LayoutParams(matchP, headerH + cardH).apply {
                topMargin = (screenH * 0.065f).toInt()
            }
        )
        root.addView(
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
        try {
            startActivity(intent)
        } catch (ex: Exception) {
            log("[abrir] erro: ${ex.javaClass.simpleName}")
        }
        dismissed = true
        hideOverlay()
    }

    private fun labelOf(pkg: String): String = try {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
    } catch (e: Exception) {
        pkg
    }

    private fun iconOf(pkg: String): Drawable = try {
        packageManager.getApplicationIcon(pkg)
    } catch (e: Exception) {
        packageManager.defaultActivityIcon
    }

    private fun readList(): List<String> {
        val prefs = getSharedPreferences("recents", Context.MODE_PRIVATE)
        return (prefs.getString("list", "") ?: "")
            .split("\n")
            .filter { it.isNotEmpty() }
    }

    private fun saveList(list: List<String>) {
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
        val list = readList().filter { it != pkg }.toMutableList()
        list.add(0, pkg)
        saveList(list.take(20))
    }
}
