package com.focusvault.app.manager

import org.junit.Test
import org.junit.Assert.*

class SecurityManagerTest {
    @Test
    fun testMaxAttemptsAndLockoutLogic() {
        assertEquals(3, SecurityManager.MAX_ATTEMPTS_BEFORE_LOCKOUT)
    }
}
