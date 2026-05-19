# Blackjack Strategy Fixes + Targeted Refactor Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Fix the confirmed blackjack basic-strategy and platform bugs and extract the tangled pure logic into JVM-testable units, with a hard verify checkpoint after the strategy correctness lands.

**Architecture:** Correct the already-pure `BlackjackStrategy`; extract `HandTracker`, `Bankroll`, and calibration math/store out of `FloatingOverlayService` as Android-free units tested with plain JUnit; fix the Android 14 MediaProjection consent flow. Data-flow seams stay byte-identical except the deliberate B25 change.

**Tech Stack:** Kotlin, Android (compileSdk/targetSdk 34), Gradle 8.7, JUnit 4.13.2 local unit tests (`testDebugUnitTest`), ML Kit text recognition (unchanged).

**Spec:** `docs/superpowers/specs/2026-05-19-blackjack-strategy-fixes-and-refactor-design.md`

---

## File Structure

**Created:**
- `app/src/test/java/com/breadlab/blackjackadvisor/SmokeTest.kt` — toolchain fail-fast probe (deleted in Task 2).
- `app/src/test/java/com/breadlab/blackjackadvisor/BlackjackStrategyTest.kt` — full strategy table + per-bug regressions.
- `app/src/main/java/com/breadlab/blackjackadvisor/Bankroll.kt` — pure bet/format math.
- `app/src/test/java/com/breadlab/blackjackadvisor/BankrollTest.kt`
- `app/src/main/java/com/breadlab/blackjackadvisor/Calibration.kt` — pure `CalibrationMath` + thin `CalibrationStore` shell.
- `app/src/test/java/com/breadlab/blackjackadvisor/CalibrationMathTest.kt`
- `app/src/main/java/com/breadlab/blackjackadvisor/HandTracker.kt` — pure detection state machine (incl. B25).
- `app/src/test/java/com/breadlab/blackjackadvisor/HandTrackerTest.kt`

**Modified:**
- `app/build.gradle` — add `testImplementation 'junit:junit:4.13.2'`.
- `app/src/main/java/com/breadlab/blackjackadvisor/BlackjackStrategy.kt` — B1/B2/B4/B5/B7/B26/B13-B14/INCOMPLETE/B3.
- `app/src/main/java/com/breadlab/blackjackadvisor/CardDetector.kt` — read zones via `CalibrationStore`; drop its prefs keys/`readRect`/`loadCalibratedZones`.
- `app/src/main/java/com/breadlab/blackjackadvisor/FloatingOverlayService.kt` — delegate to `HandTracker`/`CalibrationStore`/`Bankroll`; shrink.
- `app/src/main/java/com/breadlab/blackjackadvisor/MainActivity.kt` — B19 consent flow.
- `app/src/main/java/com/breadlab/blackjackadvisor/ScreenCaptureService.kt` — B19 `SecurityException` safety.
- `README.md` — B23 rewrite incl. retained ToS disclaimer.

---

## Task 1: Toolchain fail-fast

**Files:**
- Modify: `app/build.gradle`
- Create: `app/src/test/java/com/breadlab/blackjackadvisor/SmokeTest.kt`

- [ ] **Step 1: Add the JUnit test dependency**

In `app/build.gradle`, the `dependencies { }` block currently ends with the LocalBroadcastManager line. Add JUnit as the last entry:

```groovy
    // LocalBroadcastManager for service-to-service communication
    implementation 'androidx.localbroadcastmanager:localbroadcastmanager:1.1.0'

    // Local JVM unit tests (pure logic only — no Robolectric needed)
    testImplementation 'junit:junit:4.13.2'
}
```

- [ ] **Step 2: Write a trivial passing test**

Create `app/src/test/java/com/breadlab/blackjackadvisor/SmokeTest.kt`:

```kotlin
package com.breadlab.blackjackadvisor

import org.junit.Assert.assertEquals
import org.junit.Test

class SmokeTest {
    @Test fun toolchainRuns() = assertEquals(4, 2 + 2)
}
```

- [ ] **Step 3: Run it and confirm the toolchain works**

Run: `./gradlew :app:testDebugUnitTest --tests "com.breadlab.blackjackadvisor.SmokeTest"`
Expected: `BUILD SUCCESSFUL`, 1 test passed.

**If this fails because the environment has no Android SDK / no network:** stop and report the exact error to the user. Do **not** proceed silently. The plan still holds, but tests will be run by the user at each checkpoint instead of by the executor. Record the decision and continue only with explicit user direction.

- [ ] **Step 4: Commit**

```bash
git add app/build.gradle app/src/test/java/com/breadlab/blackjackadvisor/SmokeTest.kt
git commit -m "test: add JUnit local-test source set + toolchain smoke test

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>"
```

---

## Task 2: Correct `BlackjackStrategy`

**Files:**
- Create: `app/src/test/java/com/breadlab/blackjackadvisor/BlackjackStrategyTest.kt`
- Modify: `app/src/main/java/com/breadlab/blackjackadvisor/BlackjackStrategy.kt`
- Delete: `app/src/test/java/com/breadlab/blackjackadvisor/SmokeTest.kt`

- [ ] **Step 1: Write the failing strategy test (full table + per-bug regressions)**

Create `app/src/test/java/com/breadlab/blackjackadvisor/BlackjackStrategyTest.kt`:

