package com.gizli.bakis

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.io.File

object Overlay {
    private val main = Handler(Looper.getMainLooper())
    private var view: View? = null
    private var manager: WindowManager? = null
    private var autoHide: Runnable? = null

    @Volatile var preview = false
    val isShowing: Boolean get() = view != null

    fun show(ctx: Context, isPreview: Boolean = false, onError: (String) -> Unit = {}) {
        val app = ctx.applicationContext
        runMain { showNow(app, isPreview, onError) }
    }

    fun hide(immediate: Boolean = false) = runMain { hideNow(immediate) }

    private fun runMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else main.post { block() }
    }

    private fun showNow(app: Context, isPreview: Boolean, onError: (String) -> Unit) {
        if (view != null) return
        if (!Settings.canDrawOverlays(app)) {
            onError("Katman izni kapalı")
            return
        }
        val prefs = app.getSharedPreferences(WatchService.PREFS, Context.MODE_PRIVATE)
        val dark = prefs.getBoolean(WatchService.KEY_DARK, false)
        val bgColor = if (dark) 0xFF0F1217.toInt() else Color.WHITE
        val fgColor = if (dark) 0xFFE8E6E1.toInt() else 0xFF14161A.toInt()
        val d = app.resources.displayMetrics.density
        fun px(v: Int) = (v * d).toInt()

        val col = LinearLayout(app).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(22), px(64), px(22), px(110))
        }
        val note = prefs.getString(WatchService.KEY_TEXT, "")?.trim().orEmpty()
        var hasContent = false

        if (note.isNotEmpty()) {
            col.addView(TextView(app).apply {
                setText(note)
                setTextColor(fgColor)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 19f)
                setLineSpacing(0f, 1.25f)
            })
            hasContent = true
        }

        val imgFile = File(app.filesDir, WatchService.IMG_NAME)
        if (imgFile.exists()) {
            val bmp = try { BitmapFactory.decodeFile(imgFile.path) } catch (e: Throwable) { null }
            if (bmp != null) {
                col.addView(
                    ImageView(app).apply {
                        setImageBitmap(bmp)
                        adjustViewBounds = true
                        scaleType = ImageView.ScaleType.FIT_CENTER
                    },
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply { topMargin = if (hasContent) px(18) else 0 }
                )
                hasContent = true
            }
        }

        if (!hasContent) {
            col.addView(TextView(app).apply {
                setText("Ders notları")
                setTextColor(fgColor)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 26f)
            })
        }

        val root = FrameLayout(app).apply {
            setBackgroundColor(bgColor)
            alpha = 0f
        }
        root.addView(
            ScrollView(app).apply {
                isVerticalScrollBarEnabled = false
                addView(col)
            },
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )

        if (isPreview) {
            val close = TextView(app).apply {
                setText("Önizleme  •  kapatmak için dokun")
                setTextColor(0xFF1B1406.toInt())
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                gravity = Gravity.CENTER
                setPadding(px(22), px(14), px(22), px(14))
                background = GradientDrawable().apply {
                    setColor(0xFFE9B872.toInt())
                    cornerRadius = px(24).toFloat()
                }
                setOnClickListener { hideNow(false) }
            }
            root.addView(
                close,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                ).apply { bottomMargin = px(40) }
            )
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        if (Build.VERSION.SDK_INT >= 28) {
            params.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }

        val wm = app.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        try {
            wm.addView(root, params)
        } catch (e: Exception) {
            onError("Katman açılamadı: " + (e.message ?: "izin engeli"))
            return
        }
        manager = wm
        view = root
        preview = isPreview
        root.animate().alpha(1f).setDuration(110).start()

        if (isPreview) {
            val r = Runnable { hideNow(false) }
            autoHide = r
            main.postDelayed(r, 6000)
        }
    }

    private fun hideNow(immediate: Boolean) {
        val v = view ?: return
        val wm = manager
        autoHide?.let { main.removeCallbacks(it) }
        autoHide = null
        view = null
        manager = null
        preview = false
        if (immediate) {
            try { wm?.removeViewImmediate(v) } catch (_: Exception) {}
        } else {
            v.animate().alpha(0f).setDuration(110).withEndAction {
                try { wm?.removeViewImmediate(v) } catch (_: Exception) {}
            }.start()
        }
    }
}
