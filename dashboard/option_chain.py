"""
Fetches NSE option chain (Nifty & Sensex/BankNifty) from NSE public API.
No authentication required. Provides OI, IV, LTP per strike.
"""
import time, logging, threading
import requests
from config import NIFTY_STRIKE_STEP, SENSEX_STRIKE_STEP, OC_STRIKES_EACH_SIDE

log = logging.getLogger(__name__)

_NSE_HEADERS = {
    "User-Agent":      "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
    "Accept":          "application/json, text/plain, */*",
    "Accept-Language": "en-US,en;q=0.9",
    "Referer":         "https://www.nseindia.com/",
    "Connection":      "keep-alive",
}
_NSE_BASE  = "https://www.nseindia.com"
_OC_URL    = _NSE_BASE + "/api/option-chain-indices?symbol={symbol}"
_SESS_LOCK = threading.Lock()
_nse_sess  = None
_sess_ts   = 0
SESSION_TTL = 300  # refresh cookies every 5 min

_oc_cache = {}   # symbol -> {ts, data}
_oi_history = {} # symbol -> list of {ts, total_ce_oi, total_pe_oi}
OI_HISTORY_MINUTES = 20  # keep 20 min of snapshots


def _get_session():
    global _nse_sess, _sess_ts
    with _SESS_LOCK:
        now = time.time()
        if _nse_sess is None or (now - _sess_ts) > SESSION_TTL:
            s = requests.Session()
            try:
                s.get(_NSE_BASE, headers=_NSE_HEADERS, timeout=10)
                s.get(_NSE_BASE + "/option-chain", headers=_NSE_HEADERS, timeout=10)
            except Exception as e:
                log.warning(f"NSE session init: {e}")
            _nse_sess = s
            _sess_ts  = now
        return _nse_sess


def _fetch_raw(symbol):
    sess = _get_session()
    url  = _OC_URL.format(symbol=symbol)
    resp = sess.get(url, headers=_NSE_HEADERS, timeout=12)
    resp.raise_for_status()
    return resp.json()


def _round_atm(price, step):
    return round(price / step) * step


def parse_chain(symbol, raw, strike_step):
    """Parse NSE option chain JSON into a list of strike rows."""
    records   = raw.get("records", {})
    data      = records.get("data", [])
    underlying = records.get("underlyingValue", 0)
    expiries  = records.get("expiryDates", [])
    expiry    = expiries[0] if expiries else None

    atm = _round_atm(underlying, strike_step)
    valid_strikes = set(
        atm + i * strike_step
        for i in range(-OC_STRIKES_EACH_SIDE, OC_STRIKES_EACH_SIDE + 1)
    )

    rows = {}
    for entry in data:
        strike = entry.get("strikePrice")
        exp    = entry.get("expiryDate")
        if exp != expiry or strike not in valid_strikes:
            continue

        ce = entry.get("CE", {})
        pe = entry.get("PE", {})
        rows[strike] = {
            "strike":    strike,
            "atm":       strike == atm,
            "ce_oi":     ce.get("openInterest", 0),
            "ce_coi":    ce.get("changeinOpenInterest", 0),
            "ce_vol":    ce.get("totalTradedVolume", 0),
            "ce_iv":     ce.get("impliedVolatility", 0),
            "ce_ltp":    ce.get("lastPrice", 0),
            "pe_oi":     pe.get("openInterest", 0),
            "pe_coi":    pe.get("changeinOpenInterest", 0),
            "pe_vol":    pe.get("totalTradedVolume", 0),
            "pe_iv":     pe.get("impliedVolatility", 0),
            "pe_ltp":    pe.get("lastPrice", 0),
        }

    sorted_rows    = [rows[s] for s in sorted(rows.keys())]
    total_ce_oi    = sum(r["ce_oi"] for r in sorted_rows)
    total_pe_oi    = sum(r["pe_oi"] for r in sorted_rows)
    pcr            = round(total_pe_oi / total_ce_oi, 2) if total_ce_oi else 0
    max_pain       = _calc_max_pain(rows)

    return {
        "symbol":       symbol,
        "underlying":   underlying,
        "atm":          atm,
        "expiry":       expiry,
        "strikes":      sorted_rows,
        "total_ce_oi":  total_ce_oi,
        "total_pe_oi":  total_pe_oi,
        "pcr":          pcr,
        "max_pain":     max_pain,
    }


def _calc_max_pain(rows):
    """Strike where total option buyers lose the most money."""
    if not rows:
        return 0
    strikes = sorted(rows.keys())
    min_loss, mp = float("inf"), strikes[0]
    for candidate in strikes:
        loss = sum(
            max(0, candidate - s) * rows[s]["ce_oi"] +
            max(0, s - candidate) * rows[s]["pe_oi"]
            for s in strikes
        )
        if loss < min_loss:
            min_loss, mp = loss, candidate
    return mp


def get_option_chain(symbol="NIFTY", force=False):
    """Returns parsed option chain, cached for 30s."""
    step = NIFTY_STRIKE_STEP if symbol == "NIFTY" else SENSEX_STRIKE_STEP
    now  = time.time()
    cached = _oc_cache.get(symbol)
    if not force and cached and (now - cached["ts"]) < 30:
        return cached["data"]
    try:
        raw    = _fetch_raw(symbol)
        parsed = parse_chain(symbol, raw, step)
        _oc_cache[symbol] = {"ts": now, "data": parsed}
        _record_oi_snapshot(symbol, parsed["total_ce_oi"], parsed["total_pe_oi"])
        return parsed
    except Exception as e:
        log.error(f"OC fetch {symbol}: {e}")
        return cached["data"] if cached else {}


def _record_oi_snapshot(symbol, ce_oi, pe_oi):
    hist = _oi_history.setdefault(symbol, [])
    hist.append({"ts": time.time(), "ce": ce_oi, "pe": pe_oi})
    cutoff = time.time() - OI_HISTORY_MINUTES * 60
    _oi_history[symbol] = [h for h in hist if h["ts"] >= cutoff]


def get_oi_direction(symbol="NIFTY", minutes=15):
    """
    Returns OI direction signal for last `minutes` minutes.
    Positive net = bullish (PE OI building), negative = bearish (CE OI building).
    """
    hist = _oi_history.get(symbol, [])
    if len(hist) < 2:
        return {"signal": "neutral", "ce_chg": 0, "pe_chg": 0, "net": 0, "pct": 0}

    now     = time.time()
    cutoff  = now - minutes * 60
    past    = [h for h in hist if h["ts"] <= cutoff]
    ref     = past[-1] if past else hist[0]
    current = hist[-1]

    ce_chg = current["ce"] - ref["ce"]
    pe_chg = current["pe"] - ref["pe"]
    net    = pe_chg - ce_chg   # positive = bullish pressure

    total  = abs(ce_chg) + abs(pe_chg) or 1
    pct    = round((net / total) * 100, 1)

    if pct > 10:
        signal = "bullish"
    elif pct < -10:
        signal = "bearish"
    else:
        signal = "neutral"

    return {
        "signal": signal,
        "ce_chg": ce_chg,
        "pe_chg": pe_chg,
        "net":    net,
        "pct":    pct,
        "minutes": minutes,
    }
