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

    fun onFrame(detected: List<Int>, dealer: Int): Update {
        val dealerChanged = dealer > 0 && dealerCard > 0 && dealer != dealerCard
        if (dealerChanged) {
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
        // Surface only a NEWLY raised ambiguity. While one is already pending
        // and unchanged, return null so the Service does not re-render the
        // prompt every frame (preserves the original no-churn behavior).
        if (pendingAmbiguity != null) return null
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
