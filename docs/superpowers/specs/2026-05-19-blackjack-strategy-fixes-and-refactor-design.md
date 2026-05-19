# Blackjack Advisor — Strategy Bug Fixes + Targeted Refactor + Tests

- **Date:** 2026-05-19
- **Status:** Approved design — ready for implementation planning
- **Scope owner:** zhan (fixing on behalf of a friend; not a blackjack domain expert — strategy correctness is grounded in an external authority, not user judgment)

---

## 1. Problem statement

`BlackJackhelper` is an Android/Kotlin floating-overlay app that reads blackjack
cards off the screen (MediaProjection + ML Kit OCR) and renders basic-strategy
advice. It has correctness bugs in the strategy engine, a platform bug that
breaks screen capture on its own target SDK, and pure logic trapped inside
Android classes where it cannot be tested. This effort fixes the confirmed bugs
and does the minimum extraction needed to make the fixes verifiable.

## 2. Locked decisions

| Decision | Choice | Rationale |
|---|---|---|
| Scope | Fix confirmed bugs + targeted refactor for testability + unit tests | Bug fixes and extraction are coupled — the buggy logic is untestable until extracted |
| Ruleset | **LV Strip: S17, DAS, late surrender, no re-split aces** | Documented spec in `README.md:20-30`; code structure (`Action.SURRENDER`, `dealer == 11` branches) corroborates this intent. Stake (referenced in code comments) is the OCR-tuning target only, not a strategy spec |
| Approach | **A** — extract pure units, fix bugs there, JVM tests; no broader UI decomposition | Matches scope; isolates and verifies the bug-prone logic without over-refactoring |
| B25 hardening | Ignore confusable dealer flips (don't treat a confusable-pair dealer change as a new hand) | Reuses the app's own `confusablePairs` data; a real new deal rarely lands the dealer's confusable twin |
| B19 flow | Consume screen-capture consent on "Enable Auto-Detect"; never store/reuse the Intent | Required by Android 14 (the app's `targetSdk 34`); smallest correct change, preserves two-step UX |

Out of scope (noted, not addressed): LocalBroadcastManager deprecation; the
`CardDetector.extractBalance` "largest number wins" heuristic; ML Kit
`16.0.0 → 16.0.1` (API-identical, optional); an H17/no-surrender rule toggle
(future extension); Approach C overlay-controller decomposition.

## 3. Bug inventory

### 3.1 Strategy correctness (`BlackjackStrategy.kt`)

| ID | Bug | Current → Correct | Fix |
|---|---|---|---|
| **B1** | Dealer Ace passed as `1`; logic compares vs `11` (`:82,152,153`) and treats `1` as a weak card. Root cause of ~12 wrong cells. | vs A: hard 13/14 STAND→**HIT**, 16 HIT→**R**, 10 DOUBLE→**HIT**; pair 9,9 SPLIT→**STAND**; pairs 2,2/3,3/6,6/7,7 SPLIT→**HIT** | Normalize dealer Ace `1→11` once at `getAdvice` entry; pass normalized value everywhere |
| **B2** | Soft 21 falls to `else` → HIT (`:141`) | HIT → **STAND** | Soft total ≥ 20 → STAND (covers 20, 21) |
| **B4** | Soft 19 vs 6 → DOUBLE_OR_STAND (`:111`) — an H17 play | DOUBLE/STAND → **STAND** | Soft 19 → STAND vs all (S17) |
| **B5** | Soft 18 vs 2 lumped into double range (`:116`) | DOUBLE/STAND → **STAND** | Soft 18: D vs 3–6 · S vs 2,7,8 · H vs 9,10,A |
| **B7** | Hard 16 vs 9 → HIT (`:149-153`) | HIT → **SURRENDER** (2-card) | Hard 16 → R vs 9,10,A |
| **B13/B14** | DOUBLE/SURRENDER returned for 3+ card hands (illegal once you've hit) | e.g. 5+3+2 = hard 10 vs 7: DOUBLE → **HIT** | Legality gate by card count (see §4.3) |
| **B26** | Hard 11 vs A → DOUBLE unconditionally (`:169`) — an H17 play; **caught only by verifying against the authority, not from memory** | DOUBLE → **HIT** (S17) | Hard 11: D vs 2–10, H vs A |

**Invariant (not a code change, but a required test):** a pair of 8,8 is
**never surrendered** — always split, even vs 9/10/A. Guaranteed structurally
because pairs are evaluated before hard totals; a test pins it so a future
refactor cannot silently break it.

**Contract:** add a new `Action.INCOMPLETE` enum value (neutral label/emoji);
`getAdvice` returns it when the player has < 2 cards **or** the dealer upcard is
0 — one source of truth for "is this an advisable situation," replacing the
scattered guards in the Service, which then renders `INCOMPLETE` as the existing
"Enter cards" state through the same single rendering path as every other action.
`INCOMPLETE` rendering must also reset the collapsed FAB to its neutral state
(🃏, neutral color) so a prior hand's advice emoji/color cannot persist after
New Hand.

### 3.2 Dead code (B3) — remove

- `getAdvice`'s `normalized` local (`:39`) — computed, never used.
- `BlackjackStrategy.normalizeCard` (`:195`) — never called.
- `Action.SPLIT_OR_HIT` — defined, never returned.

These are symptoms of the B1 Ace confusion; removing them clarifies intent.

### 3.3 Detection reliability

| ID | Bug | Fix |
|---|---|---|
| **B25** | Two consecutive dealer OCR misreads to the same wrong value (~3 s; `newHandConfirmFrames = 2`) silently wipe the player's hand and adopt whatever is on screen. The dealer card is read by the *same* fallible OCR the `:30` comment distrusts for player cards. 8↔3, 4↔A etc. are in the app's own `confusablePairs`. | In the extracted `HandTracker`: if the "new" dealer value forms a `confusablePair` with the stored dealer value, treat it as a misread — do **not** start a new hand. Pinned by tests. |

### 3.4 Platform / lifecycle

| ID | Bug | Fix |
|---|---|---|
| **B19** (headline) | `MainActivity` caches the MediaProjection result Intent (`:21-22,133-135`) and a later, separate "Start" tap (`:48-51`) passes it to `ScreenCaptureService` (`:78`). Android 14 (`targetSdk 34`) throws `SecurityException` for caching/reusing the consent Intent or reusing a `MediaProjection` instance. **Auto-detect is fundamentally broken on the target platform.** | "Enable Auto-Detect" requests consent and, in `onActivityResult` on `RESULT_OK`, **immediately** starts `ScreenCaptureService` with the fresh Intent (used once, now). Remove the instance fields. Overlay start stays independent. Stop → re-enable re-prompts consent. Keep the existing pre-`createVirtualDisplay` `registerCallback` (required ≥ API 34). |
| **B20** | Calibration overlay window is focusable (`flags = 0`, `:414`) with no key handler → swallows Back; user can get stuck. | Calibration root handles `KEYCODE_BACK` → `stopCalibration()`. Stays touch-focusable. |

### 3.5 Documentation

| ID | Bug | Fix |
|---|---|---|
| **B23** | `README.md` documents OCR/auto-detect as a *future* "Extending the App" idea though it ships; File Structure omits `ScreenCaptureService`/`CardDetector`/`CalibrationView`; permissions table omits `FOREGROUND_SERVICE_MEDIA_PROJECTION` and `FOREGROUND_SERVICE_SPECIAL_USE`. | Rewrite to shipped reality; document the locked ruleset and the Android 14 per-session consent behavior; correct File Structure (incl. new units) and permissions table; **retain and prominently surface** the entertainment-only / ToS disclaimer — corrected advice is *more* accurate, which does not reduce the account/fund risk of automated screen-reading advice on a real-money platform. |

## 4. The corrected strategy table (authoritative)

Source: Wizard of Odds, 4–8 decks, dealer **stands** on soft 17, DAS, late
surrender (cited in §8). Dealer upcard column = 2–9, 10, A. Internally the
dealer Ace is `11` (after B1 normalization). `D` = double if legal else
fallback; `R` = surrender; `Spl` = split; `S` = stand; `H` = hit.

**Assumption:** the engine is standard **total-dependent** basic strategy.
Composition-dependent refinements (e.g. a 4-card 16 vs 10 standing instead of
hitting) are deliberately out of scope — a basic-strategy advisor looks up by
total/soft/pair only. This is a documented design decision, not an omission.

### 4.1 Hard totals
| Total | Strategy |
|---|---|
| 5–8 | H all |
| 9 | D vs 3–6 · H vs 2,7,8,9,10,A |
| 10 | D vs 2–9 · H vs 10,A |
| 11 | D vs 2–10 · **H vs A** (B26) |
| 12 | S vs 4–6 · H vs 2,3,7,8,9,10,A |
| 13 | S vs 2–6 · H vs 7,8,9,10,A |
| 14 | S vs 2–6 · H vs 7,8,9,10,A |
| 15 | S vs 2–6 · **R vs 10** · H vs 7,8,9,A |
| 16 | S vs 2–6 · **R vs 9,10,A** · H vs 7,8 |
| 17–21 | S all |

### 4.2 Soft totals
| Hand | Strategy |
|---|---|
| A,2 (s13) | D vs 5–6 · H else |
| A,3 (s14) | D vs 5–6 · H else |
| A,4 (s15) | D vs 4–6 · H else |
| A,5 (s16) | D vs 4–6 · H else |
| A,6 (s17) | D vs 3–6 · H else (incl. vs 2) |
| A,7 (s18) | D vs 3–6 · S vs 2,7,8 · H vs 9,10,A |
| A,8 (s19) | S all |
| A,9 (s20), s21 | S all |

### 4.3 Pairs (DAS) and the legality gate
| Pair | Strategy |
|---|---|
| 2,2 · 3,3 | Spl vs 2–7 · H else |
| 4,4 | Spl vs 5–6 · H else |
| 5,5 | never split → play as hard 10 (D 2–9, H 10/A) |
| 6,6 | Spl vs 2–6 · H else |
| 7,7 | Spl vs 2–7 · H else |
| 8,8 · A,A | Spl all (8,8 never surrenders) |
| 9,9 | Spl vs 2–6,8,9 · S vs 7,10,A |
| 10,10 | S (never split) |

**Legality gate** applied after the table lookup: if the hand does not have
exactly 2 cards, `DOUBLE → HIT`, `DOUBLE_OR_HIT → HIT`,
`DOUBLE_OR_STAND → STAND`, `SURRENDER → HIT` (the standard
"hit if surrender unavailable" fallback). `SPLIT` only arises for 2-card pairs.
< 2 player cards or dealer 0 → `Action.INCOMPLETE`.

The app advises a single hand and does **not** model post-split continuation,
so "exactly 2 cards" is the legality proxy for double/surrender. DAS is already
reflected in the split *ranges* of §4.3 (e.g. 2,2/3,3/4,4/6,6), not in any
post-split doubling logic — there is no split-state to track.

## 5. Architecture / module decomposition (Approach A)

**Pure units (no Android imports → JVM-testable, no Robolectric):**

- **`BlackjackStrategy`** *(corrected; already pure)* — public API unchanged
  (`getAdvice`, `cardDisplayName`); delete dead `normalizeCard`. Internally:
  normalize dealer Ace at entry (B1); add `INCOMPLETE`; apply the legality gate
  (B13/B14); table corrections B2/B4/B5/B7/B26.
- **`HandTracker`** *(new — extracted from `FloatingOverlayService`'s
  `cardDetectionReceiver` + ambiguity helpers)* — owns sticky accumulation, the
  N-frame auto-new-hand confirm **with B25 hardening**, confusable-pair
  ambiguity, and manual ops (`addPlayerCard`, `setDealer`, `undo`, `reset`,
  `resolveAmbiguity`, `setAutoDetect`). `onFrame(player, dealer): TrackerUpdate`
  where `TrackerUpdate` is a plain data class expressing what changed
  (cards/advice, ambiguity prompt to show/clear, new-hand committed).
  **Contract: main-thread-confined; no internal locking.**
- **`Bankroll`** *(new — extracted)* — `recommendedBet`, `formatMoney`.
- **`CalibrationMath`** *(new — pure functions colocated with
  `CalibrationStore`, not a standalone class)* — rect validity
  (`right > left && bottom > top && ≥ 0`) and fraction↔pixel conversion,
  operating on plain numeric types (returns int quads, not `android.graphics.Rect`).

**Thin Android shells:**

- **`CalibrationStore`** *(new — SharedPreferences shell)* — single owner of
  `PREFS_NAME` + the 12 keys. `load`/`save`/`clear`; builds `android.graphics.Rect`
  from `CalibrationMath` output. **Read-through, never caches** (preserves live
  recalibration: `CardDetector` reads zones every frame). Replaces the duplicated
  prefs I/O in `FloatingOverlayService` *and* `CardDetector`.
- **`FloatingOverlayService`** *(shrinks)* — window/view lifecycle, button →
  `HandTracker` wiring, rendering `StrategyResult`/`TrackerUpdate`, calibration
  overlay via `CalibrationStore`, bankroll via `Bankroll`. No embedded state
  machine or prefs I/O. Target: well under half its current 610 lines.
- `CardDetector`, `ScreenCaptureService`, `MainActivity` — unchanged except:
  `CardDetector` reads zones via `CalibrationStore`; `MainActivity`/
  `ScreenCaptureService` get the B19/B20 fixes.

**Dependency direction:** pure units depend on nothing Android; shells depend on
pure units; the data-flow seams are byte-identical to today.

**Extraction boundaries (do not leak UI/state into the pure units):** the
`isExpanded && !isAutoDetect` frame gate stays in the Service — the Service
decides whether to feed a frame to `HandTracker` at all, so `HandTracker` never
sees `isExpanded`. `lastBalance` is display-only state and stays in the Service
(not hand-reconciliation state). `confusablePairs` **moves into** `HandTracker`,
shared by both ambiguity detection and the B25 dealer-flip guard.

## 6. Data flow

`ScreenCaptureService` (1.5 s capture loop) → `CardDetector.detectCards`
(ML Kit OCR; zones via `CalibrationStore`, read-through) → `DetectedCards` →
`LocalBroadcastManager` → `FloatingOverlayService` receiver →
`HandTracker.onFrame` → `TrackerUpdate` → Service renders advice via
`BlackjackStrategy.getAdvice` + bankroll via `Bankroll`. Manual mode: button
taps → `HandTracker` methods → re-render. Calibration: Service overlay →
`CalibrationStore.save/clear`. All `HandTracker` access is on the main thread
(broadcast delivered on ML Kit's main-thread callback; taps on the UI thread) —
a stated contract, no locks.

## 7. Error handling (no seam behavior change)

- `BlackjackStrategy.getAdvice`: < 2 player cards or dealer 0 → `INCOMPLETE`
  (Service renders the existing "Enter cards" UX). Never throws.
- `HandTracker`: tolerates empty/zero/oversized frames (mirrors the current
  null-safe receiver). Never throws.
- `CalibrationStore`: missing/invalid rect → null → caller falls back to
  defaults (current behavior preserved).
- `ScreenCaptureService`: catches transient capture errors and
  `SecurityException` (e.g. revoked Android 14 consent) → clean stop + a
  "re-enable auto-detect" status, instead of crashing.

## 8. Test plan

Location `app/src/test/`, JUnit 4.13.2, pure JVM, no Robolectric (validated via
Context7: local unit tests throw on Android-API access unless
`returnDefaultValues` — our units avoid Android entirely, so plain JUnit suffices).

- **`BlackjackStrategyTest`** — full sweep of every (hand × dealer 2–A) cell
  against §4; explicit named regressions for B1 (all dealer-Ace spots), B2, B4,
  B5, B7, **B26**, B13/B14 (3+ card hands never DOUBLE/SURRENDER), and the
  8,8-never-surrender invariant.
- **`HandTrackerTest`** — **characterization tests** that pin the *existing*
  behavior so the extraction is provably behavior-preserving, with B25 as the
  one deliberately-changed behavior: sticky accumulation across dropped frames;
  N-frame auto-new-hand commit; **B25** (8↔3 confusable dealer flip does *not*
  reset the hand — the intentional change); ambiguity raised once per pair per
  hand and resolved; manual ops; reset.
- **`BankrollTest`** — stepped-bet boundaries (0.99, 1.0, 4.99, 25, 100, 1000)
  and money formatting (whole vs fractional).
- **`CalibrationMathTest`** — validity gate; fraction↔pixel round-trip.

Build additions: `testImplementation 'junit:junit:4.13.2'` and the `src/test`
source set (neither exists today). `testOptions.unitTests.returnDefaultValues`
is documented but unnecessary for these pure units.

**Manual device verification** (the half of the headline bugs unit tests cannot
cover — to be run on a physical Android 14+ device by the user/friend):

- **B19:** "Enable Auto-Detect" → consent prompt appears → capture starts and
  cards are detected. Stop, then re-enable → consent prompts *again* (no
  `SecurityException`). Rotate the screen mid-flow → no crash. Background/return
  → still works.
- **B20:** Open calibration → press Back → calibration cancels (no trap, no
  stuck full-screen overlay).
- **Regression after Service shrink:** FAB drag, expand/collapse, manual card
  taps, undo, New Hand, the confusable-ambiguity prompt, and the bankroll line
  all still work; calibrate → save → detection uses the new zones next frame.

**Verification honesty:** if this environment cannot run
`./gradlew testDebugUnitTest` (no Android SDK / offline), that will be stated
explicitly and the user asked to run it — no green-test claims without observed
output (per `verification-before-completion`). The manual checklist above is
explicitly the user's to run; it will never be reported as passed by me.

## 9. References (Context7- and authority-verified)

- Android 14 MediaProjection per-session consent / `SecurityException` on
  cached Intent — `developer.android.com/about/versions/14/behavior-changes-14`
  (via Context7). Drives **B19**.
- ML Kit Text Recognition current API (`TextRecognition.getClient` →
  `process` → `close`) — `developers.google.com/ml-kit/vision/text-recognition/android`
  (via Context7). Confirms `CardDetector` is current; no migration.
- Android local unit tests / Gradle test deps —
  `developer.android.com/build/dependencies`, `.../gradle-tips` (via Context7).
  Drives the test stack.
- Basic strategy (S17, 4–8 decks, DAS, late surrender) and surrender cells —
  `wizardofodds.com/games/blackjack/strategy/4-decks/` and
  `wizardofodds.com/games/blackjack/surrender/`. Drives §4 and caught **B26**.

Standing practice: every library/platform API touched is checked against
Context7 (or the relevant authority for non-library facts) and cited before
relying on it.

## 10. Build sequence (for the implementation plan)

1. **Toolchain fail-fast:** add the `src/test` source set + `junit:junit:4.13.2`
   with one trivial passing test; confirm `./gradlew testDebugUnitTest` actually
   runs in the target environment *before* any other work. If it cannot run
   here, restructure so the user runs tests at every checkpoint — surfaced now,
   not after the extraction.
2. Correct `BlackjackStrategy` (B1, B2, B4, B5, B7, B26, B13/B14,
   `Action.INCOMPLETE`, B3 dead-code removal) + the full `BlackjackStrategyTest`
   sweep. Pure, highest-value, lowest-risk.
3. **CHECKPOINT — hard stop.** Run the strategy tests; report observed output.
   The user (or their friend) confirms the corrected advice is right. **No
   extraction or B19/B20 work begins until this is signed off.** This increment
   is self-contained and shippable on its own.
4. Extract `CalibrationMath` + `CalibrationStore`; route `CardDetector` and the
   Service through it + `CalibrationMathTest`.
5. Extract `HandTracker` (with B25 hardening) from the Service + the
   characterization `HandTrackerTest`.
6. Extract `Bankroll` + `BankrollTest`.
7. B19 (Android 14 consent flow) and B20 (calibration Back).
8. B23 (README rewrite, incl. the retained ToS disclaimer).
9. Run the full `./gradlew testDebugUnitTest`; report observed results honestly;
   hand the §8 manual device-verification checklist to the user.
