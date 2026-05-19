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
        t.onFrame(emptyList(), 7)
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
        t.onFrame(listOf(9), 5)
        assertEquals(listOf(10, 6), t.playerCards)
        t.onFrame(listOf(9), 5)
        assertEquals(listOf(9), t.playerCards)
        assertEquals(5, t.dealerCard)
    }

    @Test fun b25_confusableDealerFlipDoesNotResetHand() {
        val t = tracker()
        t.onFrame(listOf(10, 6), 8)
        t.onFrame(listOf(10, 6), 3)
        t.onFrame(listOf(10, 6), 3)
        t.onFrame(listOf(10, 6), 3)
        assertEquals(listOf(10, 6), t.playerCards)
        assertEquals(8, t.dealerCard)
    }

    @Test fun ambiguityRaisedOncePerPairPerHand() {
        val t = tracker()
        t.onFrame(listOf(4), 7)
        val u = t.onFrame(listOf(1), 7)
        assertEquals(4 to 1, u.ambiguity!!.let { it.stored to it.detectedAlt })
        t.resolveAmbiguity(4)
        assertNull(t.onFrame(listOf(1), 7).ambiguity)
        assertEquals(listOf(4), t.playerCards)
    }

    @Test fun manualOpsAndReset() {
        val t = HandTracker()
        t.addPlayerCard(10); t.addPlayerCard(6); t.setDealer(9)
        assertEquals(listOf(10, 6), t.playerCards)
        t.undo(); assertEquals(listOf(10), t.playerCards)
        t.reset()
        assertTrue(t.playerCards.isEmpty()); assertEquals(0, t.dealerCard)
    }
}
