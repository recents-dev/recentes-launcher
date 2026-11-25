package dev.recents.launcher

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class RecentsService : AccessibilityService() {

    private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    private var lastDump = 0L

    companion object {
        const val LAUNCHER = "com.motorola.launcher3"
        val KEYWORDS = listOf("overview", "recents", "task", "clear", "snapshot")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val e = event ?: return
        val pkg = e.packageName?.toString() ?: return
        if (pkg == packageName) return

        if (pkg == LAUNCHER) {
            val now = System.currentTimeMillis()
            if (now - lastDump > 400) {
                lastDump = now
                dumpLauncher(e.eventType)
            }
            return
        }

        if (e.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        log("$pkg\n    ${e.className ?: "?"}")

        if (pkg == "com.android.systemui") return
        if (packageManager.getLaunchIntentForPackage(pkg) == null) return
        addRecent(pkg)
    }

    override fun onInterrupt() {}

    private fun dumpLauncher(type: Int) {
        try {
            val root = rootInActiveWindow ?: return
            if (root.packageName?.toString() != LAUNCHER) return

            val ids = LinkedHashSet<String>()
            var recentsAny = false
            var recentsVis = false
            var taskVis = false
            var clearVis = false
            var count = 0

            val queue = ArrayDeque<Pair<AccessibilityNodeInfo, Int>>()
            queue.add(root to 0)
            while (queue.isNotEmpty() && count < 600) {
                val (n, depth) = queue.removeFirst()
                count++
                val cls = n.className?.toString() ?: ""
                val id = n.viewIdResourceName?.substringAfter(":id/") ?: ""
                val txt = n.text?.toString() ?: ""
                val vis = n.isVisibleToUser

                if (cls.contains("RecentsView")) {
                    recentsAny = true
                    if (vis) recentsVis = true
                    ids.add("cls:" + cls.substringAfterLast('.'))
                }
                if (cls.contains("TaskView")) {
                    if (vis) taskVis = true
                    ids.add("cls:" + cls.substringAfterLast('.'))
                }
                if (vis && (id.contains("clear_all") || txt == "Remover tudo" || txt == "Clear all")) {
                    clearVis = true
                }
                if (id.isNotEmpty() && KEYWORDS.any { id.contains(it, true) }) {
                    ids.add(if (vis) id else "$id(oculto)")
                }
                if (depth < 8) {
                    for (i in 0 until n.childCount) {
                        n.getChild(i)?.let { queue.add(it to depth + 1) }
                    }
                }
            }

            val tipo = if (type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) "estado" else "conteudo"
            val idList = ids.take(12).joinToString(", ")
            log(
                "[launcher] $tipo nos=$count\n" +
                    "    RecentsView existe=$recentsAny visivel=$recentsVis | TaskView visivel=$taskVis | ClearAll visivel=$clearVis\n" +
                    "    ids: $idList"
            )
        } catch (ex: Exception) {
            log("[launcher] erro: ${ex.javaClass.simpleName}")
        }
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
