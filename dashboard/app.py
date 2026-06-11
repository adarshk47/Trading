import logging
from flask import Flask, render_template, jsonify, request
from config import INDICES, FUTURES, INDIAN_WATCHLIST
from angel_client import start_background_refresh, get_cached_data
from option_chain import get_option_chain, get_oi_direction
from data_store   import setup as db_setup, get_price_history, get_oi_history
import scheduler

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")

app = Flask(__name__)

# Startup
db_setup()
start_background_refresh(interval=5)
scheduler.start()

INDEX_TOKENS  = {tok for _, tok, _ in INDICES}
FUTURE_TOKENS = {tok for _, tok, _ in FUTURES}
STOCK_TOKENS  = {tok for _, tok, _ in INDIAN_WATCHLIST}


@app.route("/")
def index():
    return render_template("dashboard.html")


@app.route("/api/market")
def market_api():
    raw = get_cached_data()
    indices, futures, stocks = [], [], []
    for token, q in raw.items():
        row = {**q, "token": token}
        if token in INDEX_TOKENS:   indices.append(row)
        elif token in FUTURE_TOKENS: futures.append(row)
        elif token in STOCK_TOKENS:  stocks.append(row)

    def _sort(lst, ref):
        order = {tok: i for i, (_, tok, _) in enumerate(ref)}
        return sorted(lst, key=lambda x: order.get(x["token"], 999))

    # OI direction for Nifty
    oi_dir = get_oi_direction("NIFTY", minutes=15)

    return jsonify({
        "indices": _sort(indices, INDICES),
        "futures": _sort(futures, FUTURES),
        "stocks":  _sort(stocks,  INDIAN_WATCHLIST),
        "oi_direction": oi_dir,
    })


@app.route("/api/optionchain/<symbol>")
def option_chain_api(symbol):
    symbol = symbol.upper()
    if symbol not in ("NIFTY", "BANKNIFTY", "SENSEX"):
        return jsonify({"error": "invalid symbol"}), 400
    chain  = get_option_chain(symbol)
    oi_dir = get_oi_direction(symbol, minutes=15)
    return jsonify({"chain": chain, "oi_direction": oi_dir})


@app.route("/api/history/<symbol>")
def history_api(symbol):
    interval = request.args.get("interval", "5m")
    limit    = int(request.args.get("limit", 120))
    bars     = get_price_history(symbol.upper(), interval, limit)
    return jsonify({"symbol": symbol.upper(), "interval": interval, "bars": bars})


@app.route("/api/oi_history/<symbol>/<int:strike>")
def oi_history_api(symbol, strike):
    interval = request.args.get("interval", "5m")
    bars     = get_oi_history(symbol.upper(), strike, interval)
    return jsonify({"symbol": symbol.upper(), "strike": strike, "bars": bars})


if __name__ == "__main__":
    app.run(host="0.0.0.0", port=5000, debug=False)
