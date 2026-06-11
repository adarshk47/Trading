"""
Option chain fetcher:
- NIFTY / BANKNIFTY: NSE public API
- SENSEX: BSE public API
No authentication required.
"""
import time, logging, threading
import requests
from config import NIFTY_STRIKE_STEP, SENSEX_STRIKE_STEP, OC_STRIKES_EACH_SIDE

log = logging.getLogger(__name__)

# ---- NSE session ----
_NSE_HEADERS = {
    "User-Agent":      "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
    "Accept":          "application/json, text/plain, */*",
    "Accept-Language": "en-US,en;q=0.9",
    "Referer":         "https://www.nseindia.com/",
}
_BSE_HEADERS = {
    "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
    "Accept":     "application/json, text/plain, */*",
    "Referer":    "https://www.bseindia.com/",
}

_sess_store = {}  # "NSE"/"BSE" -> {sess, ts}
_SESS_LOCK  = threading.Lock()
SESSION_TTL = 300

_oc_cache   = {}
_oi_history = {}
OI_HISTORY_MINUTES = 20


def _get_session(market="NSE"):
    with _SESS_LOCK:
        now   = time.time()
        entry = _sess_store.get(market, {})
        if entry.get("sess") and now - entry.get("ts", 0) < SESSION_TTL:
            return entry["sess"]
        s = requests.Session()
        try:
            if market == "NSE":
                s.get("https://www.nseindia.com",             headers=_NSE_HEADERS, timeout=10)
                s.get("https://www.nseindia.com/option-chain", headers=_NSE_HEADERS, timeout=10)
            else:
                s.get("https://www.bseindia.com", headers=_BSE_HEADERS, timeout=10)
        except Exception:
            pass
        _sess_store[market] = {"sess": s, "ts": now}
        return s


def _round_atm(price, step):
    return round(price / step) * step


# ============================================================
# NSE option chain (NIFTY, BANKNIFTY)
# ============================================================
def _fetch_nse_chain(symbol):
    sess = _get_session("NSE")
    url  = f"https://www.nseindia.com/api/option-chain-indices?symbol={symbol}"
    r    = sess.get(url, headers=_NSE_HEADERS, timeout=12)
    if r.status_code == 403:
        _sess_store.pop("NSE", None)
        sess = _get_session("NSE")
        r    = sess.get(url, headers=_NSE_HEADERS, timeout=12)
    r.raise_for_status()
    return r.json()


def _parse_nse_chain(symbol, raw, strike_step):
    records    = raw.get("records", {})
    data       = records.get("data", [])
    underlying = records.get("underlyingValue", 0)
    expiries   = records.get("expiryDates", [])
    expiry     = expiries[0] if expiries else None

    atm = _round_atm(underlying, strike_step)
    valid = set(atm + i * strike_step for i in range(-OC_STRIKES_EACH_SIDE, OC_STRIKES_EACH_SIDE + 1))

    rows = {}
    for entry in data:
        strike = entry.get("strikePrice")
        if entry.get("expiryDate") != expiry or strike not in valid:
            continue
        ce, pe = entry.get("CE", {}), entry.get("PE", {})
        rows[strike] = {
            "strike": strike, "atm": strike == atm,
            "ce_oi": ce.get("openInterest", 0), "ce_coi": ce.get("changeinOpenInterest", 0),
            "ce_vol": ce.get("totalTradedVolume", 0), "ce_iv": ce.get("impliedVolatility", 0),
            "ce_ltp": ce.get("lastPrice", 0),
            "pe_oi": pe.get("openInterest", 0), "pe_coi": pe.get("changeinOpenInterest", 0),
            "pe_vol": pe.get("totalTradedVolume", 0), "pe_iv": pe.get("impliedVolatility", 0),
            "pe_ltp": pe.get("lastPrice", 0),
        }
    return _finalize_chain(symbol, underlying, atm, expiry, rows)


# ============================================================
# BSE option chain (SENSEX)
# ============================================================
_BSE_SCRIP = "16"   # BSE SENSEX scripcode for option chain

def _fetch_bse_chain():
    """BSE public option chain API for SENSEX."""
    sess = _get_session("BSE")
    # Get nearest expiry list first
    exp_url = f"https://api.bseindia.com/BseIndiaAPI/api/OptionChain/w?scripcode={_BSE_SCRIP}&expirydate=&optiontype=&strikeprice="
    r = sess.get(exp_url, headers=_BSE_HEADERS, timeout=12)
    if r.status_code in (403, 401):
        _sess_store.pop("BSE", None)
        sess = _get_session("BSE")
        r    = sess.get(exp_url, headers=_BSE_HEADERS, timeout=12)
    r.raise_for_status()
    return r.json()


