package com.gastos.feature.settings

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BillingManagerTest {
    @Test
    fun `debug override keeps premium active in debug builds`() {
        assertTrue(BillingManager.resolvePremium(
            isDebugBuild = true,
            playEntitled = false,
            debugOverride = true
        ))
    }

    @Test
    fun `debug override is ignored in release builds`() {
        assertFalse(BillingManager.resolvePremium(
            isDebugBuild = false,
            playEntitled = false,
            debugOverride = true
        ))
    }

    @Test
    fun `play entitlement always enables premium`() {
        assertTrue(BillingManager.resolvePremium(
            isDebugBuild = false,
            playEntitled = true,
            debugOverride = false
        ))
    }
}
