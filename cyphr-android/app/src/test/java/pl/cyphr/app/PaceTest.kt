package pl.cyphr.app

import org.junit.Assert.assertEquals
import org.junit.Test

class PaceTest {
    @Test
    fun burstIsSpreadOut() {
        val pace = Pace(600)
        assertEquals(0L, pace.slot(1_000))
        assertEquals(600L, pace.slot(1_000))
        assertEquals(1_200L, pace.slot(1_000))
    }

    @Test
    fun noWaitAfterQuietPeriod() {
        val pace = Pace(600)
        pace.slot(1_000)
        assertEquals(0L, pace.slot(5_000))
    }

    @Test
    fun waitsOnlyForTheRestOfTheGap() {
        val pace = Pace(600)
        pace.slot(1_000)
        assertEquals(200L, pace.slot(1_400))
    }
}
