# Blackjack Advisor — Android Floating Overlay

A floating overlay Android app that gives you real-time blackjack strategy advice on top of any game app. Auto-detect mode uses screen capture and OCR to read cards from the screen automatically; manual mode lets you tap cards in directly.

> ⚠️ **Entertainment / learning use only.** This app reads another app's screen
> and gives automated blackjack advice. On real-money platforms (e.g. Stake)
> that violates their Terms of Service and can get an account and its funds
> frozen. The corrected strategy is *more* accurate — it does **not** reduce
> that risk.

---

## How It Works

### Manual Mode

1. **Launch the app** → grant overlay permission
2. **Tap "Start Floating Advisor"** → a floating card button appears
3. **Switch to your blackjack game** (the floating button stays on top)
4. **Tap the floating button** → panel expands
5. **Tap your cards + the dealer's upcard** → get instant strategy advice
6. **Tap "New Hand"** to reset between rounds

The overlay is draggable — position it wherever it is not in the way.

### Auto-Detect Mode (Screen Capture + OCR)

Auto-detect is a shipped feature that reads the screen automatically:

1. **Tap "Enable Auto-Detect"** in the floating panel
2. Android asks for screen-capture consent — **grant it**
3. A **3-zone calibration overlay** appears; drag the zones to cover:
   - the dealer's card area
   - your card area
   - the balance / bet display
4. Tap **"Done"** — `ScreenCaptureService` begins capturing frames; `CardDetector` runs ML Kit text recognition on each zone
5. Strategy advice and a bankroll / suggested-bet readout update automatically as cards are detected
6. **Tap "Stop Auto-Detect"** to end — screen capture stops and consent is released

**Android 14 consent behavior:** Screen-capture consent is requested each time auto-detect is enabled, and is re-requested after stopping. This is an Android 14 OS requirement (single-use consent per session) — it is expected behavior, not a bug.

---

## Strategy Engine

Implements **Las Vegas Strip basic strategy** with this locked ruleset:

- Dealer **stands** on soft 17 (S17)
- Double after split allowed (DAS)
- Late surrender allowed
- Re-split aces **not** allowed
- Standard **total-dependent** basic strategy (no composition-dependent plays)

Covers all decision types:

- **Hard totals** (8 through 17+)
- **Soft hands** (A-2 through A-9)
- **Pairs** (2-2 through A-A)
- **Surrender** recommendations (16 vs 10/Ace, 15 vs 10)

---

## Build Instructions

### Requirements

- Android Studio Hedgehog (2023.1.1) or newer
- Android SDK 34
- Kotlin 1.9.x
- A physical Android device (API 26+) or emulator

### Steps

1. **Open in Android Studio**
   ```
   File → Open → select the BlackjackAdvisor folder
   ```

2. **Sync Gradle**
   Android Studio will prompt you — click "Sync Now"

3. **Connect your device**
   - Enable Developer Options on your Android phone
   - Enable USB Debugging
   - Connect via USB

4. **Run the app**
   - Click the Run button or press Shift+F10
   - Select your device

5. **Grant permissions when prompted**
   - Overlay permission (`SYSTEM_ALERT_WINDOW`) — required to draw the floating widget
   - Notification permission — required on Android 13+ for foreground services
   - Screen-capture consent — requested each time auto-detect is enabled

---

## Permissions Explained

| Permission | Why |
|---|---|
| `SYSTEM_ALERT_WINDOW` | Draws the floating widget over other apps |
| `FOREGROUND_SERVICE` | Base permission required for all foreground services |
| `FOREGROUND_SERVICE_SPECIAL_USE` | Required for the overlay foreground service (`FloatingOverlayService`) |
| `FOREGROUND_SERVICE_MEDIA_PROJECTION` | Required for the screen-capture foreground service (`ScreenCaptureService`) |
| `POST_NOTIFICATIONS` | Required on Android 13+ to post foreground-service notifications |

---

## File Structure

```
app/src/main/
├── java/com/breadlab/blackjackadvisor/
│   ├── BlackjackStrategy.kt      ← Complete LV Strip S17/DAS/surrender strategy engine (pure logic)
│   ├── CardDetector.kt           ← ML Kit text recognition; parses OCR output into card values
│   ├── ScreenCaptureService.kt   ← MediaProjection foreground service; captures screen frames for OCR
│   ├── CalibrationView.kt        ← Draggable 3-zone on-screen overlay for positioning capture regions
│   ├── Calibration.kt            ← CalibrationMath (zone geometry) + CalibrationStore (SharedPreferences persistence)
│   ├── FloatingOverlayService.kt ← Floating widget window, card-input UI, and auto-detect controls
│   ├── HandTracker.kt            ← Tracks cards seen in the current hand; drives the advice display
│   ├── Bankroll.kt               ← Bankroll state and suggested-bet calculations
│   └── MainActivity.kt           ← Permission flow, start/stop controls, and service lifecycle
├── res/
│   ├── layout/
│   │   ├── activity_main.xml        ← Main setup screen
│   │   ├── overlay_layout.xml       ← Floating widget UI
│   │   └── calibration_overlay.xml  ← Calibration zone overlay layout
│   ├── values/
│   │   └── styles.xml               ← Dark theme + card button styles
│   └── drawable/
│       ├── fab_background.xml            ← Circular FAB shape
│       └── overlay_panel_background.xml  ← Panel background shape
└── AndroidManifest.xml
```

---

## Notes

- The strategy engine is stateless — it gives optimal EV advice for the current hand only, based on total-dependent LV Strip S17/DAS/late-surrender basic strategy.
- For online RNG blackjack, basic strategy is sufficient; the locked ruleset matches standard Las Vegas Strip shoe games.
