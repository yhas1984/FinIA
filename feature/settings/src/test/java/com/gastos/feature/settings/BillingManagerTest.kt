package com.gastos.feature.settings

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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

    @Test
    fun `ack backoff grows and caps`() {
        assertTrue(BillingManager.nextAckDelayMillis(1) > BillingManager.nextAckDelayMillis(0))
        assertTrue(BillingManager.nextAckDelayMillis(8) >= BillingManager.nextAckDelayMillis(5))
    }

    @Test
    fun `verification notice stays empty without failures`() {
        assertNull(BillingManager.buildVerificationNotice(playEntitled = true, lastVerifiedAt = System.currentTimeMillis(), hasFailure = false))
    }

    @Test
    fun `verification notice warns when premium cached after failure`() {
        assertTrue(
            BillingManager.buildVerificationNotice(
                playEntitled = true,
                lastVerifiedAt = System.currentTimeMillis(),
                hasFailure = true
            ).orEmpty().isNotBlank()
        )
    }
}
