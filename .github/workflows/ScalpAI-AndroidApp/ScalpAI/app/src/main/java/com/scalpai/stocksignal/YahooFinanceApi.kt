package com.scalpai.stocksignal

import okhttp3.*
import org.json.JSONObject
import java.io.IOException

data class QuoteData(
    val symbol: String,
    val price: Double,
    val open: Double,
    val high: Double,
    val low: Double,
    val volume: Long,
    val changePercent: Double,
    val change: Double,
    val ma50: Double,
    val marketState: String
)

object YahooFinanceApi {

    private val client = OkHttpClient.Builder()
        .connectTimeout(8, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(8, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    // Convert NSE symbol → Yahoo Finance symbol
    fun toYahooSymbol(nse: String): String {
        val upper = nse.uppercase().trim()
        return if (upper.endsWith(".NS")) upper else "$upper.NS"
    }

    fun fetchQuote(
        nseSymbol: String,
        onSuccess: (QuoteData) -> Unit,
        onError: (String) -> Unit
    ) {
        val yahooSym = toYahooSymbol(nseSymbol)
        val url = "https://query1.finance.yahoo.com/v8/finance/quote" +
                "?symbols=$yahooSym&region=IN&lang=en-IN"

        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "Mozilla/5.0")
            .header("Accept", "application/json")
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                onError("Network error: ${e.message}")
            }

            override fun onResponse(call: Call, response: Response) {
                try {
                    val body = response.body?.string() ?: ""
                    val json = JSONObject(body)
                    val results = json
                        .getJSONObject("quoteResponse")
                        .getJSONArray("result")

                    if (results.length() == 0) {
                        onError("Symbol not found: $nseSymbol")
                        return
                    }

                    val q = results.getJSONObject(0)

                    val quote = QuoteData(
                        symbol        = nseSymbol.uppercase(),
                        price         = q.optDouble("regularMarketPrice", 0.0),
                        open          = q.optDouble("regularMarketOpen", 0.0),
                        high          = q.optDouble("regularMarketDayHigh", 0.0),
                        low           = q.optDouble("regularMarketDayLow", 0.0),
                        volume        = q.optLong("regularMarketVolume", 0L),
                        changePercent = q.optDouble("regularMarketChangePercent", 0.0),
                        change        = q.optDouble("regularMarketChange", 0.0),
                        ma50          = q.optDouble("fiftyDayAverage", 0.0),
                        marketState   = q.optString("marketState", "REGULAR")
                    )
                    onSuccess(quote)
                } catch (e: Exception) {
                    onError("Parse error: ${e.message}")
                }
            }
        })
    }

    // Fetch multiple symbols at once for scanner
    fun fetchMultiple(
        nseSymbols: List<String>,
        onSuccess: (List<QuoteData>) -> Unit,
        onError: (String) -> Unit
    ) {
        val yahooSyms = nseSymbols.joinToString(",") { toYahooSymbol(it) }
        val url = "https://query1.finance.yahoo.com/v8/finance/quote" +
                "?symbols=$yahooSyms&region=IN&lang=en-IN"

        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "Mozilla/5.0")
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                onError(e.message ?: "Network error")
            }

            override fun onResponse(call: Call, response: Response) {
                try {
                    val body = response.body?.string() ?: ""
                    val json = JSONObject(body)
                    val results = json
                        .getJSONObject("quoteResponse")
                        .getJSONArray("result")

                    val list = mutableListOf<QuoteData>()
                    for (i in 0 until results.length()) {
                        val q = results.getJSONObject(i)
                        val rawSym = q.optString("symbol", "")
                            .replace(".NS", "").replace(".BO", "")
                        list.add(QuoteData(
                            symbol        = rawSym,
                            price         = q.optDouble("regularMarketPrice", 0.0),
                            open          = q.optDouble("regularMarketOpen", 0.0),
                            high          = q.optDouble("regularMarketDayHigh", 0.0),
                            low           = q.optDouble("regularMarketDayLow", 0.0),
                            volume        = q.optLong("regularMarketVolume", 0L),
                            changePercent = q.optDouble("regularMarketChangePercent", 0.0),
                            change        = q.optDouble("regularMarketChange", 0.0),
                            ma50          = q.optDouble("fiftyDayAverage", 0.0),
                            marketState   = q.optString("marketState", "REGULAR")
                        ))
                    }
                    onSuccess(list)
                } catch (e: Exception) {
                    onError("Parse error: ${e.message}")
                }
            }
        })
    }
}
