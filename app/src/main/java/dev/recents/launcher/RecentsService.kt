package dev.recents.launcher

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.view.accessibility.AccessibilityEvent

class RecentsService : AccessibilityService() {

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val e = event ?: return
        if (e.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = e.packageName?.toString() ?: return
        if (pkg == packageName || pkg == "com.android.systemui") return
        if (packageManager.getLaunchIntentForPackage(pkg) == null) return
        addRecent(pkg)
    }

    override fun onInterrupt() {}

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
