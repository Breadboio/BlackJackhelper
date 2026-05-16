package com.breadlab.blackjackadvisor

/**
 * Complete Basic Strategy Engine
 * Based on standard Las Vegas Strip rules:
 *   - Dealer stands on soft 17
 *   - Double after split allowed
 *   - Re-split aces not allowed
 *   - Surrender allowed
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
        SPLIT_OR_HIT("SPLIT / HIT", "✂️", "#8E44AD"),
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
     * Get the best move given player cards and dealer upcard
     * @param playerCards list of card values (1=Ace, 2-9, 10=Ten/Face)
     * @param dealerUpcard dealer's visible card value (1-10)
     */
    fun getAdvice(playerCards: List<Int>, dealerUpcard: Int): StrategyResult {
        val normalized = playerCards.map { if (it == 1) 11 else it }

        // Check for pair
        if (playerCards.size == 2 && playerCards[0] == playerCards[1]) {
            val pairValue = if (playerCards[0] == 1) 11 else playerCards[0]
            val splitAction = getPairStrategy(pairValue, dealerUpcard)
            if (splitAction != null) return splitAction
        }

        // Check for soft hand (has an ace counted as 11)
        val (total, isSoft) = calculateHand(playerCards)

        return if (isSoft) {
            getSoftStrategy(total, dealerUpcard)
        } else {
            getHardStrategy(total, dealerUpcard)
        }
    }

    private fun calculateHand(cards: List<Int>): Pair<Int, Boolean> {
        var total = 0
        var aces = 0
        for (card in cards) {
            if (card == 1) {
                aces++
                total += 11
            } else {
                total += minOf(card, 10)
            }
        }
        var isSoft = aces > 0 && total <= 21
        while (total > 21 && aces > 0) {
            total -= 10
            aces--
        }
        if (aces == 0) isSoft = false
        return Pair(total, isSoft)
    }

    private fun getPairStrategy(pairValue: Int, dealer: Int): StrategyResult? {
        val action = when (pairValue) {
            11 -> Action.SPLIT  // Always split aces
            10 -> null          // Never split tens (stand)
            9 -> if (dealer in listOf(7, 10, 11)) null else Action.SPLIT
            8 -> Action.SPLIT   // Always split 8s
            7 -> if (dealer <= 7) Action.SPLIT else null
            6 -> if (dealer <= 6) Action.SPLIT else null
            5 -> null           // Never split 5s (treat as 10, double)
            4 -> if (dealer in 5..6) Action.SPLIT else null
            3 -> if (dealer <= 7) Action.SPLIT else null
            2 -> if (dealer <= 7) Action.SPLIT else null
            else -> null
        } ?: return null

        val reason = when (pairValue) {
            11 -> "Always split Aces — gives two strong starting hands"
            8 -> "Always split 8s — 16 is the worst hand, two 8s are much better"
            10 -> "Never split 10s — 20 is already an excellent hand"
            9 -> "Split 9s unless dealer shows 7, 10, or Ace"
            7 -> "Split 7s against dealer ${dealer} or less"
            6 -> "Split 6s against weak dealer (2-6)"
            5 -> "Treat as 10 — double or hit, don't split"
            4 -> "Split 4s only against dealer 5 or 6 (weakest upcards)"
            else -> "Split ${pairValue}s against dealer ${dealer}"
        }

        return StrategyResult(action, reason, pairValue * 2, dealer, false, true)
    }

    private fun getSoftStrategy(total: Int, dealer: Int): StrategyResult {
        val (action, reason) = when (total) {
            20 -> Pair(Action.STAND, "Soft 20 (A-9) — always stand, near perfect hand")
            19 -> if (dealer == 6) {
                Pair(Action.DOUBLE_OR_STAND, "Soft 19 vs dealer 6 — double if allowed, else stand")
            } else {
                Pair(Action.STAND, "Soft 19 (A-8) — stand, strong hand")
            }
            18 -> when (dealer) {
                in 2..6 -> Pair(Action.DOUBLE_OR_STAND, "Soft 18 vs weak dealer — double down to maximize profit")
                7, 8 -> Pair(Action.STAND, "Soft 18 vs ${dealer} — stand, you're likely ahead")
                else -> Pair(Action.HIT, "Soft 18 vs strong dealer — hit to improve")
            }
            17 -> when (dealer) {
                in 3..6 -> Pair(Action.DOUBLE_OR_HIT, "Soft 17 vs weak dealer — double if allowed")
                else -> Pair(Action.HIT, "Soft 17 — always improve, can't bust with one hit")
            }
            16 -> when (dealer) {
                in 4..6 -> Pair(Action.DOUBLE_OR_HIT, "Soft 16 vs weak dealer — double if allowed")
                else -> Pair(Action.HIT, "Soft 16 — hit, not a strong enough hand to stand")
            }
            15 -> when (dealer) {
                in 4..6 -> Pair(Action.DOUBLE_OR_HIT, "Soft 15 vs weak dealer — double if allowed")
                else -> Pair(Action.HIT, "Soft 15 — hit")
            }
            14 -> when (dealer) {
                5, 6 -> Pair(Action.DOUBLE_OR_HIT, "Soft 14 vs dealer 5/6 — double if allowed")
                else -> Pair(Action.HIT, "Soft 14 — hit")
            }
            13 -> when (dealer) {
                5, 6 -> Pair(Action.DOUBLE_OR_HIT, "Soft 13 vs dealer 5/6 — double if allowed")
                else -> Pair(Action.HIT, "Soft 13 — hit")
            }
            else -> Pair(Action.HIT, "Soft hand — hit")
        }
        return StrategyResult(action, reason, total, dealer, true, false)
    }

    private fun getHardStrategy(total: Int, dealer: Int): StrategyResult {
        val (action, reason) = when {
            total >= 17 -> Pair(Action.STAND, "Hard ${total} — always stand at 17+")
            total == 16 -> when (dealer) {
                in 2..6 -> Pair(Action.STAND, "Hard 16 vs weak dealer — dealer likely to bust")
                10 -> Pair(Action.SURRENDER, "Hard 16 vs dealer 10 — surrender to save half your bet")
                11 -> Pair(Action.SURRENDER, "Hard 16 vs Ace — surrender (or hit if surrender unavailable)")
                else -> Pair(Action.HIT, "Hard 16 vs ${dealer} — hit, dealer too strong to stand")
            }
            total == 15 -> when (dealer) {
                in 2..6 -> Pair(Action.STAND, "Hard 15 vs weak dealer — stand, let dealer bust")
                10 -> Pair(Action.SURRENDER, "Hard 15 vs dealer 10 — surrender saves money long-term")
                else -> Pair(Action.HIT, "Hard 15 vs ${dealer} — hit")
            }
            total in 13..14 -> if (dealer <= 6) {
                Pair(Action.STAND, "Hard ${total} vs weak dealer (${dealer}) — stand, dealer likely busts")
            } else {
                Pair(Action.HIT, "Hard ${total} vs ${dealer} — hit, dealer too strong")
            }
            total == 12 -> when (dealer) {
                in 4..6 -> Pair(Action.STAND, "Hard 12 vs weak dealer 4-6 — stand, high bust risk for dealer")
                else -> Pair(Action.HIT, "Hard 12 vs ${dealer} — hit")
            }
            total == 11 -> Pair(Action.DOUBLE, "Hard 11 — almost always double down, great position")
            total == 10 -> if (dealer <= 9) {
                Pair(Action.DOUBLE, "Hard 10 vs ${dealer} — double down, you have the edge")
            } else {
                Pair(Action.HIT, "Hard 10 vs ${dealer} — hit (dealer too strong to double)")
            }
            total == 9 -> if (dealer in 3..6) {
                Pair(Action.DOUBLE, "Hard 9 vs weak dealer — double down")
            } else {
                Pair(Action.HIT, "Hard 9 — hit")
            }
            total <= 8 -> Pair(Action.HIT, "Hard ${total} — always hit, can't bust")
            else -> Pair(Action.HIT, "Hit")
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

    fun normalizeCard(value: Int): Int = when (value) {
        11, 12, 13 -> 10  // J, Q, K = 10
        else -> value
    }
}