```kotlin
package com.breadlab.blackjackadvisor

import com.breadlab.blackjackadvisor.BlackjackStrategy.Action
import com.breadlab.blackjackadvisor.BlackjackStrategy.getAdvice
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Ruleset: LV Strip, dealer STANDS on soft 17, DAS, late surrender, no RSA.
 * Dealer Ace is supplied as value 1 everywhere in the app (OCR + buttons),
 * so every "vs A" case is tested with dealerUpcard = 1.
 */
class BlackjackStrategyTest {

    private fun act(player: List<Int>, dealer: Int): Action =
        getAdvice(player, dealer).action

    // ---- Contract: INCOMPLETE ----
    @Test fun emptyHand_isIncomplete() =
        assertEquals(Action.INCOMPLETE, act(emptyList(), 5))
    @Test fun oneCard_isIncomplete() =
        assertEquals(Action.INCOMPLETE, act(listOf(10), 5))
    @Test fun noDealer_isIncomplete() =
        assertEquals(Action.INCOMPLETE, act(listOf(10, 6), 0))

    // ---- B1: dealer Ace (value 1) must behave as 11, not as a weak card ----
    @Test fun b1_hard13_vsAce_hits() = assertEquals(Action.HIT, act(listOf(10, 3), 1))
    @Test fun b1_hard14_vsAce_hits() = assertEquals(Action.HIT, act(listOf(10, 4), 1))
    @Test fun b1_hard16_vsAce_surrenders() = assertEquals(Action.SURRENDER, act(listOf(10, 6), 1))
    @Test fun b1_hard10_vsAce_hits() = assertEquals(Action.HIT, act(listOf(6, 4), 1))
    @Test fun b1_pair9_vsAce_stands() = assertEquals(Action.STAND, act(listOf(9, 9), 1))
    @Test fun b1_pair2_vsAce_hits() = assertEquals(Action.HIT, act(listOf(2, 2), 1))
    @Test fun b1_pair7_vsAce_hits() = assertEquals(Action.HIT, act(listOf(7, 7), 1))

    // ---- B2: soft 21 stands (not hit) ----
    @Test fun b2_soft21_stands() = assertEquals(Action.STAND, act(listOf(1, 10), 7))
    @Test fun b2_soft21_threeCards_stands() = assertEquals(Action.STAND, act(listOf(1, 4, 6), 9))
    @Test fun b2_soft20_stands() = assertEquals(Action.STAND, act(listOf(1, 9), 6))

    // ---- B4: soft 19 stands vs 6 (S17, no double) ----
    @Test fun b4_soft19_vs6_stands() = assertEquals(Action.STAND, act(listOf(1, 8), 6))

    // ---- B5: soft 18 vs 2 stands (not double) ----
    @Test fun b5_soft18_vs2_stands() = assertEquals(Action.STAND, act(listOf(1, 7), 2))
    @Test fun soft18_vs3_doubleOrStand() = assertEquals(Action.DOUBLE_OR_STAND, act(listOf(1, 7), 3))
    @Test fun soft18_vs9_hits() = assertEquals(Action.HIT, act(listOf(1, 7), 9))
    @Test fun soft18_vs7_stands() = assertEquals(Action.STAND, act(listOf(1, 7), 7))

    // ---- B7: hard 16 surrenders vs 9 (as well as 10, A) ----
    @Test fun b7_hard16_vs9_surrenders() = assertEquals(Action.SURRENDER, act(listOf(10, 6), 9))
    @Test fun hard16_vs10_surrenders() = assertEquals(Action.SURRENDER, act(listOf(10, 6), 10))
    @Test fun hard16_vs7_hits() = assertEquals(Action.HIT, act(listOf(10, 6), 7))
    @Test fun hard15_vs10_surrenders() = assertEquals(Action.SURRENDER, act(listOf(10, 5), 10))
    @Test fun hard15_vsAce_hits() = assertEquals(Action.HIT, act(listOf(10, 5), 1))

    // ---- B26: hard 11 vs Ace hits under S17 (not double) ----
    @Test fun b26_hard11_vsAce_hits() = assertEquals(Action.HIT, act(listOf(6, 5), 1))
    @Test fun hard11_vs10_doubles() = assertEquals(Action.DOUBLE, act(listOf(6, 5), 10))

    // ---- B13/B14: double/surrender illegal on 3+ cards ----
    @Test fun b14_threeCardTen_doesNotDouble() = assertEquals(Action.HIT, act(listOf(5, 3, 2), 7))
    @Test fun b13_threeCardSixteen_doesNotSurrender() = assertEquals(Action.HIT, act(listOf(5, 6, 5), 10))
    @Test fun b14_threeCardSoft18_vs4_doesNotDouble() = assertEquals(Action.STAND, act(listOf(1, 2, 5), 4))

    // ---- Invariant: 8,8 always splits, never surrenders ----
    @Test fun pair8_vs10_splits_neverSurrender() = assertEquals(Action.SPLIT, act(listOf(8, 8), 10))
    @Test fun pair8_vsAce_splits() = assertEquals(Action.SPLIT, act(listOf(8, 8), 1))

    // ---- Pairs (DAS), Aces, tens ----
    @Test fun pairAces_split() = assertEquals(Action.SPLIT, act(listOf(1, 1), 6))
    @Test fun pairTens_stand() = assertEquals(Action.STAND, act(listOf(10, 10), 6))
    @Test fun pair5_playsAsHard10_double() = assertEquals(Action.DOUBLE, act(listOf(5, 5), 8))
    @Test fun pair4_vs5_splits() = assertEquals(Action.SPLIT, act(listOf(4, 4), 5))
    @Test fun pair4_vs8_doesNotSplit() = assertEquals(Action.HIT, act(listOf(4, 4), 8))
    @Test fun pair6_vs2_splits_das() = assertEquals(Action.SPLIT, act(listOf(6, 6), 2))
    @Test fun pair9_vs7_stands() = assertEquals(Action.STAND, act(listOf(9, 9), 7))
    @Test fun pair9_vs8_splits() = assertEquals(Action.SPLIT, act(listOf(9, 9), 8))

    // ---- Full hard-total table sweep (dealer 2..9,10, and 1==Ace) ----
    @Test fun hardTotalTableSweep() {
        // total to (expectedAction by dealer upcard). Dealer key: 2..10, and 1 for Ace.
        val expect: Map<Int, Map<Int, Action>> = mapOf(
            8  to allBut(Action.HIT),
            9  to dealerMap(h = Action.HIT, double = 3..6),
            10 to dealerMap(h = Action.HIT, double = 2..9),
            12 to dealerMap(h = Action.HIT, stand = 4..6),
            13 to dealerMap(h = Action.HIT, stand = 2..6),
            14 to dealerMap(h = Action.HIT, stand = 2..6),
            17 to allBut(Action.STAND),
        )
        for ((total, byDealer) in expect) {
            for (d in dealerCards()) {
                val player = handForHardTotal(total)
                assertEquals("hard $total vs $d", byDealer.getValue(d), act(player, d))
            }
        }
    }

    // helpers ---------------------------------------------------------------
    private fun dealerCards() = listOf(2, 3, 4, 5, 6, 7, 8, 9, 10, 1) // 1 == Ace
    private fun allBut(a: Action): Map<Int, Action> = dealerCards().associateWith { a }
    private fun dealerMap(h: Action, double: IntRange? = null, stand: IntRange? = null): Map<Int, Action> =
        dealerCards().associateWith { d ->
            val v = if (d == 1) 11 else d
            when {
                double != null && v in double -> Action.DOUBLE
                stand != null && v in stand -> Action.STAND
                else -> h
            }
        }
    private fun handForHardTotal(total: Int): List<Int> = when {
        total <= 11 -> listOf(total - 2, 2)          // e.g. 10 -> [8,2]
        else -> listOf(10, total - 10)               // e.g. 16 -> [10,6]
    }
}
```

- [ ] **Step 2: Run the test, verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "com.breadlab.blackjackadvisor.BlackjackStrategyTest"`
Expected: FAIL — `INCOMPLETE` does not exist yet (compile error), and B1/B2/B26 assertions fail against the old logic.

- [ ] **Step 3: Replace `BlackjackStrategy.kt` with the corrected engine**

Replace the entire contents of `app/src/main/java/com/breadlab/blackjackadvisor/BlackjackStrategy.kt` with:

```kotlin
package com.breadlab.blackjackadvisor

/**
 * Complete Basic Strategy Engine — Las Vegas Strip rules:
 *   - Dealer STANDS on soft 17 (S17)
 *   - Double after split allowed (DAS)
 *   - Late surrender allowed
 *   - Re-split aces not allowed
 *
 * Standard total-dependent basic strategy (no composition-dependent plays).
 * Dealer Ace arrives as value 1 (OCR + manual buttons) and is normalized to
 * 11 once, at the single entry point. See the design spec for the bug history.
 */
object BlackjackStrategy {

    enum class Action(val label: String, val emoji: String, val colorHex: String) {
        HIT("HIT", "👊", "#E74C3C"),
        STAND("STAND", "✋", "#27AE60"),
        DOUBLE("DOUBLE DOWN", "💰", "#F39C12"),
        SPLIT("SPLIT", "✂️", "#9B59B6"),
        SURRENDER("SURRENDER", "🏳️", "#95A5A6"),
        DOUBLE_OR_HIT("DOUBLE / HIT", "💰", "#E67E22"),
        DOUBLE_OR_STAND("DOUBLE / STAND", "💰", "#E67E22"),
        INCOMPLETE("WAITING", "🃏", "#1A1A2E"),
    }

    data class StrategyResult(
        val action: Action,
        val reason: String,
        val playerTotal: Int,
        val dealerUpcard: Int,
        val isSoft: Boolean,
        val isPair: Boolean
    )

