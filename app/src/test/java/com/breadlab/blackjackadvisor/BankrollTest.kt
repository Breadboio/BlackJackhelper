package com.breadlab.blackjackadvisor

import org.junit.Assert.assertEquals
import org.junit.Test

class BankrollTest {
    @Test fun belowOne_floorsToOne() = assertEquals(1.0, Bankroll.recommendedBet(50.0), 0.0)
    @Test fun under5_roundsToWhole() = assertEquals(3.0, Bankroll.recommendedBet(270.0), 0.0)
    // kotlin.math.round rounds ties to the even integer (round(2.5)==2.0).
    // This preserves the original FloatingOverlayService behavior verbatim;
    // pinned here so the quirk stays intentional and visible.
    @Test fun under5_halfRoundsToEven_preservesOriginalBehavior() =
        assertEquals(2.0, Bankroll.recommendedBet(250.0), 0.0)
    @Test fun under25_step5() = assertEquals(10.0, Bankroll.recommendedBet(1000.0), 0.0)
    @Test fun under100_step10() = assertEquals(50.0, Bankroll.recommendedBet(5000.0), 0.0)
    @Test fun under1000_step25() = assertEquals(250.0, Bankroll.recommendedBet(25000.0), 0.0)
    @Test fun large_step100() = assertEquals(1900.0, Bankroll.recommendedBet(189994.26), 0.0)
    @Test fun wholeMoney_noDecimals() = assertEquals("1,000", Bankroll.formatMoney(1000.0))
    @Test fun fractionalMoney_twoDecimals() = assertEquals("12.50", Bankroll.formatMoney(12.5))

    // ---- Step-boundary coverage (spec §8: raw 0.99, 1.0, 4.99, 25, 100, 1000) ----
    @Test fun boundary_raw0_99_floorsToOne() = assertEquals(1.0, Bankroll.recommendedBet(99.0), 0.0)
    @Test fun boundary_raw1_0_roundsToOne() = assertEquals(1.0, Bankroll.recommendedBet(100.0), 0.0)
    @Test fun boundary_raw4_99_roundsToFive() = assertEquals(5.0, Bankroll.recommendedBet(499.0), 0.0)
    // raw==25.0 falls to the <100 step; round(25/10)=round(2.5)=2 (ties-to-even) → 20.0.
    @Test fun boundary_raw25_step10_tiesToEven() = assertEquals(20.0, Bankroll.recommendedBet(2500.0), 0.0)
    @Test fun boundary_raw100_step25() = assertEquals(100.0, Bankroll.recommendedBet(10000.0), 0.0)
    @Test fun boundary_raw1000_step100() = assertEquals(1000.0, Bankroll.recommendedBet(100000.0), 0.0)
}
