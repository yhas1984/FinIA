package com.gastos.feature.settings

import android.app.Activity
import android.content.Context
import android.content.pm.ApplicationInfo
import com.android.billingclient.api.AcknowledgePurchaseParams
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.PurchasesUpdatedListener
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams
import com.gastos.extension.SafeLog
import com.gastos.repository.PremiumStatusProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BillingManager @Inject constructor(
    @ApplicationContext private val context: Context
) : PurchasesUpdatedListener, BillingClientStateListener, PremiumStatusProvider {
    companion object {
        private const val TAG = "BillingManager"
        private const val PREFS_NAME = "finai_billing"
        private const val KEY_IS_PREMIUM = "is_premium"
        private const val KEY_PLAY_PREMIUM = "play_premium"
        private const val KEY_DEBUG_PREMIUM = "debug_premium"
        private const val KEY_LAST_VERIFIED_AT = "last_verified_at"
        private const val KEY_LAST_SYNC_AT = "last_sync_at"
        private const val KEY_HAS_PENDING_PURCHASE = "has_pending_purchase"
        private const val KEY_ACK_TOKEN = "ack_token"
        private const val KEY_ACK_ATTEMPT = "ack_attempt"
        private const val KEY_ACK_NEXT_AT = "ack_next_at"
        private const val AUTO_REFRESH_DEBOUNCE_MS = 5_000L
        private const val STALE_VERIFICATION_MS = 24L * 60L * 60L * 1000L
        const val PREMIUM_SKU = "finai_premium"

        internal fun resolvePremium(isDebugBuild: Boolean, playEntitled: Boolean, debugOverride: Boolean): Boolean {
            return playEntitled || (isDebugBuild && debugOverride)
        }

        internal fun nextAckDelayMillis(attempt: Int): Long = when (attempt.coerceAtLeast(0)) {
            0 -> 60_000L
            1 -> 5 * 60_000L
            2 -> 15 * 60_000L
            3 -> 60 * 60_000L
            4 -> 6 * 60 * 60_000L
            else -> 24 * 60 * 60_000L
        }

        internal fun buildVerificationNotice(playEntitled: Boolean, lastVerifiedAt: Long?, hasFailure: Boolean): String? {
            if (!hasFailure || !playEntitled) return null
            val verifiedAt = lastVerifiedAt ?: return "No se pudo verificar Premium con Google Play. Se conserva el acceso actual."
            return if (System.currentTimeMillis() - verifiedAt > STALE_VERIFICATION_MS) {
                "Premium sigue activo, pero Google Play no se pudo verificar desde hace tiempo. Abre la app con conexión para resincronizar."
            } else {
                "Premium sigue activo. Google Play no pudo verificarse ahora y FinAI conservará el acceso actual temporalmente."
            }
        }
    }

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    val isDebugBuild: Boolean = (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
    private val legacyPremium = prefs.getBoolean(KEY_IS_PREMIUM, false)
    private var playEntitled: Boolean = prefs.getBoolean(KEY_PLAY_PREMIUM, legacyPremium)
    private var debugOverride: Boolean = prefs.getBoolean(KEY_DEBUG_PREMIUM, if (isDebugBuild) legacyPremium else false)
    private var isStartingConnection: Boolean = false
    private var lastAutomaticRefreshAt: Long = 0L
    private val _isPremium = MutableStateFlow(resolvePremium(isDebugBuild, playEntitled, debugOverride))
    override val isPremium: StateFlow<Boolean> = _isPremium.asStateFlow()
    private val _productDetails = MutableStateFlow<ProductDetails?>(null)
    val productDetails: StateFlow<ProductDetails?> = _productDetails.asStateFlow()
    private val _isConnecting = MutableStateFlow(false)
    val isConnecting: StateFlow<Boolean> = _isConnecting.asStateFlow()
    private val _purchaseError = MutableStateFlow<String?>(null)
    val purchaseError: StateFlow<String?> = _purchaseError.asStateFlow()
    private val _hasPendingPurchase = MutableStateFlow(prefs.getBoolean(KEY_HAS_PENDING_PURCHASE, false))
    val hasPendingPurchase: StateFlow<Boolean> = _hasPendingPurchase.asStateFlow()
    private val _billingNotice = MutableStateFlow<String?>(null)
    val billingNotice: StateFlow<String?> = _billingNotice.asStateFlow()
    private var billingClient: BillingClient? = null

    init {
        refreshBillingNotice(hasFailure = false)
        startConnection()
    }

    fun startConnection() {
        if (billingClient == null) {
            billingClient = BillingClient.newBuilder(context)
                .setListener(this)
                .enablePendingPurchases(PendingPurchasesParams.newBuilder().enableOneTimeProducts().build())
                .enableAutoServiceReconnection()
                .build()
        }
        if (billingClient?.isReady == true || isStartingConnection) return
        isStartingConnection = true
        _isConnecting.value = true
        billingClient?.startConnection(this)
    }

    override fun onBillingSetupFinished(billingResult: BillingResult) {
        isStartingConnection = false
        _isConnecting.value = false
        if (billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
            SafeLog.d(TAG, "BillingClient conectado")
            refreshNow(force = true)
        } else {
            SafeLog.e(TAG, "Error conectando billing: ${billingResult.responseCode}")
            _purchaseError.value = "No se pudo conectar con Google Play (código ${billingResult.responseCode})."
            refreshBillingNotice(hasFailure = playEntitled)
        }
    }

    override fun onBillingServiceDisconnected() {
        SafeLog.w(TAG, "BillingClient desconectado")
        isStartingConnection = false
        _isConnecting.value = false
        refreshBillingNotice(hasFailure = playEntitled)
    }

    fun refreshNow(force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force && now - lastAutomaticRefreshAt < AUTO_REFRESH_DEBOUNCE_MS) return
        if (!force) lastAutomaticRefreshAt = now
        val client = billingClient
        if (client == null || !client.isReady) {
            startConnection()
            return
        }
        queryProductDetails()
        queryPurchases()
        retryPendingAcknowledgeIfDue(force = force)
    }

    fun onAppResumed() {
        refreshNow(force = false)
    }

    fun queryProductDetails() {
        val client = billingClient ?: return
        val params = QueryProductDetailsParams.newBuilder()
            .setProductList(
                listOf(
                    QueryProductDetailsParams.Product.newBuilder()
                        .setProductId(PREMIUM_SKU)
                        .setProductType(BillingClient.ProductType.INAPP)
                        .build()
                )
            )
            .build()
        client.queryProductDetailsAsync(params) { result, queryResult ->
            if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                _productDetails.value = queryResult.productDetailsList.firstOrNull { it.productId == PREMIUM_SKU }
                if (_productDetails.value == null) {
                    SafeLog.w(TAG, "Producto $PREMIUM_SKU no encontrado en Play Console")
                }
            } else {
                SafeLog.e(TAG, "Error consultando producto: ${result.responseCode}")
            }
        }
    }

    fun launchBillingFlow(activity: Activity) {
        val client = billingClient
        val details = _productDetails.value
        if (_hasPendingPurchase.value) {
            _billingNotice.value = "Hay una compra pendiente en Google Play. Espera a que se confirme antes de intentar otra vez."
            return
        }
        if (client == null || !client.isReady) {
            _purchaseError.value = "Play Billing no está listo. Inténtalo de nuevo."
            startConnection()
            return
        }
        if (details == null) {
            _purchaseError.value = "No se pudo cargar el producto. Verifica tu conexión."
            queryProductDetails()
            return
        }
        val productDetailsParams = BillingFlowParams.ProductDetailsParams.newBuilder()
            .setProductDetails(details)
            .build()
        val flowParams = BillingFlowParams.newBuilder()
            .setProductDetailsParamsList(listOf(productDetailsParams))
            .build()
        val result = client.launchBillingFlow(activity, flowParams)
        if (result.responseCode != BillingClient.BillingResponseCode.OK) {
            _purchaseError.value = "No se pudo iniciar la compra (código ${result.responseCode})."
        }
    }

    override fun onPurchasesUpdated(billingResult: BillingResult, purchases: MutableList<Purchase>?) {
        when (billingResult.responseCode) {
            BillingClient.BillingResponseCode.OK -> {
                if (purchases.isNullOrEmpty()) {
                    queryPurchases()
                } else {
                    purchases.forEach(::handlePurchase)
                }
            }
            BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED -> queryPurchases()
            BillingClient.BillingResponseCode.USER_CANCELED -> _purchaseError.value = "Compra cancelada."
            else -> _purchaseError.value = "Error en la compra (código ${billingResult.responseCode})."
        }
    }

    fun queryPurchases() {
        val client = billingClient ?: return
        if (!client.isReady) return
        val params = QueryPurchasesParams.newBuilder().setProductType(BillingClient.ProductType.INAPP).build()
        client.queryPurchasesAsync(params) { result, purchasesList ->
            if (result.responseCode != BillingClient.BillingResponseCode.OK) {
                SafeLog.w(TAG, "No se pudieron consultar compras: ${result.responseCode}")
                _purchaseError.value = null
                updateLastSyncAt()
                refreshBillingNotice(hasFailure = true)
                return@queryPurchasesAsync
            }
            _purchaseError.value = null
            updateLastSyncAt()
            val matching = purchasesList.filter { it.products.contains(PREMIUM_SKU) }
            val purchased = matching.firstOrNull { it.purchaseState == Purchase.PurchaseState.PURCHASED }
            val pending = matching.firstOrNull { it.purchaseState == Purchase.PurchaseState.PENDING }
            when {
                purchased != null -> handlePurchase(purchased)
                pending != null -> setPendingPurchase(true)
                else -> revokePremium()
            }
        }
    }

    private fun handlePurchase(purchase: Purchase) {
        when (purchase.purchaseState) {
            Purchase.PurchaseState.PENDING -> {
                setPendingPurchase(true)
                setPlayEntitled(false, verified = false)
            }
            Purchase.PurchaseState.PURCHASED -> {
                setPendingPurchase(false)
                setPlayEntitled(true, verified = true)
                if (purchase.isAcknowledged) {
                    clearAckRetryState()
                    refreshBillingNotice(hasFailure = false)
                } else {
                    scheduleAckRetry(purchase.purchaseToken, attempt = 0, nextAt = System.currentTimeMillis())
                    retryPendingAcknowledgeIfDue(force = true)
                }
            }
        }
    }

    private fun retryPendingAcknowledgeIfDue(force: Boolean) {
        val client = billingClient ?: return
        if (!client.isReady) return
        val token = prefs.getString(KEY_ACK_TOKEN, null) ?: return
        val attempt = prefs.getInt(KEY_ACK_ATTEMPT, 0)
        val nextAt = prefs.getLong(KEY_ACK_NEXT_AT, 0L)
        if (!force && System.currentTimeMillis() < nextAt) return
        val params = AcknowledgePurchaseParams.newBuilder().setPurchaseToken(token).build()
        client.acknowledgePurchase(params) { result ->
            when (result.responseCode) {
                BillingClient.BillingResponseCode.OK -> {
                    clearAckRetryState()
                    refreshBillingNotice(hasFailure = false)
                }
                BillingClient.BillingResponseCode.ITEM_NOT_OWNED -> {
                    clearAckRetryState()
                    queryPurchases()
                }
                BillingClient.BillingResponseCode.ERROR,
                BillingClient.BillingResponseCode.SERVICE_DISCONNECTED,
                BillingClient.BillingResponseCode.SERVICE_UNAVAILABLE,
                BillingClient.BillingResponseCode.NETWORK_ERROR -> {
                    val nextAttempt = attempt + 1
                    scheduleAckRetry(token, nextAttempt, System.currentTimeMillis() + nextAckDelayMillis(attempt))
                    _billingNotice.value = "La compra está activa, pero Google Play aún no la ha confirmado. FinAI lo reintentará automáticamente."
                }
                else -> {
                    val nextAttempt = attempt + 1
                    scheduleAckRetry(token, nextAttempt, System.currentTimeMillis() + nextAckDelayMillis(attempt))
                    _billingNotice.value = "La compra está activa, pero Google Play aún no la ha confirmado. FinAI lo reintentará automáticamente."
                }
            }
        }
    }

    private fun revokePremium() {
        clearAckRetryState()
        setPendingPurchase(false)
        setPlayEntitled(false, verified = false)
        refreshBillingNotice(hasFailure = false)
    }

    private fun setPlayEntitled(value: Boolean, verified: Boolean) {
        playEntitled = value
        val editor = prefs.edit()
            .putBoolean(KEY_PLAY_PREMIUM, value)
            .putBoolean(KEY_IS_PREMIUM, value)
        if (verified && value) {
            editor.putLong(KEY_LAST_VERIFIED_AT, System.currentTimeMillis())
        }
        editor.apply()
        refreshPremiumState()
    }

    private fun setPendingPurchase(value: Boolean) {
        prefs.edit().putBoolean(KEY_HAS_PENDING_PURCHASE, value).apply()
        _hasPendingPurchase.value = value
        refreshBillingNotice(hasFailure = false)
    }

    private fun scheduleAckRetry(token: String, attempt: Int, nextAt: Long) {
        prefs.edit()
            .putString(KEY_ACK_TOKEN, token)
            .putInt(KEY_ACK_ATTEMPT, attempt)
            .putLong(KEY_ACK_NEXT_AT, nextAt)
            .apply()
        _billingNotice.value = "La compra está activa, pero Google Play aún no la ha confirmado. FinAI lo reintentará automáticamente."
    }

    private fun clearAckRetryState() {
        prefs.edit()
            .remove(KEY_ACK_TOKEN)
            .remove(KEY_ACK_ATTEMPT)
            .remove(KEY_ACK_NEXT_AT)
            .apply()
    }

    private fun refreshBillingNotice(hasFailure: Boolean) {
        _billingNotice.value = when {
            _hasPendingPurchase.value -> "Compra pendiente en Google Play. FinAI activará Premium cuando Google la confirme."
            prefs.contains(KEY_ACK_TOKEN) -> "La compra está activa, pero Google Play aún no la ha confirmado. FinAI lo reintentará automáticamente."
            else -> buildVerificationNotice(playEntitled = playEntitled, lastVerifiedAt = lastVerifiedAt(), hasFailure = hasFailure)
        }
    }

    private fun updateLastSyncAt() {
        prefs.edit().putLong(KEY_LAST_SYNC_AT, System.currentTimeMillis()).apply()
    }

    private fun lastVerifiedAt(): Long? = prefs.getLong(KEY_LAST_VERIFIED_AT, 0L).takeIf { it > 0L }

    private fun setDebugOverride(value: Boolean) {
        debugOverride = value
        prefs.edit().putBoolean(KEY_DEBUG_PREMIUM, value).apply()
        refreshPremiumState()
    }

    private fun refreshPremiumState() {
        _isPremium.value = resolvePremium(isDebugBuild, playEntitled, debugOverride)
    }

    fun debugSetPremium(value: Boolean) {
        if (!isDebugBuild) return
        setDebugOverride(value)
    }

    fun clearError() {
        _purchaseError.value = null
    }

    fun endConnection() {
        billingClient?.endConnection()
        billingClient = null
        isStartingConnection = false
    }
}
