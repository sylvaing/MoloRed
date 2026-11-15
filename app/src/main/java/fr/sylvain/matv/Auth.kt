package fr.sylvain.matv

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import kotlin.coroutines.resume

/** Outcome of a background login attempt on the SFR CAS page. */
sealed class LoginResult {
    data class Ok(val token: String) : LoginResult()
    data class Rejected(val message: String) : LoginResult()   // wrong credentials, captcha…
    object Failed : LoginResult()                               // timeout / network
}

/**
 * Holds the short-lived SFR "castoken" (the `tu` claim of the CAS OIDC access token).
 * Tokens are obtained by running SFR's own login flow in an invisible WebView:
 * silently with the CAS session cookies, or by filling the SFR login form with the
 * credentials saved in [CredStore] when the session has expired.
 */
object Auth {
    private const val PREFS = "auth"
    private const val DEFAULT_TTL_S = 12 * 60L
    private const val TAG = "MaTV"
    private val mutex = Mutex()

    fun saveToken(ctx: Context, token: String, expiresInS: Long?) {
        val exp = System.currentTimeMillis() + 1000L * (expiresInS ?: DEFAULT_TTL_S)
        ctx.getSharedPreferences(PREFS, 0).edit().putString("token", token).putLong("exp", exp).apply()
        CookieManager.getInstance().flush()
    }

    fun cachedToken(ctx: Context, marginMs: Long = 60_000): String? {
        val p = ctx.getSharedPreferences(PREFS, 0)
        val t = p.getString("token", null) ?: return null
        return if (p.getLong("exp", 0) - marginMs > System.currentTimeMillis()) t else null
    }

    /** True when the app can get a token without asking the user. */
    fun canAuthenticate(ctx: Context) =
        CredStore.load(ctx) != null || ctx.getSharedPreferences(PREFS, 0).contains("token")

    fun invalidate(ctx: Context) {
        ctx.getSharedPreferences(PREFS, 0).edit().putLong("exp", 0).apply()
    }

    fun logout(ctx: Context) {
        ctx.getSharedPreferences(PREFS, 0).edit().clear().apply()
        CredStore.clear(ctx)
        CookieManager.getInstance().removeAllCookies(null)
        CookieManager.getInstance().flush()
    }

    /** Valid token: cached, else silent refresh, else automatic login with saved credentials; null = ask the user. */
    suspend fun validToken(ctx: Context): String? = mutex.withLock {
        cachedToken(ctx)?.let { return it }
        val app = ctx.applicationContext
        val r = webLogin(app, CredStore.load(app))
        Log.i(TAG, "background auth: ${r.javaClass.simpleName}")
        (r as? LoginResult.Ok)?.token
    }

    /** Login from the app's own form: saves the credentials only if SFR accepts them. */
    suspend fun loginWithPassword(ctx: Context, user: String, password: String): LoginResult = mutex.withLock {
        val app = ctx.applicationContext
        CookieManager.getInstance().removeAllCookies(null)
        val r = webLogin(app, user to password)
        if (r is LoginResult.Ok) CredStore.save(app, user, password)
        r
    }

