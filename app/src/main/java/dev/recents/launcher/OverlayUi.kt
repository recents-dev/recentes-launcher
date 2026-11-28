package dev.recents.launcher

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView

internal fun RecentsService.buildOverlayView(screenW: Int, screenH: Int): View {
    val matchP = FrameLayout.LayoutParams.MATCH_PARENT
    val wrapP = FrameLayout.LayoutParams.WRAP_CONTENT

    val cardW = (screenW * 0.625f).toInt()
    val cardH = (cardW * 2.11f).toInt()
    val iconSize = dp(60)
    val pageH = cardH + iconSize / 2
    val step = (cardW * 0.36f).toInt()
    val slot = (step - cardW) / 2
    val sidePad = (screenW - step) / 2

    val root = FrameLayout(this).apply {
        background = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(Color.parseColor("#FF1C2A3A"), Color.parseColor("#FF0D1015"))
        )
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
            buildCard(pkg, cardW, cardH, iconSize, slot) { v ->
                row.removeView(v)
                updateEmpty()
                scroll.post { applyDepth(scroll, row, step, cardW) }
            }
        )
    }
    updateEmpty()

    scroll.setOnScrollChangeListener { _, _, _, _, _ ->
        applyDepth(scroll, row, step, cardW)
    }
    scroll.setOnTouchListener { _, ev ->
        if (ev.actionMasked == MotionEvent.ACTION_UP ||
            ev.actionMasked == MotionEvent.ACTION_CANCEL
        ) {
            snapWhenIdle(scroll, row)
        }
        false
    }
    scroll.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
        if (needsScroll && row.width > 0) {
            needsScroll = false
            scroll.scrollTo(row.width, 0)
        }
        applyDepth(scroll, row, step, cardW)
    }
    scrollRef = scroll
    rowRef = row
    contentRef = content

    val clearBtn = TextView(this).apply {
        text = "Fechar tudo"
        setTextColor(Color.WHITE)
        textSize = 18f
        typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER
        background = GradientDrawable().apply {
            cornerRadius = dp(30).toFloat()
            setColor(Color.TRANSPARENT)
            setStroke(dp(1), Color.parseColor("#66FFFFFF"))
        }
        setOnClickListener {
            saveList(emptyList())
            goHome()
        }
    }

    content.addView(empty, FrameLayout.LayoutParams(wrapP, wrapP, Gravity.CENTER))
    content.addView(
        scroll,
        FrameLayout.LayoutParams(matchP, pageH).apply {
            topMargin = (screenH * 0.1475f).toInt() - iconSize / 2
        }
    )
    content.addView(
        clearBtn,
        FrameLayout.LayoutParams(
            (screenW * 0.53f).toInt(),
            dp(52),
            Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        ).apply {
            bottomMargin = (screenH * 0.16f).toInt()
        }
    )
    return root
}
