package ar.com.hst.app

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import androidx.appcompat.app.AppCompatActivity
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import ar.com.hst.app.databinding.ActivityLoginBinding
import okhttp3.*
import org.json.JSONObject
import java.io.IOException

class LoginActivity : AppCompatActivity() {

    private lateinit var b: ActivityLoginBinding
    private lateinit var session: SessionManager
    private var deepLinkUrl: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(b.root)

        session = SessionManager(this)

        // Check for deep link
        deepLinkUrl = intent?.data?.toString()

        if (session.isLoggedIn) {
            goToDashboard()
            return
        }

        // Biometric setup
        if (canUseBiometric() && session.hasBiometricCredentials()) {
            b.biometricButton.visibility = View.VISIBLE
            showBiometricPrompt()
        }

        b.loginButton.setOnClickListener { doLogin() }
        b.biometricButton.setOnClickListener { showBiometricPrompt() }
        b.passwordInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) { doLogin(); true } else false
        }
    }

    private fun canUseBiometric(): Boolean {
        val manager = BiometricManager.from(this)
        return manager.canAuthenticate(
            BiometricManager.Authenticators.BIOMETRIC_STRONG or
            BiometricManager.Authenticators.BIOMETRIC_WEAK
        ) == BiometricManager.BIOMETRIC_SUCCESS
    }

    private fun showBiometricPrompt() {
        val executor = ContextCompat.getMainExecutor(this)
        val prompt = BiometricPrompt(this, executor, object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                val creds = session.getBiometricCredentials()
                if (creds != null) {
                    b.emailInput.setText(creds.first)
                    doLoginWithCredentials(creds.first, creds.second)
                }
            }
            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                // User cancelled or error - show normal login form
            }
            override fun onAuthenticationFailed() {
                // Fingerprint not recognized
            }
        })

        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle("Acceso HST")
            .setSubtitle("Usa tu huella para ingresar")
            .setNegativeButtonText("Usar contrasena")
            .build()

        prompt.authenticate(promptInfo)
    }

    private fun doLogin() {
        val email = b.emailInput.text.toString().trim().lowercase()
        val password = b.passwordInput.text.toString()

        if (email.isBlank() || password.isBlank()) {
            showError("Completa email y contrasena")
            return
        }

        hideKeyboard()
        doLoginWithCredentials(email, password)
    }

    private fun doLoginWithCredentials(email: String, password: String) {
        b.progressBar.visibility = View.VISIBLE
        b.loginButton.isEnabled = false
        b.biometricButton.isEnabled = false
        b.errorText.visibility = View.GONE

        val client = OkHttpClient.Builder()
            .followRedirects(false)
            .cookieJar(SimpleCookieJar())
            .build()

        val getReq = Request.Builder()
            .url("https://hst.ar/asociados/login.php")
            .get()
            .build()

        client.newCall(getReq).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                runOnUiThread {
                    b.progressBar.visibility = View.GONE
                    b.loginButton.isEnabled = true
                    b.biometricButton.isEnabled = true
                    showError("Sin conexion: ${e.message}")
                }
            }

            override fun onResponse(call: Call, response: Response) {
                val body = response.body?.string() ?: ""
                val csrfRegex = Regex("""name="_csrf"\s+value="([^"]+)"""")
                val csrf = csrfRegex.find(body)?.groupValues?.get(1) ?: ""

                val cookies = response.headers("Set-Cookie")
                val sessionCookieStr = cookies.find { it.contains("HST_ASSOC_SESSID") } ?: ""
                val cookieValue = sessionCookieStr.substringBefore(";")

                val formBody = FormBody.Builder()
                    .add("email", email)
                    .add("password", password)
                    .add("_csrf", csrf)
                    .build()

                val postReq = Request.Builder()
                    .url("https://hst.ar/asociados/login.php")
                    .header("Cookie", cookieValue)
                    .post(formBody)
                    .build()

                client.newCall(postReq).enqueue(object : Callback {
                    override fun onFailure(call: Call, e: IOException) {
                        runOnUiThread {
                            b.progressBar.visibility = View.GONE
                            b.loginButton.isEnabled = true
                            b.biometricButton.isEnabled = true
                            showError("Error de conexion")
                        }
                    }

                    override fun onResponse(call: Call, response: Response) {
                        val respBody = response.body?.string() ?: ""
                        val isSuccess = response.code == 302 ||
                                respBody.contains("Asociados") && !respBody.contains("Ingreso")

                        val newCookies = response.headers("Set-Cookie")
                        val finalCookie = newCookies.find { it.contains("HST_ASSOC_SESSID") }
                            ?.substringBefore(";") ?: cookieValue

                        if (isSuccess) {
                            session.email = email
                            session.sessionCookie = finalCookie
                            session.syncCookiesToWebView()
                            if (canUseBiometric()) {
                                session.saveBiometricCredentials(email, password)
                            }
                            fetchUserTools(finalCookie)
                        } else {
                            runOnUiThread {
                                b.progressBar.visibility = View.GONE
                                b.loginButton.isEnabled = true
                                b.biometricButton.isEnabled = true
                                showError("Email o contrasena incorrectos")
                            }
                        }
                    }
                })
            }
        })
    }

    private fun showError(msg: String) {
        b.errorText.text = msg
        b.errorText.visibility = View.VISIBLE
    }

    private fun hideKeyboard() {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(b.root.windowToken, 0)
    }

    private fun fetchUserTools(cookie: String) {
        val client = OkHttpClient()
        val request = Request.Builder()
            .url("https://hst.ar/asociados/api/me.php")
            .header("Cookie", cookie)
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                runOnUiThread {
                    b.progressBar.visibility = View.GONE
                    b.loginButton.isEnabled = true
                    b.biometricButton.isEnabled = true
                    goToDashboard()
                }
            }

            override fun onResponse(call: Call, response: Response) {
                try {
                    val json = JSONObject(response.body?.string() ?: "")
                    if (json.optBoolean("logged", false)) {
                        val tools = json.optJSONArray("app_tools")
                        if (tools != null) {
                            val toolSet = mutableSetOf<String>()
                            for (i in 0 until tools.length()) {
                                toolSet.add(tools.getString(i))
                            }
                            session.allowedTools = toolSet
                        }
                        // Store CSRF token for native API calls
                        val csrf = json.optString("csrf", "")
                        if (csrf.isNotBlank()) {
                            session.csrfToken = csrf
                        }
                    }
                } catch (_: Exception) {}
                runOnUiThread {
                    b.progressBar.visibility = View.GONE
                    b.loginButton.isEnabled = true
                    b.biometricButton.isEnabled = true
                    goToDashboard()
                }
            }
        })
    }

    private fun goToDashboard() {
        val url = deepLinkUrl
        if (url != null && url.contains("hst.ar")) {
            session.syncCookiesToWebView()
            val intent = Intent(this, ToolWebViewActivity::class.java).apply {
                putExtra("url", url)
                putExtra("title", "HST")
            }
            startActivity(intent)
        } else {
            startActivity(Intent(this, DashboardActivity::class.java))
        }
        finish()
    }

    // Simple cookie jar for OkHttp
    class SimpleCookieJar : CookieJar {
        private val store = mutableListOf<Cookie>()
        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) { store.addAll(cookies) }
        override fun loadForRequest(url: HttpUrl): List<Cookie> = store
    }
}
