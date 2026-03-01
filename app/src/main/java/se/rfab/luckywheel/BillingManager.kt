package se.rfab.luckywheel

import android.app.Activity
import android.content.Context
import android.util.Log
import com.android.billingclient.api.AcknowledgePurchaseParams
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.ConsumeParams
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.PurchasesUpdatedListener
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams
import com.android.billingclient.api.acknowledgePurchase
import com.android.billingclient.api.consumePurchase
import com.android.billingclient.api.queryProductDetails
import com.android.billingclient.api.queryPurchasesAsync
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

// Product IDs matching Play Console setup
const val PRODUCT_SESSION  = "extra_options_session"
const val PRODUCT_LIFETIME = "extra_options_lifetime"

val BILLING_DEBUG_MODE = BuildConfig.BILLING_DEBUG_MODE

class BillingManager(
    private val context: Context,
    private val scope: CoroutineScope
) : PurchasesUpdatedListener {

    private val tag = "BillingManager"

    // ── Purchase state ────────────────────────────────────────────────────
    private val _sessionUnlocked  = MutableStateFlow(false)
    private val _lifetimeUnlocked = MutableStateFlow(false)

    val sessionUnlocked:  StateFlow<Boolean> = _sessionUnlocked.asStateFlow()
    val lifetimeUnlocked: StateFlow<Boolean> = _lifetimeUnlocked.asStateFlow()

    /** True whenever 4–6 options are available (either via session or lifetime purchase). */
    val hasExtraOptions: StateFlow<Boolean> =
        combine(_sessionUnlocked, _lifetimeUnlocked) { s, l -> s || l }
            .stateIn(scope, SharingStarted.Eagerly, false)

    // ── Displayed prices (updated from ProductDetails; defaults are static) ─
    private val _sessionPrice  = MutableStateFlow("1 USD")
    private val _lifetimePrice = MutableStateFlow("15 USD")
    val sessionPrice:  StateFlow<String> = _sessionPrice.asStateFlow()
    val lifetimePrice: StateFlow<String> = _lifetimePrice.asStateFlow()

    // ── Error events ──────────────────────────────────────────────────────
    private val _billingError = MutableSharedFlow<String>()
    val billingError = _billingError.asSharedFlow()

    // ── Session expiry timer ──────────────────────────────────────────────
    private var sessionExpiryJob: Job? = null

    // ── Internal billing client ───────────────────────────────────────────
    private var billingClient: BillingClient? = null
    private var productDetails: Map<String, ProductDetails> = emptyMap()

    init {
        // Restore persisted lifetime unlock from DataStore
        scope.launch {
            AppSettings.lifetimeUnlocked(context).collect { unlocked ->
                _lifetimeUnlocked.value = unlocked
            }
        }

        if (!BILLING_DEBUG_MODE) {
            setupBillingClient()
        } else {
            Log.d(tag, "[DEBUG] Mock billing mode active – real Play Billing is disabled.")
        }
    }

    // ── BillingClient setup ───────────────────────────────────────────────

    private fun setupBillingClient() {
        billingClient = BillingClient.newBuilder(context)
            .setListener(this)
            .enablePendingPurchases(
                PendingPurchasesParams.newBuilder()
                    .enableOneTimeProducts()
                    .build()
            )
            .build()
        connect()
    }

    private fun connect() {
        billingClient?.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(result: BillingResult) {
                if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                    Log.d(tag, "BillingClient connected.")
                    scope.launch {
                        queryProductDetails()
                        restorePurchases()
                    }
                } else {
                    Log.w(tag, "BillingClient setup failed: ${result.debugMessage}")
                }
            }

            override fun onBillingServiceDisconnected() {
                Log.w(tag, "BillingClient disconnected – retrying in 5 s.")
                scope.launch {
                    delay(5_000)
                    connect()
                }
            }
        })
    }

    private suspend fun queryProductDetails() {
        val client = billingClient ?: return
        val params = QueryProductDetailsParams.newBuilder()
            .setProductList(
                listOf(
                    QueryProductDetailsParams.Product.newBuilder()
                        .setProductId(PRODUCT_SESSION)
                        .setProductType(BillingClient.ProductType.INAPP)
                        .build(),
                    QueryProductDetailsParams.Product.newBuilder()
                        .setProductId(PRODUCT_LIFETIME)
                        .setProductType(BillingClient.ProductType.INAPP)
                        .build()
                )
            )
            .build()

        val result = client.queryProductDetails(params)
        if (result.billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
            productDetails = result.productDetailsList?.associateBy { it.productId } ?: emptyMap()
            // Update displayed prices from Play Console metadata
            productDetails[PRODUCT_SESSION]?.oneTimePurchaseOfferDetails
                ?.formattedPrice?.let { _sessionPrice.value = it }
            productDetails[PRODUCT_LIFETIME]?.oneTimePurchaseOfferDetails
                ?.formattedPrice?.let { _lifetimePrice.value = it }
            Log.d(tag, "Product details loaded: ${productDetails.keys}")
        } else {
            Log.w(tag, "queryProductDetails failed: ${result.billingResult.debugMessage}")
        }
    }

    private suspend fun restorePurchases() {
        val client = billingClient ?: return
        val params = QueryPurchasesParams.newBuilder()
            .setProductType(BillingClient.ProductType.INAPP)
            .build()
        val result = client.queryPurchasesAsync(params)
        if (result.billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
            result.purchasesList.forEach { handlePurchase(it) }
        }
    }

    // ── Purchase flow ─────────────────────────────────────────────────────

    /**
     * Launch the purchase UI for [productId].
     * In debug mode this simulates a successful purchase without Play Console.
     */
    fun launchPurchase(activity: Activity, productId: String) {
        if (BILLING_DEBUG_MODE) {
            Log.d(tag, "[DEBUG] Simulating purchase: $productId")
            scope.launch { simulatePurchase(productId) }
            return
        }

        val details = productDetails[productId]
        if (details == null) {
            scope.launch {
                _billingError.emit("Produktinformation saknas. Kontrollera nätverksanslutning och försök igen.")
            }
            return
        }

        val productParams = BillingFlowParams.ProductDetailsParams.newBuilder()
            .setProductDetails(details)
            .build()

        val flowParams = BillingFlowParams.newBuilder()
            .setProductDetailsParamsList(listOf(productParams))
            .build()

        val result = billingClient?.launchBillingFlow(activity, flowParams)
        if (result?.responseCode != BillingClient.BillingResponseCode.OK) {
            scope.launch {
                _billingError.emit("Kunde inte öppna köpdialogrutan (${result?.debugMessage}).")
            }
        }
    }

    // ── PurchasesUpdatedListener ──────────────────────────────────────────

    override fun onPurchasesUpdated(result: BillingResult, purchases: List<Purchase>?) {
        when (result.responseCode) {
            BillingClient.BillingResponseCode.OK -> {
                purchases?.forEach { purchase ->
                    scope.launch { handlePurchase(purchase) }
                }
            }
            BillingClient.BillingResponseCode.USER_CANCELED -> {
                Log.d(tag, "User cancelled purchase.")
            }
            else -> {
                Log.w(tag, "Purchase update error: ${result.debugMessage}")
                scope.launch {
                    _billingError.emit("Köpet misslyckades (kod ${result.responseCode}).")
                }
            }
        }
    }

    // ── Purchase handling ─────────────────────────────────────────────────

    private suspend fun handlePurchase(purchase: Purchase) {
        if (purchase.purchaseState != Purchase.PurchaseState.PURCHASED) return

        purchase.products.forEach { productId ->
            when (productId) {
                PRODUCT_LIFETIME -> {
                    Log.d(tag, "Lifetime purchase confirmed.")
                    AppSettings.setLifetimeUnlocked(context, true)
                    _lifetimeUnlocked.value = true
                    if (!purchase.isAcknowledged) acknowledgePurchase(purchase)
                }
                PRODUCT_SESSION -> {
                    Log.d(tag, "Session purchase confirmed.")
                    _sessionUnlocked.value = true
                    startSessionTimer()
                    consumePurchase(purchase) // Makes it re-purchasable
                }
            }
        }
    }

    private suspend fun acknowledgePurchase(purchase: Purchase) {
        val client = billingClient ?: return
        val params = AcknowledgePurchaseParams.newBuilder()
            .setPurchaseToken(purchase.purchaseToken)
            .build()
        val result = client.acknowledgePurchase(params)
        if (result.responseCode != BillingClient.BillingResponseCode.OK) {
            Log.w(tag, "acknowledgePurchase failed: ${result.debugMessage}")
        }
    }

    private suspend fun consumePurchase(purchase: Purchase) {
        val client = billingClient ?: return
        val params = ConsumeParams.newBuilder()
            .setPurchaseToken(purchase.purchaseToken)
            .build()
        val result = client.consumePurchase(params)
        if (result.billingResult.responseCode != BillingClient.BillingResponseCode.OK) {
            Log.w(tag, "consumePurchase failed: ${result.billingResult.debugMessage}")
        }
    }

    // ── Debug mock ────────────────────────────────────────────────────────

    private suspend fun simulatePurchase(productId: String) {
        delay(400) // Simulate a short network round-trip
        when (productId) {
            PRODUCT_LIFETIME -> {
                AppSettings.setLifetimeUnlocked(context, true)
                _lifetimeUnlocked.value = true
                Log.d(tag, "[DEBUG] Lifetime unlocked via mock purchase.")
            }
            PRODUCT_SESSION -> {
                _sessionUnlocked.value = true
                startSessionTimer()
                Log.d(tag, "[DEBUG] Session unlocked via mock purchase.")
            }
        }
    }

    // ── Session management ────────────────────────────────────────────────

    private fun startSessionTimer() {
        sessionExpiryJob?.cancel()
        sessionExpiryJob = scope.launch {
            delay(12 * 60 * 60 * 1000L)
            _sessionUnlocked.value = false
        }
    }

    /** Call when the user leaves the wheel to expire the session purchase. */
    fun resetSession() {
        _sessionUnlocked.value = false
    }

    /** Call from onDestroy / DisposableEffect to clean up the BillingClient. */
    fun destroy() {
        billingClient?.endConnection()
    }
}
