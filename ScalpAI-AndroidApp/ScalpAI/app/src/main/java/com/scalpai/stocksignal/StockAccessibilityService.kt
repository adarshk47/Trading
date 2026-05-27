package com.scalpai.stocksignal

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.util.regex.Pattern

class StockAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "ScalpAI_Accessibility"
        // Debounce: don't re-scan same symbol within 15 seconds
        private const val DEBOUNCE_MS = 15_000L

        var isRunning = false
            private set
    }

    // Regex patterns to detect NSE stock symbols in any app
    private val SYMBOL_PATTERNS = listOf(
        // e.g.  SBIN, RELIANCE, TATAMOTORS (all-caps 2-12 chars)
        Pattern.compile("\\b([A-Z]{2,12})\\b"),
        // e.g.  NSE:SBIN  or  BSE:RELIANCE
        Pattern.compile("(?:NSE|BSE):([A-Z]{2,12})"),
        // e.g.  SBIN.NS   RELIANCE.BO
        Pattern.compile("([A-Z]{2,12})\\.(?:NS|BO)"),
    )

    // Common words to ignore so we don't trigger on "OK", "ADD", "BUY" etc.
    private val IGNORE_WORDS = setOf(
        "OK", "ADD", "BUY", "SELL", "EXIT", "OPEN", "CLOSE", "EDIT",
        "BACK", "NEXT", "DONE", "SAVE", "YES", "NO", "ALL", "NEW",
        "NET", "APP", "NSE", "BSE", "MCX", "F&O", "IPO", "ETF",
        "PE", "CE", "OI", "IV", "ATM", "ITM", "OTM", "LTP",
        "PNL", "MTM", "NAV", "SIP", "MF", "RBI", "SEBI"
    )

    // Last time we fetched data for a symbol
    private val lastFetchTime = mutableMapOf<String, Long>()

    override fun onServiceConnected() {
        super.onServiceConnected()
        isRunning = true
        Log.d(TAG, "Accessibility Service Connected")

        val info = serviceInfo ?: AccessibilityServiceInfo()
        info.eventTypes = AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED or
                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
        info.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
        info.notificationTimeout = 500
        info.flags = AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS or
                AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        serviceInfo = info
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return

        // Skip our own app to avoid infinite loops
        val pkg = event.packageName?.toString() ?: ""
        if (pkg == "com.scalpai.stocksignal") return

        // Only process meaningful events
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED &&
            event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return

        // Scrape text from the screen
        val rootNode = rootInActiveWindow ?: return
        val allText = StringBuilder()
        collectNodeText(rootNode, allText)
        rootNode.recycle()

        val screenText = allText.toString()
        if (screenText.isBlank()) return

        // Extract all potential stock symbols
        val detected = extractSymbols(screenText)
        if (detected.isEmpty()) return

        val now = System.currentTimeMillis()
        detected.forEach { symbol ->
            val lastTime = lastFetchTime[symbol] ?: 0L
            if (now - lastTime > DEBOUNCE_MS) {
                lastFetchTime[symbol] = now
                Log.d(TAG, "Detected symbol on screen: $symbol (from $pkg)")
                triggerSignalFetch(symbol)
            }
        }
    }

    // Recursively collect all text from view tree
    private fun collectNodeText(node: AccessibilityNodeInfo?, sb: StringBuilder, depth: Int = 0) {
        node ?: return
        if (depth > 12) return  // max depth

        val text = node.text?.toString()
        if (!text.isNullOrBlank()) sb.append(" $text ")

        val desc = node.contentDescription?.toString()
        if (!desc.isNullOrBlank()) sb.append(" $desc ")

        for (i in 0 until node.childCount) {
            collectNodeText(node.getChild(i), sb, depth + 1)
        }
    }

    private fun extractSymbols(text: String): Set<String> {
        val found = mutableSetOf<String>()

        for (pattern in SYMBOL_PATTERNS) {
            val matcher = pattern.matcher(text)
            while (matcher.find()) {
                val sym = matcher.group(1) ?: continue
                val upper = sym.uppercase()
                if (upper in IGNORE_WORDS) continue
                if (upper.length < 2 || upper.length > 12) continue
                if (NSE_FNO_STOCKS.containsKey(upper)) {
                    found.add(upper)
                }
            }
        }
        return found
    }

    private fun triggerSignalFetch(symbol: String) {
        val intent = Intent(this, OverlayService::class.java).apply {
            action = OverlayService.ACTION_FETCH_SYMBOL
            putExtra(OverlayService.EXTRA_SYMBOL, symbol)
        }
        startService(intent)
    }

    override fun onInterrupt() {
        isRunning = false
        Log.d(TAG, "Accessibility Service Interrupted")
    }

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
    }
}
