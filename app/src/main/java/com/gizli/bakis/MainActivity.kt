package com.gizli.bakis

import android.Manifest
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import java.io.File

class MainActivity : ComponentActivity() {

    private companion object {
        const val BG = 0xFF0F1217.toInt()
        const val CARD = 0xFF181C23.toInt()
        const val LINE = 0xFF262B34.toInt()
        const val INK = 0xFFEDEAE4.toInt()
        const val MUTED = 0xFF8D939E.toInt()
        const val ACCENT = 0xFFE9B872.toInt()
        const val ACCENT_INK = 0xFF1B1406.toInt()
        const val OK = 0xFF7FD6A0.toInt()
        const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
    }

    private lateinit var prefs: SharedPreferences
    private val ui = Handler(Looper.getMainLooper())

    private lateinit var dot: View
    private lateinit var statusText: TextView
    private lateinit var toggle: TextView
    private lateinit var bigCount: TextView
    private lateinit var liveCaption: TextView
    private lateinit var hint: TextView
    private lateinit var imgBtn: TextView
    private lateinit var imgClear: TextView
    private lateinit var thumb: ImageView
    private lateinit var sensLabel: TextView
    private val minChips = mutableListOf<TextView>()
    private val themeChips = mutableListOf<TextView>()
    private var shownRunning: Boolean? = null

