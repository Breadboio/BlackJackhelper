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