def _parse_bse_chain(raw):
    """Parse BSE option chain JSON for SENSEX."""
    table    = raw.get("Table", [])
    table2   = raw.get("Table1", [])   # may contain spot price

    # Get underlying from Table1 or compute from ATM
    underlying = 0
    if table2:
        underlying = float(table2[0].get("SpotPrice", 0) or 0)

    if not underlying and table:
        # derive from first entry
        underlying = float(table[0].get("SpotPrice", 0) or 0)

    expiries = sorted(set(e.get("ExpiryDate", "") for e in table if e.get("ExpiryDate")))
    expiry   = expiries[0] if expiries else None

    atm = _round_atm(underlying, SENSEX_STRIKE_STEP)
    valid = set(atm + i * SENSEX_STRIKE_STEP for i in range(-OC_STRIKES_EACH_SIDE, OC_STRIKES_EACH_SIDE + 1))

    rows = {}
    for entry in table:
        if entry.get("ExpiryDate") != expiry:
            continue
        strike = int(float(entry.get("StrikePrice", 0) or 0))
        if strike not in valid:
            continue
        opt = entry.get("OptionType", "").upper()
        if strike not in rows:
            rows[strike] = {
                "strike": strike, "atm": strike == atm,
                "ce_oi": 0, "ce_coi": 0, "ce_vol": 0, "ce_iv": 0, "ce_ltp": 0,
                "pe_oi": 0, "pe_coi": 0, "pe_vol": 0, "pe_iv": 0, "pe_ltp": 0,
            }
        oi   = int(float(entry.get("OpenInterest", 0) or 0))
        coi  = int(float(entry.get("ChangeInOI",   0) or 0))
        vol  = int(float(entry.get("TradedQty",    0) or 0))
        iv   = float(entry.get("IV", 0) or 0)
        ltp  = float(entry.get("LTP", 0) or 0)
        if opt == "CE":
            rows[strike].update(ce_oi=oi, ce_coi=coi, ce_vol=vol, ce_iv=iv, ce_ltp=ltp)
        elif opt == "PE":
            rows[strike].update(pe_oi=oi, pe_coi=coi, pe_vol=vol, pe_iv=iv, pe_ltp=ltp)

    return _finalize_chain("SENSEX", underlying, atm, expiry, rows)


# ============================================================
# Common finalization
# ============================================================
def _finalize_chain(symbol, underlying, atm, expiry, rows):
    sorted_rows   = [rows[s] for s in sorted(rows.keys())]
    total_ce_oi   = sum(r["ce_oi"] for r in sorted_rows)
    total_pe_oi   = sum(r["pe_oi"] for r in sorted_rows)
    pcr           = round(total_pe_oi / total_ce_oi, 2) if total_ce_oi else 0
    max_pain      = _calc_max_pain(rows)

    # CE Resistance = strike with max CE OI (above ATM)
    ce_candidates = [(r["ce_oi"], r["strike"]) for r in sorted_rows if r["strike"] >= atm]
    ce_resistance = max(ce_candidates)[1] if ce_candidates else 0

    # PE Support = strike with max PE OI (below ATM)
    pe_candidates = [(r["pe_oi"], r["strike"]) for r in sorted_rows if r["strike"] <= atm]
    pe_support    = max(pe_candidates)[1] if pe_candidates else 0

    return {
        "symbol":        symbol,
        "underlying":    underlying,
        "atm":           atm,
        "expiry":        expiry,
        "strikes":       sorted_rows,
        "total_ce_oi":   total_ce_oi,
        "total_pe_oi":   total_pe_oi,
        "pcr":           pcr,
        "max_pain":      max_pain,
        "ce_resistance": ce_resistance,
        "pe_support":    pe_support,
    }


def _calc_max_pain(rows):
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


# ============================================================
# Public API
# ============================================================
def get_option_chain(symbol="NIFTY", force=False):
    step   = NIFTY_STRIKE_STEP if symbol != "SENSEX" else SENSEX_STRIKE_STEP
    now    = time.time()
    cached = _oc_cache.get(symbol)
    if not force and cached and (now - cached["ts"]) < 30:
        return cached["data"]
    try:
        if symbol == "SENSEX":
            raw    = _fetch_bse_chain()
            parsed = _parse_bse_chain(raw)
        else:
            raw    = _fetch_nse_chain(symbol)
            parsed = _parse_nse_chain(symbol, raw, step)

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
    hist = _oi_history.get(symbol, [])
    if len(hist) < 2:
        return {"signal": "neutral", "ce_chg": 0, "pe_chg": 0, "net": 0, "pct": 0}
    now    = time.time()
    past   = [h for h in hist if h["ts"] <= now - minutes * 60]
    ref    = past[-1] if past else hist[0]
    cur    = hist[-1]
    ce_chg = cur["ce"] - ref["ce"]
    pe_chg = cur["pe"] - ref["pe"]
    net    = pe_chg - ce_chg
    total  = abs(ce_chg) + abs(pe_chg) or 1
    pct    = round((net / total) * 100, 1)
    signal = "bullish" if pct > 10 else "bearish" if pct < -10 else "neutral"
    return {"signal": signal, "ce_chg": ce_chg, "pe_chg": pe_chg, "net": net, "pct": pct, "minutes": minutes}