    private val permLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
                launchService()
            else toast("Kamera izni gerekli")
        }

    private val imgPicker =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            if (uri != null) saveImage(uri)
        }

    // ---------- küçük yardımcılar ----------

    private fun dp(v: Float) = (v * resources.displayMetrics.density + 0.5f).toInt()
    private fun dp(v: Int) = dp(v.toFloat())

    private fun shape(fill: Int, r: Float, stroke: Int = 0) = GradientDrawable().apply {
        setColor(fill)
        cornerRadius = dp(r).toFloat()
        if (stroke != 0) setStroke(dp(1), stroke)
    }

    private fun tv(txt: String, size: Float, color: Int, font: String = "sans-serif") =
        TextView(this).apply {
            text = txt
            setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
            setTextColor(color)
            typeface = Typeface.create(font, Typeface.NORMAL)
        }

    private fun pill(label: String, onClick: () -> Unit) =
        tv(label, 15f, INK, "sans-serif-medium").apply {
            gravity = Gravity.CENTER
            setPadding(dp(16), dp(14), dp(16), dp(14))
            isClickable = true
            setOnClickListener { onClick() }
        }

    private fun style(v: TextView, on: Boolean) {
        v.background = if (on) shape(ACCENT, 14f) else shape(Color.TRANSPARENT, 14f, LINE)
        v.setTextColor(if (on) ACCENT_INK else INK)
    }

    private fun lp(w: Int, h: Int, top: Int = 0) =
        LinearLayout.LayoutParams(w, h).apply { topMargin = dp(top) }

    private fun card(vararg children: View) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = shape(CARD, 22f, LINE)
        setPadding(dp(20), dp(18), dp(20), dp(20))
        children.forEach { addView(it) }
        layoutParams = lp(MATCH, WRAP, 14)
    }

    private fun sectionTitle(t: String) = tv(t.uppercase(), 11f, MUTED, "sans-serif-medium").apply {
        letterSpacing = 0.14f
    }

    // ---------- ekran ----------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences(WatchService.PREFS, MODE_PRIVATE)

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(20), dp(18), dp(40))
        }
        val animViews = mutableListOf<View>()

        // Başlık
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(4), dp(8), dp(4), dp(6))
            addView(tv("Perde", 36f, INK, "sans-serif-light"))
            addView(
                tv(
                    "Ekranına başkası bakınca notların açılır. Bakmayı bırakınca her şey eski haline döner.",
                    14f, MUTED
                ).apply {
                    setLineSpacing(0f, 1.2f)
                    setPadding(0, dp(6), 0, 0)
                }
            )
        }
        col.addView(header, lp(MATCH, WRAP))
        animViews.add(header)

        // Durum + başlat
        dot = View(this)
        statusText = tv("", 15f, INK, "sans-serif-medium")
        val statusRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(dot, LinearLayout.LayoutParams(dp(10), dp(10)).apply { rightMargin = dp(10) })
            addView(statusText)
        }
        toggle = pill("Başlat") { onToggle() }
        val c1 = card(statusRow)
        c1.addView(toggle, lp(MATCH, WRAP, 16))
        col.addView(c1)
        animViews.add(c1)

        // Tetikleme eşiği
        hint = tv("", 13f, MUTED).apply {
            setLineSpacing(0f, 1.2f)
            setPadding(0, dp(12), 0, 0)
        }
        val chipRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        for (n in 1..4) {
            val chip = pill("$n") { setMin(n) }
            minChips.add(chip)
            chipRow.addView(chip, LinearLayout.LayoutParams(0, WRAP, 1f).apply {
                if (n < 4) rightMargin = dp(8)
            })
        }
        bigCount = tv("–", 46f, ACCENT, "sans-serif-light")
        liveCaption = tv("", 13f, MUTED)
        val divider = View(this).apply { setBackgroundColor(LINE) }
        val c2 = card(
            sectionTitle("Kaç yüz görünce açılsın"),
            Space(14), chipRow, hint
        )
        c2.addView(divider, lp(MATCH, dp(1), 16))
        c2.addView(bigCount, lp(WRAP, WRAP, 10))
        c2.addView(liveCaption)
        col.addView(c2)
        animViews.add(c2)

        // Not
        val edit = EditText(this).apply {
            hint = "Ders notlarını buraya yaz"
            setHintTextColor(MUTED)
            setTextColor(INK)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            background = shape(BG, 14f, LINE)
            setPadding(dp(14), dp(12), dp(14), dp(12))
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 5
            gravity = Gravity.TOP
            setText(prefs.getString(WatchService.KEY_TEXT, ""))
            addTextChangedListener(object : TextWatcher {
                override fun afterTextChanged(s: Editable?) {
                    prefs.edit().putString(WatchService.KEY_TEXT, s?.toString() ?: "").apply()
                }
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            })
        }
        thumb = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = shape(BG, 12f)
            outlineProvider = ViewOutlineProvider.BACKGROUND
            clipToOutline = true
        }
        imgBtn = pill("Görsel ekle") { imgPicker.launch("image/*") }
        imgClear = pill("Kaldır") {
            File(filesDir, WatchService.IMG_NAME).delete()
            loadThumb()
            shownRunning = null
            refresh()
        }
        style(imgBtn, false)
        style(imgClear, false)
        val imgRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(thumb, LinearLayout.LayoutParams(dp(56), dp(56)).apply { rightMargin = dp(10) })
            addView(imgBtn, LinearLayout.LayoutParams(0, WRAP, 1f).apply { rightMargin = dp(8) })
            addView(imgClear, LinearLayout.LayoutParams(WRAP, WRAP))
        }

        val themeRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        listOf("Açık zemin", "Koyu zemin").forEachIndexed { i, label ->
            val chip = pill(label) {
                prefs.edit().putBoolean(WatchService.KEY_DARK, i == 1).apply()
                paintTheme()
            }
            themeChips.add(chip)
            themeRow.addView(chip, LinearLayout.LayoutParams(0, WRAP, 1f).apply {
                if (i == 0) rightMargin = dp(8)
            })
        }
        val previewBtn = pill("Önizleme göster") { preview() }
        style(previewBtn, false)

        val c3 = card(sectionTitle("Gösterilecek not"), Space(12), edit)
        c3.addView(imgRow, lp(MATCH, WRAP, 12))
        c3.addView(themeRow, lp(MATCH, WRAP, 12))
        c3.addView(previewBtn, lp(MATCH, WRAP, 12))
        col.addView(c3)
        animViews.add(c3)

        // Mesafe
        sensLabel = tv("", 13f, MUTED).apply { setLineSpacing(0f, 1.2f); setPadding(0, dp(10), 0, 0) }
        val seek = SeekBar(this).apply {
            max = 35
            progress = prefs.getInt(WatchService.KEY_SENS, 12) - 5
            progressTintList = ColorStateList.valueOf(ACCENT)
            thumbTintList = ColorStateList.valueOf(ACCENT)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, u: Boolean) {
                    sensLabel.text = sensText(p + 5)
                }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {
                    prefs.edit().putInt(WatchService.KEY_SENS, progress + 5).apply()
                    if (WatchService.running) {
                        stopService(Intent(this@MainActivity, WatchService::class.java))
                        window.decorView.postDelayed({ launchService() }, 600)
                    }
                }
            })
        }
        sensLabel.text = sensText(seek.progress + 5)
        val c4 = card(sectionTitle("Algılama mesafesi"), Space(8), seek, sensLabel)
        col.addView(c4)
        animViews.add(c4)

        // Kurulum
        val setupBtn = pill("Uygulama ayarlarını aç") {
            startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
            )
        }
        style(setupBtn, false)
        val c5 = card(
            sectionTitle("Telefon ayarı (Xiaomi)"),
            tv(
                "Pil tasarrufu: Kısıtlama yok\nOtomatik başlat: Açık\nDiğer izinler: Ekranın üzerinde göster: Açık\nSon uygulamalarda Perde kartını kilitle",
                13f, MUTED
            ).apply { setLineSpacing(0f, 1.3f); setPadding(0, dp(10), 0, 0) }
        )
        c5.addView(setupBtn, lp(MATCH, WRAP, 14))
        col.addView(c5)
        animViews.add(c5)

        val content = ScrollView(this).apply {
            setBackgroundColor(BG)
            isVerticalScrollBarEnabled = false
            addView(col)
        }
        val frame = FrameLayout(this).apply {
            setBackgroundColor(BG)
            addView(content, FrameLayout.LayoutParams(MATCH, MATCH))
        }
        setContentView(frame)

        paintMin()
        paintTheme()
        loadThumb()

        if (savedInstanceState == null) {
            animViews.forEach { it.alpha = 0f; it.translationY = dp(22).toFloat() }
            playSplash(frame)
            animViews.forEachIndexed { i, v ->
                v.animate().alpha(1f).translationY(0f)
                    .setStartDelay(1250L + i * 80L).setDuration(480)
                    .setInterpolator(DecelerateInterpolator()).start()
            }
        }
    }

    private fun Space(h: Int) = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(1, dp(h))
    }

    // ---------- açılış animasyonu: perde açılır ----------

    private fun playSplash(frame: FrameLayout) {
        fun fold(leftToRight: Boolean) = View(this).apply {
            val a = 0xFF12161C.toInt()
            val b = 0xFF1D222B.toInt()
            background = GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                if (leftToRight) intArrayOf(a, b) else intArrayOf(b, a)
            )
        }
        val left = fold(true)
        val right = fold(false)
        val panels = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(left, LinearLayout.LayoutParams(0, MATCH, 1f))
            addView(right, LinearLayout.LayoutParams(0, MATCH, 1f))
        }
        val logo = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            alpha = 0f
            scaleX = 0.92f
            scaleY = 0.92f
            addView(tv("PERDE", 30f, ACCENT, "sans-serif-light").apply { letterSpacing = 0.35f })
            addView(tv("sadece sana açık", 13f, MUTED).apply { setPadding(0, dp(8), 0, 0) })
        }
        val layer = FrameLayout(this).apply {
            isClickable = true
            addView(panels, FrameLayout.LayoutParams(MATCH, MATCH))
            addView(logo, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.CENTER))
        }
        frame.addView(layer, FrameLayout.LayoutParams(MATCH, MATCH))

        logo.animate().alpha(1f).scaleX(1f).scaleY(1f)
            .setDuration(650).setInterpolator(DecelerateInterpolator())
            .withEndAction {
                logo.animate().alpha(0f).setStartDelay(300).setDuration(250).start()
                left.animate().translationX(-left.width.toFloat())
                    .setStartDelay(400).setDuration(750)
                    .setInterpolator(AccelerateDecelerateInterpolator()).start()
                right.animate().translationX(right.width.toFloat())
                    .setStartDelay(400).setDuration(750)
                    .setInterpolator(AccelerateDecelerateInterpolator())
                    .withEndAction { frame.removeView(layer) }
                    .start()
            }.start()
    }

    // ---------- durum ----------

    override fun onResume() {
        super.onResume()
        ui.post(tick)
    }

    override fun onPause() {
        super.onPause()
        ui.removeCallbacks(tick)
    }

    private val tick = object : Runnable {
        override fun run() {
            refresh()
            ui.postDelayed(this, 350)
        }
    }

    private fun refresh() {
        val r = WatchService.running
        if (shownRunning != r) {
            shownRunning = r
            dot.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(if (r) OK else MUTED)
            }
            statusText.text = if (r) "Çalışıyor" else "Kapalı"
            toggle.text = if (r) "Durdur" else "Başlat"
            style(toggle, !r)
            val has = File(filesDir, WatchService.IMG_NAME).exists()
            imgBtn.text = if (has) "Görseli değiştir" else "Görsel ekle"
            imgClear.visibility = if (has) View.VISIBLE else View.GONE
        }
        val need = prefs.getInt(WatchService.KEY_MIN, 2)
        val stale = SystemClock.elapsedRealtime() - WatchService.lastFrameAt > 2500
        if (!r) {
            bigCount.text = "–"
            liveCaption.text = "Başlatınca canlı yüz sayısı burada görünür"
        } else if (WatchService.liveFaces < 0 || stale) {
            bigCount.text = "…"
            liveCaption.text = "Kamera hazırlanıyor"
        } else {
            val c = WatchService.liveFaces
            bigCount.text = "$c"
            liveCaption.text =
                if (c >= need) "görülen yüz  •  not gösteriliyor" else "görülen yüz"
        }
    }

    private fun setMin(n: Int) {
        prefs.edit().putInt(WatchService.KEY_MIN, n).apply()
        paintMin()
    }

    private fun paintMin() {
        val n = prefs.getInt(WatchService.KEY_MIN, 2)
        minChips.forEachIndexed { i, c -> style(c, i + 1 == n) }
        hint.text =
            if (n == 1) "1: Sen de sayılırsın. Tek başına denemek için ideal; kameraya bakınca not açılır, bakmayı bırakınca kapanır. Gerçek kullanımda 2 seç."
            else "$n: Sen dahil $n veya daha fazla yüz görününce not açılır."
    }

    private fun paintTheme() {
        val dark = prefs.getBoolean(WatchService.KEY_DARK, false)
        themeChips.forEachIndexed { i, c -> style(c, (i == 1) == dark) }
    }

    private fun sensText(p: Int) =
        "Mesafe: %$p  •  düşük değer uzaktaki yüzü de yakalar, yüksek değer sadece yakındakileri."

    private fun loadThumb() {
        val f = File(filesDir, WatchService.IMG_NAME)
        if (!f.exists()) {
            thumb.visibility = View.GONE
            return
        }
        val bmp = try {
            BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = 8 })
        } catch (e: Throwable) { null }
        if (bmp == null) { thumb.visibility = View.GONE; return }
        thumb.setImageBitmap(bmp)
        thumb.visibility = View.VISIBLE
    }

    private fun saveImage(uri: Uri) {
        try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            var sample = 1
            while (bounds.outWidth / sample > 1800 || bounds.outHeight / sample > 1800) sample *= 2
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            val bmp = contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, opts)
            }
            if (bmp == null) { toast("Görsel okunamadı"); return }
            File(filesDir, WatchService.IMG_NAME).outputStream().use {
                bmp.compress(Bitmap.CompressFormat.JPEG, 90, it)
            }
            loadThumb()
            shownRunning = null
            refresh()
        } catch (e: Exception) {
            toast("Görsel kaydedilemedi")
        }
    }

    // ---------- eylemler ----------

    private fun askOverlayPermission() {
        toast("'Diğer uygulamaların üzerinde göster' iznini aç")
        startActivity(
            Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
        )
    }

    private fun preview() {
        if (!Settings.canDrawOverlays(this)) { askOverlayPermission(); return }
        Overlay.show(this, true) { toast(it) }
    }

    private fun onToggle() {
        if (WatchService.running) {
            stopService(Intent(this, WatchService::class.java))
            window.decorView.postDelayed({ shownRunning = null; refresh() }, 500)
            return
        }
        if (!Settings.canDrawOverlays(this)) { askOverlayPermission(); return }
        val need = mutableListOf(Manifest.permission.CAMERA)
        if (Build.VERSION.SDK_INT >= 33) need.add(Manifest.permission.POST_NOTIFICATIONS)
        val missing = need.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) { permLauncher.launch(missing.toTypedArray()); return }
        launchService()
    }

    private fun launchService() {
        try {
            ContextCompat.startForegroundService(this, Intent(this, WatchService::class.java))
        } catch (e: Exception) {
            toast("Başlatılamadı: " + (e.message ?: "bilinmeyen hata"))
        }
        window.decorView.postDelayed({ shownRunning = null; refresh() }, 500)
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()
}
