package com.example.pickupdist

import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.TextView
import kotlin.math.abs

class PickupService : AccessibilityService() {

    private lateinit var wm: WindowManager
    private var button: TextView? = null
    private val handler = Handler(Looper.getMainLooper())

    override fun onServiceConnected() {
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        addButton()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    override fun onDestroy() {
        button?.let { runCatching { wm.removeView(it) } }
        super.onDestroy()
    }

    // ---------- Nút nổi ----------

    @SuppressLint("ClickableViewAccessibility")
    private fun addButton() {
        val size = (44 * resources.displayMetrics.density).toInt()
        val tv = TextView(this).apply {
            text = "📍"
            textSize = 16f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#1A7F64"))
            }
            alpha = 0.4f // làm mờ
        }
        val lp = WindowManager.LayoutParams(
            size, size,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = (resources.displayMetrics.heightPixels * 0.35).toInt()
        }

        var downX = 0f; var downY = 0f; var startX = 0; var startY = 0; var moved = false
        tv.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY; startX = lp.x; startY = lp.y; moved = false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX; val dy = e.rawY - downY
                    if (abs(dx) > 15 || abs(dy) > 15) moved = true
                    if (moved) {
                        lp.x = startX + dx.toInt(); lp.y = startY + dy.toInt()
                        wm.updateViewLayout(tv, lp)
                    }
                }
                MotionEvent.ACTION_UP -> if (!moved) onTap()
            }
            true
        }
        wm.addView(tv, lp)
        button = tv
    }

    private fun showOnButton(text: String, holdMs: Long = 5_000) {
        val b = button ?: return
        b.text = text
        b.textSize = 13f
        b.alpha = 0.95f
        val lp = b.layoutParams as WindowManager.LayoutParams
        lp.width = WindowManager.LayoutParams.WRAP_CONTENT
        lp.height = WindowManager.LayoutParams.WRAP_CONTENT
        b.setPadding(24, 12, 24, 12)
        wm.updateViewLayout(b, lp)
        handler.removeCallbacksAndMessages(null)
        handler.postDelayed({ resetButton() }, holdMs)
    }

    private fun resetButton() {
        val b = button ?: return
        val size = (44 * resources.displayMetrics.density).toInt()
        val lp = b.layoutParams as WindowManager.LayoutParams
        lp.width = size; lp.height = size
        b.setPadding(0, 0, 0, 0)
        b.text = "📍"; b.textSize = 16f; b.alpha = 0.4f
        wm.updateViewLayout(b, lp)
    }

    // ---------- Xử lý khi bấm: đọc điểm đón -> chép vào clipboard -> mở Google Maps ----------

    private fun onTap() {
        val root = rootInActiveWindow
        if (root == null) { showOnButton("Không đọc được màn hình"); return }

        val trip = TripParser.parse(collectTexts(root))
        if (trip == null) { showOnButton("Không thấy cuốc xe"); return }

        val q = trip.pickupQuery

        // 1) Chép sẵn vào clipboard (dự phòng nếu Maps không tự mở)
        val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("diem don", q))
        showOnButton("Đã chép: " + q.take(28))

        // 2) Mở Google Maps chỉ đường từ vị trí hiện tại tới điểm đón (xe máy)
        openMaps(q)
    }

    private fun openMaps(q: String) {
        val uri = Uri.parse(
            "https://www.google.com/maps/dir/?api=1&destination=${Uri.encode(q)}&travelmode=two-wheeler"
        )
        val base = Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            startActivity(Intent(base).setPackage("com.google.android.apps.maps"))
        } catch (e: Exception) {
            try { startActivity(base) } catch (_: Exception) { }
        }
    }

    private fun collectTexts(root: AccessibilityNodeInfo): List<String> {
        val items = mutableListOf<Triple<Int, Int, String>>()
        fun walk(n: AccessibilityNodeInfo?) {
            if (n == null) return
            val t = (n.text ?: n.contentDescription)?.toString()?.trim()
            if (!t.isNullOrEmpty()) {
                val r = Rect(); n.getBoundsInScreen(r)
                items += Triple(r.top, r.left, t)
            }
            for (i in 0 until n.childCount) walk(n.getChild(i))
        }
        walk(root)
        return items.sortedWith(compareBy({ it.first }, { it.second })).map { it.third }
            .fold(mutableListOf()) { acc, s -> if (acc.lastOrNull() != s) acc.add(s); acc }
    }
}
