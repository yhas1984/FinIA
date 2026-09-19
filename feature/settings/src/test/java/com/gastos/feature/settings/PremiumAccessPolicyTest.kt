package com.gastos.feature.settings

import org.junit.Assert.*
import org.junit.Test

class PremiumAccessPolicyTest {
    @Test fun `grace never starts from an undated or future legacy flag`() {
        assertFalse(PremiumAccessPolicy.isValid(0, null, 100))
        assertFalse(PremiumAccessPolicy.isValid(101, null, 100))
    }
    @Test fun `legacy and signed grants expire at seven days or earlier signed expiry`() {
        val start = 1000L
        val end = start + PremiumAccessPolicy.MAX_GRACE_MILLIS
        assertTrue(PremiumAccessPolicy.isValid(start, null, end - 1))
        assertFalse(PremiumAccessPolicy.isValid(start, null, end))
        assertFalse(PremiumAccessPolicy.isValid(start, end + 100, end))
        assertFalse(PremiumAccessPolicy.isValid(start, 2000, 2000))
        assertTrue(PremiumAccessPolicy.isValid(start, 2000, 1999))
    }
}
