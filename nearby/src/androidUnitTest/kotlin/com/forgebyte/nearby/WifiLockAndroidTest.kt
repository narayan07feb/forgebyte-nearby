package com.forgebyte.nearby

import kotlin.test.Test

class WifiLockAndroidTest {
    @Test
    fun givenInitNotCalled_whenMatchStartsAndEnds_thenNoLockAndNoThrow() {
        // A game that joins by code without opening discovery never called init: the match must still run.
        newWifiLatencyLock().run {
            acquire()
            release()
        }
    }
}
