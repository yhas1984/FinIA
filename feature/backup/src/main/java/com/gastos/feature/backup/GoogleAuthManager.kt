package com.gastos.feature.backup

import android.content.Context
import android.content.Intent
import com.google.android.gms.auth.GoogleAuthException
import com.google.android.gms.auth.GoogleAuthUtil
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.Scope
import com.google.api.services.sheets.v4.SheetsScopes
import com.google.android.gms.tasks.Tasks
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manager **único** de la autenticación con Google.
 *
 * El problema que resuelve: `GoogleSignIn.getClient(context, gso)` registra
 * las opciones GLOBALES en Play Services. Si varios componentes (BackupService,
 * SheetsExportService, SheetsSyncManager) crean cada uno su propio cliente
 * con scopes distintos, el último en inicializarse SOBREESCRIBE las opciones
 * anteriores, y las llamadas de los otros fallan porque los scopes que
 * esperaban ya no están registrados.
 *
 * Este manager centraliza UNA sola instancia de `GoogleSignInClient` con
 * los scopes correctos: SPREADSHEETS + DRIVE_FILE.
 *
 * Además, encapsula `GoogleAuthUtil.getToken()` con control de errores
 * robusto: token cacheado, `silentSignIn()` para auto-recuperación, y
 * propagación tipada de errores.
 */
@Singleton
class GoogleAuthManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        // Scopes que la app necesita. Deben ser los mismos en TODO el código
        // que use `GoogleAuthUtil.getToken()` o `GoogleAccountCredential`.
        private val SCOPES = listOf(
            Scope(SheetsScopes.SPREADSHEETS),
            Scope("https://www.googleapis.com/auth/drive.file")
        )

        // Scope string para `GoogleAuthUtil.getToken()`. Formato:
        // "oauth2:scope1 scope2 scope3"
        val OAUTH_SCOPE_STRING: String = SCOPES.joinToString(" ") {
            if (it.scopeUri.startsWith("http")) it.scopeUri
            else "https://www.googleapis.com/auth/${it.scopeUri}"
        }.let { "oauth2:$it" }

        private const val TAG = "GoogleAuthManager"
    }

    /**
     * El ÚNICO `GoogleSignInClient` de la app. Inicializado lazy para
     * asegurar que las opciones se registran UNA sola vez.
     *
     * IMPORTANTE: NO usamos `requestIdToken(serverClientId)` porque ese flujo
     * requiere un **Web Client ID** (no Android). El cliente que el usuario
     * tiene en GCP es de tipo Android, así que sólo pedimos scopes.
     *
     * El `client_id` Android se usa internamente por Play Services para
     * validar la combinación `package_name + SHA-1` contra GCP, lo que
     * desbloquea `GoogleAuthUtil.getToken()` sin necesidad de idToken.
     */
    val signInClient: GoogleSignInClient by lazy {
        val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestEmail()
            .apply {
                SCOPES.forEach { requestScopes(it) }
            }
            .build()
        android.util.Log.d(TAG, "GoogleSignIn inicializado con scopes: $SCOPES (sin requestIdToken)")
        GoogleSignIn.getClient(context, gso)
    }

    /** Intent para lanzar el flujo de sign-in. */
    fun getSignInIntent(): Intent = signInClient.signInIntent

    /**
     * ¿Hay una cuenta con los scopes correctos concedidos?
     * IMPORTANTE: usa `hasPermissions` (no solo `account != null`) para
     * verificar que la cuenta realmente tiene los scopes que la app
     * necesita.
     */
    fun isSignedIn(): Boolean {
        val account = GoogleSignIn.getLastSignedInAccount(context) ?: return false
        return SCOPES.all { GoogleSignIn.hasPermissions(account, it) }
    }

    fun getLastSignedInAccount(): GoogleSignInAccount? =
        GoogleSignIn.getLastSignedInAccount(context)

    fun getSignedInEmail(): String? = getLastSignedInAccount()?.email

    /**
     * Re-autentica silenciosamente (sin mostrar UI). Si el usuario sigue
     * autenticado en Google pero los tokens están caducados, refresca
     * automáticamente.
     *
     * Devuelve `true` si la re-autenticación tuvo éxito.
     */
    suspend fun silentReauthenticate(): Boolean = withContext(Dispatchers.IO) {
        try {
            val account = Tasks.await(signInClient.silentSignIn())
            android.util.Log.d(TAG, "silentReauthenticate OK: ${account?.email}")
            account != null
        } catch (e: Exception) {
            android.util.Log.w(TAG, "silentReauthenticate fallo: ${e.message}")
            false
        }
    }

    /**
     * Obtiene un access token para la cuenta actual con los scopes correctos.
     * Lanza [GoogleAuthException] si falla.
     *
     * Usa `GoogleAuthUtil.getToken()` directamente (no `GoogleAccountCredential`)
     * para tener control directo sobre el scope string y poder capturar
     * errores específicos.
     */
    suspend fun getAccessToken(): String = withContext(Dispatchers.IO) {
        val account: GoogleSignInAccount = getLastSignedInAccount()
            ?: throw GoogleAuthException("No hay cuenta Google con sesión activa")
        // `account.account` puede ser null en versiones recientes de Play
        // Services. Forzamos no-null con un check explícito.
        val androidAccount: android.accounts.Account = account.account
            ?: throw GoogleAuthException("La cuenta no tiene un Account de Android asociado")
        try {
            GoogleAuthUtil.getToken(context, androidAccount, OAUTH_SCOPE_STRING)
        } catch (e: GoogleAuthException) {
            android.util.Log.w(TAG, "getAccessToken fallo: ${e.message}")
            throw e
        }
    }
}
