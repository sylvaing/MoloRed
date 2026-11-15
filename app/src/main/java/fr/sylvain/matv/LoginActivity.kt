package fr.sylvain.matv

import android.annotation.SuppressLint
import android.app.Activity
import android.os.Bundle
import android.view.View
import android.view.inputmethod.EditorInfo
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.TextView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * App's own login screen (login + password). The credentials are submitted to SFR's
 * official login form in an invisible WebView and kept encrypted (Keystore) only if
 * SFR accepts them. Fallback: the official SFR page, e.g. if SFR asks for a captcha.
 */
class LoginActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var user: EditText
    private lateinit var password: EditText
    private lateinit var error: TextView
    private lateinit var login: Button
    private lateinit var progress: ProgressBar
    private var web: WebView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_login)
        user = findViewById(R.id.user)
        password = findViewById(R.id.password)
        error = findViewById(R.id.error)
        login = findViewById(R.id.login)
        progress = findViewById(R.id.progress)

        password.typeface = android.graphics.Typeface.DEFAULT // textPassword defaults to monospace
        CredStore.user(this)?.let { user.setText(it) }
        login.setOnClickListener { submit() }
        password.setOnEditorActionListener { _, action, _ ->
            if (action == EditorInfo.IME_ACTION_DONE) { submit(); true } else false
        }
        findViewById<Button>(R.id.sfr_page).setOnClickListener { showSfrPage() }
        intent.getStringExtra(EXTRA_ERROR)?.let { showError(it) }
        (if (user.text.isEmpty()) user else password).requestFocus()
    }

    override fun onDestroy() {
        scope.cancel()
        web?.destroy()
        super.onDestroy()
    }

    private fun showError(msg: String) {
        error.text = msg
        error.visibility = View.VISIBLE
    }

    private fun submit() {
        val u = user.text.toString().trim()
        val p = password.text.toString()
        if (u.isEmpty() || p.isEmpty()) { showError(getString(R.string.login_empty)); return }
        error.visibility = View.GONE
        login.isEnabled = false
        login.text = getString(R.string.login_progress)
        progress.visibility = View.VISIBLE
        scope.launch {
            val r = Auth.loginWithPassword(this@LoginActivity, u, p)
            progress.visibility = View.GONE
            login.isEnabled = true
            login.text = getString(R.string.login_button)
            when (r) {
                is LoginResult.Ok -> { setResult(RESULT_OK); finish() }
                is LoginResult.Rejected -> { showError(r.message); password.setText(""); password.requestFocus() }
                LoginResult.Failed -> showError(getString(R.string.login_failed))
            }
        }
    }

    /** Fallback: official SFR login page, visible. */
    @SuppressLint("SetJavaScriptEnabled")
    private fun showSfrPage() {
        val wv = WebView(this)
        web = wv
        setContentView(wv)
        CookieManager.getInstance().setAcceptThirdPartyCookies(wv, true)
        wv.settings.javaScriptEnabled = true
        wv.settings.domStorageEnabled = true
        wv.settings.useWideViewPort = true
        wv.settings.loadWithOverviewMode = true
        wv.setInitialScale(150)
        wv.webViewClient = object : WebViewClient() {
            fun check(url: String): Boolean {
                val r = Auth.parseRedirect(url) ?: return false
                Auth.saveToken(this@LoginActivity, r.first, r.second)
                setResult(RESULT_OK)
                finish()
                return true
            }
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) =
                check(request.url.toString())
            override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
                check(url)
            }
        }
        wv.loadUrl(Sfr.authorizeUrl())
        wv.requestFocus()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        val wv = web
        if (wv != null && wv.canGoBack()) wv.goBack() else {
            setResult(RESULT_CANCELED)
            @Suppress("DEPRECATION")
            super.onBackPressed()
        }
    }

    companion object { const val EXTRA_ERROR = "error" }
}
