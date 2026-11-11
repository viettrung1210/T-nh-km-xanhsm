package com.example.pickupdist

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (16 * resources.displayMetrics.density).toInt()
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        fun label(t: String) = TextView(this).apply { text = t; setPadding(0, pad, 0, 4) }

        col.addView(label("1. Goong API key (goong.io → tạo key REST)"))
        val keyBox = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT
            setText(getSharedPreferences("cfg", MODE_PRIVATE).getString("goong_key", ""))
        }
        col.addView(keyBox)
        col.addView(Button(this).apply {
            text = "Lưu key"
            setOnClickListener {
                getSharedPreferences("cfg", MODE_PRIVATE).edit()
                    .putString("goong_key", keyBox.text.toString().trim()).apply()
                Toast.makeText(context, "Đã lưu", Toast.LENGTH_SHORT).show()
            }
        })

        col.addView(label("2. Cấp quyền vị trí"))
        col.addView(Button(this).apply {
            text = "Cho phép vị trí"
            setOnClickListener {
                requestPermissions(
                    arrayOf(
                        Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.ACCESS_COARSE_LOCATION
                    ), 1
                )
            }
        })
        col.addView(Button(this).apply {
            text = "Mở cài đặt quyền → chọn \"Luôn cho phép\""
            setOnClickListener {
                startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
                )
            }
        })

        col.addView(label("3. Bật dịch vụ trợ năng (Km điểm đón)"))
        col.addView(Button(this).apply {
            text = "Mở cài đặt Trợ năng"
            setOnClickListener { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        })

        setContentView(col)
    }
}
