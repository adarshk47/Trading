import pyotp, time, threading, logging, requests
from SmartApi import SmartConnect
from config import ANGEL, INDIAN_WATCHLIST, INDICES, FUTURES

log = logging.getLogger(__name__)

_lock    = threading.Lock()
_session = {"obj": None, "expires_at": 0}
_market_data = {}

# NSE All-Indices endpoint gives Sensex too (no auth needed)
_NSE_HEADERS = {
    "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64)",
    "Referer":    "https://www.nseindia.com/",
    "Accept":     "application/json",
}
_nse_index_cache = {"ts": 0, "data": {}}


def _login():
    obj  = SmartConnect(api_key=ANGEL["api_key"])
    totp = pyotp.TOTP(ANGEL["totp_secret"]).now()
    data = obj.generateSession(ANGEL["client_id"], ANGEL["password"], totp)
    if not data.get("status"):
        raise RuntimeError(f"Login failed: {data.get('message')}")
    return obj


def get_session():
    with _lock:
        now = time.time()
        if _session["obj"] is None or now >= _session["expires_at"]:
            _session["obj"]        = _login()
            _session["expires_at"] = now + 3600
        return _session["obj"]


def _quote_row(item, sym, token, exch):
    ltp   = item.get("ltp", 0) or 0
    close = item.get("close", 0) or 1
    return {
        "symbol":   sym,
        "ltp":      ltp,
        "open":     item.get("open", 0),
        "high":     item.get("high", 0),
        "low":      item.get("low", 0),
        "close":    close,
        "change":   round(ltp - close, 2),
        "pct":      round(((ltp - close) / close) * 100, 2),
        "volume":   item.get("tradeVolume", 0),
        "exchange": exch,
    }


# ---- NSE public index data (Sensex included) ----
_nse_session = {"sess": None, "ts": 0}

def _get_nse_session():
    now = time.time()
    if _nse_session["sess"] and now - _nse_session["ts"] < 300:
        return _nse_session["sess"]
    sess = requests.Session()
    try:
        sess.get("https://www.nseindia.com", headers=_NSE_HEADERS, timeout=10)
        sess.get("https://www.nseindia.com/market-data/live-equity-market", headers=_NSE_HEADERS, timeout=10)
    except Exception:
        pass
    _nse_session["sess"] = sess
    _nse_session["ts"]   = now
    return sess


def _fetch_nse_indices():
    """Fetch all NSE + BSE index values from NSE's public API. Cached 15s."""
    now = time.time()
    if now - _nse_index_cache["ts"] < 15:
        return _nse_index_cache["data"]
    try:
        sess = _get_nse_session()
        r = sess.get("https://www.nseindia.com/api/allIndices", headers=_NSE_HEADERS, timeout=10)
        if r.status_code == 403:
            # Session expired, refresh and retry once
            _nse_session["ts"] = 0
            sess = _get_nse_session()
            r = sess.get("https://www.nseindia.com/api/allIndices", headers=_NSE_HEADERS, timeout=10)
        r.raise_for_status()
        parsed = {}
        for entry in r.json().get("data", []):
            name = entry.get("index", "").upper()
            parsed[name] = {
                "ltp":    entry.get("last", 0),
                "open":   entry.get("open", 0),
                "high":   entry.get("high", 0),
                "low":    entry.get("low", 0),
                "close":  entry.get("previousClose", 0) or entry.get("last", 1),
                "change": entry.get("change", 0),
                "pct":    entry.get("percentChange", 0),
                "volume": 0,
            }
        _nse_index_cache["data"] = parsed
        _nse_index_cache["ts"]   = now
        return parsed
    except Exception as e:
        log.warning(f"NSE allIndices fetch failed: {e}")
        return _nse_index_cache.get("data", {})


# NSE index name -> our symbol name mapping
_NSE_INDEX_MAP = {
    "S&P BSE SENSEX":    "SENSEX",
    "NIFTY 50":          "NIFTY 50",
    "NIFTY BANK":        "BANK NIFTY",
    "NIFTY IT":          "NIFTY IT",
    "NIFTY MIDCAP 100":  "NIFTY MIDCAP",
    "NIFTY MIDCAP 50":   "NIFTY MIDCAP",
}


def fetch_quotes(retries=2):
    """
    Fetch all price quotes.
    - Indices: from NSE allIndices API (more reliable, includes Sensex)
    - Futures + Stocks: from Angel One SmartAPI
    """
    result = {}

    # 1. Get indices from NSE public API
    nse_data = _fetch_nse_indices()
    for nse_name, our_sym in _NSE_INDEX_MAP.items():
        if nse_name in nse_data and our_sym not in {r["symbol"] for r in result.values()}:
            # find matching token from INDICES config
            token = next((tok for sym, tok, _ in INDICES if sym == our_sym), our_sym)
            q = nse_data[nse_name]
            cl = q["close"] or 1
            result[token] = {
                "symbol":   our_sym,
                "ltp":      q["ltp"],
                "open":     q["open"],
                "high":     q["high"],
                "low":      q["low"],
                "close":    cl,
                "change":   round(q["ltp"] - cl, 2),
                "pct":      round(q["pct"], 2),
                "volume":   0,
                "exchange": "NSE",
            }

    # 1b. If NSE API didn't give us SENSEX, try Angel One BSE token "1"
    if "SENSEX" not in {r["symbol"] for r in result.values()}:
        try:
            obj = get_session()
            r2  = obj.getMarketData("FULL", [{"exchange": "BSE", "symboltoken": "1"}])
            if r2 and r2.get("status"):
                items = r2["data"].get("fetched", [])
                if items and items[0].get("ltp", 0) > 50000:  # sanity: Sensex > 50k
                    q = items[0]
                    result["SENSEX"] = _quote_row(q, "SENSEX", "SENSEX", "BSE")
        except Exception as e:
            log.warning(f"SENSEX BSE fallback failed: {e}")

    # 2. Futures + Stocks from Angel One
    angel_refs = FUTURES + INDIAN_WATCHLIST
    instruments = [{"exchange": e, "symboltoken": t} for _, t, e in angel_refs]

    for attempt in range(retries + 1):
        try:
            obj  = get_session()
            resp = obj.getMarketData("FULL", instruments)
            if resp and resp.get("status"):
                fetched = resp["data"].get("fetched", [])
                # Angel One returns items in same order as requested
                for i, item in enumerate(fetched):
                    if i < len(angel_refs):
                        sym, tok, exch = angel_refs[i]
                        # sanity check: ltp should be > 0
                        ltp = item.get("ltp", 0) or 0
                        if ltp > 0:
                            result[tok] = _quote_row(item, sym, tok, exch)
                break
        except Exception as e:
            log.warning(f"Angel fetch attempt {attempt+1}: {e}")
            if attempt < retries:
                time.sleep(1)

    return result


def get_cached_data():
    return dict(_market_data)


def _refresh_loop(interval):
    global _market_data
    while True:
        try:
            data = fetch_quotes()
            if data:
                _market_data = data
        except Exception as e:
            log.error(f"Refresh error: {e}")
        time.sleep(interval)


def start_background_refresh(interval=5):
    t = threading.Thread(target=_refresh_loop, args=(interval,), daemon=True)
    t.start()
    log.info(f"Price refresh started ({interval}s)")
