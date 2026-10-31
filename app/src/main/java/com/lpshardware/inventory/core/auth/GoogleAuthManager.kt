package com.lpshardware.inventory.core.auth

import android.accounts.AccountManager
import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Immutable
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.lpshardware.inventory.core.sync.SyncManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.TimeUnit

@Immutable
data class SignedInGoogleUser(
    val googleUserId: String,
    val email: String,
    val displayName: String,
    val photoUrl: String = "",
    val firebaseUid: String = "",
    val firebaseIdToken: String = "",
    val firebaseRefreshToken: String = "",
    val tokenExpiresAtMs: Long = 0L
) {
    /**
     * Deterministic cloud collection key for this Google account so that logging in with the
     * same Google account on ANY new device maps to the exact same Firestore document path.
     */
    val cloudAccountDocumentId: String
        get() {
            val base = email.ifBlank { googleUserId }.lowercase().trim()
            return "user_" + base.replace(Regex("""[^a-z0-9]+"""), "_").trim('_')
        }
}

object GoogleAuthManager {
    private const val PREFS_NAME = "lps_google_auth_prefs"
    private const val KEY_GOOGLE_ID = "google_user_id"
    private const val KEY_EMAIL = "google_email"
    private const val KEY_DISPLAY_NAME = "google_display_name"
    private const val KEY_PHOTO_URL = "google_photo_url"
    private const val KEY_FIREBASE_UID = "firebase_uid"
    private const val KEY_FIREBASE_ID_TOKEN = "firebase_id_token"
    private const val KEY_FIREBASE_REFRESH_TOKEN = "firebase_refresh_token"
    private const val KEY_TOKEN_EXPIRES_AT = "token_expires_at_ms"

    private val _currentUser = MutableStateFlow<SignedInGoogleUser?>(null)
    val currentUser: StateFlow<SignedInGoogleUser?> = _currentUser.asStateFlow()