    /**
     * @param playerCards card values (1=Ace, 2-9, 10=Ten/Face)
     * @param dealerUpcard dealer's visible card (1=Ace, 2-10)
     */
    fun getAdvice(playerCards: List<Int>, dealerUpcard: Int): StrategyResult {
        if (playerCards.size < 2 || dealerUpcard <= 0) {
            return StrategyResult(
                Action.INCOMPLETE, "Enter your cards and the dealer's upcard",
                0, dealerUpcard, false, false
            )
        }

        // B1: normalize dealer Ace 1 -> 11 once, here. All thresholds below
        // are written for Ace == 11.
        val dealer = if (dealerUpcard == 1) 11 else dealerUpcard
        val twoCards = playerCards.size == 2

        if (twoCards && playerCards[0] == playerCards[1]) {
            val pairValue = if (playerCards[0] == 1) 11 else playerCards[0]
            val pair = getPairStrategy(pairValue, dealer)
            if (pair != null) return legalize(pair, twoCards)
        }

        val (total, isSoft) = calculateHand(playerCards)
        val raw = if (isSoft) getSoftStrategy(total, dealer) else getHardStrategy(total, dealer)
        return legalize(raw, twoCards)
    }

    /**
     * B13/B14: DOUBLE and SURRENDER are only legal on the initial two cards.
     * On 3+ card hands collapse to the standard fallback. SPLIT only ever
     * comes from the 2-card pair path, so it needs no gate.
     */
    private fun legalize(r: StrategyResult, twoCards: Boolean): StrategyResult {
        if (twoCards) return r
        val collapsed = when (r.action) {
            Action.DOUBLE, Action.DOUBLE_OR_HIT -> Action.HIT
            Action.DOUBLE_OR_STAND -> Action.STAND
            Action.SURRENDER -> Action.HIT
            else -> return r
        }
        return r.copy(
            action = collapsed,
            reason = r.reason + " — (3+ cards: can't double/surrender, fallback shown)"
        )
    }

    private fun calculateHand(cards: List<Int>): Pair<Int, Boolean> {
        var total = 0
        var aces = 0
        for (card in cards) {
            if (card == 1) { aces++; total += 11 } else total += minOf(card, 10)
        }
        while (total > 21 && aces > 0) { total -= 10; aces-- }
        val isSoft = aces > 0 && total <= 21   // an ace still valued 11 => soft
        return Pair(total, isSoft)
    }

    private fun getPairStrategy(pairValue: Int, dealer: Int): StrategyResult? {
        val action = when (pairValue) {
            11 -> Action.SPLIT                                   // A,A always
            10 -> null                                           // never (stand 20)
            9  -> if (dealer == 7 || dealer == 10 || dealer == 11) null else Action.SPLIT
            8  -> Action.SPLIT                                    // always; never surrender 8,8
            7  -> if (dealer in 2..7) Action.SPLIT else null
            6  -> if (dealer in 2..6) Action.SPLIT else null      // DAS includes 2
            5  -> null                                           // play as hard 10
            4  -> if (dealer in 5..6) Action.SPLIT else null      // DAS only
            3  -> if (dealer in 2..7) Action.SPLIT else null      // DAS
            2  -> if (dealer in 2..7) Action.SPLIT else null      // DAS
            else -> null
        } ?: return null

        val reason = when (pairValue) {
            11 -> "Always split Aces"
            8  -> "Always split 8s (never surrender 8,8)"
            9  -> "Split 9s except vs 7, 10, or Ace (then stand 18)"
            7  -> "Split 7s vs dealer 2-7"
            6  -> "Split 6s vs dealer 2-6 (DAS)"
            4  -> "Split 4s only vs dealer 5-6 (DAS)"
            3  -> "Split 3s vs dealer 2-7 (DAS)"
            2  -> "Split 2s vs dealer 2-7 (DAS)"
            else -> "Split ${pairValue}s"
        }
        return StrategyResult(action, reason, pairValue * 2, dealer, false, true)
    }

    private fun getSoftStrategy(total: Int, dealer: Int): StrategyResult {
        val (action, reason) = when {
            total >= 20 -> Action.STAND to "Soft $total — stand"                       // B2 (20 & 21)
            total == 19 -> Action.STAND to "Soft 19 — stand (S17: no double vs 6)"      // B4
            total == 18 -> when (dealer) {
                in 3..6 -> Action.DOUBLE_OR_STAND to "Soft 18 vs $dealer — double, else stand"
                2, 7, 8 -> Action.STAND to "Soft 18 vs $dealer — stand"                 // B5
                else    -> Action.HIT to "Soft 18 vs $dealer — hit"                     // 9,10,A
            }
            total == 17 -> when (dealer) {
                in 3..6 -> Action.DOUBLE_OR_HIT to "Soft 17 vs $dealer — double, else hit"
                else    -> Action.HIT to "Soft 17 — hit"
            }
            total == 16 -> when (dealer) {
                in 4..6 -> Action.DOUBLE_OR_HIT to "Soft 16 vs $dealer — double, else hit"
                else    -> Action.HIT to "Soft 16 — hit"
            }
            total == 15 -> when (dealer) {
                in 4..6 -> Action.DOUBLE_OR_HIT to "Soft 15 vs $dealer — double, else hit"
                else    -> Action.HIT to "Soft 15 — hit"
            }
            total == 14 -> when (dealer) {
                5, 6 -> Action.DOUBLE_OR_HIT to "Soft 14 vs $dealer — double, else hit"
                else -> Action.HIT to "Soft 14 — hit"
            }
            total == 13 -> when (dealer) {
                5, 6 -> Action.DOUBLE_OR_HIT to "Soft 13 vs $dealer — double, else hit"
                else -> Action.HIT to "Soft 13 — hit"
            }
            else -> Action.HIT to "Soft hand — hit"
        }
        return StrategyResult(action, reason, total, dealer, true, false)
    }

    private fun getHardStrategy(total: Int, dealer: Int): StrategyResult {
        val (action, reason) = when {
            total >= 17 -> Action.STAND to "Hard $total — always stand at 17+"
            total == 16 -> when (dealer) {
                in 2..6   -> Action.STAND to "Hard 16 vs $dealer — stand, dealer likely busts"
                9, 10, 11 -> Action.SURRENDER to "Hard 16 vs $dealer — surrender (else hit)"   // B7 + B1
                else      -> Action.HIT to "Hard 16 vs $dealer — hit"                          // 7,8
            }
            total == 15 -> when (dealer) {
                in 2..6 -> Action.STAND to "Hard 15 vs $dealer — stand"
                10      -> Action.SURRENDER to "Hard 15 vs 10 — surrender (else hit)"
                else    -> Action.HIT to "Hard 15 vs $dealer — hit"
            }
            total in 13..14 ->
                if (dealer in 2..6) Action.STAND to "Hard $total vs $dealer — stand, dealer likely busts"
                else Action.HIT to "Hard $total vs $dealer — hit"                              // B1: vs A hits
            total == 12 -> when (dealer) {
                in 4..6 -> Action.STAND to "Hard 12 vs $dealer — stand"
                else    -> Action.HIT to "Hard 12 vs $dealer — hit"
            }
            total == 11 ->
                if (dealer == 11) Action.HIT to "Hard 11 vs Ace — hit (S17)"                   // B26
                else Action.DOUBLE to "Hard 11 — double down"
            total == 10 ->
                if (dealer in 2..9) Action.DOUBLE to "Hard 10 vs $dealer — double down"
                else Action.HIT to "Hard 10 vs $dealer — hit"                                  // B1: vs 10/A
            total == 9 ->
                if (dealer in 3..6) Action.DOUBLE to "Hard 9 vs $dealer — double down"
                else Action.HIT to "Hard 9 — hit"
            else -> Action.HIT to "Hard $total — hit, can't bust"                              // <= 8
        }
        return StrategyResult(action, reason, total, dealer, false, false)
    }

