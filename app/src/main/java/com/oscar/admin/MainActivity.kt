package com.oscar.admin

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.ViewGroup
import android.webkit.*
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {

    private lateinit var rootLayout: LinearLayout
    private lateinit var statusBar: TextView
    private lateinit var webView: WebView
    private lateinit var bottomBar: LinearLayout
    private lateinit var btnSettings: Button
    private lateinit var btnMarket: Button
    private lateinit var prefs: SharedPreferences

    private var currentTab = "settings"
    private var isProcessingSSID = false
    private var isLoggedIn = false
    private var lastFailedUrl: String? = null

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    private val BASE_URL = "https://signal-bott.onrender.com"
    private val QX_DOMAIN = "https://quotex.com"
    private val ADMIN_PANEL_URL = "$BASE_URL/admin/panel"

    private val EARLY_HOOK_JS = """
    (function() {
        if (window.__oscarEarlyHook) return;
        window.__oscarEarlyHook = true;

        function extractAndSend(text) {
            try {
                if (typeof text !== 'string' || text.length < 5) return;
                // استخراج SSID
                var pats = [ /"session"\s*:\s*"([^"]+)"/, /"token"\s*:\s*"([^"]+)"/ ];
                for (var i = 0; i < pats.length; i++) {
                    var m = text.match(pats[i]);
                    if (m && m[1] && m[1].length > 25) {
                        if (window.OscarBridge && window.OscarBridge.onSsid) {
                            window.OscarBridge.onSsid(m[1]);
                        }
                    }
                }
                // اعتراض الصفقات
                if (text.indexOf('"buy"') !== -1 || text.indexOf('"order"') !== -1) {
                    try {
                        var data = JSON.parse(text);
                        if (data && data.asset && data.direction && data.amount && data.duration) {
                            var trade = {
                                asset: data.asset,
                                direction: data.direction,
                                amount: data.amount,
                                expiry: data.duration
                            };
                            if (window.OscarBridge && window.OscarBridge.onTrade) {
                                window.OscarBridge.onTrade(JSON.stringify(trade));
                            }
                        }
                    } catch(e) {}
                }
            } catch(e) {}
        }

        var OWS = window.WebSocket;
        if (OWS) {
            var HookedWS = function(url, protocols) {
                var ws = protocols ? new OWS(url, protocols) : new OWS(url);
                try {
                    var origAdd = ws.addEventListener.bind(ws);
                    ws.addEventListener = function(type, listener, opts) {
                        if (type === 'message') {
                            return origAdd(type, function(ev) {
                                if (typeof ev.data === 'string') extractAndSend(ev.data);
                                return listener(ev);
                            }, opts);
                        }
                        return origAdd(type, listener, opts);
                    };
                    ws.onmessage = null;
                } catch(e){}
                return ws;
            };
            HookedWS.prototype = OWS.prototype;
            HookedWS.CONNECTING = OWS.CONNECTING; HookedWS.OPEN = OWS.OPEN;
            HookedWS.CLOSING = OWS.CLOSING; HookedWS.CLOSED = OWS.CLOSED;
            window.WebSocket = HookedWS;
        }
        if (window.WebSocket && window.WebSocket.prototype && window.WebSocket.prototype.send) {
            var origSend = window.WebSocket.prototype.send;
            window.WebSocket.prototype.send = function(data) {
                if (typeof data === 'string') extractAndSend(data);
                return origSend.apply(this, arguments);
            };
        }
    })();
    """.trimIndent()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("oscar_admin_prefs", Context.MODE_PRIVATE)
        isLoggedIn = prefs.getBoolean("is_admin_logged_in", false)

        if (isLoggedIn) {
            setupUi()
            showSettings()
        } else {
            showAdminLoginDialog()
        }
    }

    private fun showAdminLoginDialog() {
        val input = EditText(this).apply {
            hint = "كلمة مرور المشرف"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            setTextColor(Color.BLACK)
            textSize = 16f
            gravity = Gravity.CENTER
        }
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(50, 30, 50, 10)
        }
        container.addView(input)

        AlertDialog.Builder(this)
            .setTitle("🔐 دخول المشرف")
            .setMessage("يرجى إدخال كلمة المرور للوصول إلى لوحة التحكم")
            .setView(container)
            .setCancelable(false)
            .setNegativeButton("خروج") { _, _ -> finish() }
            .setPositiveButton("دخول", null)
            .create()
            .apply {
                setOnShowListener {
                    val btn = getButton(AlertDialog.BUTTON_POSITIVE)
                    btn.setOnClickListener {
                        val pwd = input.text.toString().trim()
                        if (pwd.isEmpty()) {
                            Toast.makeText(this@MainActivity, "الرجاء إدخال كلمة المرور", Toast.LENGTH_SHORT).show()
                            return@setOnClickListener
                        }
                        btn.isEnabled = false
                        btn.text = "جاري التحقق..."
                        verifyAdminPassword(pwd) { ok, err ->
                            if (ok) {
                                prefs.edit().putBoolean("is_admin_logged_in", true).apply()
                                isLoggedIn = true
                                dismiss()
                                setupUi()
                                showSettings()
                            } else {
                                btn.isEnabled = true
                                btn.text = "دخول"
                                Toast.makeText(this@MainActivity, "❌ $err", Toast.LENGTH_LONG).show()
                            }
                        }
                    }
                }
                show()
            }
    }

    private fun verifyAdminPassword(password: String, onResult: (Boolean, String) -> Unit) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val req = Request.Builder()
                    .url("$BASE_URL/admin")
                    .post("password=$password".toRequestBody("application/x-www-form-urlencoded".toMediaType()))
                    .build()
                val res = client.newCall(req).execute()
                if (res.isSuccessful || res.code == 302) {
                    withContext(Dispatchers.Main) { onResult(true, "OK") }
                } else {
                    withContext(Dispatchers.Main) { onResult(false, "كلمة المرور غير صحيحة") }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { onResult(false, "خطأ اتصال: ${e.message}") }
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupUi() {
        rootLayout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        statusBar = TextView(this).apply {
            text = "📊 لوحة التحكم"
            textSize = 16f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#1a1a1a"))
            setPadding(30, 25, 30, 25)
            gravity = Gravity.CENTER
        }

        webView = WebView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        }

        bottomBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.parseColor("#0d0d0d"))
            setPadding(10, 10, 10, 10)
        }
        
        btnSettings = Button(this).apply {
            text = "📊 الإعدادات"
            textSize = 14f
            setTypeface(null, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(5, 0, 5, 0) }
            setOnClickListener { showSettings() }
        }
        
        btnMarket = Button(this).apply {
            text = "📈 السوق"
            textSize = 14f
            setTypeface(null, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(5, 0, 5, 0) }
            setOnClickListener { showMarket() }
        }
        
        bottomBar.addView(btnSettings)
        bottomBar.addView(btnMarket)

        rootLayout.addView(statusBar)
        rootLayout.addView(webView)
        rootLayout.addView(bottomBar)
        setContentView(rootLayout)
        configureWebView()
    }

    private fun showSettings() {
        currentTab = "settings"
        statusBar.text = "📊 لوحة التحكم"
        updateTabButtons()
        webView.loadUrl(ADMIN_PANEL_URL)
    }

    private fun showMarket() {
        currentTab = "market"
        statusBar.text = "📈 السوق — سجّل دخولك لربط الحساب"
        updateTabButtons()
        if (webView.url == null || !webView.url!!.contains("quotex.com")) {
            webView.loadUrl("$QX_DOMAIN/ar/trade")
        }
    }

    private fun updateTabButtons() {
        if (currentTab == "settings") {
            btnSettings.setBackgroundColor(Color.parseColor("#d4af37"))
            btnSettings.setTextColor(Color.BLACK)
            btnMarket.setBackgroundColor(Color.parseColor("#2a2a2a"))
            btnMarket.setTextColor(Color.WHITE)
        } else {
            btnSettings.setBackgroundColor(Color.parseColor("#2a2a2a"))
            btnSettings.setTextColor(Color.WHITE)
            btnMarket.setBackgroundColor(Color.parseColor("#d4af37"))
            btnMarket.setTextColor(Color.BLACK)
        }
    }

    @SuppressLint("SetJavaScriptEnabled", "AddJavascriptInterface")
    private fun configureWebView() {
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            loadWithOverviewMode = true
            useWideViewPort = true
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            userAgentString = "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
        }
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)
        webView.addJavascriptInterface(Bridge(), "OscarBridge")

        webView.webViewClient = object : WebViewClient() {
            override fun onReceivedSslError(v: WebView?, h: SslErrorHandler?, e: android.net.http.SslError?) { h?.proceed() }
            
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                return false
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                super.onPageStarted(view, url, favicon)
                if (url?.contains("quotex.com") == true) view?.evaluateJavascript(EARLY_HOOK_JS, null)
            }

            override fun onPageFinished(view: WebView, url: String) {
                super.onPageFinished(view, url)
                if (url.contains("quotex.com")) {
                    view.evaluateJavascript(EARLY_HOOK_JS, null)
                }
            }

            override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                super.onReceivedError(view, request, error)
                if (error?.errorCode == WebViewClient.ERROR_HOST_LOOKUP) {
                    lastFailedUrl = request?.url.toString()
                    statusBar.text = "⚠️ فشل الاتصال، جاري إعادة المحاولة..."
                    Handler(Looper.getMainLooper()).postDelayed({
                        val urlToRetry = lastFailedUrl
                        if (urlToRetry != null) {
                            view?.loadUrl(urlToRetry)
                            lastFailedUrl = null
                        }
                    }, 1500)
                }
            }
        }
    }

    private fun sendSsidToServer(ssid: String) {
        if (isProcessingSSID || ssid.length < 25) return
        isProcessingSSID = true
        statusBar.text = "🔄 جاري ربط حساب الماستر..."
        
        val cookies = CookieManager.getInstance().getCookie(QX_DOMAIN) ?: ""

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val payload = JSONObject().apply {
                    put("ssid", ssid)
                    put("cookies", cookies)
                }
                val req = Request.Builder()
                    .url("$BASE_URL/api/admin/link_master")
                    .post(payload.toString().toRequestBody("application/json".toMediaType()))
                    .build()
                val res = client.newCall(req).execute()
                val json = JSONObject(res.body?.string() ?: "{}")

                withContext(Dispatchers.Main) {
                    if (json.optBoolean("ok")) {
                        statusBar.text = "✅ تم ربط حساب الماستر بنجاح!"
                        Toast.makeText(this@MainActivity, "تم ربط الحساب", Toast.LENGTH_LONG).show()
                    } else {
                        val err = json.optString("error", "خطأ")
                        statusBar.text = "⚠️ فشل: $err"
                    }
                    isProcessingSSID = false
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    statusBar.text = "⚠️ خطأ اتصال: ${e.message}"
                    isProcessingSSID = false
                }
            }
        }
    }

    private fun sendTradeToServer(tradeJson: String) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val payload = JSONObject(tradeJson)
                val req = Request.Builder()
                    .url("$BASE_URL/api/admin/realtime_trade")
                    .post(payload.toString().toRequestBody("application/json".toMediaType()))
                    .build()
                val res = client.newCall(req).execute()
                val json = JSONObject(res.body?.string() ?: "{}")
                withContext(Dispatchers.Main) {
                    if (json.optBoolean("ok")) {
                        Toast.makeText(this@MainActivity, "✅ تم نسخ الصفقة إلى ${json.optInt("copied")} مستخدم", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(this@MainActivity, "⚠️ فشل نسخ الصفقة: ${json.optString("error")}", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "خطأ اتصال: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    inner class Bridge {
        @JavascriptInterface
        fun onSsid(ssid: String) {
            if (ssid.length < 25) return
            runOnUiThread {
                if (currentTab == "market" && !isProcessingSSID) {
                    sendSsidToServer(ssid)
                }
            }
        }

        @JavascriptInterface
        fun onTrade(tradeJson: String) {
            runOnUiThread {
                if (currentTab == "market") {
                    sendTradeToServer(tradeJson)
                }
            }
        }
    }

    private fun logoutAdmin() {
        prefs.edit().remove("is_admin_logged_in").apply()
        val cookieManager = CookieManager.getInstance()
        cookieManager.removeAllCookies(null)
        cookieManager.flush()
        webView.clearCache(true)
        webView.clearHistory()
        webView.loadUrl("about:blank")
        isLoggedIn = false
        val intent = Intent(this, MainActivity::class.java)
        finish()
        startActivity(intent)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (currentTab == "market") {
            showSettings()
        } else if (::webView.isInitialized && webView.canGoBack()) {
            webView.goBack()
        } else {
            super.onBackPressed()
        }
    }
}
