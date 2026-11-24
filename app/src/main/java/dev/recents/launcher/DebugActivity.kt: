package dev.recents.launcher

import android.app.Activity
import android.graphics.Typeface
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

class DebugActivity : Activity() {

    private lateinit var logView: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (16 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad * 3, pad, pad)
        }
        val title = TextView(this).apply {
            text = "Log de janelas (mais recente primeiro)"
            textSize = 16f
        }
        val clear = Button(this).apply {
            text = "Limpar log"
            setOnClickListener {
                getSharedPreferences("debug", MODE_PRIVATE).edit().clear().apply()
                refresh()
            }
        }
        logView = TextView(this).apply {
            textSize = 12f
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
        }
        val scroll = ScrollView(this).apply { addView(logView) }

        val match = LinearLayout.LayoutParams.MATCH_PARENT
        val wrap = LinearLayout.LayoutParams.WRAP_CONTENT
        root.addView(title, LinearLayout.LayoutParams(match, wrap))
        root.addView(clear, LinearLayout.LayoutParams(match, wrap))
        root.addView(scroll, LinearLayout.LayoutParams(match, 0, 1f))
        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        val log = getSharedPreferences("debug", MODE_PRIVATE).getString("log", "") ?: ""
        logView.text = if (log.isEmpty()) "Vazio." else log
    }
}
