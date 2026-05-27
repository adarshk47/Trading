package com.scalpai.stocksignal

import android.app.*
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.*
import android.util.Log
import android.view.*
import android.view.animation.AnimationUtils
import android.widget.*
import androidx.core.app.NotificationCompat

class OverlayService : Service() {

    companion object {
        const val ACTION_FETCH_SYMBOL = "com.scalpai.FETCH_SYMBOL"
        const val ACTION_SHOW_SIGNAL  = "com.scalpai.SHOW_SIGNAL"
        const val EXTRA_SYMBOL        = "symbol"
        private const val NOTIF_CHANNEL = "scalp_ai_channel"
        private const val NOTIF_ID      = 1001
        private const val TAG           = "ScalpAI_Overlay"

        // Track currently showing popup to avoid stacking
        private var currentSymbol: String? = null
    }

    private var windowManager: WindowManager? = null
    private var overlayView: View? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    // Queue of symbols to avoid showing multiple popups simultaneously
    private val pendingQueue = ArrayDeque<String>()
    private var isShowingPopup = false

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        createNotificationChannel()
        startForeground(NOTIF_ID, buildNotification("Watching screen for stocks…"))
        Log.d(TAG, "OverlayService started")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_FETCH_SYMBOL -> {
                val symbol = intent.getStringExtra(EXTRA_SYMBOL) ?: return START_STICKY
                Log.d(TAG, "Fetch requested for: $symbol")
                fetchAndShow(symbol)
            }
            "DISMISS" -> dismissOverlay()
        }
        return START_STICKY
    }

    private fun fetchAndShow(symbol: String) {
        // Don't re-fetch if same symbol popup already showing
        if (isShowingPopup && currentSymbol == symbol) return

        updateNotification("Fetching $symbol…")

        val avgVol = SignalEngine.getAvgVolume(symbol)

        YahooFinanceApi.fetchQuote(
            nseSymbol = symbol,
            onSuccess = { quote ->
                val signal = SignalEngine.calculate(
                    symbol        = symbol,
                    price         = quote.price,
                    open          = quote.open,
                    high          = quote.high,
                    low           = quote.low,
                    volume        = quote.volume,
                    avgVolume     = avgVol,
                    changePercent = quote.changePercent,
                    change        = quote.change,
                    ma50          = quote.ma50
                )
                mainHandler.post { showOverlay(signal) }
                updateNotification("Ready — watching screen")
            },
            onError = { err ->
                Log.w(TAG, "Fetch error for $symbol: $err")
                updateNotification("Ready — watching screen")
            }
        )
    }

    // ── Build & display the floating overlay ────────────────────────────────
    private fun showOverlay(signal: StockSignal) {
        // If another popup is showing, queue this one
        if (isShowingPopup) {
            pendingQueue.addLast(signal.symbol)
            return
        }
        dismissOverlay()  // clear any stale view

        isShowingPopup = true
        currentSymbol  = signal.symbol

        // Build view from code (no XML inflation needed for overlay)
        val ctx = this
        val v   = buildOverlayView(ctx, signal)
        overlayView = v

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y       = 80
        }

        try {
            windowManager?.addView(v, params)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to add overlay: ${e.message}")
            isShowingPopup = false
            return
        }

        // Vibrate on high confidence
        if (signal.confidence >= 75) vibrate()

        // Auto-dismiss after 12 seconds
        mainHandler.postDelayed({
            dismissOverlay()
            // Show next queued symbol
            if (pendingQueue.isNotEmpty()) {
                val next = pendingQueue.removeFirst()
                fetchAndShow(next)
            }
        }, 12_000L)
    }

    // ── Programmatically build the popup card ───────────────────────────────
    private fun buildOverlayView(ctx: Context, sig: StockSignal): View {
        val isCall = sig.direction == "CALL"
        val isPut  = sig.direction == "PUT"
        val isWait = sig.direction == "WAIT"

        val accentHex = when {
            isCall -> "#00E87A"
            isPut  -> "#FF3A5C"
            else   -> "#FFC830"
        }
        val accentColor = Color.parseColor(accentHex)
        val bgColor     = Color.parseColor("#EE0D1422")
        val cardColor   = Color.parseColor("#F00A1020")

        // Root card
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20, 0, 20, 20)
        }

        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(28, 24, 28, 24)
            setBackgroundColor(cardColor)
            background = ctx.getDrawable(android.R.drawable.dialog_holo_dark_frame)
        }

        // Header row: Symbol + Close button
        val headerRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity     = Gravity.CENTER_VERTICAL
        }

        val symbolTV = TextView(ctx).apply {
            text      = "  ${sig.symbol}"
            textSize  = 22f
            setTextColor(Color.WHITE)
            typeface  = android.graphics.Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val sectorLabel = TextView(ctx).apply {
            text    = "NSE F&O"
            textSize = 10f
            setTextColor(Color.parseColor("#556090"))
        }

        val closeBtn = Button(ctx).apply {
            text      = "✕"
            textSize  = 14f
            setTextColor(Color.parseColor("#AAAAAA"))
            setBackgroundColor(Color.TRANSPARENT)
            setPadding(16, 4, 16, 4)
            setOnClickListener { dismissOverlay() }
        }

        headerRow.addView(symbolTV)
        headerRow.addView(sectorLabel)
        headerRow.addView(closeBtn)
        card.addView(headerRow)

        // Divider
        card.addView(makeDivider(ctx))

        // Price row
        val priceRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity     = Gravity.CENTER_VERTICAL
            setPadding(0, 12, 0, 4)
        }
        val priceTV = TextView(ctx).apply {
            text     = "₹${"%.2f".format(sig.price)}"
            textSize = 30f
            setTextColor(Color.WHITE)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val changeClr = if (sig.changePercent >= 0) Color.parseColor("#00E87A") else Color.parseColor("#FF3A5C")
        val changeTV  = TextView(ctx).apply {
            val arrow = if (sig.changePercent >= 0) "▲" else "▼"
            text     = "$arrow ${"%.2f".format(Math.abs(sig.changePercent))}%"
            textSize = 15f
            setTextColor(changeClr)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }
        priceRow.addView(priceTV)
        priceRow.addView(changeTV)
        card.addView(priceRow)

        // Volume label
        val volSurgeClr = when {
            sig.volSurge > 180 -> Color.parseColor("#FF7040")
            sig.volSurge > 130 -> Color.parseColor("#00E87A")
            else               -> Color.parseColor("#556090")
        }
        val volLabel = when {
            sig.volSurge > 200 -> "🔥 VOLUME SURGE ${"%.0f".format(sig.volSurge)}%"
            sig.volSurge > 140 -> "⬆ HIGH VOLUME ${"%.0f".format(sig.volSurge)}%"
            else               -> "VOLUME ${"%.0f".format(sig.volSurge)}% of avg"
        }
        card.addView(makeLabel(ctx, volLabel, volSurgeClr, 11f))

        card.addView(makeDivider(ctx))

        // ── BIG Signal Badge ──
        val signalBadge = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity     = Gravity.CENTER
            setPadding(0, 20, 0, 12)
        }

        val dirIcon = TextView(ctx).apply {
            text     = if (isCall) "📈" else if (isPut) "📉" else "⏸"
            textSize = 36f
        }
        val dirLabel = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16, 0, 0, 0)
        }
        val dirTV = TextView(ctx).apply {
            text     = if (isCall) "BUY CALL" else if (isPut) "BUY PUT" else "WAIT / SKIP"
            textSize = 28f
            setTextColor(accentColor)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }
        val subTV = TextView(ctx).apply {
            text     = if (isCall) "Bullish Setup Detected" else if (isPut) "Bearish Setup Detected" else "No clear edge — hold off"
            textSize = 11f
            setTextColor(Color.parseColor("#8899BB"))
        }
        dirLabel.addView(dirTV)
        dirLabel.addView(subTV)
        signalBadge.addView(dirIcon)
        signalBadge.addView(dirLabel)
        card.addView(signalBadge)

        // Confidence bar
        val confRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity     = Gravity.CENTER_VERTICAL
            setPadding(4, 0, 4, 0)
        }
        val confLabel = TextView(ctx).apply {
            text     = "Confidence"
            textSize = 11f
            setTextColor(Color.parseColor("#556090"))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val confValue = TextView(ctx).apply {
            text     = "${sig.confidence}%  ${sig.strength}"
            textSize = 13f
            setTextColor(accentColor)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }
        confRow.addView(confLabel)
        confRow.addView(confValue)
        card.addView(confRow)

        // Progress bar for confidence
        val progBar = ProgressBar(ctx, null, android.R.attr.progressBarStyleHorizontal).apply {
            max      = 100
            progress = sig.confidence
            progressDrawable?.setColorFilter(accentColor, android.graphics.PorterDuff.Mode.SRC_IN)
            setPadding(4, 8, 4, 12)
        }
        card.addView(progBar)

        // ✅ High confidence banner
        if (sig.confidence >= 75 && !isWait) {
            val banner = TextView(ctx).apply {
                text    = "  ✅  75%+ CONFIDENCE — TRADE ELIGIBLE  "
                textSize = 12f
                setTextColor(accentColor)
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                gravity  = Gravity.CENTER
                setPadding(12, 10, 12, 10)
            }
            card.addView(banner)
        }

        if (sig.confidence in 60..74 && !isWait) {
            val banner = TextView(ctx).apply {
                text    = "  ⚠  MODERATE — Use tight stop loss  "
                textSize = 11f
                setTextColor(Color.parseColor("#FFC830"))
                gravity  = Gravity.CENTER
                setPadding(12, 8, 12, 8)
            }
            card.addView(banner)
        }

        card.addView(makeDivider(ctx))

        // Strike + SL + Target (only for directional signals)
        if (!isWait) {
            val tradeCard = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, 10, 0, 6)
            }
            val strikeTV = makeLabel(ctx, "Strike: ${sig.suggestedStrike}", Color.WHITE, 12f)
            val slTV     = makeLabel(ctx, sig.stopLoss, Color.parseColor("#FF7070"), 12f)
            val tgtTV    = makeLabel(ctx, sig.target,   Color.parseColor("#70FF99"), 12f)
            tradeCard.addView(strikeTV)
            tradeCard.addView(slTV)
            tradeCard.addView(tgtTV)
            card.addView(tradeCard)
            card.addView(makeDivider(ctx))
        }

        // Top 3 factors
        val factorTitle = makeLabel(ctx, "KEY SIGNALS:", Color.parseColor("#556090"), 10f)
        card.addView(factorTitle)
        sig.factors.take(4).forEach { f ->
            val clr = when (f.type) {
                FactorType.BULL    -> Color.parseColor("#00E87A")
                FactorType.BEAR    -> Color.parseColor("#FF3A5C")
                FactorType.WARN    -> Color.parseColor("#FFC830")
                FactorType.NEUTRAL -> Color.parseColor("#8899BB")
            }
            val icon = when (f.type) {
                FactorType.BULL    -> "🟢"
                FactorType.BEAR    -> "🔴"
                FactorType.WARN    -> "🟡"
                FactorType.NEUTRAL -> "⚪"
            }
            card.addView(makeLabel(ctx, "$icon ${f.text}", clr, 11f))
        }

        // Disclaimer
        card.addView(makeDivider(ctx))
        card.addView(makeLabel(ctx, "⚠ Educational only. Not SEBI advice. Use SL always.",
            Color.parseColor("#445070"), 9f))

        root.addView(card)
        return root
    }

    private fun makeDivider(ctx: Context) = View(ctx).apply {
        setBackgroundColor(Color.parseColor("#1A2040"))
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1).apply {
            setMargins(0, 8, 0, 8)
        }
    }

    private fun makeLabel(ctx: Context, text: String, color: Int, sizeSp: Float) =
        TextView(ctx).apply {
            this.text = text
            textSize  = sizeSp
            setTextColor(color)
            setPadding(4, 3, 4, 3)
        }

    private fun dismissOverlay() {
        overlayView?.let {
            try { windowManager?.removeView(it) } catch (_: Exception) {}
            overlayView    = null
            isShowingPopup = false
            currentSymbol  = null
        }
    }

    private fun vibrate() {
        val vib = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            getSystemService(VIBRATOR_MANAGER_SERVICE) as? VibratorManager
        } else null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && vib != null) {
            vib.defaultVibrator.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 100, 80, 150), -1))
        } else {
            @Suppress("DEPRECATION")
            val v = getSystemService(VIBRATOR_SERVICE) as? Vibrator
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                v?.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 100, 80, 150), -1))
            } else {
                @Suppress("DEPRECATION")
                v?.vibrate(longArrayOf(0, 100, 80, 150), -1)
            }
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(
                NOTIF_CHANNEL, "ScalpAI Signals",
                NotificationManager.IMPORTANCE_LOW
            ).apply { description = "Running in background watching screen" }
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(ch)
        }
    }

    private fun buildNotification(text: String): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, NOTIF_CHANNEL)
            .setContentTitle("ScalpAI")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(text: String) {
        val nm = getSystemService(NotificationManager::class.java)
        nm?.notify(NOTIF_ID, buildNotification(text))
    }

    override fun onBind(intent: Intent?) = null

    override fun onDestroy() {
        super.onDestroy()
        dismissOverlay()
        mainHandler.removeCallbacksAndMessages(null)
    }
}
