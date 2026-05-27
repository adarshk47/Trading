package com.scalpai.stocksignal

data class StockSignal(
    val symbol: String,
    val price: Double,
    val change: Double,
    val changePercent: Double,
    val volume: Long,
    val avgVolume: Long,
    val direction: String,   // CALL | PUT | WAIT
    val confidence: Int,     // 0-100
    val score: Int,          // raw 0-100
    val strength: String,    // STRONG | GOOD | MODERATE | WEAK
    val factors: List<SignalFactor>,
    val volSurge: Double,    // volume as % of average
    val suggestedStrike: String,
    val stopLoss: String,
    val target: String
)

data class SignalFactor(
    val text: String,
    val type: FactorType  // BULL, BEAR, NEUTRAL, WARN
)

enum class FactorType { BULL, BEAR, NEUTRAL, WARN }

// Known F&O stocks with their average daily volumes
val NSE_FNO_STOCKS: Map<String, Long> = mapOf(
    "SBIN"        to 16_700_000L,
    "ICICIBANK"   to 14_000_000L,
    "HDFCBANK"    to 12_000_000L,
    "TATAMOTORS"  to 22_000_000L,
    "ITC"         to 20_000_000L,
    "NTPC"        to 18_000_000L,
    "ONGC"        to 14_000_000L,
    "TATASTEEL"   to 25_000_000L,
    "AXISBANK"    to 10_000_000L,
    "WIPRO"       to  9_000_000L,
    "BPCL"        to  9_000_000L,
    "INFY"        to  8_000_000L,
    "HINDALCO"    to 10_000_000L,
    "COALINDIA"   to  8_000_000L,
    "JSWSTEEL"    to  8_000_000L,
    "BHARTIARTL"  to  6_000_000L,
    "RELIANCE"    to  6_500_000L,
    "ADANIPORTS"  to  5_000_000L,
    "CIPLA"       to  4_000_000L,
    "KOTAKBANK"   to  4_500_000L,
    "POWERGRID"   to 12_000_000L,
    "LT"          to  4_000_000L,
    "SUNPHARMA"   to  5_000_000L,
    "INDUSINDBK"  to  5_000_000L,
    "TCS"         to  3_500_000L,
    "ADANIENT"    to  3_000_000L,
    "HINDUNILVR"  to  3_000_000L,
    "BAJFINANCE"  to  2_000_000L,
    "DRREDDY"     to  2_000_000L,
    "MARUTI"      to  1_500_000L,
    "NIFTY"       to  0L,
    "BANKNIFTY"   to  0L
)

object SignalEngine {

    fun isKnownStock(symbol: String): Boolean =
        NSE_FNO_STOCKS.containsKey(symbol.uppercase())

    fun getAvgVolume(symbol: String): Long =
        NSE_FNO_STOCKS[symbol.uppercase()] ?: 5_000_000L

