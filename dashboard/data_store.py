"""
SQLite-based historical data store.
Saves price snapshots every minute and OI snapshots every minute.
All timeframe aggregations (5m/15m/30m/1h etc.) are derived on read.
"""
import sqlite3, time, threading, logging
from contextlib import contextmanager
from config import DB_PATH

log = logging.getLogger(__name__)
_lock = threading.Lock()


def _conn():
    c = sqlite3.connect(DB_PATH, check_same_thread=False)
    c.execute("PRAGMA journal_mode=WAL")
    c.execute("PRAGMA synchronous=NORMAL")
    return c


@contextmanager
def _db():
    with _lock:
        c = _conn()
        try:
            yield c
            c.commit()
        finally:
            c.close()


def setup():
    with _db() as c:
        c.executescript("""
        CREATE TABLE IF NOT EXISTS price_1m (
            ts      INTEGER NOT NULL,
            symbol  TEXT    NOT NULL,
            ltp     REAL,
            open    REAL,
            high    REAL,
            low     REAL,
            close   REAL,
            volume  INTEGER,
            PRIMARY KEY (ts, symbol)
        );
        CREATE TABLE IF NOT EXISTS oi_1m (
            ts         INTEGER NOT NULL,
            symbol     TEXT    NOT NULL,
            strike     INTEGER NOT NULL,
            ce_oi      INTEGER,
            pe_oi      INTEGER,
            ce_coi     INTEGER,
            pe_coi     INTEGER,
            ce_ltp     REAL,
            pe_ltp     REAL,
            PRIMARY KEY (ts, symbol, strike)
        );
        CREATE INDEX IF NOT EXISTS idx_p_sym ON price_1m(symbol, ts);
        CREATE INDEX IF NOT EXISTS idx_o_sym ON oi_1m(symbol, ts);
        """)
    log.info(f"DB ready: {DB_PATH}")


def save_prices(market_data: dict):
    """market_data: token -> quote dict from angel_client"""
    ts   = int(time.time() // 60) * 60   # floor to minute
    rows = [
        (ts, q["symbol"], q["ltp"], q["open"], q["high"], q["low"], q["close"], q.get("volume", 0))
        for q in market_data.values()
    ]
    if not rows:
        return
    with _db() as c:
        c.executemany(
            "INSERT OR REPLACE INTO price_1m VALUES (?,?,?,?,?,?,?,?)", rows
        )


def save_oi(symbol: str, strikes: list):
    """strikes: list of strike dicts from option_chain.parse_chain"""
    ts   = int(time.time() // 60) * 60
    rows = [
        (ts, symbol, s["strike"],
         s["ce_oi"], s["pe_oi"], s["ce_coi"], s["pe_coi"],
         s["ce_ltp"], s["pe_ltp"])
        for s in strikes
    ]
    if not rows:
        return
    with _db() as c:
        c.executemany(
            "INSERT OR REPLACE INTO oi_1m VALUES (?,?,?,?,?,?,?,?,?)", rows
        )


# ---- Read helpers ----

_INTERVALS = {
    "1m":  1,   "5m":  5,   "10m": 10,  "15m": 15,  "30m": 30,
    "1h":  60,  "2h":  120, "3h":  180, "4h":  240, "5h":  300, "6h": 360,
}


def get_price_history(symbol: str, interval: str = "5m", limit: int = 120):
    """
    Returns OHLCV bars for given interval by aggregating 1m candles.
    Returns list of {ts, open, high, low, close, volume}.
    """
    minutes = _INTERVALS.get(interval, 5)
    since   = int(time.time()) - minutes * limit * 60

    with _lock:
        c = _conn()
        try:
            rows = c.execute(
                "SELECT ts, ltp, open, high, low, close, volume FROM price_1m "
                "WHERE symbol=? AND ts>=? ORDER BY ts",
                (symbol, since)
            ).fetchall()
        finally:
            c.close()

    if not rows:
        return []

    # Aggregate into bars
    bars, buf = [], []
    bar_start = None
    for (ts, ltp, o, h, l, cl, vol) in rows:
        bucket = (ts // (minutes * 60)) * (minutes * 60)
        if bar_start is None:
            bar_start = bucket
        if bucket != bar_start:
            if buf:
                bars.append(_agg(buf, bar_start))
                buf = []
            bar_start = bucket
        buf.append((ts, ltp, o, h, l, cl, vol))
    if buf:
        bars.append(_agg(buf, bar_start))
    return bars[-limit:]


def _agg(rows, ts):
    opens  = [r[2] or r[1] for r in rows]
    highs  = [r[3] or r[1] for r in rows]
    lows   = [r[4] or r[1] for r in rows]
    closes = [r[1] for r in rows]
    vols   = [r[6] or 0 for r in rows]
    return {
        "ts":    ts,
        "open":  opens[0],
        "high":  max(highs),
        "low":   min(lows),
        "close": closes[-1],
        "volume": sum(vols),
    }


def get_oi_history(symbol: str, strike: int, interval: str = "5m", limit: int = 60):
    """Returns CE/PE OI history for a specific strike."""
    minutes = _INTERVALS.get(interval, 5)
    since   = int(time.time()) - minutes * limit * 60

    with _lock:
        c = _conn()
        try:
            rows = c.execute(
                "SELECT ts, ce_oi, pe_oi FROM oi_1m "
                "WHERE symbol=? AND strike=? AND ts>=? ORDER BY ts",
                (symbol, strike, since)
            ).fetchall()
        finally:
            c.close()

    # Aggregate to interval
    bars, buf, bar_start = [], [], None
    for (ts, ce, pe) in rows:
        bucket = (ts // (minutes * 60)) * (minutes * 60)
        if bar_start is None:
            bar_start = bucket
        if bucket != bar_start:
            if buf:
                bars.append({"ts": bar_start, "ce_oi": buf[-1][1], "pe_oi": buf[-1][2]})
                buf = []
            bar_start = bucket
        buf.append((ts, ce, pe))
    if buf:
        bars.append({"ts": bar_start, "ce_oi": buf[-1][1], "pe_oi": buf[-1][2]})
    return bars[-limit:]
