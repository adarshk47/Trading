package com.scalpai.stocksignal

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import android.widget.*
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    companion object {
        const val PREFS = "scalp_prefs"
        const val PREF_MIN_CONFIDENCE = "min_confidence"
        const val PREF_SHOW_WAIT      = "show_wait"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        setupUI()
    }

    override fun onResume() {
        super.onResume()
        refreshPermissionStatus()
    }

    private fun setupUI() {
        // ── Permission Buttons ───────────────────────────────────────────
        findViewById<Button>(R.id.btnOverlayPermission).setOnClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")))
            } else {
                toast("Overlay permission already granted ✓")
            }
        }

        findViewById<Button>(R.id.btnAccessibilityPermission).setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Enable Accessibility Service")
                .setMessage(
                    "Steps:\n\n" +
                    "1. Tap 'Open Settings' below\n" +
                    "2. Tap 'Installed Apps' or 'Downloaded Apps'\n" +
                    "3. Tap 'ScalpAI'\n" +
                    "4. Toggle ON\n\n" +
                    "This lets ScalpAI read stock symbols from any open app (Zerodha, Groww, NSE, etc.) " +
                    "and automatically show you a signal popup."
                )
                .setPositiveButton("Open Settings") { _, _ ->
                    startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                }
                .setNegativeButton("Cancel", null)
                .show()
        }

        // ── Manual Symbol Search ─────────────────────────────────────────
        val etSymbol = findViewById<EditText>(R.id.etSymbol)
        findViewById<Button>(R.id.btnSearch).setOnClickListener {
            val sym = etSymbol.text.toString().trim().uppercase()
            if (sym.isEmpty()) { toast("Enter a symbol"); return@setOnClickListener }
            if (!SignalEngine.isKnownStock(sym)) {
                toast("$sym not in F&O list. Try: SBIN, RELIANCE, TATAMOTORS…")
                return@setOnClickListener
            }
            if (!hasOverlayPermission()) {
                toast("Grant Overlay permission first")
                return@setOnClickListener
            }
            startOverlayService(sym)
            toast("Fetching $sym…")
        }

        // ── Quick-pick chips ─────────────────────────────────────────────
        val quickSymbols = listOf("SBIN", "RELIANCE", "TATAMOTORS", "ICICIBANK", "TATASTEEL", "ONGC")
        val chipContainer = findViewById<LinearLayout>(R.id.chipContainer)
        quickSymbols.forEach { sym ->
            val btn = Button(this).apply {
                text = sym
                textSize = 11f
                setBackgroundResource(R.drawable.chip_bg)
                setPadding(24, 8, 24, 8)
                val lp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                lp.setMargins(0, 0, 12, 0)
                layoutParams = lp
                setOnClickListener {
                    if (!hasOverlayPermission()) { toast("Grant Overlay permission first"); return@setOnClickListener }
                    startOverlayService(sym)
                    toast("Fetching $sym…")
                }
            }
            chipContainer.addView(btn)
        }

        // ── Settings ─────────────────────────────────────────────────────
        val prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)

        val seekConf = findViewById<SeekBar>(R.id.seekConfidence)
        val tvConf   = findViewById<TextView>(R.id.tvConfidenceValue)
        seekConf.progress = prefs.getInt(PREF_MIN_CONFIDENCE, 60)
        tvConf.text       = "${seekConf.progress}%"
        seekConf.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, p: Int, u: Boolean) {
                tvConf.text = "$p%"
                prefs.edit().putInt(PREF_MIN_CONFIDENCE, p).apply()
            }
            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) {}
        })

        val swWait = findViewById<Switch>(R.id.switchShowWait)
        swWait.isChecked = prefs.getBoolean(PREF_SHOW_WAIT, false)
        swWait.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean(PREF_SHOW_WAIT, checked).apply()
        }
    }

    private fun refreshPermissionStatus() {
        val tvOverlay = findViewById<TextView>(R.id.tvOverlayStatus)
        val tvAccess  = findViewById<TextView>(R.id.tvAccessibilityStatus)

        val hasOverlay = hasOverlayPermission()
        val hasAccess  = isAccessibilityEnabled()

        val green = getColor(android.R.color.holo_green_dark)
        val red   = getColor(android.R.color.holo_red_light)

        tvOverlay.text      = if (hasOverlay) "✓ Granted" else "✗ Not Granted"
        tvOverlay.setTextColor(if (hasOverlay) green else red)

        tvAccess.text       = if (hasAccess) "✓ Enabled" else "✗ Not Enabled"
        tvAccess.setTextColor(if (hasAccess) green else red)

        // Auto-start service if all permissions OK
        if (hasOverlay && hasAccess) {
            startOverlayServiceBackground()
        }
    }

    private fun startOverlayService(symbol: String) {
        val intent = Intent(this, OverlayService::class.java).apply {
            action = OverlayService.ACTION_FETCH_SYMBOL
            putExtra(OverlayService.EXTRA_SYMBOL, symbol)
        }
        startService(intent)
    }

    private fun startOverlayServiceBackground() {
        startService(Intent(this, OverlayService::class.java))
    }

    private fun hasOverlayPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(this)

    private fun isAccessibilityEnabled(): Boolean {
        val am = getSystemService(ACCESSIBILITY_SERVICE) as AccessibilityManager
        val services = am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
        return services.any { it.id.startsWith(packageName) }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