    fun cardDisplayName(value: Int): String = when (value) {
        1 -> "A"
        10 -> "10"
        11 -> "J"
        12 -> "Q"
        13 -> "K"
        else -> value.toString()
    }
}
```

(This deletes the dead `normalized` local, the unused `normalizeCard`, and the unused `Action.SPLIT_OR_HIT` — B3.)

- [ ] **Step 4: Delete the smoke test (its purpose is served)**

```bash
git rm app/src/test/java/com/breadlab/blackjackadvisor/SmokeTest.kt
```

- [ ] **Step 5: Run the strategy tests, verify they pass**

Run: `./gradlew :app:testDebugUnitTest --tests "com.breadlab.blackjackadvisor.BlackjackStrategyTest"`
Expected: PASS — all tests green.

- [ ] **Step 6: Verify nothing else referenced removed symbols**

Run: `grep -rn "normalizeCard\|SPLIT_OR_HIT" app/src/main`
Expected: no output. If any hit appears, replace the call site (the only possible consumer is `FloatingOverlayService`, which uses `cardDisplayName`/`getAdvice`/`Action` — none of the removed symbols).

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/breadlab/blackjackadvisor/BlackjackStrategy.kt \
        app/src/test/java/com/breadlab/blackjackadvisor/BlackjackStrategyTest.kt
git rm --cached app/src/test/java/com/breadlab/blackjackadvisor/SmokeTest.kt 2>/dev/null; true
git commit -m "fix: correct LV Strip S17 basic strategy (B1,B2,B4,B5,B7,B26,B13/14,B3)

Dealer Ace normalized to 11; soft 19/20/21 + soft 18 vs 2 fixed; 16 surrenders
vs 9/10/A; 11 hits vs A under S17; double/surrender gated to 2-card hands;
Action.INCOMPLETE replaces scattered guards; dead code removed.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>"
```

---

## Task 3: CHECKPOINT — hard stop

- [ ] **Step 1: Produce the verification evidence**

Run and capture full output: `./gradlew :app:testDebugUnitTest --tests "com.breadlab.blackjackadvisor.BlackjackStrategyTest" -i | tail -n 30`

- [ ] **Step 2: Report to the user and STOP**

Present the observed test output verbatim. State: corrected advice is now landed and self-contained on branch `blackjack-strategy-fixes-and-refactor`. **Do not begin Task 4.** Ask the user (or their friend) to confirm the corrected advice is right. Only proceed past this point on explicit user sign-off. If the toolchain could not run (Task 1 Step 3), present the test code instead and ask the user to run it.

---

## Task 4: Extract `Bankroll`

**Files:**
- Create: `app/src/main/java/com/breadlab/blackjackadvisor/Bankroll.kt`
- Create: `app/src/test/java/com/breadlab/blackjackadvisor/BankrollTest.kt`
- Modify: `app/src/main/java/com/breadlab/blackjackadvisor/FloatingOverlayService.kt`

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/com/breadlab/blackjackadvisor/BankrollTest.kt`:

```kotlin
package com.breadlab.blackjackadvisor

import org.junit.Assert.assertEquals
import org.junit.Test

class BankrollTest {
    @Test fun belowOne_floorsToOne() = assertEquals(1.0, Bankroll.recommendedBet(50.0), 0.0)
    @Test fun under5_roundsToWhole() = assertEquals(3.0, Bankroll.recommendedBet(250.0), 0.0)
    @Test fun under25_step5() = assertEquals(10.0, Bankroll.recommendedBet(1000.0), 0.0)
    @Test fun under100_step10() = assertEquals(50.0, Bankroll.recommendedBet(5000.0), 0.0)
    @Test fun under1000_step25() = assertEquals(250.0, Bankroll.recommendedBet(25000.0), 0.0)
    @Test fun large_step100() = assertEquals(1900.0, Bankroll.recommendedBet(189994.26), 0.0)
    @Test fun wholeMoney_noDecimals() = assertEquals("1,000", Bankroll.formatMoney(1000.0))
    @Test fun fractionalMoney_twoDecimals() = assertEquals("12.50", Bankroll.formatMoney(12.5))
}
```

- [ ] **Step 2: Run, verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "com.breadlab.blackjackadvisor.BankrollTest"`
Expected: FAIL — `Bankroll` unresolved.

- [ ] **Step 3: Create `Bankroll.kt`**

```kotlin
package com.breadlab.blackjackadvisor

import kotlin.math.round

/** Flat-bet sizing + money formatting. Pure; JVM-tested. */
object Bankroll {

    /** 1% of bankroll, snapped to a clean step (1, 5, 10, 25, 100…). */
    fun recommendedBet(balance: Double): Double {
        val raw = balance * 0.01
        return when {
            raw < 1.0    -> 1.0
            raw < 5.0    -> round(raw)
            raw < 25.0   -> (round(raw / 5.0) * 5.0).coerceAtLeast(5.0)
            raw < 100.0  -> (round(raw / 10.0) * 10.0).coerceAtLeast(10.0)
            raw < 1000.0 -> (round(raw / 25.0) * 25.0).coerceAtLeast(25.0)
            else         -> (round(raw / 100.0) * 100.0).coerceAtLeast(100.0)
        }
    }

    fun formatMoney(amount: Double): String =
        if (amount % 1.0 == 0.0) String.format("%,.0f", amount)
        else String.format("%,.2f", amount)
}
```

- [ ] **Step 4: Run, verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "com.breadlab.blackjackadvisor.BankrollTest"`
Expected: PASS.

- [ ] **Step 5: Delete the duplicated logic from `FloatingOverlayService.kt`**

In `app/src/main/java/com/breadlab/blackjackadvisor/FloatingOverlayService.kt`, delete the `recommendedBet(...)` function and the `formatMoney(...)` function (the two private helpers, ~lines 487-506). In `updateBankrollDisplay()` change the two call sites:

```kotlin
        balanceText?.text = "💰 Balance: ${Bankroll.formatMoney(balance)}"
        betText?.text = "🎯 Suggested bet: ${Bankroll.formatMoney(Bankroll.recommendedBet(balance))}"
```

- [ ] **Step 6: Confirm nothing else used the old helpers**

Run: `grep -rn "recommendedBet\|formatMoney" app/src/main`
Expected: only `Bankroll.kt` definitions and the two `Bankroll.`-qualified call sites in `FloatingOverlayService.kt`.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/breadlab/blackjackadvisor/Bankroll.kt \
        app/src/test/java/com/breadlab/blackjackadvisor/BankrollTest.kt \
        app/src/main/java/com/breadlab/blackjackadvisor/FloatingOverlayService.kt
git commit -m "refactor: extract pure Bankroll from FloatingOverlayService + tests

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>"
```

---

## Task 5: Extract `CalibrationMath` + `CalibrationStore`

**Files:**
- Create: `app/src/main/java/com/breadlab/blackjackadvisor/Calibration.kt`
- Create: `app/src/test/java/com/breadlab/blackjackadvisor/CalibrationMathTest.kt`
- Modify: `app/src/main/java/com/breadlab/blackjackadvisor/CardDetector.kt`
- Modify: `app/src/main/java/com/breadlab/blackjackadvisor/FloatingOverlayService.kt`

- [ ] **Step 1: Write the failing test (pure math only)**

Create `app/src/test/java/com/breadlab/blackjackadvisor/CalibrationMathTest.kt`:

