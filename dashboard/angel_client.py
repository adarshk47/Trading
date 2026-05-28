import pyotp
import time
import threading
import logging
from SmartApi import SmartConnect
from config import ANGEL, INDIAN_WATCHLIST, INDICES, FUTURES

logger = logging.getLogger(__name__)

_lock = threading.Lock()
_session = {"obj": None, "auth_token": None, "expires_at": 0}
_market_data = {}  # token -> quote dict


def _login():
    obj = SmartConnect(api_key=ANGEL["api_key"])
    totp = pyotp.TOTP(ANGEL["totp_secret"]).now()
    data = obj.generateSession(ANGEL["client_id"], ANGEL["password"], totp)
    if data["status"] is False:
        raise RuntimeError(f"Angel One login failed: {data['message']}")
    return obj, data["data"]["jwtToken"]


def get_session():
    with _lock:
        now = time.time()
        if _session["obj"] is None or now >= _session["expires_at"]:
            obj, jwt = _login()
            _session["obj"] = obj
            _session["auth_token"] = jwt
            _session["expires_at"] = now + 3600  # re-login every hour
        return _session["obj"]


def fetch_quotes(retries=2):
    obj = get_session()
    all_instruments = []
    for sym, token, exch in (INDICES + FUTURES + INDIAN_WATCHLIST):
        all_instruments.append({"exchange": exch, "symboltoken": token})

    for attempt in range(retries + 1):
        try:
            resp = obj.getMarketData("FULL", all_instruments)
            if resp and resp.get("status"):
                fetched = resp["data"].get("fetched", [])
                result = {}
                all_items = INDICES + FUTURES + INDIAN_WATCHLIST
                for i, item in enumerate(fetched):
                    if i < len(all_items):
                        sym, token, exch = all_items[i]
                        result[token] = {
                            "symbol":  sym,
                            "ltp":     item.get("ltp", 0),
                            "open":    item.get("open", 0),
                            "high":    item.get("high", 0),
                            "low":     item.get("low", 0),
                            "close":   item.get("close", 0),
                            "change":  round(item.get("ltp", 0) - item.get("close", 0), 2),
                            "pct":     round(
                                ((item.get("ltp", 0) - item.get("close", 0)) / item.get("close", 1)) * 100, 2
                            ),
                            "volume":  item.get("tradeVolume", 0),
                            "exchange": exch,
                        }
                return result
        except Exception as e:
            logger.warning(f"fetch_quotes attempt {attempt+1} failed: {e}")
            if attempt < retries:
                time.sleep(1)
            else:
                raise
    return {}


def get_cached_data():
    return dict(_market_data)


def _refresh_loop(interval=5):
    global _market_data
    while True:
        try:
            data = fetch_quotes()
            _market_data = data
        except Exception as e:
            logger.error(f"Market data refresh error: {e}")
        time.sleep(interval)


def start_background_refresh(interval=5):
    t = threading.Thread(target=_refresh_loop, args=(interval,), daemon=True)
    t.start()
    logger.info(f"Background refresh started (every {interval}s)")
