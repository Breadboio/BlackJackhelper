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
