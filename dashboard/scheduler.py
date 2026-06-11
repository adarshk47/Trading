"""
Background scheduler — saves price + OI data every minute.
Runs as daemon thread regardless of whether browser is open.
"""
import time, threading, logging
from angel_client import get_cached_data
from option_chain import get_option_chain
from data_store   import save_prices, save_oi

log = logging.getLogger(__name__)


def _run():
    last_save = 0
    while True:
        now = time.time()
        # Save every 60 seconds
        if now - last_save >= 60:
            try:
                prices = get_cached_data()
                if prices:
                    save_prices(prices)
                    log.debug(f"Saved {len(prices)} price rows")
            except Exception as e:
                log.error(f"Scheduler price save: {e}")

            for symbol in ("NIFTY", "BANKNIFTY"):
                try:
                    chain = get_option_chain(symbol)
                    if chain and chain.get("strikes"):
                        save_oi(symbol, chain["strikes"])
                        log.debug(f"Saved OI for {symbol}")
                except Exception as e:
                    log.error(f"Scheduler OI save {symbol}: {e}")

            last_save = now
        time.sleep(5)


def start():
    t = threading.Thread(target=_run, daemon=True, name="DataScheduler")
    t.start()
    log.info("Background data scheduler started (saves every 60s)")
