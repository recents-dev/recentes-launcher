package dev.recents.launcher

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.view.accessibility.AccessibilityEvent
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class RecentsService : AccessibilityService() {

    private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val e = event ?: return
        if (e.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = e.packageName?.toString() ?: return
        if (pkg == packageName) return

        logEvent(pkg, e.className?.toString() ?: "?")

        if (pkg == "com.android.systemui") return
        if (packageManager.getLaunchIntentForPackage(pkg) == null) return
        addRecent(pkg)
    }

    override fun onInterrupt() {}

    private fun logEvent(pkg: String, cls: String) {
        val prefs = getSharedPreferences("debug", Context.MODE_PRIVATE)
        val line = "${timeFmt.format(Date())}  $pkg\n    $cls"
        val old = (prefs.getString("log", "") ?: "")
            .split("\n\n")
            .filter { it.isNotEmpty() }
        val list = (listOf(line) + old).take(40)
        prefs.edit().putString("log", list.joinToString("\n\n")).apply()
    }

    private fun addRecent(pkg: String) {
        val prefs = getSharedPreferences("recents", Context.MODE_PRIVATE)
        val list = (prefs.getString("list", "") ?: "")
            .split("\n")
            .filter { it.isNotEmpty() && it != pkg }
            .toMutableList()
        list.add(0, pkg)
        prefs.edit().putString("recents_placeholder", "").apply()
        prefs.edit().putString("list", list.take(20).joinToString("\n")).apply()
    }
}
