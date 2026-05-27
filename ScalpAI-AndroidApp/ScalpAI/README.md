# 📱 ScalpAI — Android Stock Signal App

Automatically reads stock symbols from **any open app** (Zerodha, Groww, NSE, Upstox)
and instantly pops up a **CALL / PUT / WAIT signal** with confidence %, suggested strike, stop loss, and target.

---

## ✅ What This App Does

| Feature | Detail |
|---------|--------|
| **Screen Reading** | Accessibility Service reads stock symbols from any app automatically |
| **Live Data** | Fetches from Yahoo Finance (free, no API key needed) |
| **Signal Engine** | Scores each stock on Price, Volume, Range, Momentum, DMA |
| **Floating Popup** | Overlay appears over Zerodha/Groww with full signal detail |
| **Manual Search** | Type any NSE F&O symbol and get instant signal |
| **Auto-Start** | Starts automatically when phone boots |
| **Vibration** | Vibrates on high-confidence (75%+) signals |

---

## 🏗 BUILD INSTRUCTIONS

### Requirements
- Android Studio Hedgehog (2023.1.1) or later
- JDK 17
- Android device/emulator with API 26+

### Steps

```bash
# 1. Open Android Studio
# 2. File → Open → select the ScalpAI folder
# 3. Let Gradle sync (first time takes 2-3 min)
# 4. Connect your Android phone via USB
# 5. Enable Developer Options + USB Debugging on phone
# 6. Run → Run 'app'  (or press Shift+F10)
```

### Build APK directly
```bash
cd ScalpAI
./gradlew assembleDebug
# APK will be at: app/build/outputs/apk/debug/app-debug.apk
```
Copy the APK to your phone and install it (enable "Install from unknown sources").

---

## 📲 FIRST-TIME SETUP ON PHONE (2 minutes)

### Permission 1: Draw Over Other Apps
1. Open ScalpAI
2. Tap **"Grant"** next to "Draw Over Apps"
3. Find **ScalpAI** in the list → Toggle ON

### Permission 2: Accessibility Service
1. Tap **"Enable"** next to "Accessibility Service"
2. Settings opens → tap **"Installed services"** or **"Downloaded apps"**
3. Tap **ScalpAI** → Toggle ON
4. Tap **Allow** on the confirmation

Once both are green ✓ — you're ready!

---

## 🎯 HOW TO USE (Daily Trading)

### Automatic mode (best):
1. Open **Zerodha Kite** (or Groww / NSE app / Upstox)
2. Browse your watchlist or F&O option chain
3. ScalpAI reads the screen silently in background
4. When it sees a known NSE F&O symbol → fetches live data
5. **Popup appears** showing signal within 3-5 seconds

### Manual mode:
1. Switch to ScalpAI app
2. Type `SBIN` in the search box → tap **GET SIGNAL**
3. Or tap a quick-pick chip (TATAMOTORS, RELIANCE etc.)

---

## 📊 SIGNAL LOGIC

Score is calculated 0–100 from these factors:

| Factor | Weight | Condition |
|--------|--------|-----------|
| Volume vs Average | 40% | >180% = strong signal |
| Day Change % | 30% | >0.8% move = significant |
| Position in Day Range | 15% | >72% = bulls holding highs |
| Intraday Range | 10% | >2.5% = high premium opportunity |
| 50 DMA Trend | 5% | Confirms trend direction |

**Score ≥ 65 → BUY CALL** | **Score ≤ 35 → BUY PUT** | **35–65 → WAIT**

---

## 🔐 PRIVACY

- All processing is **on-device**
- Screen text is **never sent anywhere**
- Only Yahoo Finance API is called (to fetch stock price/volume)
- No login, no account, no data stored

---

## 🧑‍💻 CUSTOMIZE

### Add more stocks
Edit `SignalEngine.kt` → `NSE_FNO_STOCKS` map:
```kotlin
"NEWSTOCK" to 5_000_000L,  // symbol to average daily volume
```

### Change alert threshold
In the app Settings screen → drag the **Min Confidence** slider

### Add more apps to watch
The Accessibility Service already watches ALL apps.
To restrict to only specific apps, edit `StockAccessibilityService.kt`:
```kotlin
// Add to the skip list:
val WATCH_ONLY = setOf("com.zerodha.kite3", "com.groww.app")
if (pkg !in WATCH_ONLY) return
```

---

## ⚠️ DISCLAIMER
This app is for **educational and informational purposes only**.
It is **not SEBI-registered investment advice**.
Options trading carries significant financial risk.
Always use stop losses and trade with capital you can afford to lose.

---

## 📦 FILE STRUCTURE

```
ScalpAI/
├── app/src/main/
│   ├── AndroidManifest.xml
│   ├── java/com/scalpai/stocksignal/
│   │   ├── MainActivity.kt              ← Setup screen
│   │   ├── StockAccessibilityService.kt ← Reads screen symbols
│   │   ├── OverlayService.kt            ← Shows floating popup
│   │   ├── YahooFinanceApi.kt           ← Fetches live data
│   │   ├── SignalEngine.kt              ← CALL/PUT calculation
│   │   └── BootReceiver.kt              ← Auto-start on boot
│   └── res/
│       ├── layout/activity_main.xml
│       ├── values/{colors,strings,themes}.xml
│       └── xml/accessibility_service_config.xml
└── build.gradle / settings.gradle
```
