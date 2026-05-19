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
