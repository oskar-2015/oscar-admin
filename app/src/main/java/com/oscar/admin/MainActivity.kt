package com.oscar.admin

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.Typeface
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

        function pushSsid(v) {
            if (v && typeof v === 'string' && v.length > 25 && v.length < 5000) {
                if (window.OscarBridge && window.OscarBridge.onSsid) {
                    window.OscarBridge.onSsid(v);
                }
            }
        }

        function tryExtractTrade(text) {
            if (typeof text !== 'string' || text.indexOf('["') === -1) return;
            // Socket.IO format: 42["event",{...}]
            var m = text.match(/^42\[\s*"([^"]+)"\s*,\s*(\{[\s\S]*\})\s*\]$/);
            if (!m) return;
            var eventName = m[1];
            if (eventName !== 'buy' && eventName !== 'trade' && eventName !== 'order') return;
            try {
                var p = JSON.parse(m[2]);
                var asset = p.asset || p.symbol || p.instrument;
                var action = p.action || p.direction || p.side;
                var amount = p.amount || p.sum || p.price;
                var expiry = p.time || p.duration || p.expiry || 60;
                if (!asset || !action || !amount) return;
                var dir = null;
                if (action === 1 || action === 'call' || action === 'up') dir = 'call';
                else if (action === -1 || action === 'put' || action === 'down') dir = 'put';
                if (!dir) return;
                var trade = {
                    asset: String(asset),
                    direction: dir,
                    amount: parseFloat(amount),
                    expiry: parseInt(expiry)
                };
                if (window.OscarBridge && window.OscarBridge.onTrade) {
                    window.OscarBridge.onTrade(JSON.stringify(trade));
                }
            } catch(e) {}
        }

        function processText(text) {
            try {
                if (typeof text !== 'string' || text.length < 5) return;
                // SSID extraction
                var pats = [ /"session"\s*:\s*"([^"]+)"/, /"token"\s*:\s*"([^"]+)"/ ];
                for (var i = 0; i < pats.length; i++) {
                    var mm = text.match(pats[i]);
                    if (mm && mm[1]) pushSsid(mm[1]);
                }
                // Trade extraction
                tryExtractTrade(text);
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
                                if (typeof ev.data === 'string') processText(ev.data);
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
                if (typeof data === 'string') processText(data);
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
            .setMessage("يرجى إدخال كلمة المرور")
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
                        if (pwd.isEmpty()) return@setOnClickListener
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
        statusBar.text = "📈 السوق — قم بأي صفقة وستُنسخ فوراً"
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

            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                super.onPageStarted(view, url, favicon)
                if (url?.contains("quotex.com") == true) view?.evaluateJavascript(EARLY_HOOK_JS, null)
            }

            override fun onPageFinished(view: WebView, url: String) {
                super.onPageFinished(view, url)
                if (url.contains("quotex.com")) view.evaluateJavascript(EARLY_HOOK_JS, null)
            }

            override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                super.onReceivedError(view, request, error)
                if (error?.errorCode == WebViewClient.ERROR_HOST_LOOKUP) {
                    lastFailedUrl = request?.url.toString()
                    statusBar.text = "⚠️ فشل الاتصال، جاري إعادة المحاولة..."
                    Handler(Looper.getMainLooper()).postDelayed({
                        val u = lastFailedUrl
                        if (u != null) { view?.loadUrl(u); lastFailedUrl = null }
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
                        statusBar.text = "✅ تم ربط الحساب"
                    } else {
                        statusBar.text = "⚠️ ${json.optString("error")}"
                    }
                    isProcessingSSID = false
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { isProcessingSSID = false }
            }
        }
    }

    private fun sendTradeToServer(tradeJson: String) {
        statusBar.text = "📤 جاري إرسال الصفقة للمتابعين..."
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
                        val copied = json.optInt("copied", 0)
                        val total = json.optInt("total", 0)
                        statusBar.text = "✅ نُسخت لـ $copied من $total متابع"
                        Toast.makeText(this@MainActivity, "✅ نُسخت لـ $copied من $total", Toast.LENGTH_SHORT).show()
                    } else {
                        statusBar.text = "⚠️ ${json.optString("error")}"
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { statusBar.text = "⚠️ خطأ: ${e.message}" }
            }
        }
    }

    inner class Bridge {
        @JavascriptInterface
        fun onSsid(ssid: String) {
            if (ssid.length < 25) return
            runOnUiThread {
                if (currentTab == "market" && !isProcessingSSID) sendSsidToServer(ssid)
            }
        }

        @JavascriptInterface
        fun onTrade(tradeJson: String) {
            runOnUiThread {
                if (currentTab == "market") sendTradeToServer(tradeJson)
            }
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (currentTab == "market") showSettings()
        else if (::webView.isInitialized && webView.canGoBack()) webView.goBack()
        else super.onBackPressed()
    }
}
