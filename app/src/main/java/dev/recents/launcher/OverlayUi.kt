package dev.recents.launcher

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.abs

internal fun RecentsService.buildOverlayView(screenW: Int, screenH: Int): View {
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

internal fun RecentsService.buildCard(
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

internal fun RecentsService.attachGestures(card: View, pkg: String, onRemoved: (View) -> Unit) {
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
