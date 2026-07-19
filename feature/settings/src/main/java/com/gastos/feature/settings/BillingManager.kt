package com.gastos.feature.settings

import android.app.Activity
import android.content.Context
import android.content.pm.ApplicationInfo
import android.util.Log
import com.android.billingclient.api.AcknowledgePurchaseParams
import com.android.billingclient.api.AcknowledgePurchaseResponseListener
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.PurchasesUpdatedListener
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams
import com.gastos.repository.PremiumStatusProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Gestiona las compras integradas de Google Play para desbloquear FinAI Premium.
 *
 * SKU único: [PREMIUM_SKU] (pago único). El estado premium se persiste en
 * SharedPreferences y se sincroniza con las compras de Play Store al conectar.
 */
@Singleton
class BillingManager @Inject constructor(
    @ApplicationContext private val context: Context
) : PurchasesUpdatedListener, BillingClientStateListener, PremiumStatusProvider {

    companion object {
        private const val TAG = "BillingManager"
        private const val PREFS_NAME = "finai_billing"
        private const val KEY_IS_PREMIUM = "is_premium"
        const val PREMIUM_SKU = "finai_premium"
    }

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _isPremium = MutableStateFlow(prefs.getBoolean(KEY_IS_PREMIUM, false))
    override val isPremium: StateFlow<Boolean> = _isPremium.asStateFlow()

    private val _productDetails = MutableStateFlow<ProductDetails?>(null)
    val productDetails: StateFlow<ProductDetails?> = _productDetails.asStateFlow()

    private val _isConnecting = MutableStateFlow(false)
    val isConnecting: StateFlow<Boolean> = _isConnecting.asStateFlow()

    private val _purchaseError = MutableStateFlow<String?>(null)
    val purchaseError: StateFlow<String?> = _purchaseError.asStateFlow()

    private var billingClient: BillingClient? = null

    init {
        startConnection()
    }

    /** Conecta el BillingClient con Play Billing. */
    fun startConnection() {
        if (billingClient == null) {
            billingClient = BillingClient.newBuilder(context)
                .setListener(this)
                .enablePendingPurchases()
                .build()
        }
        if (billingClient?.isReady == true) return
        _isConnecting.value = true
        billingClient?.startConnection(this)
    }

    // ---------------- BillingClientStateListener ----------------

    override fun onBillingSetupFinished(billingResult: BillingResult) {
        _isConnecting.value = false
        if (billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
            Log.d(TAG, "BillingClient conectado")
            queryProductDetails()
            queryPurchases()
        } else {
            Log.e(TAG, "Error conectando billing: ${billingResult.responseCode}")
        }
    }

    override fun onBillingServiceDisconnected() {
        Log.w(TAG, "BillingClient desconectado")
        _isConnecting.value = false
    }

    // ---------------- Detalles del producto ----------------

    /** Consulta los detalles (precio, título) del producto premium. */
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

        client.queryProductDetailsAsync(params) { result, detailsList ->
            if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                _productDetails.value = detailsList.firstOrNull { it.productId == PREMIUM_SKU }
                if (_productDetails.value == null) {
                    Log.w(TAG, "Producto $PREMIUM_SKU no encontrado en Play Console")
                }
            } else {
                Log.e(TAG, "Error consultando producto: ${result.responseCode}")
            }
        }
    }

    // ---------------- Compras ----------------

    /** Inicia el flujo de compra de Premium desde una Activity. */
    fun launchBillingFlow(activity: Activity) {
        val client = billingClient
        val details = _productDetails.value
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

        // Para pagos únicos (INAPP) no se requiere offerToken; basta con
        // los detalles del producto.
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

    /** Llamado por Play Billing cuando una compra cambia de estado. */
    override fun onPurchasesUpdated(
        billingResult: BillingResult,
        purchases: MutableList<Purchase>?
    ) {
        when (billingResult.responseCode) {
            BillingClient.BillingResponseCode.OK -> {
                purchases?.forEach { handlePurchase(it) }
            }
            BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED -> {
                // Ya comprado: re-sincroniza el estado.
                queryPurchases()
            }
            BillingClient.BillingResponseCode.USER_CANCELED -> {
                _purchaseError.value = "Compra cancelada."
            }
            else -> {
                _purchaseError.value = "Error en la compra (código ${billingResult.responseCode})."
            }
        }
    }

    /** Confirma (acknowledge) una compra y marca Premium como activo. */
    private fun handlePurchase(purchase: Purchase) {
        if (purchase.purchaseState == Purchase.PurchaseState.PURCHASED) {
            // Marca premium inmediatamente.
            setPremium(true)

            // Confirma la compra para que no sea reembolsada automáticamente.
            if (!purchase.isAcknowledged) {
                val client = billingClient ?: return
                val params = AcknowledgePurchaseParams.newBuilder()
                    .setPurchaseToken(purchase.purchaseToken)
                    .build()
                client.acknowledgePurchase(params, AcknowledgePurchaseResponseListener { result ->
                    if (result.responseCode != BillingClient.BillingResponseCode.OK) {
                        Log.w(TAG, "Error acknowledge: ${result.responseCode}")
                    }
                })
            }
        }
    }

    /** Sincroniza el estado premium con las compras registradas en Play Store. */
    fun queryPurchases() {
        val client = billingClient ?: return
        if (!client.isReady) return

        val params = QueryPurchasesParams.newBuilder()
            .setProductType(BillingClient.ProductType.INAPP)
            .build()

        client.queryPurchasesAsync(params) { _, purchasesList ->
            val owns = purchasesList.any {
                it.products.contains(PREMIUM_SKU) &&
                    it.purchaseState == Purchase.PurchaseState.PURCHASED
            }
            setPremium(owns)
        }
    }

    private fun setPremium(value: Boolean) {
        prefs.edit().putBoolean(KEY_IS_PREMIUM, value).apply()
        _isPremium.value = value
    }

    /** `true` solo en builds depurables (debug), `false` en release. */
    val isDebugBuild: Boolean =
        (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0

    /**
     * SOLO builds debug: fuerza el estado Premium para probar las funciones
     * de pago sin una compra real. En release es un no-op.
     */
    fun debugSetPremium(value: Boolean) {
        if (!isDebugBuild) return
        setPremium(value)
    }

    /** Limpia el último error de compra mostrado en UI. */
    fun clearError() {
        _purchaseError.value = null
    }

    /** Fin de sesión del billing client (llamar si fuera necesario). */
    fun endConnection() {
        billingClient?.endConnection()
        billingClient = null
    }
}
