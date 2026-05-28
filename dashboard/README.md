# Market Dashboard

Real-time Indian market dashboard — Angel One (SmartAPI) + TradingView widgets.

## Features
- **Indian Indices**: Nifty 50, Bank Nifty, Sensex, Nifty IT, Nifty Midcap (Angel One, 5s refresh)
- **Futures**: Nifty Fut, BankNifty Fut (Angel One, 5s refresh)
- **Indian Stocks**: Top 10 NSE stocks — LTP, OHLC, volume (Angel One, 5s refresh)
- **Global Indices**: S&P 500, Nasdaq, DAX, Nikkei, Hang Seng, Gold, Crude (TradingView live)
- **Charts**: Nifty 50 advanced chart + Market Overview (TradingView)

## Setup

```bash
cd dashboard
pip install -r requirements.txt
```

Copy `.env.example` to `.env` and fill credentials:
```
ANGEL_API_KEY=...
ANGEL_CLIENT_ID=...
ANGEL_PASSWORD=...
ANGEL_TOTP_SECRET=...
```

## Run

```bash
python app.py
```

Open http://localhost:5000

## Notes
- Market data refreshes every **5 seconds** via background thread + `/api/market` JSON endpoint
- TradingView widgets are real-time (WebSocket, no API key needed)
- Futures tokens (NFO) change every expiry — update `config.py` monthly
