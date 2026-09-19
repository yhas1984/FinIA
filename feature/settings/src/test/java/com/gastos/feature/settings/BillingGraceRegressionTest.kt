package com.gastos.feature.settings

import android.content.Context
import android.content.SharedPreferences
import android.content.pm.ApplicationInfo
import com.android.billingclient.api.*
import io.mockk.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.*
import org.junit.Assert.*

@OptIn(ExperimentalCoroutinesApi::class)
class BillingGraceRegressionTest {
    private val dispatcher = StandardTestDispatcher()
    private val values = mutableMapOf<String, Any>()
    private val managers = mutableListOf<BillingManager>()
    private lateinit var context: Context
    private lateinit var client: BillingEntitlementClient
    private val ok = mockk<BillingResult> { every { responseCode } returns BillingClient.BillingResponseCode.OK }

    @Before fun setup() {
        Dispatchers.setMain(dispatcher)
        val editor = mockk<SharedPreferences.Editor>(relaxed=true)
        every { editor.putBoolean(any(), any()) } answers { values[firstArg()] = secondArg<Boolean>(); editor }
        every { editor.putLong(any(), any()) } answers { values[firstArg()] = secondArg<Long>(); editor }
        every { editor.putInt(any(), any()) } answers { values[firstArg()] = secondArg<Int>(); editor }
        every { editor.putString(any(), any()) } answers { secondArg<String?>()?.let { values[firstArg()] = it }; editor }
        every { editor.remove(any()) } answers { values.remove(firstArg<String>()); editor }
        val prefs = mockk<SharedPreferences> {
            every { getBoolean(any(), any()) } answers { values[firstArg()] as? Boolean ?: secondArg() }
            every { getLong(any(), any()) } answers { values[firstArg()] as? Long ?: secondArg() }
            every { getInt(any(), any()) } answers { values[firstArg()] as? Int ?: secondArg() }
            every { getString(any(), any()) } answers { values[firstArg()] as? String ?: secondArg() }
            every { contains(any()) } answers { values.containsKey(firstArg()) }
            every { edit() } returns editor
        }
        context = mockk {
            every { getSharedPreferences(any(), any()) } returns prefs
            every { applicationInfo } returns ApplicationInfo().apply { flags = 0 }
            every { getString(any()) } returns "Verification unavailable"
        }
        mockkStatic(BillingClient::class)
        val builder = mockk<BillingClient.Builder>(relaxed=true)
        val billing = mockk<BillingClient>(relaxed=true)
        every { BillingClient.newBuilder(context) } returns builder
        every { builder.setListener(any()) } returns builder
        every { builder.enablePendingPurchases(any()) } returns builder
        every { builder.enableAutoServiceReconnection() } returns builder
        every { builder.build() } returns billing
        client = mockk {
            every { isEnabled } returns true
            every { isRequired } returns true
            every { validateStoredGrant(any(), any(), any()) } returns (System.currentTimeMillis() + 86_400_000)
        }
    }
    private fun stopManagers() {
        managers.forEach { manager ->
            val field = BillingManager::class.java.getDeclaredField("entitlementScope").apply { isAccessible=true }
            (field.get(manager) as CoroutineScope).cancel()
        }
    }
    @After fun cleanup() {
        stopManagers()
        unmockkStatic(BillingClient::class)
        Dispatchers.resetMain()
    }
    private fun billingTest(block: suspend TestScope.() -> Unit) = runTest(dispatcher) {
        try { block() } finally { stopManagers() }
    }
    private fun manager() = BillingManager(context, mockk(relaxed=true), client).also { managers += it }
    private fun purchase(token: String) = mockk<Purchase> {
        every { purchaseState } returns Purchase.PurchaseState.PURCHASED
        every { purchaseToken } returns token
        every { isAcknowledged } returns true
        every { products } returns listOf(BillingManager.PREMIUM_SKU)
    }
    private fun seed(at: Long = System.currentTimeMillis()) {
        values["play_premium"] = true
        values["last_verified_at"] = at
        values["signed_grant"] = "signed"
        values["grant_subject"] = "subject"
    }
    @Test fun `temporary failure never flickers premium or renews its deadline and survives restart`() = billingTest {
        seed()
        val verified = values["last_verified_at"]
        val pending = CompletableDeferred<EntitlementVerification>()
        coEvery { client.verifyPurchase(any(), any()) } coAnswers { pending.await() }
        val m = manager()
        m.onPurchasesUpdated(ok, mutableListOf(purchase("owned")))
        runCurrent()
        assertTrue(m.isPremium.value)
        pending.complete(EntitlementVerification.Unavailable)
        runCurrent()
        assertTrue(m.isPremium.value)
        assertEquals(verified, values["last_verified_at"])
        assertTrue(manager().isPremium.value)
    }
    @Test fun `confirmed revocation wins over an older concurrent verification`() = billingTest {
        seed()
        val old = CompletableDeferred<EntitlementVerification>()
        coEvery { client.verifyPurchase(any(), "old") } coAnswers { old.await() }
        coEvery { client.verifyPurchase(any(), "new") } returns EntitlementVerification.Revoked
        val m = manager()
        m.onPurchasesUpdated(ok, mutableListOf(purchase("old")))
        runCurrent()
        m.onPurchasesUpdated(ok, mutableListOf(purchase("new")))
        runCurrent()
        assertFalse(m.isPremium.value)
        old.complete(EntitlementVerification.Valid("signed", System.currentTimeMillis()+86_400_000, "subject"))
        runCurrent()
        assertFalse(m.isPremium.value)
        assertFalse(manager().isPremium.value)
        assertNull(values["last_verified_at"])
    }
    @Test fun `new purchases and undated legacy flag cannot gain grace from an outage`() = billingTest {
        values["play_premium"] = true
        coEvery { client.verifyPurchase(any(), any()) } returns EntitlementVerification.Unavailable
        val m = manager()
        assertFalse(m.isPremium.value)
        m.onPurchasesUpdated(ok, mutableListOf(purchase("new")))
        runCurrent()
        assertFalse(m.isPremium.value)
        assertNull(values["last_verified_at"])
    }
    @Test fun `expiry is enforced while open and on resume`() = billingTest {
        seed()
        val m = manager()
        runCurrent()
        values["last_verified_at"] = System.currentTimeMillis() - 7*86_400_000L
        advanceTimeBy(30_001)
        runCurrent()
        assertFalse(m.isPremium.value)
        assertFalse(manager().isPremium.value)
    }
}
