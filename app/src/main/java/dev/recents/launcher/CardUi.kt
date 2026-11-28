package dev.recents.launcher

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import kotlin.math.abs

internal fun RecentsService.buildCard(
    pkg: String,
    cardW: Int,
    cardH: Int,
    iconSize: Int,
    slot: Int,
    onRemoved: (View) -> Unit
): View {
    val half = iconSize / 2
    val page = FrameLayout(this).apply {
        clipChildren = false
        layoutParams = LinearLayout.LayoutParams(cardW, cardH + half).apply {
            setMargins(slot, 0, slot, 0)
        }
    }

    val body = FrameLayout(this).apply {
        background = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(Color.parseColor("#FF30343C"), Color.parseColor("#FF1D2026"))
        ).apply { cornerRadius = dp(28).toFloat() }
        outlineProvider = ViewOutlineProvider.BACKGROUND
        clipToOutline = true
    }
    body.addView(
        ImageView(this).apply { setImageDrawable(iconOf(pkg)) },
        FrameLayout.LayoutParams(dp(88), dp(88), Gravity.CENTER)
    )
    val scrim = View(this).apply {
        setBackgroundColor(Color.BLACK)
        alpha = 0f
    }
    body.addView(
        scrim,
        FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        )
    )
    page.addView(body, FrameLayout.LayoutParams(cardW, cardH).apply { topMargin = half })

    page.addView(
        ImageView(this).apply { setImageDrawable(iconOf(pkg)) },
        FrameLayout.LayoutParams(iconSize, iconSize, Gravity.TOP or Gravity.CENTER_HORIZONTAL)
    )

    page.tag = scrim
    attachGestures(page, pkg, onRemoved)
    return page
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

private fun stackOffset(a: Float): Float {
    return when {
        a <= 1f -> 0.29f * a
        a <= 2f -> 0.29f + 0.165f * (a - 1f)
        a <= 3f -> 0.455f + 0.115f * (a - 2f)
        else -> 0.57f + 0.05f * (a - 3f)
    }
}

internal fun applyDepth(scroll: HorizontalScrollView, row: LinearLayout, step: Int, cardW: Int) {
    val center = scroll.scrollX + scroll.width / 2f
    for (i in 0 until row.childCount) {
        val c = row.getChildAt(i)
        val dx = (c.left + c.width / 2f) - center
        val a = abs(dx) / step
        val sign = if (dx < 0f) -1f else 1f
        c.translationX = sign * stackOffset(a) * cardW - dx
        val s = maxOf(0.55f, 1f - 0.14f * a)
        c.scaleX = s
        c.scaleY = s
        c.translationZ = -a
        (c.tag as? View)?.alpha = minOf(0.75f, 0.35f * a)
    }
}

internal fun snapWhenIdle(scroll: HorizontalScrollView, row: LinearLayout) {
    var last = Int.MIN_VALUE
    var stable = 0
    val check = object : Runnable {
        override fun run() {
            val x = scroll.scrollX
            if (x == last) {
                stable++
            } else {
                stable = 0
                last = x
            }
            if (stable >= 2) {
                snapToNearest(scroll, row)
            } else {
                scroll.postDelayed(this, 60)
            }
        }
    }
    scroll.postDelayed(check, 60)
}

internal fun snapToNearest(scroll: HorizontalScrollView, row: LinearLayout) {
    if (row.childCount == 0) return
    val center = scroll.scrollX + scroll.width / 2f
    var best = 0f
    var bestDist = Float.MAX_VALUE
    for (i in 0 until row.childCount) {
        val c = row.getChildAt(i)
        val mid = c.left + c.width / 2f
        val dist = abs(mid - center)
        if (dist < bestDist) {
            bestDist = dist
            best = mid
        }
    }
    if (bestDist > 2f) {
        scroll.smoothScrollTo((best - scroll.width / 2f).toInt().coerceAtLeast(0), 0)
    }
}
