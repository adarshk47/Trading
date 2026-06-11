import os
from dotenv import load_dotenv

load_dotenv(os.path.join(os.path.dirname(__file__), ".env"))

ANGEL = {
    "api_key":     os.getenv("ANGEL_API_KEY",     "LkKs5NJG"),
    "secret_key":  os.getenv("ANGEL_SECRET_KEY",  "600734be-7bf2-4bfe-a00c-a5972673d16d"),
    "client_id":   os.getenv("ANGEL_CLIENT_ID",   "A114064"),
    "password":    os.getenv("ANGEL_PASSWORD",     "Mahadev1@#"),
    "mpin":        os.getenv("ANGEL_MPIN",         "1008"),
    "totp_secret": os.getenv("ANGEL_TOTP_SECRET",  "6IK5P2KWF3YULRMR6VSUVZZVLI"),
}

DB_PATH = os.path.join(os.path.dirname(__file__), "market_data.db")

INDIAN_WATCHLIST = [
    ("RELIANCE",   "2885",  "NSE"),
    ("TCS",        "11536", "NSE"),
    ("INFY",       "1594",  "NSE"),
    ("HDFCBANK",   "1333",  "NSE"),
    ("ICICIBANK",  "4963",  "NSE"),
    ("SBIN",       "3045",  "NSE"),
    ("AXISBANK",   "5900",  "NSE"),
    ("WIPRO",      "3787",  "NSE"),
    ("LT",         "11483", "NSE"),
    ("BAJFINANCE", "317",   "NSE"),
]

INDICES = [
    ("NIFTY 50",     "99926000", "NSE"),
    ("BANK NIFTY",   "99926009", "NSE"),
    ("SENSEX",       "99919000", "BSE"),
    ("NIFTY IT",     "99926036", "NSE"),
    ("NIFTY MIDCAP", "99926015", "NSE"),
]

# Near-month futures (update monthly)
FUTURES = [
    ("NIFTY FUT",     "58662", "NFO"),
    ("BANKNIFTY FUT", "57595", "NFO"),
]

# Options chain config
NIFTY_STRIKE_STEP   = 50   # Nifty strikes are in multiples of 50
SENSEX_STRIKE_STEP  = 100
OC_STRIKES_EACH_SIDE = 5   # 5 strikes above and below ATM
