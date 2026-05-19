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

    // ---- Full soft-total sweep (A,2..A,9) over dealer 2..10 and 1==Ace ----
    @Test fun softTotalTableSweep() {
        fun n(dealer: Int) = if (dealer == 1) 11 else dealer
        val expect: Map<Int, (Int) -> Action> = mapOf(
            2 to { dd -> if (n(dd) in 5..6) Action.DOUBLE_OR_HIT else Action.HIT },          // A,2 soft 13
            3 to { dd -> if (n(dd) in 5..6) Action.DOUBLE_OR_HIT else Action.HIT },          // A,3 soft 14
            4 to { dd -> if (n(dd) in 4..6) Action.DOUBLE_OR_HIT else Action.HIT },          // A,4 soft 15
            5 to { dd -> if (n(dd) in 4..6) Action.DOUBLE_OR_HIT else Action.HIT },          // A,5 soft 16
            6 to { dd -> if (n(dd) in 3..6) Action.DOUBLE_OR_HIT else Action.HIT },          // A,6 soft 17
            7 to { dd -> when (n(dd)) {                                                       // A,7 soft 18
                in 3..6 -> Action.DOUBLE_OR_STAND
                2, 7, 8 -> Action.STAND
                else -> Action.HIT
            } },
            8 to { _ -> Action.STAND },                                                       // A,8 soft 19
            9 to { _ -> Action.STAND },                                                       // A,9 soft 20
        )
        for ((other, fn) in expect) {
            for (dd in listOf(2, 3, 4, 5, 6, 7, 8, 9, 10, 1)) {
                assertEquals("soft A,$other vs $dd", fn(dd), act(listOf(1, other), dd))
            }
        }
    }

    // ---- Full pair sweep over dealer 2..10 and 1==Ace (5,5 covered by hard-10 sweep) ----
    @Test fun pairTableSweep() {
        fun n(dealer: Int) = if (dealer == 1) 11 else dealer
        val expect: Map<Int, (Int) -> Action> = mapOf(
            2  to { dd -> if (n(dd) in 2..7) Action.SPLIT else Action.HIT },                  // 2,2
            3  to { dd -> if (n(dd) in 2..7) Action.SPLIT else Action.HIT },                  // 3,3
            4  to { dd -> if (n(dd) in 5..6) Action.SPLIT else Action.HIT },                  // 4,4 (else hard 8)
            6  to { dd -> if (n(dd) in 2..6) Action.SPLIT else Action.HIT },                  // 6,6 (else hard 12)
            7  to { dd -> if (n(dd) in 2..7) Action.SPLIT else Action.HIT },                  // 7,7 (else hard 14)
            8  to { _ -> Action.SPLIT },                                                      // 8,8 always
            9  to { dd -> if (n(dd) == 7 || n(dd) == 10 || n(dd) == 11) Action.STAND else Action.SPLIT }, // 9,9
            10 to { _ -> Action.STAND },                                                      // 10,10 never split
            1  to { _ -> Action.SPLIT },                                                      // A,A always
        )
        for ((card, fn) in expect) {
            for (dd in listOf(2, 3, 4, 5, 6, 7, 8, 9, 10, 1)) {
                assertEquals("pair $card,$card vs $dd", fn(dd), act(listOf(card, card), dd))
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
        total <= 11 -> listOf(total - 2, 2)
        else -> listOf(10, total - 10)
    }
}