    fun calculate(
        symbol: String,
        price: Double,
        open: Double,
        high: Double,
        low: Double,
        volume: Long,
        avgVolume: Long,
        changePercent: Double,
        change: Double,
        ma50: Double = 0.0
    ): StockSignal {
        var score = 50
        val factors = mutableListOf<SignalFactor>()

        fun add(pts: Int, text: String, type: FactorType) {
            score += pts
            factors.add(SignalFactor(text, type))
        }

        // ── Factor 1: Price vs Open ──────────────────────────────────────
        val openDiff = if (open > 0) ((price - open) / open) * 100.0 else 0.0
        when {
            openDiff > 0.4  -> add(+14, "Above Open +${"%.1f".format(openDiff)}%", FactorType.BULL)
            openDiff < -0.4 -> add(-14, "Below Open ${"%.1f".format(openDiff)}%",  FactorType.BEAR)
            else            -> add(  0, "Near Open (${"%.1f".format(openDiff)}%)",  FactorType.NEUTRAL)
        }

        // ── Factor 2: Day change % ───────────────────────────────────────
        when {
            changePercent > 0.8  -> add(+13, "Day +${"%.2f".format(changePercent)}% ▲", FactorType.BULL)
            changePercent < -0.8 -> add(-13, "Day ${"%.2f".format(changePercent)}% ▼",  FactorType.BEAR)
            changePercent > 0.3  -> add( +7, "Mild Up ${"%.2f".format(changePercent)}%", FactorType.BULL)
            changePercent < -0.3 -> add( -7, "Mild Down ${"%.2f".format(changePercent)}%", FactorType.BEAR)
            else                 -> add(  0, "Day Flat (${"%.2f".format(changePercent)}%)", FactorType.NEUTRAL)
        }

        // ── Factor 3: Position in day range ─────────────────────────────
        val range = high - low
        val pos = if (range > 0) ((price - low) / range) * 100.0 else 50.0
        when {
            pos > 72 -> add(+10, "Holding Day Highs (${"%.0f".format(pos)}%)", FactorType.BULL)
            pos < 28 -> add(-10, "Holding Day Lows (${"%.0f".format(pos)}%)",  FactorType.BEAR)
            pos > 55 -> add( +5, "Upper Half Range (${"%.0f".format(pos)}%)",  FactorType.BULL)
            pos < 45 -> add( -5, "Lower Half Range (${"%.0f".format(pos)}%)",  FactorType.BEAR)
            else     -> add(  0, "Mid Range (${"%.0f".format(pos)}%)",          FactorType.NEUTRAL)
        }

        // ── Factor 4: Volume vs Average ──────────────────────────────────
        val volRatio = if (avgVolume > 0) (volume.toDouble() / avgVolume) * 100.0 else 100.0
        when {
            volRatio > 180 && changePercent > 0 -> add(+14, "🔥 Huge Volume Buy ${"%.0f".format(volRatio)}%", FactorType.BULL)
            volRatio > 180 && changePercent < 0 -> add(-14, "🔥 Huge Volume Sell ${"%.0f".format(volRatio)}%", FactorType.BEAR)
            volRatio > 130 && changePercent > 0 -> add( +9, "High Volume Bullish ${"%.0f".format(volRatio)}%", FactorType.BULL)
            volRatio > 130 && changePercent < 0 -> add( -9, "High Volume Bearish ${"%.0f".format(volRatio)}%", FactorType.BEAR)
            volRatio <  50                      -> add(  0, "⚠ Low Volume — unreliable", FactorType.WARN)
            else                                -> add(  0, "Normal Volume ${"%.0f".format(volRatio)}%", FactorType.NEUTRAL)
        }

        // ── Factor 5: Intraday volatility ────────────────────────────────
        val intradayRange = if (low > 0) ((high - low) / low) * 100.0 else 0.0
        when {
            intradayRange > 2.5 -> add( +5, "High Intraday Range ${"%.1f".format(intradayRange)}%", FactorType.BULL)
            intradayRange > 1.2 -> add( +3, "Good Intraday Range ${"%.1f".format(intradayRange)}%", FactorType.NEUTRAL)
            intradayRange < 0.5 -> add(  0, "⚠ Very Narrow Range — low premium", FactorType.WARN)
            else                -> add(  0, "Normal Range ${"%.1f".format(intradayRange)}%", FactorType.NEUTRAL)
        }

        // ── Factor 6: 50 DMA ────────────────────────────────────────────
        if (ma50 > 0) {
            when {
                price > ma50 * 1.01 -> add(+5, "Above 50 DMA ₹${"%.0f".format(ma50)}", FactorType.BULL)
                price < ma50 * 0.99 -> add(-5, "Below 50 DMA ₹${"%.0f".format(ma50)}", FactorType.BEAR)
                else                -> add( 0, "Near 50 DMA ₹${"%.0f".format(ma50)}", FactorType.NEUTRAL)
            }
        }

        score = score.coerceIn(0, 100)

        // ── Determine direction & confidence ────────────────────────────
        val direction = when {
            score >= 65 -> "CALL"
            score <= 35 -> "PUT"
            else        -> "WAIT"
        }
        val confidence = when (direction) {
            "CALL" -> score
            "PUT"  -> 100 - score
            else   -> 50
        }
        val strength = when {
            confidence >= 82 -> "STRONG"
            confidence >= 70 -> "GOOD"
            confidence >= 58 -> "MODERATE"
            else             -> "WEAK"
        }

        // ── Suggested strike & levels ────────────────────────────────────
        val roundedPrice = (price / 10).toLong() * 10
        val suggestedStrike = when (direction) {
            "CALL" -> "₹${roundedPrice + 10} CE (ATM+1)"
            "PUT"  -> "₹${roundedPrice}     PE (ATM)"
            else   -> "Wait for signal"
        }
        val slPct = 0.008
        val tgtPct = if (confidence >= 75) 0.018 else 0.012
        val stopLoss = when (direction) {
            "CALL" -> "SL: ₹${"%.2f".format(price * (1 - slPct))} (${(slPct * 100).toInt()}% below)"
            "PUT"  -> "SL: ₹${"%.2f".format(price * (1 + slPct))} (${(slPct * 100).toInt()}% above)"
            else   -> "—"
        }
        val target = when (direction) {
            "CALL" -> "TGT: ₹${"%.2f".format(price * (1 + tgtPct))} (+${"%.1f".format(tgtPct * 100)}%)"
            "PUT"  -> "TGT: ₹${"%.2f".format(price * (1 - tgtPct))} (-${"%.1f".format(tgtPct * 100)}%)"
            else   -> "—"
        }

        return StockSignal(
            symbol         = symbol.uppercase(),
            price          = price,
            change         = change,
            changePercent  = changePercent,
            volume         = volume,
            avgVolume      = avgVolume,
            direction      = direction,
            confidence     = confidence,
            score          = score,
            strength       = strength,
            factors        = factors,
            volSurge       = volRatio,
            suggestedStrike = suggestedStrike,
            stopLoss       = stopLoss,
            target         = target
        )
    }
}
