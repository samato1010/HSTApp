package ar.com.hst.app

import android.content.Context
import android.content.SharedPreferences
import android.webkit.CookieManager
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys

class SessionManager(context: Context) {
    private val ctx = context.applicationContext
    private val prefs = ctx.getSharedPreferences("hst_session", Context.MODE_PRIVATE)

    var email: String
        get() = prefs.getString("email", "") ?: ""
        set(v) = prefs.edit().putString("email", v).apply()

    var sessionCookie: String
        get() = prefs.getString("session_cookie", "") ?: ""
        set(v) = prefs.edit().putString("session_cookie", v).apply()

    var csrfToken: String
        get() = prefs.getString("csrf_token", "") ?: ""
        set(v) = prefs.edit().putString("csrf_token", v).apply()

    var allowedTools: Set<String>
        get() {
            val str = prefs.getString("allowed_tools", "") ?: ""
            return if (str.isBlank()) emptySet() else str.split(",").toSet()
        }
        set(v) = prefs.edit().putString("allowed_tools", v.joinToString(",")).apply()

    val isLoggedIn: Boolean
        get() = email.isNotBlank() && sessionCookie.isNotBlank()

    fun syncCookiesToWebView() {
        val cm = CookieManager.getInstance()
        if (sessionCookie.isNotBlank()) {
            cm.setCookie("https://hst.ar", sessionCookie)
        }
        cm.flush()
    }

    fun logout() {
        prefs.edit().clear().apply()
        CookieManager.getInstance().removeAllCookies(null)
        CookieManager.getInstance().flush()
    }

    // --- Biometric credential storage (encrypted) ---

    private val encPrefs: SharedPreferences? by lazy {
        try {
            val masterKeyAlias = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC)
            EncryptedSharedPreferences.create(
                "hst_secure",
                masterKeyAlias,
                ctx,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (_: Exception) {
            null
        }
    }

    fun saveBiometricCredentials(email: String, password: String) {
        encPrefs?.edit()
            ?.putString("bio_email", email)
            ?.putString("bio_pass", password)
            ?.apply()
    }

    fun getBiometricCredentials(): Pair<String, String>? {
        val ep = encPrefs ?: return null
        val e = ep.getString("bio_email", "") ?: ""
        val p = ep.getString("bio_pass", "") ?: ""
        return if (e.isNotBlank() && p.isNotBlank()) Pair(e, p) else null
    }

    fun hasBiometricCredentials(): Boolean = getBiometricCredentials() != null

    fun clearBiometricCredentials() {
        encPrefs?.edit()?.clear()?.apply()
    }
}