```kotlin
package com.breadlab.blackjackadvisor

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CalibrationMathTest {
    @Test fun invalidWhenNegative() =
        assertNull(CalibrationMath.toPixels(-1f, 0.1f, 0.5f, 0.2f, 1000, 2000))
    @Test fun invalidWhenRightNotPastLeft() =
        assertNull(CalibrationMath.toPixels(0.5f, 0.1f, 0.5f, 0.2f, 1000, 2000))
    @Test fun invalidWhenBottomNotPastTop() =
        assertNull(CalibrationMath.toPixels(0.1f, 0.5f, 0.5f, 0.5f, 1000, 2000))
    @Test fun validConvertsFractionsToPixels() =
        assertArrayEquals(
            intArrayOf(100, 400, 500, 600),
            CalibrationMath.toPixels(0.1f, 0.2f, 0.5f, 0.3f, 1000, 2000)
        )
}
```

- [ ] **Step 2: Run, verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "com.breadlab.blackjackadvisor.CalibrationMathTest"`
Expected: FAIL — `CalibrationMath` unresolved.

- [ ] **Step 3: Create `Calibration.kt` (pure math + thin store)**

```kotlin
package com.breadlab.blackjackadvisor

import android.content.Context
import android.graphics.Rect

/** Pure validity + fraction→pixel conversion. JVM-tested; no Android types. */
object CalibrationMath {
    /** Returns [left,top,right,bottom] in pixels, or null if the rect is invalid. */
    fun toPixels(l: Float, t: Float, r: Float, b: Float, w: Int, h: Int): IntArray? {
        if (l < 0f || t < 0f || r <= l || b <= t) return null
        return intArrayOf((w * l).toInt(), (h * t).toInt(), (w * r).toInt(), (h * b).toInt())
    }
}

/**
 * Single owner of the calibration SharedPreferences (3 rects as fractions).
 * Thin Android shell over [CalibrationMath]. Read-through (never caches) so
 * re-calibration takes effect on the next captured frame.
 */
class CalibrationStore(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** Saved fractions for a zone, or null if absent/invalid. [l,t,r,b]. */
    fun fractions(zone: Zone): FloatArray? {
        val k = zone.keys
        val l = prefs.getFloat(k[0], -1f); val t = prefs.getFloat(k[1], -1f)
        val r = prefs.getFloat(k[2], -1f); val b = prefs.getFloat(k[3], -1f)
        if (l < 0f || t < 0f || r <= l || b <= t) return null
        return floatArrayOf(l, t, r, b)
    }

    /** Pixel Rect for a zone at the given screen size, or null if uncalibrated. */
    fun rect(zone: Zone, w: Int, h: Int): Rect? {
        val f = fractions(zone) ?: return null
        val p = CalibrationMath.toPixels(f[0], f[1], f[2], f[3], w, h) ?: return null
        return Rect(p[0], p[1], p[2], p[3])
    }

    fun save(dealer: FloatArray, player: FloatArray, balance: FloatArray) {
        prefs.edit().apply {
            putZone(Zone.DEALER, dealer); putZone(Zone.PLAYER, player); putZone(Zone.BALANCE, balance)
        }.apply()
    }

    fun clear() {
        prefs.edit().apply {
            Zone.values().forEach { z -> z.keys.forEach { remove(it) } }
        }.apply()
    }

    private fun android.content.SharedPreferences.Editor.putZone(z: Zone, f: FloatArray) {
        putFloat(z.keys[0], f[0]); putFloat(z.keys[1], f[1])
        putFloat(z.keys[2], f[2]); putFloat(z.keys[3], f[3])
    }

    enum class Zone(val keys: Array<String>) {
        DEALER(arrayOf("cal_dealer_left", "cal_dealer_top", "cal_dealer_right", "cal_dealer_bottom")),
        PLAYER(arrayOf("cal_player_left", "cal_player_top", "cal_player_right", "cal_player_bottom")),
        BALANCE(arrayOf("cal_balance_left", "cal_balance_top", "cal_balance_right", "cal_balance_bottom")),
    }

    companion object { const val PREFS_NAME = "bja_prefs" }
}
```

- [ ] **Step 4: Run, verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "com.breadlab.blackjackadvisor.CalibrationMathTest"`
Expected: PASS.

- [ ] **Step 5: Route `CardDetector` through `CalibrationStore`**

In `app/src/main/java/com/breadlab/blackjackadvisor/CardDetector.kt`: delete the `PREFS_NAME` and all `KEY_*` constants from the `companion object`, and delete `loadCalibratedZones(...)` and `readRect(...)`. Replace the zone-loading line in `detectCards` (`val (dealerZone, playerZone, balanceZone) = loadCalibratedZones(screenW, screenH)`) with:

```kotlin
        val store = context?.let { CalibrationStore(it) }
        val dealerZone = store?.rect(CalibrationStore.Zone.DEALER, screenW, screenH)
        val playerZone = store?.rect(CalibrationStore.Zone.PLAYER, screenW, screenH)
        val balanceZone = store?.rect(CalibrationStore.Zone.BALANCE, screenW, screenH)
```

(Keep the existing default-zone fallback logic that builds `dz`/`pz` from `DEFAULT_*` when the calibrated rect is null, and `bz = balanceZone`.)

- [ ] **Step 6: Route `FloatingOverlayService` calibration through `CalibrationStore`**

In `startCalibration()`: replace the `prefs` load block (the 12 `prefs.getFloat(CardDetector.KEY_*)` reads) with `CalibrationStore` reads:

```kotlin
        val store = CalibrationStore(this)
        val d = store.fractions(CalibrationStore.Zone.DEALER)
        val p = store.fractions(CalibrationStore.Zone.PLAYER)
        val b = store.fractions(CalibrationStore.Zone.BALANCE)
        if (d != null && p != null) {
            val bb = b ?: floatArrayOf(0.15f, 0.06f, 0.60f, 0.11f)
            cv.post { cv.setRects(d[0], d[1], d[2], d[3], p[0], p[1], p[2], p[3], bb[0], bb[1], bb[2], bb[3]) }
        }
```

Replace the Save button body with:

```kotlin
            store.save(cv.getDealerFractions(), cv.getPlayerFractions(), cv.getBalanceFractions())
            stopCalibration(); updateScanStatus("Calibrated — scanning…")
```

Replace the Reset button body with:

```kotlin
            store.clear(); stopCalibration(); updateScanStatus("Calibration cleared — using defaults")
```

- [ ] **Step 7: Confirm the old keys are gone**

Run: `grep -rn "KEY_DEALER_LEFT\|loadCalibratedZones\|readRect\|CardDetector.PREFS_NAME" app/src/main`
Expected: no output.

- [ ] **Step 8: Commit**

```bash
git add app/src/main/java/com/breadlab/blackjackadvisor/Calibration.kt \
        app/src/test/java/com/breadlab/blackjackadvisor/CalibrationMathTest.kt \
        app/src/main/java/com/breadlab/blackjackadvisor/CardDetector.kt \
        app/src/main/java/com/breadlab/blackjackadvisor/FloatingOverlayService.kt
git commit -m "refactor: single CalibrationStore + pure CalibrationMath; dedupe prefs I/O

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>"
```

---

## Task 6: Extract `HandTracker` (incl. B25)

**Files:**
- Create: `app/src/main/java/com/breadlab/blackjackadvisor/HandTracker.kt`
- Create: `app/src/test/java/com/breadlab/blackjackadvisor/HandTrackerTest.kt`
- Modify: `app/src/main/java/com/breadlab/blackjackadvisor/FloatingOverlayService.kt`

- [ ] **Step 1: Write the failing characterization test (incl. the B25 change)**

Create `app/src/test/java/com/breadlab/blackjackadvisor/HandTrackerTest.kt`:

```kotlin
package com.breadlab.blackjackadvisor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HandTrackerTest {
    private fun tracker() = HandTracker().apply { setAutoDetect(true) }

    @Test fun stickyKeepsCardsWhenFrameDrops() {
        val t = tracker()
        t.onFrame(listOf(10, 6), 7)
        t.onFrame(emptyList(), 7)               // OCR missed a frame
        assertEquals(listOf(10, 6), t.playerCards)
        assertEquals(7, t.dealerCard)
    }

    @Test fun stickyGrowsWhenPlayerHits() {
        val t = tracker()
        t.onFrame(listOf(10, 6), 7)
        t.onFrame(listOf(10, 6, 5), 7)
        assertEquals(listOf(10, 6, 5), t.playerCards)
    }

    @Test fun newHandNeedsTwoConsecutiveDealerChangeFrames() {
        val t = tracker()
        t.onFrame(listOf(10, 6), 7)
        t.onFrame(listOf(9), 5)                  // 1st differing dealer — not yet
        assertEquals(listOf(10, 6), t.playerCards)
        t.onFrame(listOf(9), 5)                  // 2nd consecutive — commit
        assertEquals(listOf(9), t.playerCards)
        assertEquals(5, t.dealerCard)
    }

    @Test fun b25_confusableDealerFlipDoesNotResetHand() {
        val t = tracker()
        t.onFrame(listOf(10, 6), 8)              // dealer 8
        t.onFrame(listOf(10, 6), 3)              // 8<->3 are a confusable pair
        t.onFrame(listOf(10, 6), 3)
        t.onFrame(listOf(10, 6), 3)
        assertEquals(listOf(10, 6), t.playerCards)   // hand preserved
        assertEquals(8, t.dealerCard)                // dealer unchanged
    }

    @Test fun ambiguityRaisedOncePerPairPerHand() {
        val t = tracker()
        t.onFrame(listOf(4), 7)
        val u = t.onFrame(listOf(1), 7)              // 1 vs 4 confusable, same count
        assertEquals(1 to 4, u.ambiguity!!.let { it.stored to it.detectedAlt })
        t.resolveAmbiguity(4)
        assertNull(t.onFrame(listOf(1), 7).ambiguity) // resolved, not re-raised
        assertEquals(listOf(4), t.playerCards)
    }

    @Test fun manualOpsAndReset() {
        val t = HandTracker()                        // manual mode
        t.addPlayerCard(10); t.addPlayerCard(6); t.setDealer(9)
        assertEquals(listOf(10, 6), t.playerCards)
        t.undo(); assertEquals(listOf(10), t.playerCards)
        t.reset()
        assertTrue(t.playerCards.isEmpty()); assertEquals(0, t.dealerCard)
    }
}
```

- [ ] **Step 2: Run, verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "com.breadlab.blackjackadvisor.HandTrackerTest"`
Expected: FAIL — `HandTracker` unresolved.

- [ ] **Step 3: Create `HandTracker.kt`**

This is the exact logic from `FloatingOverlayService.cardDetectionReceiver` + the ambiguity helpers, made pure. The B25 guard is the one intentional behavior change (`isConfusable(old,new)` short-circuits the dealer-change path).

```kotlin
package com.breadlab.blackjackadvisor

/**
 * Pure detection state machine. MAIN-THREAD-CONFINED by contract — the Service
 * only ever calls this from the broadcast receiver / UI taps; no locking.
 *
 * Characterizes the original FloatingOverlayService behavior, with ONE
 * deliberate change: B25 — a confusable-pair dealer flip is treated as an OCR
 * misread, not a new hand.
 */
class HandTracker {

    val playerCards = mutableListOf<Int>()
    var dealerCard: Int = 0
        private set

    private var autoDetect = false
    private var pendingNewDealer = 0
    private var pendingNewDealerFrames = 0
    private val newHandConfirmFrames = 2

    private var pendingAmbiguity: Ambiguity? = null
    private val resolvedConfusables = mutableSetOf<Set<Int>>()
    private val confusablePairs = setOf(
        setOf(1, 4), setOf(3, 8), setOf(6, 8), setOf(6, 9),
        setOf(7, 10), setOf(5, 6), setOf(5, 8), setOf(2, 7)
    )

    data class Ambiguity(val stored: Int, val detectedAlt: Int)

    /** What the Service should re-render after a frame / op. */
    data class Update(
        val cardsChanged: Boolean = false,
        val newHand: Boolean = false,
        val ambiguity: Ambiguity? = null,
        val ambiguityCleared: Boolean = false
    )

    fun setAutoDetect(on: Boolean) { autoDetect = on }
    fun isAutoDetect() = autoDetect

    fun reset(): Update {
        playerCards.clear(); dealerCard = 0
        pendingNewDealer = 0; pendingNewDealerFrames = 0
        resolvedConfusables.clear()
        val had = pendingAmbiguity != null
        pendingAmbiguity = null
        return Update(cardsChanged = true, ambiguityCleared = had)
    }

    fun addPlayerCard(v: Int): Update {
        if (!autoDetect && playerCards.size < 8) { playerCards.add(v); return Update(cardsChanged = true) }
        return Update()
    }

    fun setDealer(v: Int): Update {
        if (!autoDetect) { dealerCard = v; return Update(cardsChanged = true) }
        return Update()
    }

    fun undo(): Update {
        if (!autoDetect && playerCards.isNotEmpty()) {
            playerCards.removeAt(playerCards.size - 1); return Update(cardsChanged = true)
        }
        return Update()
    }

    fun currentAmbiguity(): Ambiguity? = pendingAmbiguity

    fun resolveAmbiguity(chosenValue: Int): Update {
        val amb = pendingAmbiguity ?: return Update()
        val a = amb.stored; val b = amb.detectedAlt
        val other = if (chosenValue == a) b else a
        val idx = playerCards.indexOf(other)
        if (idx >= 0) playerCards[idx] = chosenValue
        resolvedConfusables.add(setOf(a, b))
        pendingAmbiguity = null
        return Update(cardsChanged = true, ambiguityCleared = true)
    }

    private fun isConfusable(x: Int, y: Int) = setOf(x, y) in confusablePairs

    /** Feed one detection frame. Service must only call this in auto-detect. */
    fun onFrame(detected: List<Int>, dealer: Int): Update {
        // Auto-new-hand on a sustained, NON-confusable dealer-rank change.
        val dealerChanged = dealer > 0 && dealerCard > 0 && dealer != dealerCard
        if (dealerChanged) {
            // B25: a confusable flip (e.g. 8<->3) is almost certainly an OCR
            // misread, not a real new deal — ignore it entirely.
            if (isConfusable(dealer, dealerCard)) {
                pendingNewDealer = 0; pendingNewDealerFrames = 0
                return Update()
            }
            if (dealer == pendingNewDealer) {
                pendingNewDealerFrames++
                if (pendingNewDealerFrames >= newHandConfirmFrames) {
                    playerCards.clear(); playerCards.addAll(detected)
                    dealerCard = dealer
                    pendingNewDealer = 0; pendingNewDealerFrames = 0
                    val had = pendingAmbiguity != null
                    resolvedConfusables.clear(); pendingAmbiguity = null
                    return Update(cardsChanged = true, newHand = true, ambiguityCleared = had)
                }
            } else {
                pendingNewDealer = dealer; pendingNewDealerFrames = 1
            }
            return Update()
        }
        pendingNewDealer = 0; pendingNewDealerFrames = 0

        var changed = false
        var ambiguityCleared = false
        if (detected.isNotEmpty() && detected.size > playerCards.size) {
            playerCards.clear(); playerCards.addAll(detected)
            changed = true
            if (pendingAmbiguity != null) { pendingAmbiguity = null; ambiguityCleared = true }
        }
        if (dealer > 0 && dealerCard == 0) { dealerCard = dealer; changed = true }

        val amb = checkAmbiguity(detected)
        return Update(cardsChanged = changed, ambiguity = amb, ambiguityCleared = ambiguityCleared)
    }

