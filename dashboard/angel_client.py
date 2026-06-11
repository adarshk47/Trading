import pyotp, time, threading, logging
from SmartApi import SmartConnect
from config import ANGEL, INDIAN_WATCHLIST, INDICES, FUTURES

log = logging.getLogger(__name__)

_lock    = threading.Lock()
_session = {"obj": None, "expires_at": 0}
_market_data = {}


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


def fetch_quotes(retries=2):
    obj  = get_session()
    refs = INDICES + FUTURES + INDIAN_WATCHLIST
    instruments = [{"exchange": e, "symboltoken": t} for _, t, e in refs]

    for attempt in range(retries + 1):
        try:
            resp = obj.getMarketData("FULL", instruments)
            if resp and resp.get("status"):
                result = {}
                for i, item in enumerate(resp["data"].get("fetched", [])):
                    if i < len(refs):
                        sym, tok, exch = refs[i]
                        result[tok] = _quote_row(item, sym, tok, exch)
                return result
        except Exception as e:
            log.warning(f"fetch_quotes attempt {attempt+1}: {e}")
            if attempt < retries:
                time.sleep(1)
    return {}


def get_cached_data():
    return dict(_market_data)


def _refresh_loop(interval):
    global _market_data
    while True:
        try:
            _market_data = fetch_quotes()
        except Exception as e:
            log.error(f"Refresh error: {e}")
        time.sleep(interval)


def start_background_refresh(interval=5):
    t = threading.Thread(target=_refresh_loop, args=(interval,), daemon=True)
    t.start()
    log.info(f"Price refresh started ({interval}s)")
