package com.w.cast

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.graphics.Typeface
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {
    private lateinit var surfaceView: SurfaceView
    private lateinit var logView: TextView
    private val logListener: (String) -> Unit = { line ->
        runOnUiThread { appendLog(line) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestNotificationPermission()
        setContentView(createContentView())
        logView.text = AppLog.subscribe(logListener).joinToString("\n")
        AppLog.add("界面: 已打开，等待接收器启动日志")
        startMirrorService()
    }

    override fun onDestroy() {
        AppLog.unsubscribe(logListener)
        super.onDestroy()
    }

    private fun createContentView(): LinearLayout {
        surfaceView = SurfaceView(this)
        surfaceView.setBackgroundColor(Color.rgb(16, 24, 32))
        surfaceView.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                MirrorService.setVideoSurface(holder.surface)
            }

            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                MirrorService.setVideoSurface(holder.surface)
            }

            override fun surfaceDestroyed(holder: SurfaceHolder) {
                MirrorService.setVideoSurface(null)
            }
        })

        val title = TextView(this).apply {
            text = "i投屏"
            textSize = 24f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
        }
        val subtitle = TextView(this).apply {
            text = "AirPlay 接收器日志"
            textSize = 15f
            setTextColor(Color.LTGRAY)
            gravity = Gravity.CENTER
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(24, 36, 24, 36)
            addView(title)
            addView(subtitle)
        }

        logView = TextView(this).apply {
            textSize = 12f
            typeface = Typeface.MONOSPACE
            setTextColor(Color.rgb(220, 230, 235))
            setPadding(16, 12, 16, 12)
            setBackgroundColor(Color.rgb(24, 34, 43))
        }
        val logScroll = ScrollView(this).apply {
            addView(logView)
        }
        val copyLogsButton = Button(this).apply {
            text = "复制日志"
            isAllCaps = false
            setOnClickListener { copyLogs() }
        }
        val logPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(copyLogsButton, LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            val logLayoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0
            ).apply {
                weight = 1f
            }
            addView(logScroll, logLayoutParams)
        }
        val logHeight = (220 * resources.displayMetrics.density).toInt()

        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(16, 24, 32))
            addView(header, LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            addView(
                surfaceView,
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0).apply {
                    weight = 1f
                }
            )
            addView(logPanel, LinearLayout.LayoutParams.MATCH_PARENT, logHeight)
        }
    }

    private fun appendLog(line: String) {
        if (!::logView.isInitialized) return
        if (logView.text.isNotEmpty()) logView.append("\n")
        logView.append(line)
        val scrollView = logView.parent as? ScrollView
        scrollView?.post { scrollView.fullScroll(ScrollView.FOCUS_DOWN) }
    }

    private fun copyLogs() {
        val logs = logView.text.toString()
        if (logs.isBlank()) {
            Toast.makeText(this, "暂无日志", Toast.LENGTH_SHORT).show()
            return
        }
        val clipboard = getSystemService(ClipboardManager::class.java)
        clipboard.setPrimaryClip(ClipData.newPlainText("i投屏日志", logs))
        Toast.makeText(this, "日志已复制", Toast.LENGTH_SHORT).show()
    }

    private fun startMirrorService() {
        val intent = Intent(this, MirrorService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 100)
        }
    }
}
