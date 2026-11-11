package com.example.pickupdist

import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.TextView
import android.widget.Toast
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.*
import kotlinx.coroutines.tasks.await
import kotlin.math.abs

class PickupService : AccessibilityService() {

    private lateinit var wm: WindowManager
    private var button: TextView? = null
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val handler = Handler(Looper.getMainLooper())
    private var busy = false

    override fun onServiceConnected() {
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        addButton()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    override fun onDestroy() {
        scope.cancel()
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

    private fun showOnButton(text: String, holdMs: Long = 10_000) {
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

    // ---------- Xử lý khi bấm ----------

    private fun onTap() {
        if (busy) return
        busy = true
        showOnButton("Đang tìm…", 15_000)
        scope.launch {
            try {
                val msg = withTimeoutOrNull(20_000) {
                    withContext(Dispatchers.Default) { compute() }
                } ?: "Quá 20 giây, thử lại"
                showOnButton(msg)
            } catch (e: Exception) {
                showOnButton("Lỗi: ${e.message?.take(40)}", 6_000)
            } finally {
                busy = false
            }
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun compute(): String {
        val key = getSharedPreferences("cfg", MODE_PRIVATE).getString("goong_key", "").orEmpty()
        if (key.isBlank()) return "Chưa nhập API key"

        val root = rootInActiveWindow ?: return "Không đọc được màn hình"
        val texts = collectTexts(root)
        val trip = TripParser.parse(texts) ?: return "Không thấy cuốc xe"

        val fused = LocationServices.getFusedLocationProviderClient(this)
        // Vị trí cuối cùng thường có sẵn ngay (app Xanh SM đang bật GPS); chỉ khi quá cũ mới hỏi GPS mới, tối đa 4s
        val loc = runCatching { fused.lastLocation.await() }.getOrNull()
            ?.takeIf { System.currentTimeMillis() - it.time < 120_000 }
            ?: withTimeoutOrNull(4_000) {
                runCatching {
                    fused.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null).await()
                }.getOrNull()
            }
            ?: return "Không lấy được vị trí"
        val me = LatLng(loc.latitude, loc.longitude)

        val pickup = Goong.find(key, trip.pickupQuery, me) ?: return "Không tìm ra điểm đón"
        val r = Goong.route(key, me, pickup) ?: return "Không tìm ra đường"

        val km = r.meters / 1000.0
        val min = (r.seconds / 60).toInt()
        return "Đến điểm đón: %.1f km · ~%d phút".format(km, min)
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