    /** Parses the CAS redirect: the TV backend token is the `tu` claim of the JWT access token. */
    fun parseRedirect(url: String): Pair<String, Long?>? {
        if (!url.startsWith(Sfr.REDIRECT_URI)) return null
        val uri = Uri.parse(url)
        val frag = uri.fragment?.let { Uri.parse("x://x?$it") }
        val raw = uri.getQueryParameter("access_token") ?: frag?.getQueryParameter("access_token") ?: return null
        return try {
            val payload = raw.split('.')[1]
            val json = JSONObject(
                String(android.util.Base64.decode(payload, android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP or android.util.Base64.NO_PADDING))
            )
            val tu = json.optString("tu").ifEmpty { return null }
            val expS = json.optLong("exp", 0)
            val ttl = if (expS > 0) expS - System.currentTimeMillis() / 1000 else null
            tu to ttl
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Runs the OIDC authorize URL in an invisible WebView. If SFR shows its login form and
     * credentials are given, fills it (with "remember me") and submits it once.
     */
    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun webLogin(ctx: Context, creds: Pair<String, String>?): LoginResult =
        withContext(Dispatchers.Main) {
            suspendCancellableCoroutine { cont ->
                val wv = WebView(ctx)
                val handler = Handler(Looper.getMainLooper())
                var done = false
                var submitted = false

                fun finish(r: LoginResult) {
                    if (done) return
                    done = true
                    handler.removeCallbacksAndMessages(null)
                    wv.stopLoading()
                    wv.destroy()
                    if (cont.isActive) cont.resume(r)
                }

                fun checkRedirect(url: String): Boolean {
                    val r = parseRedirect(url) ?: return false
                    saveToken(ctx, r.first, r.second)
                    finish(LoginResult.Ok(r.first))
                    return true
                }

                fun onLoginPage() {
                    if (creds == null) { finish(LoginResult.Rejected("Session expirée")); return }
                    if (submitted) {
                        // Form shown again after submit: read SFR's error message.
                        wv.evaluateJavascript(ERROR_JS) { v ->
                            val msg = JSONObject("{\"v\":$v}").optString("v").trim()
                            finish(LoginResult.Rejected(msg.ifEmpty { "Identifiant ou mot de passe refusé" }))
                        }
                        return
                    }
                    submitted = true
                    val js = "($FILL_JS)(${JSONObject.quote(creds.first)}, ${JSONObject.quote(creds.second)})"
                    wv.evaluateJavascript(js) { v -> Log.i(TAG, "login form: $v") }
                }

                wv.settings.javaScriptEnabled = true
                wv.settings.domStorageEnabled = true
                CookieManager.getInstance().setAcceptThirdPartyCookies(wv, true)
                wv.webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) =
                        checkRedirect(request.url.toString())

                    override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
                        checkRedirect(url)
                    }

                    override fun onPageFinished(view: WebView, url: String) {
                        if (done || checkRedirect(url)) return
                        view.evaluateJavascript("!!document.getElementById('loginForm')") { has ->
                            if (has == "true") onLoginPage()
                        }
                    }
                }
                handler.postDelayed({ finish(LoginResult.Failed) }, 30_000)
                cont.invokeOnCancellation { handler.post { finish(LoginResult.Failed) } }
                wv.loadUrl(Sfr.authorizeUrl())
            }
        }

    /** Fills and submits SFR's CAS login form (ids: loginForm, username, password, remember-me). */
    private const val FILL_JS = """function(u, p) {
        var f = document.getElementById('loginForm');
        if (!f) return 'noform';
        function set(el, v) {
            el.focus(); el.value = v;
            ['input', 'change', 'blur'].forEach(function (e) { el.dispatchEvent(new Event(e, {bubbles: true})); });
        }
        set(document.getElementById('username'), u);
        set(document.getElementById('password'), p);
        var r = document.getElementById('remember-me'); if (r) r.checked = true;
        var b = f.querySelector('button[type=submit], input[type=submit]');
        if (b) b.click(); else f.submit();
        return 'submitted';
    }"""

    /** Visible error text on the CAS page after a rejected submit. */
    private const val ERROR_JS = """(function() {
        var sel = ['#passwordError', '#usernameError', '.error', '.sr-message-error', '[role=alert]'];
        for (var i = 0; i < sel.length; i++) {
            var els = document.querySelectorAll(sel[i]);
            for (var j = 0; j < els.length; j++) {
                var t = (els[j].innerText || '').trim();
                if (t && els[j].offsetParent !== null) return t;
            }
        }
        if (/captcha/i.test(document.body.innerHTML) && document.querySelector('iframe[src*=captcha], [class*=captcha] img, #captcha'))
            return 'SFR demande un captcha : utilisez la page de connexion SFR';
        return '';
    })()"""
}