    private val httpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()
    }

    fun init(context: Context) {
        _currentUser.value = loadSavedUser(context)
    }

    fun getGoogleSignInClient(context: Context, includeIdTokenRequest: Boolean = true): GoogleSignInClient {
        val syncConfig = SyncManager.getConfig(context)
        val gsoBuilder = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestEmail()
            .requestProfile()

        if (includeIdTokenRequest && syncConfig.webClientId.isNotBlank()) {
            gsoBuilder.requestIdToken(syncConfig.webClientId)
        }
        return GoogleSignIn.getClient(context, gsoBuilder.build())
    }

    fun buildSignInIntent(context: Context, includeIdTokenRequest: Boolean = true): Intent {
        return getGoogleSignInClient(context, includeIdTokenRequest).signInIntent
    }

    /**
     * Native Android / Google Play Services Account Picker intent that works in 1 tap
     * without requiring a SHA-1 certificate hash (avoiding GoogleSignIn Code 10 DEVELOPER_ERROR).
     */
    fun buildGoogleAccountPickerIntent(): Intent {
        return AccountManager.newChooseAccountIntent(
            null,
            null,
            arrayOf("com.google"),
            null,
            null,
            null,
            null
        )
    }

    /**
     * Completes sign-in using the Google account email selected from the native Android Account Picker.
     * Also acquires a Firebase Auth session token if available and triggers an immediate full Cloud Sync.
     */
    suspend fun handleGoogleEmailSignIn(
        context: Context,
        rawEmail: String
    ): SignedInGoogleUser = withContext(Dispatchers.IO) {
        val email = rawEmail.trim().lowercase(Locale.ROOT)
        val displayName = email.substringBefore("@")
            .split(".", "_", "-")
            .filter { it.isNotBlank() }
            .joinToString(" ") { part ->
                part.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.ROOT) else it.toString() }
            }
            .ifBlank { "LPS Shop Owner" }

        val config = SyncManager.getConfig(context)
        var firebaseUid = ""
        var firebaseIdToken = ""
        var firebaseRefreshToken = ""
        var expiresAtMs = 0L

        if (config.firebaseApiKey.isNotBlank()) {
            val sessionJson = obtainFirebaseAnonymousOrEmailSession(config.firebaseApiKey)
            if (sessionJson != null) {
                firebaseUid = sessionJson.optString("localId", "")
                firebaseIdToken = sessionJson.optString("idToken", "")
                firebaseRefreshToken = sessionJson.optString("refreshToken", "")
                val expiresInSec = sessionJson.optString("expiresIn", "3600").toLongOrNull() ?: 3600L
                expiresAtMs = System.currentTimeMillis() + (expiresInSec * 1000L) - 60_000L
            }
        }

        val signedInUser = SignedInGoogleUser(
            googleUserId = email,
            email = email,
            displayName = displayName,
            photoUrl = "",
            firebaseUid = firebaseUid,
            firebaseIdToken = firebaseIdToken,
            firebaseRefreshToken = firebaseRefreshToken,
            tokenExpiresAtMs = expiresAtMs
        )

        saveUser(context, signedInUser)
        _currentUser.value = signedInUser

        SyncManager.onUserSignedInTriggerFullSync(context, signedInUser.cloudAccountDocumentId)
        signedInUser
    }

    /**
     * Completes the sign-in flow after the user picks their Google Account in the Android Account Picker.
     * If Firebase Web API Key + Google ID Token are present, exchanges the Google ID Token with
     * Firebase Authentication (`identitytoolkit.googleapis.com/v1/accounts:signInWithIdp`).
     * Then triggers an immediate full Cloud-to-Local sync so all items download onto the new device.
     */
    suspend fun handleGoogleAccountSignIn(
        context: Context,
        account: GoogleSignInAccount
    ): SignedInGoogleUser = withContext(Dispatchers.IO) {
        val email = account.email.orEmpty()
        val displayName = account.displayName?.takeIf { it.isNotBlank() }
            ?: email.substringBefore("@").ifEmpty { "LPS Shop Owner" }
        val googleId = account.id ?: email.ifEmpty { "default_owner" }
        val photoUrl = account.photoUrl?.toString().orEmpty()
        val googleIdToken = account.idToken.orEmpty()

        val config = SyncManager.getConfig(context)
        var firebaseUid = ""
        var firebaseIdToken = ""
        var firebaseRefreshToken = ""
        var expiresAtMs = 0L

        if (googleIdToken.isNotBlank() && config.firebaseApiKey.isNotBlank()) {
            val exchanged = exchangeGoogleTokenWithFirebase(
                firebaseApiKey = config.firebaseApiKey,
                googleIdToken = googleIdToken
            )
            if (exchanged != null) {
                firebaseUid = exchanged.optString("localId", "")
                firebaseIdToken = exchanged.optString("idToken", "")
                firebaseRefreshToken = exchanged.optString("refreshToken", "")
                val expiresInSec = exchanged.optString("expiresIn", "3600").toLongOrNull() ?: 3600L
                expiresAtMs = System.currentTimeMillis() + (expiresInSec * 1000L) - 60_000L
            }
        }

        val signedInUser = SignedInGoogleUser(
            googleUserId = googleId,
            email = email,
            displayName = displayName,
            photoUrl = photoUrl,
            firebaseUid = firebaseUid,
            firebaseIdToken = firebaseIdToken,
            firebaseRefreshToken = firebaseRefreshToken,
            tokenExpiresAtMs = expiresAtMs
        )

        saveUser(context, signedInUser)
        _currentUser.value = signedInUser

        // Automatically bind the shop collection namespace to this user's Google account
        // if still on the default namespace, and trigger an immediate Full Pull + Push Sync!
        SyncManager.onUserSignedInTriggerFullSync(context, signedInUser.cloudAccountDocumentId)

        signedInUser
    }

    /**
     * Returns a valid Firebase ID token for authenticated Firestore calls, automatically
     * refreshing via `securetoken.googleapis.com` if expired.
     */
    suspend fun getValidFirebaseIdToken(context: Context): String? = withContext(Dispatchers.IO) {
        val user = _currentUser.value ?: loadSavedUser(context) ?: return@withContext null
        if (user.firebaseIdToken.isBlank()) return@withContext null

        val now = System.currentTimeMillis()
        if (now < user.tokenExpiresAtMs) {
            return@withContext user.firebaseIdToken
        }

        val apiKey = SyncManager.getConfig(context).firebaseApiKey
        if (apiKey.isBlank() || user.firebaseRefreshToken.isBlank()) {
            return@withContext user.firebaseIdToken
        }

        try {
            val url = "https://securetoken.googleapis.com/v1/token?key=$apiKey"
            val bodyJson = JSONObject()
                .put("grant_type", "refresh_token")
                .put("refresh_token", user.firebaseRefreshToken)
                .toString()

            val request = Request.Builder()
                .url(url)
                .post(bodyJson.toRequestBody("application/json; charset=utf-8".toMediaType()))
                .build()

            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext user.firebaseIdToken
                val respStr = response.body?.string().orEmpty()
                val json = JSONObject(respStr)
                val newIdToken = json.optString("id_token", user.firebaseIdToken)
                val newRefresh = json.optString("refresh_token", user.firebaseRefreshToken)
                val expiresInSec = json.optString("expires_in", "3600").toLongOrNull() ?: 3600L

                val updated = user.copy(
                    firebaseIdToken = newIdToken,
                    firebaseRefreshToken = newRefresh,
                    tokenExpiresAtMs = System.currentTimeMillis() + (expiresInSec * 1000L) - 60_000L
                )
                saveUser(context, updated)
                _currentUser.value = updated
                newIdToken
            }
        } catch (_: Exception) {
            user.firebaseIdToken
        }
    }

    fun signOut(context: Context) {
        try {
            getGoogleSignInClient(context, includeIdTokenRequest = false).signOut()
        } catch (_: Exception) {
        }
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .apply()
        _currentUser.value = null
    }

    private fun exchangeGoogleTokenWithFirebase(
        firebaseApiKey: String,
        googleIdToken: String
    ): JSONObject? {
        return try {
            val url = "https://identitytoolkit.googleapis.com/v1/accounts:signInWithIdp?key=$firebaseApiKey"
            val payload = JSONObject()
                .put("postBody", "id_token=$googleIdToken&providerId=google.com")
                .put("requestUri", "http://localhost")
                .put("returnIdpCredential", true)
                .put("returnSecureToken", true)
                .toString()

            val req = Request.Builder()
                .url(url)
                .post(payload.toRequestBody("application/json; charset=utf-8".toMediaType()))
                .build()

            httpClient.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) {
                    val bodyStr = resp.body?.string().orEmpty()
                    JSONObject(bodyStr)
                } else {
                    null
                }
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun obtainFirebaseAnonymousOrEmailSession(firebaseApiKey: String): JSONObject? {
        return try {
            val url = "https://identitytoolkit.googleapis.com/v1/accounts:signUp?key=$firebaseApiKey"
            val payload = JSONObject()
                .put("returnSecureToken", true)
                .toString()

            val req = Request.Builder()
                .url(url)
                .post(payload.toRequestBody("application/json; charset=utf-8".toMediaType()))
                .build()

            httpClient.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) {
                    val bodyStr = resp.body?.string().orEmpty()
                    JSONObject(bodyStr)
                } else {
                    null
                }
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun saveUser(context: Context, user: SignedInGoogleUser) {
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_GOOGLE_ID, user.googleUserId)
            .putString(KEY_EMAIL, user.email)
            .putString(KEY_DISPLAY_NAME, user.displayName)
            .putString(KEY_PHOTO_URL, user.photoUrl)
            .putString(KEY_FIREBASE_UID, user.firebaseUid)
            .putString(KEY_FIREBASE_ID_TOKEN, user.firebaseIdToken)
            .putString(KEY_FIREBASE_REFRESH_TOKEN, user.firebaseRefreshToken)
            .putLong(KEY_TOKEN_EXPIRES_AT, user.tokenExpiresAtMs)
            .apply()
    }

    private fun loadSavedUser(context: Context): SignedInGoogleUser? {
        val p = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val email = p.getString(KEY_EMAIL, "")?.trim().orEmpty()
        val googleId = p.getString(KEY_GOOGLE_ID, "")?.trim().orEmpty()
        if (email.isEmpty() && googleId.isEmpty()) return null
        return SignedInGoogleUser(
            googleUserId = googleId,
            email = email,
            displayName = p.getString(KEY_DISPLAY_NAME, "")?.trim().orEmpty().ifEmpty { email },
            photoUrl = p.getString(KEY_PHOTO_URL, "").orEmpty(),
            firebaseUid = p.getString(KEY_FIREBASE_UID, "").orEmpty(),
            firebaseIdToken = p.getString(KEY_FIREBASE_ID_TOKEN, "").orEmpty(),
            firebaseRefreshToken = p.getString(KEY_FIREBASE_REFRESH_TOKEN, "").orEmpty(),
            tokenExpiresAtMs = p.getLong(KEY_TOKEN_EXPIRES_AT, 0L)
        )
    }
}
