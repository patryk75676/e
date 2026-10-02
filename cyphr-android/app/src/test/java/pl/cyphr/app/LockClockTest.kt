package pl.cyphr.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Kiedy aplikacja znowu prosi o odcisk — przy starcie i po powrocie z tla. */
class LockClockTest {
    private val now = 1_800_000_000_000L
    private val hour = 3_600_000L

    @Test
    fun `w oknie 24 h nie pyta, takze po ponownym uruchomieniu`() {
        assertFalse(LockClock.due(now, now - 23 * hour, lockOn = true, afterSeconds = DAY_SECONDS))
    }

    @Test
    fun `po 24 h pyta`() {
        assertTrue(LockClock.due(now, now - 24 * hour, lockOn = true, afterSeconds = DAY_SECONDS))
    }

    @Test
    fun `bez zapisanego czasu pyta`() {
        assertTrue(LockClock.due(now, null, lockOn = true, afterSeconds = DAY_SECONDS))
    }

    @Test
    fun `czas z przyszlosci, czyli cofniety zegar, nie otwiera bez odcisku`() {
        assertTrue(LockClock.due(now, now + hour, lockOn = true, afterSeconds = DAY_SECONDS))
    }

    @Test
    fun `wylaczona blokada nigdy nie pyta`() {
        assertFalse(LockClock.due(now, null, lockOn = false, afterSeconds = 60))
    }

    @Test
    fun `krotkie okno liczy sie w sekundach`() {
        assertFalse(LockClock.due(now, now - 59_000, lockOn = true, afterSeconds = 60))
        assertTrue(LockClock.due(now, now - 60_000, lockOn = true, afterSeconds = 60))
    }
}