    private fun checkAmbiguity(detected: List<Int>): Ambiguity? {
        if (pendingAmbiguity != null) return pendingAmbiguity
        if (detected.size != playerCards.size || playerCards.isEmpty()) return null
        val stored = playerCards.groupingBy { it }.eachCount()
        val det = detected.groupingBy { it }.eachCount()
        if (stored == det) return null
        val storedOnly = stored.keys - det.keys
        val detOnly = det.keys - stored.keys
        if (storedOnly.size != 1 || detOnly.size != 1) return null
        val a = storedOnly.first(); val b = detOnly.first()
        val pair = setOf(a, b)
        if (pair in confusablePairs && pair !in resolvedConfusables) {
            pendingAmbiguity = Ambiguity(a, b)
            return pendingAmbiguity
        }
        return null
    }
}
```

- [ ] **Step 4: Run, verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "com.breadlab.blackjackadvisor.HandTrackerTest"`
Expected: PASS.

- [ ] **Step 5: Make `FloatingOverlayService` delegate to `HandTracker`**

In `FloatingOverlayService.kt`:

1. Delete the fields `playerCards`, `dealerCard`, `pendingNewDealer`, `pendingNewDealerFrames`, `newHandConfirmFrames`, `pendingAmbiguity`, `resolvedConfusables`, `confusablePairs`, and the helper functions `checkAmbiguity`, `resolveAmbiguity`, `clearAmbiguity`. Add one field: `private val tracker = HandTracker()`.
2. Replace `isAutoDetect` usages: reads become `tracker.isAutoDetect()`; the toggle sets `tracker.setAutoDetect(...)`.
3. Replace the `cardDetectionReceiver.onReceive` body with (keeping the diagnostic readout and the `isExpanded` gate in the Service — the gate must NOT move into `HandTracker`):

```kotlin
        override fun onReceive(context: Context?, intent: Intent?) {
            val frame = intent?.getIntExtra(ScreenCaptureService.EXTRA_FRAME_COUNT, 0) ?: 0
            val rawDealer = intent?.getStringArrayListExtra(ScreenCaptureService.EXTRA_RAW_DEALER) ?: arrayListOf()
            val rawPlayer = intent?.getStringArrayListExtra(ScreenCaptureService.EXTRA_RAW_PLAYER) ?: arrayListOf()
            val detected = intent?.getIntegerArrayListExtra(ScreenCaptureService.EXTRA_PLAYER_CARDS) ?: arrayListOf()
            val dealer = intent?.getIntExtra(ScreenCaptureService.EXTRA_DEALER_CARD, 0) ?: 0
            val dStr = if (rawDealer.isEmpty()) "—" else rawDealer.take(5).joinToString(",")
            val pStr = if (rawPlayer.isEmpty()) "—" else rawPlayer.take(5).joinToString(",")
            updateScanStatus("f$frame d:$dealer p:[${detected.joinToString(",")}]  D[$dStr] P[$pStr]")

            val rawBalance = intent?.getDoubleExtra(ScreenCaptureService.EXTRA_BALANCE, -1.0) ?: -1.0
            if (rawBalance >= 0) { lastBalance = rawBalance; updateBankrollDisplay() }

            if (isExpanded && !tracker.isAutoDetect()) return   // UI gate stays here

            val u = tracker.onFrame(detected, dealer)
            if (u.ambiguityCleared || u.ambiguity != null) updateAmbiguityPrompt()
            if (u.cardsChanged) updateAdvice()
        }
```

4. Replace `updateAmbiguityPrompt()` to read from the tracker:

```kotlin
    private fun updateAmbiguityPrompt() {
        val section = overlayView.findViewById<View>(R.id.ambiguity_section)
        val prompt = overlayView.findViewById<TextView>(R.id.tv_ambiguity_prompt)
        val btnA = overlayView.findViewById<Button>(R.id.btn_ambig_a)
        val btnB = overlayView.findViewById<Button>(R.id.btn_ambig_b)
        val amb = tracker.currentAmbiguity() ?: run { section?.visibility = View.GONE; return }
        val aName = BlackjackStrategy.cardDisplayName(amb.stored)
        val bName = BlackjackStrategy.cardDisplayName(amb.detectedAlt)
        section?.visibility = View.VISIBLE
        prompt?.text = "🤔 Is your card a $aName or a $bName?"
        btnA?.text = aName
        btnA?.setOnClickListener { val u = tracker.resolveAmbiguity(amb.stored); updateAmbiguityPrompt(); if (u.cardsChanged) updateAdvice() }
        btnB?.text = bName
        btnB?.setOnClickListener { val u = tracker.resolveAmbiguity(amb.detectedAlt); updateAmbiguityPrompt(); if (u.cardsChanged) updateAdvice() }
    }
```

5. In `setupButtonListeners()`: card buttons call `tracker.addPlayerCard(value)`; dealer buttons `tracker.setDealer(value)`; `btn_undo` calls `tracker.undo()`; `btn_clear` calls `tracker.reset()` then `updateAmbiguityPrompt()`; `btn_toggle_auto` calls `tracker.setAutoDetect(!tracker.isAutoDetect())` then `updateAutoDetectUI()`. After each, `if (update.cardsChanged) updateAdvice()`.
6. In `updateAdvice()`, `updatePlayerCardsDisplay()`, `buildMathSummary()`: replace `playerCards`/`dealerCard` with `tracker.playerCards`/`tracker.dealerCard`. With `Action.INCOMPLETE` now returned by `getAdvice`, replace the manual `if (playerCards.isEmpty() || dealerCard == 0)` early-return block: always call `BlackjackStrategy.getAdvice(tracker.playerCards, tracker.dealerCard)` and render `result.action` (the `INCOMPLETE` branch sets `fab_label`="🃏", neutral FAB color `#1A1A2E`, hides `fab_math`, advice text "Enter cards"). One render path for every action.

- [ ] **Step 6: Verify the Service no longer owns hand state**

Run: `grep -n "private val playerCards\|private var dealerCard\|confusablePairs\|pendingAmbiguity" app/src/main/java/com/breadlab/blackjackadvisor/FloatingOverlayService.kt`
Expected: no output (all moved to `HandTracker`).

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/breadlab/blackjackadvisor/HandTracker.kt \
        app/src/test/java/com/breadlab/blackjackadvisor/HandTrackerTest.kt \
        app/src/main/java/com/breadlab/blackjackadvisor/FloatingOverlayService.kt
git commit -m "refactor: extract pure HandTracker from Service; harden B25 + tests

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>"
```

---

## Task 7: B19 (Android 14 consent) + B20 (calibration Back)

**Files:**
- Modify: `app/src/main/java/com/breadlab/blackjackadvisor/MainActivity.kt`
- Modify: `app/src/main/java/com/breadlab/blackjackadvisor/ScreenCaptureService.kt`
- Modify: `app/src/main/java/com/breadlab/blackjackadvisor/FloatingOverlayService.kt`

> No unit tests — these are device/lifecycle behaviors. The §8 manual checklist verifies them; this task is verified by inspection + the manual run in Task 9.

- [ ] **Step 1: B19 — consume consent immediately, never store the Intent**

In `MainActivity.kt`: delete the fields `screenCaptureResultCode` and `screenCaptureResultData`. In `onActivityResult`, the `REQUEST_SCREEN_CAPTURE` branch becomes:

```kotlin
            REQUEST_SCREEN_CAPTURE -> {
                val granted = resultCode == Activity.RESULT_OK && data != null
                if (granted) {
                    // Android 14: consent is single-use — consume it NOW, never cache.
                    ScreenCaptureService.start(this, resultCode, data!!)
                }
                updateScreenCaptureStatus(granted)
            }
