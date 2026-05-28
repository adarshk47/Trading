import json
import logging
from flask import Flask, render_template, jsonify
from config import INDICES, FUTURES, INDIAN_WATCHLIST
from angel_client import start_background_refresh, get_cached_data

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")

app = Flask(__name__)

# start background refresh once (works with gunicorn --preload or direct run)
start_background_refresh(interval=5)

# token sets for categorization
INDEX_TOKENS   = {tok for _, tok, _ in INDICES}
FUTURE_TOKENS  = {tok for _, tok, _ in FUTURES}
STOCK_TOKENS   = {tok for _, tok, _ in INDIAN_WATCHLIST}


@app.route("/")
def index():
    return render_template("dashboard.html")


@app.route("/api/market")
def market_api():
    raw = get_cached_data()
    indices, futures, stocks = [], [], []

    for token, q in raw.items():
        row = {**q, "token": token}
        if token in INDEX_TOKENS:
            indices.append(row)
        elif token in FUTURE_TOKENS:
            futures.append(row)
        elif token in STOCK_TOKENS:
            stocks.append(row)

    # preserve insertion order
    def sort_key(lst, ref):
        order = {tok: i for i, (_, tok, _) in enumerate(ref)}
        return sorted(lst, key=lambda x: order.get(x["token"], 999))

    return jsonify({
        "indices": sort_key(indices, INDICES),
        "futures": sort_key(futures, FUTURES),
        "stocks":  sort_key(stocks,  INDIAN_WATCHLIST),
    })


if __name__ == "__main__":
    app.run(host="0.0.0.0", port=5000, debug=False)
