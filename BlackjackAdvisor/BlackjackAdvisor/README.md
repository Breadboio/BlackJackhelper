# 🃏 Blackjack Advisor — Android Floating Overlay

A floating overlay Android app that gives you real-time blackjack strategy advice on top of any game app.

## How It Works

1. **Launch the app** → grant overlay permission
2. **Tap "Start Floating Advisor"** → a floating 🃏 button appears
3. **Switch to your blackjack game** (the floating button stays on top)
4. **Tap the floating button** → panel expands
5. **Tap your cards + the dealer's upcard** → get instant strategy advice
6. **Tap "New Hand"** to reset between rounds

The overlay is draggable — position it wherever it's not in the way.

---

## Strategy Engine

Implements **complete Las Vegas Strip basic strategy**:
- Dealer stands on soft 17
- Double after split allowed
- Late surrender allowed
- Re-split aces not allowed

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
   - Click the ▶ Run button or press Shift+F10
   - Select your device

5. **Grant permissions when prompted**
   - The app will direct you to Android Settings → Overlay permission
   - Enable it for "Blackjack Advisor"

---

## Permissions Explained

| Permission | Why |
|---|---|
| `SYSTEM_ALERT_WINDOW` | Draws the floating widget over other apps |
| `FOREGROUND_SERVICE` | Keeps the overlay alive when you switch apps |
| `POST_NOTIFICATIONS` | Required on Android 13+ for foreground service |

---

## File Structure

```
app/src/main/
├── java/com/breadlab/blackjackadvisor/
│   ├── BlackjackStrategy.kt      ← Complete strategy engine (pure logic)
│   ├── FloatingOverlayService.kt ← Overlay window + card input UI
│   └── MainActivity.kt           ← Permission flow + start/stop controls
├── res/
│   ├── layout/
│   │   ├── activity_main.xml     ← Main setup screen
│   │   └── overlay_layout.xml   ← Floating widget UI
│   ├── values/
│   │   └── styles.xml            ← Dark theme + card button styles
│   └── drawable/
│       ├── fab_background.xml    ← Circular FAB shape
│       └── overlay_panel_background.xml
└── AndroidManifest.xml
```

---

## Extending the App

### Add screen reading (OCR)
To automatically detect cards without manual input, you'd add:
- `CameraX` or `MediaProjection` API to capture the screen
- `ML Kit Text Recognition` (`com.google.mlkit:text-recognition`) to OCR card values
- Card detection logic to parse OCR output into card values

### Add count tracking
`BlackjackStrategy.kt` could be extended with a Hi-Lo count tracker:
```kotlin
object CardCounter {
    var runningCount = 0
    var decksRemaining = 6.0
    val trueCount get() = runningCount / decksRemaining

    fun trackCard(value: Int) {
        runningCount += when (value) {
            in 2..6 -> +1   // Low cards favor player
            in 7..9 -> 0    // Neutral
            else    -> -1   // High cards leave the deck
        }
    }
}
```

### Rule variations
Edit `getHardStrategy()` / `getSoftStrategy()` in `BlackjackStrategy.kt` to match your specific table rules (H17 vs S17, no-surrender, etc.).

---

## Notes

- **This is for entertainment / learning purposes.** Using assistance devices in casinos may violate their terms of service.
- The strategy engine is stateless — it gives optimal EV advice for the current hand only.
- For online RNG blackjack, basic strategy is sufficient. For shoe games, adding a count would further reduce house edge.