```

In `setupUI()`, the `btn_start_overlay` listener loses the screen-capture block — it only starts the overlay:

```kotlin
        findViewById<Button>(R.id.btn_start_overlay).setOnClickListener {
            if (Settings.canDrawOverlays(this)) {
                FloatingOverlayService.start(this)
                updateOverlayRunningState(true)
                moveTaskToBack(true)
            } else {
                requestOverlayPermission()
            }
        }
```

Update `updateScreenCaptureStatus(granted=true)` text to: `"✅ Auto-detect running — switch to your game"` (capture now starts on grant, not later).

- [ ] **Step 2: B19 — fail safe if consent was revoked**

In `ScreenCaptureService.onStartCommand`, wrap the projection acquisition so a revoked/!invalid token stops the service cleanly instead of crashing:

```kotlin
        val projectionManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = try {
            projectionManager.getMediaProjection(resultCode, resultData).also { mp ->
                mp.registerCallback(object : MediaProjection.Callback() {
                    override fun onStop() { isRunning = false; handler.removeCallbacks(captureRunnable) }
                }, null)
            }
        } catch (e: SecurityException) {
            stopSelf(); return START_NOT_STICKY
        }
```

- [ ] **Step 3: B20 — calibration overlay handles Back**

In `FloatingOverlayService.startCalibration()`, after `windowManager.addView(calView, params)`, make the calibration root consume Back to cancel. Add focusability + a key listener:

```kotlin
        calView.isFocusableInTouchMode = true
        calView.requestFocus()
        calView.setOnKeyListener { _, keyCode, event ->
            if (keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) {
                stopCalibration(); true
            } else false
        }
```

Add the import `import android.view.KeyEvent` if not already present (the file already does `import android.view.*`, so it is covered — verify).

- [ ] **Step 4: Inspection verification**

Run: `grep -n "screenCaptureResultData\|screenCaptureResultCode" app/src/main/java/com/breadlab/blackjackadvisor/MainActivity.kt`
Expected: no output (cached token fully removed).
Run: `grep -n "SecurityException\|setOnKeyListener" app/src/main/java/com/breadlab/blackjackadvisor/ScreenCaptureService.kt app/src/main/java/com/breadlab/blackjackadvisor/FloatingOverlayService.kt`
Expected: the new `catch (e: SecurityException)` and the calibration `setOnKeyListener` are present.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/breadlab/blackjackadvisor/MainActivity.kt \
        app/src/main/java/com/breadlab/blackjackadvisor/ScreenCaptureService.kt \
        app/src/main/java/com/breadlab/blackjackadvisor/FloatingOverlayService.kt
git commit -m "fix: Android 14 MediaProjection consent flow (B19) + calibration Back (B20)

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>"
```

---

## Task 8: B23 — README rewrite

**Files:**
- Modify: `README.md`

- [ ] **Step 1: Rewrite the inaccurate sections**

Replace the "Strategy Engine", "File Structure", "Permissions Explained", "Extending the App", and "Notes" sections so they describe shipped reality. Concretely:

- **Strategy Engine:** state the locked ruleset — "Las Vegas Strip, dealer **stands** on soft 17 (S17), double after split, late surrender, no re-split aces; standard total-dependent basic strategy."
- **File Structure:** list all current source files: `BlackjackStrategy.kt`, `HandTracker.kt`, `Bankroll.kt`, `Calibration.kt` (`CalibrationMath` + `CalibrationStore`), `CardDetector.kt`, `CalibrationView.kt`, `ScreenCaptureService.kt`, `FloatingOverlayService.kt`, `MainActivity.kt`.
- **Permissions:** add `FOREGROUND_SERVICE_MEDIA_PROJECTION` (screen capture) and `FOREGROUND_SERVICE_SPECIAL_USE` (overlay) to the table.
- **Auto-detect:** describe it as a shipped feature (screen capture + ML Kit OCR + 3-zone calibration + bankroll/bet suggestion), not a "future extension." Document the Android 14 behavior: screen-capture consent is requested each time auto-detect is enabled and re-prompts after a stop (OS requirement, not a bug).
- **Notes / disclaimer (retain & surface prominently, near the top):**

```markdown
> ⚠️ **Entertainment / learning use only.** This reads another app's screen and
> gives automated advice. On real-money platforms (e.g. Stake) that violates
> their Terms of Service and can get an account and its funds frozen. The
> corrected strategy is *more* accurate — it does **not** reduce that risk.
```

- [ ] **Step 2: Sanity-check no stale claims remain**

Run: `grep -n "Add screen reading\|future\|CameraX\|To automatically detect" README.md`
Expected: no output (the "Extending the App → Add screen reading (OCR)" future-tense section is gone).

- [ ] **Step 3: Commit**

```bash
git add README.md
git commit -m "docs: rewrite README to shipped reality + ruleset + retained ToS disclaimer (B23)

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>"
```

---

## Task 9: Full verification + handoff

- [ ] **Step 1: Run the entire unit suite**

Run: `./gradlew :app:testDebugUnitTest`
Expected: `BUILD SUCCESSFUL`; `BlackjackStrategyTest`, `BankrollTest`, `CalibrationMathTest`, `HandTrackerTest` all green. Report observed output verbatim. If the toolchain cannot run here, say so explicitly and provide the suite for the user to run — no green claims without observed output.

- [ ] **Step 2: Hand the manual device checklist to the user**

Present the §8 "Manual device verification" checklist from the spec (B19 consent re-prompt + rotate; B20 Back during calibration; post-shrink overlay regression). State plainly that these are the user's to run on a physical Android 14+ device and will not be reported as passed by the assistant.

- [ ] **Step 3: Finish the branch**

Use the `superpowers:finishing-a-development-branch` skill to decide merge/PR/cleanup for branch `blackjack-strategy-fixes-and-refactor`.

---

## Self-Review

**1. Spec coverage:** B1/B2/B4/B5/B7/B26/B13-B14/B3 → Task 2. `Action.INCOMPLETE` + single render path → Task 2 + Task 6 Step 5.6. 8,8 invariant → Task 2 tests. B25 → Task 6 (code + test). B19 → Task 7 Steps 1-2. B20 → Task 7 Step 3. B23 incl. ToS → Task 8. CalibrationStore/Math dedupe → Task 5. HandTracker extraction + main-thread/`isExpanded`/`lastBalance`/`confusablePairs` boundaries → Task 6 Steps 3,5. Bankroll → Task 4. Toolchain fail-fast → Task 1. Hard checkpoint → Task 3. Characterization framing → Task 6 Step 1. Manual device verification → Task 9 Step 2. Total-dependent assumption → encoded by the table in Task 2 (no composition logic). No gaps.

**2. Placeholder scan:** every code step contains complete code; every run step has an exact command + expected result; no "TBD/TODO/handle edge cases/similar to Task N". Clear.

**3. Type consistency:** `BlackjackStrategy.Action` (incl. `INCOMPLETE`, no `SPLIT_OR_HIT`), `getAdvice(List<Int>, Int)` used consistently in Tasks 2/6. `HandTracker` API (`onFrame→Update`, `Update{cardsChanged,newHand,ambiguity,ambiguityCleared}`, `Ambiguity{stored,detectedAlt}`, `playerCards`, `dealerCard`, `setAutoDetect/isAutoDetect/addPlayerCard/setDealer/undo/reset/resolveAmbiguity/currentAmbiguity`) defined in Task 6 Step 3 and consumed identically in Task 6 Step 5 and the Task 6 test. `CalibrationStore` (`Zone`, `fractions`, `rect`, `save`, `clear`, `PREFS_NAME`) / `CalibrationMath.toPixels` consistent across Tasks 5 consumers. `Bankroll.recommendedBet/formatMoney` consistent across Task 4. Consistent.
