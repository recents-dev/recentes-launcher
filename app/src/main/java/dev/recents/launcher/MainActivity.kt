package dev.recents.launcher

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

class MainActivity : Activity() {

    private lateinit var status: TextView
    private lateinit var list: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 120, 48, 48)
        }
        status = TextView(this).apply { textSize = 18f }
        val button = Button(this).apply {
            text = "Abrir configurações de acessibilidade"
            setOnClickListener {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
        }
        list = TextView(this).apply {
            textSize = 16f
            setPadding(0, 32, 0, 0)
        }
        val scroll = ScrollView(this).apply { addView(list) }

        root.addView(status)
        root.addView(button)
        root.addView(scroll)
        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        status.text = if (serviceEnabled()) "Serviço: ATIVO" else "Serviço: DESATIVADO"
        list.text = loadRecents()
    }

    private fun serviceEnabled(): Boolean {
        val enabled = Settings.Secure.getString(
            contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        return enabled.contains("$packageName/")
    }

    private fun loadRecents(): String {
        val prefs = getSharedPreferences("recents", MODE_PRIVATE)
        val pkgs = (prefs.getString("list", "") ?: "")
            .split("\n")
            .filter { it.isNotEmpty() }
        if (pkgs.isEmpty()) return "Nenhum app registrado ainda.\nAbra alguns apps e volte aqui."
        return pkgs.joinToString("\n") { "• " + labelOf(it) }
    }

    private fun labelOf(pkg: String): String = try {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
    } catch (e: Exception) {
        pkg
    }
}
