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
