package dev.recents.launcher

import android.app.Activity
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.provider.Settings
import android.text.TextUtils
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.view.ViewConfiguration
import kotlin.math.abs

class MainActivity : Activity() {

    private lateinit var status: TextView
    private lateinit var empty: TextView
    private lateinit var cards: LinearLayout

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(48), dp(16), dp(24))
        }
        status = TextView(this).apply { textSize = 16f }
        val settingsBtn = Button(this).apply {
            text = "Abrir configurações de acessibilidade"
            setOnClickListener {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
        }
        empty = TextView(this).apply {
            textSize = 16f
            text = "Nenhum app registrado ainda.\nAbra alguns apps e volte aqui."
            setPadding(0, dp(16), 0, dp(16))
        }
        cards = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(8), dp(16), dp(8), dp(16))
        }
        val scroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            isFillViewport = true
            addView(
                cards,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
            )
        }
        val clearBtn = Button(this).apply {
            text = "Fechar tudo"
            setOnClickListener {
                saveList(emptyList())
                reload()
            }
        }

        val match = LinearLayout.LayoutParams.MATCH_PARENT
        val wrap = LinearLayout.LayoutParams.WRAP_CONTENT
        root.addView(status, LinearLayout.LayoutParams(match, wrap))
        root.addView(settingsBtn, LinearLayout.LayoutParams(match, wrap))
        root.addView(empty, LinearLayout.LayoutParams(match, wrap))
        root.addView(scroll, LinearLayout.LayoutParams(match, 0, 1f))
        root.addView(clearBtn, LinearLayout.LayoutParams(match, wrap))
        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        status.text = if (serviceEnabled()) "Serviço: ATIVO" else "Serviço: DESATIVADO"
        reload()
    }

    private fun reload() {
        cards.removeAllViews()
        for (pkg in readList()) {
            if (packageManager.getLaunchIntentForPackage(pkg) == null) continue
            cards.addView(buildCard(pkg))
        }
        updateEmpty()
    }

    private fun updateEmpty() {
        empty.visibility = if (cards.childCount == 0) View.VISIBLE else View.GONE
    }

    private fun buildCard(pkg: String): View {
        val night = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(16), dp(20), dp(16), dp(16))
            background = GradientDrawable().apply {
                cornerRadius = dp(28).toFloat()
                setColor(Color.parseColor(if (night) "#2B2B2B" else "#E4E4E4"))
            }
            layoutParams = LinearLayout.LayoutParams(
                dp(220), LinearLayout.LayoutParams.MATCH_PARENT
            ).apply { setMargins(dp(8), 0, dp(8), 0) }
        }
        val name = TextView(this).apply {
            text = labelOf(pkg)
            textSize = 16f
            gravity = Gravity.CENTER
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        val iconBox = FrameLayout(this)
        iconBox.addView(
            ImageView(this).apply { setImageDrawable(iconOf(pkg)) },
            FrameLayout.LayoutParams(dp(96), dp(96), Gravity.CENTER)
        )
        card.addView(
            name,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )
        card.addView(
            iconBox,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        )
        attachGestures(card, pkg)
        return card
    }

    private fun attachGestures(card: View, pkg: String) {
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
                        if (v.translationY < -v.height * 0.25f) {
                            dismiss(v, pkg)
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

    private fun dismiss(v: View, pkg: String) {
        v.animate()
            .translationY(-v.height.toFloat())
            .alpha(0f)
            .setDuration(150)
            .withEndAction {
                saveList(readList().filter { it != pkg })
                cards.removeView(v)
                updateEmpty()
            }
            .start()
    }

    private fun openApp(pkg: String) {
        packageManager.getLaunchIntentForPackage(pkg)?.let { startActivity(it) }
    }

    private fun serviceEnabled(): Boolean {
        val enabled = Settings.Secure.getString(
            contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        return enabled.contains("$packageName/")
    }

    private fun readList(): List<String> {
        val prefs = getSharedPreferences("recents", MODE_PRIVATE)
        return (prefs.getString("list", "") ?: "")
            .split("\n")
            .filter { it.isNotEmpty() }
    }

    private fun saveList(list: List<String>) {
        getSharedPreferences("recents", MODE_PRIVATE)
            .edit()
            .putString("list", list.joinToString("\n"))
            .apply()
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
}
