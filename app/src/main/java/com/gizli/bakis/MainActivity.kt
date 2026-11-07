package com.gizli.bakis

import android.Manifest
import android.app.AlertDialog
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat

class MainActivity : ComponentActivity() {

    private lateinit var prefs: SharedPreferences
    private lateinit var status: TextView
    private lateinit var btnNotes: Button
    private lateinit var btnReels: Button
    private lateinit var btnToggle: Button
    private lateinit var sensLabel: TextView

    private val permLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
                launchService()
            else toast("Kamera izni gerekli")
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences(WatchService.PREFS, MODE_PRIVATE)

        if (prefs.getString(WatchService.KEY_REELS, null) == null &&
            packageManager.getLaunchIntentForPackage("com.instagram.android") != null
        ) prefs.edit().putString(WatchService.KEY_REELS, "com.instagram.android").apply()

        val d = resources.displayMetrics.density
        val pad = (20 * d).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad * 2, pad, pad)
        }

        status = TextView(this).apply { textSize = 22f; gravity = Gravity.CENTER }
        btnNotes = Button(this).apply { setOnClickListener { pickApp(WatchService.KEY_NOTES) } }
        btnReels = Button(this).apply { setOnClickListener { pickApp(WatchService.KEY_REELS) } }
        sensLabel = TextView(this).apply { setPadding(0, pad, 0, 0) }

        val seek = SeekBar(this).apply {
            max = 35
            progress = prefs.getInt(WatchService.KEY_SENS, 12) - 5
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, u: Boolean) {
                    sensLabel.text = sensText(p + 5)
                }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {
                    prefs.edit().putInt(WatchService.KEY_SENS, progress + 5).apply()
                    if (WatchService.running) {
                        stopService(Intent(this@MainActivity, WatchService::class.java))
                        window.decorView.postDelayed({ launchService() }, 500)
                    }
                }
            })
        }
        sensLabel.text = sensText(seek.progress + 5)

        btnToggle = Button(this).apply { setOnClickListener { onToggle() } }

        root.addView(status)
        root.addView(btnNotes)
        root.addView(btnReels)
        root.addView(sensLabel)
        root.addView(seek)
        root.addView(btnToggle)
        setContentView(ScrollView(this).apply { addView(root) })
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun sensText(p: Int) =
        "Algılama mesafesi: %$p\n(düşük = uzaktaki yüzü de görür, yüksek = sadece yakın yüzler)"

    private fun label(pkg: String?): String {
        if (pkg == null) return "seçilmedi"
        return try {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
        } catch (e: Exception) { "seçilmedi" }
    }

    private fun refresh() {
        btnNotes.text = "Başkası bakınca AÇILACAK: " + label(prefs.getString(WatchService.KEY_NOTES, null))
        btnReels.text = "Geri DÖNÜLECEK: " + label(prefs.getString(WatchService.KEY_REELS, null))
        val r = WatchService.running
        status.text = if (r) "Çalışıyor ✓" else "Kapalı"
        btnToggle.text = if (r) "DURDUR" else "BAŞLAT"
    }

    private fun pickApp(key: String) {
        val q = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = packageManager.queryIntentActivities(q, 0)
            .map { it.activityInfo.packageName to it.loadLabel(packageManager).toString() }
            .filter { it.first != packageName }
            .distinctBy { it.first }
            .sortedBy { it.second.lowercase() }
        AlertDialog.Builder(this)
            .setTitle("Uygulama seç")
            .setItems(apps.map { it.second }.toTypedArray()) { _, i ->
                prefs.edit().putString(key, apps[i].first).apply()
                refresh()
            }
            .show()
    }

    private fun onToggle() {
        if (WatchService.running) {
            stopService(Intent(this, WatchService::class.java))
            window.decorView.postDelayed({ refresh() }, 500)
            return
        }
        if (prefs.getString(WatchService.KEY_NOTES, null) == null) {
            toast("Önce açılacak uygulamayı seç"); return
        }
        if (!Settings.canDrawOverlays(this)) {
            toast("'Diğer uygulamaların üzerinde göster' iznini aç")
            startActivity(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            )
            return
        }
        val need = mutableListOf(Manifest.permission.CAMERA)
        if (Build.VERSION.SDK_INT >= 33) need.add(Manifest.permission.POST_NOTIFICATIONS)
        val missing = need.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) { permLauncher.launch(missing.toTypedArray()); return }
        launchService()
    }

    private fun launchService() {
        ContextCompat.startForegroundService(this, Intent(this, WatchService::class.java))
        window.decorView.postDelayed({ refresh() }, 500)
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()
}
