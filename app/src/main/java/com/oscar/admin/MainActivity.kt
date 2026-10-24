package com.oscar.admin

import android.annotation.SuppressLint
import android.content.Context
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
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
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

    private lateinit var statusBar: TextView
    private lateinit var webView: WebView
    private lateinit var swipeRefresh: SwipeRefreshLayout
    private lateinit var btnSettings: Button
    private lateinit var btnMarket: Button
    private lateinit var prefs: SharedPreferences

    private var currentTab = "settings"
    private var isLoggedIn = false
    private var isMasterLinked = false
    private var ssidSentThisSession = false

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    private val BASE_URL = "https://signal-bott.onrender.com"
    private val QX_DOMAIN = "https://quotex.com"
    private val ADMIN_PANEL_URL = "$BASE_URL/admin/panel"

    private val EARLY_HOOK_JS = """
    (function() {
        if (window.__oscarAdminHook) return;
        window.__oscarAdminHook = true;

        function log(msg) {
            try {
                if (window.OscarBridge && window.OscarBridge.onLog) {
                    window.OscarBridge.onLog(String(msg).substring(0, 300));
                }
            } catch(e) {}
        }

        function raw(msg) {
            try {
                if (window.OscarBridge && window.OscarBridge.onRaw) {
                    window.OscarBridge.onRaw(String(msg).substring(0, 500));
                }
            } catch(e) {}
        }

        function pushSsid(v) {
            try {
                if (v && typeof v === 'string' && v.length > 25 && v.length < 5000) {
                    if (window.OscarBridge && window.OscarBridge.onSsid) {
                        window.OscarBridge.onSsid(v);
                    }
                }
            } catch(e) {}
        }

        function findJsonEnd(text, start) {
            var depth = 0;
            var inString = false;
            var escapeNext = false;
            for (var i = start; i < text.length; i++) {
                var c = text[i];
                if (escapeNext) { escapeNext = false; continue; }
                if (c === '\\') { escapeNext = true; continue; }
                if (c === '"' && !inString) { inString = true; continue; }
                if (c === '"' && inString) { inString = false; continue; }
                if (inString) continue;
                if (c === '{') depth++;
                else if (c === '}') { depth--; if (depth === 0) return i; }
            }
            return -1;
        }

        function tryExtractTrade(text) {
            if (typeof text !== 'string' || text.length < 10) return;
            
            if (text.indexOf('"asset"') === -1 && text.indexOf("'asset'") === -1) return;
            if (text.indexOf('"amount"') === -1 && text.indexOf('"sum"') === -1) return;
            
            var start = text.indexOf('{');
            if (start === -1) return;
            var end = findJsonEnd(text, start);
            if (end === -1) return;
            
            try {
                var p = JSON.parse(text.substring(start, end + 1));
            } catch(e) {
                log('ParseFail: ' + e.message);
                return;
            }
            
            var asset = p.asset || p.symbol || p.instrument || p.pair;
            var amount = p.amount || p.sum || p.price || p.investment;
            var action = p.action !== undefined ? p.action : (p.direction !== undefined ? p.direction : p.side);
            var expiry = p.time || p.duration || p.expiry || 60;
            
            if (!asset || !amount) {
                log('Missing: ' + Object.keys(p).join(','));
                return;
            }
            
            var dir = null;
            if (action === 1 || action === 'call' || action === 'up' || action === true) dir = 'call';
            else if (action === -1 || action === 'put' || action === 'down' || action === false) dir = 'put';
            
            if (!dir) {
                log('BadDir: ' + String(action));
                return;
            }
            
            var trade = {
                asset: String(asset),
                direction: dir,
                amount: parseFloat(amount),
                expiry: parseInt(expiry)
            };
            
            log('TRADE: ' + JSON.stringify(trade));
            
            try {
                if (window.OscarBridge && window.OscarBridge.onTrade) {
                    window.OscarBridge.onTrade(JSON.stringify(trade));
                }
            } catch(e) {}
        }

        function processText(text) {
            try {
                if (typeof text !== 'string' || text.length < 5) return;
                var m1 = text.match(/"session"\s*:\s*"([^"]+)"/);
                if (m1 && m1[1]) pushSsid(m1[1]);
                tryExtractTrade(text);
            } catch(e) {}
        }

        function processData(d) {
            try {
                if (typeof d === 'string') { processText(d); return; }
                if (d instanceof Blob) { d.text().then(processText); return; }
                if (d instanceof ArrayBuffer) {
                    try { processText(new TextDecoder('utf-8').decode(new Uint8Array(d))); } catch(e) {}
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
                                processData(ev.data);
                                return listener(ev);
                            }, opts);
                        }
                        return origAdd(type, listener, opts);
                    };
                } catch(e) {}
                return ws;
            };
            HookedWS.prototype = OWS.prototype;
            HookedWS.CONNECTING = OWS.CONNECTING; HookedWS.OPEN = OWS.OPEN;
            HookedWS.CLOSING = OWS.CLOSING; HookedWS.CLOSED = OWS.CLOSED;
            window.WebSocket = HookedWS;
        }

        if (OWS && OWS.prototype && OWS.prototype.send) {
            var origSend = OWS.prototype.send;
            OWS.prototype.send = function(data) {
                try {
                    if (typeof data === 'string') processText(data);
                    else if (data instanceof ArrayBuffer) {
                        try { processText(new TextDecoder('utf-8').decode(new Uint8Array(data))); } catch(e) {}
                    }
                } catch(e) {}
                return origSend.apply(this, arguments);
            };
        }

        var origFetch = window.fetch;
        if (origFetch) {
            window.fetch = function(url, opts) {
                try {
                    if (opts && opts.body) {
                        var body = String(opts.body);
                        if (body.indexOf('asset') !== -1 && (body.indexOf('amount') !== -1 || body.indexOf('sum') !== -1)) {
                            raw('[FETCH] ' + (typeof url === 'string' ? url : (url && url.url) || '') + ' | ' + body.substring(0, 400));
                            processText(body);
                        }
                    }
                } catch(e) {}
                return origFetch.apply(this, arguments);
            };
        }

        var OrigXHR = window.XMLHttpRequest;
        if (OrigXHR && OrigXHR.prototype && OrigXHR.prototype.send) {
            var origXhrSend = OrigXHR.prototype.send;
            OrigXHR.prototype.send = function(body) {
                try {
                    if (body && typeof body === 'string') {
                        if (body.indexOf('asset') !== -1 && (body.indexOf('amount') !== -1 || body.indexOf('sum') !== -1)) {
                            raw('[XHR] ' + body.substring(0, 400));
                            processText(body);
                        }
                    }
                } catch(e) {}
                return origXhrSend.apply(this, arguments);
            };
        }

        // محاولة الضغط على الحساب التجريبي
        var demoAttempts = 0;
        function tryForceDemo() {
            if (demoAttempts > 15) return;
            try {
                var sels = [
                    '[data-account="demo"]:not(.active):not(.selected)',
                    '[data-account-type="demo"]:not(.active):not(.selected)',
                    '[data-testid="demo-account"]:not(.active):not(.selected)'
                ];
                for (var i = 0; i < sels.length; i++) {
                    var el = document.querySelector(sels[i]);
                    if (el) {
                        el.click();
                        demoAttempts++;
                        log('ForcedDemo#' + i);
                        return;
                    }
                }
                var clickables = document.querySelectorAll('button, [role="button"], [role="tab"]');
                for (var j = 0; j < clickables.length; j++) {
                    var t = (clickables[j].textContent || '').trim();
                    if ((t === 'Demo' || t === 'تجريبي' || t.toLowerCase() === 'demo') 
                        && !clickables[j].classList.contains('active')
                        && !clickables[j].classList.contains('selected')) {
                        clickables[j].click();
                        demoAttempts++;
                        log('ForcedDemoText');
                        return;
                    }
                }
            } catch(e) {}
        }
        setInterval(tryForceDemo, 3000);

        log('Hook injected');
    })();
    """.trimIndent()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("oscar_admin_prefs", Context.MODE_PRIVATE)
        isLoggedIn = prefs.getBoolean("is_admin_logged_in", false)

        if (isLoggedIn) {
            checkMasterLinked {
                setupUi()
                showSettings()
            }
        } else {
            showAdminLoginDialog()
        }
    }

    private fun checkMasterLinked(callback: () -> Unit) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val req = Request.Builder().url("$BASE_URL/api/admin/is_linked").build()
                val res = client.newCall(req).execute()
                val json = JSONObject(res.body?.string() ?: "{}")
                isMasterLinked = json.optBoolean("linked", false)
                android.util.Log.d("OscarAdmin", "is_linked check: $isMasterLinked")
            } catch (e: Exception) {
                isMasterLinked = false
                android.util.Log.e("OscarAdmin", "is_linked check failed: ${e.message}")
            }
            withContext(Dispatchers.Main) { callback() }
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
                                checkMasterLinked {
                                    setupUi()
                                    showSettings()
                                }
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
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        statusBar = TextView(this).apply {
            text = "📊 لوحة التحكم"
            textSize = 14f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#1a1a1a"))
            setPadding(30, 20, 30, 20)
            gravity = Gravity.CENTER
        }

        swipeRefresh = SwipeRefreshLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
            setColorSchemeColors(Color.parseColor("#d4af37"))
            setOnRefreshListener {
                if (currentTab == "settings") webView.reload()
                swipeRefresh.isRefreshing = false
            }
        }

        webView = WebView(this).apply {
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }
        swipeRefresh.addView(webView)

        val bottomBar = LinearLayout(this).apply {
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
            setOnLongClickListener {
                AlertDialog.Builder(this@MainActivity)
                    .setTitle("تسجيل الخروج")
                    .setMessage("هل تريد تسجيل الخروج من لوحة الإدارة؟")
                    .setPositiveButton("نعم") { _, _ -> logoutAdmin() }
                    .setNegativeButton("إلغاء", null)
                    .show()
                true
            }
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

        root.addView(statusBar)
        root.addView(swipeRefresh)
        root.addView(bottomBar)
        setContentView(root)
        configureWebView()
    }

    private fun showSettings() {
        currentTab = "settings"
        swipeRefresh.isEnabled = true
        statusBar.text = if (isMasterLinked) "📊 الإعدادات — الحساب مربوط ✅" else "📊 الإعدادات — الحساب غير مربوط ⚠️"
        updateTabButtons()
        webView.loadUrl(ADMIN_PANEL_URL)
    }

    private fun showMarket() {
        currentTab = "market"
        swipeRefresh.isEnabled = false
        updateTabButtons()
        // إعادة التحقق من ربط الماستر (بدون إرسال SSID جديد)
        checkMasterLinked {
            statusBar.text = if (isMasterLinked) "📈 السوق — الماستر مربوط ✅ — افتح صفقة وستُنسخ" else "📈 السوق — ربط الماستر..."
            if (webView.url == null || !webView.url!!.contains("quotex.com")) {
                webView.loadUrl("$QX_DOMAIN/ar/trade")
            }
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

        try {
            if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
                WebViewCompat.addDocumentStartJavaScript(webView, EARLY_HOOK_JS, setOf("*"))
            }
        } catch (e: Exception) {
            android.util.Log.e("OscarAdmin", "DocStart failed: ${e.message}")
        }

        webView.webViewClient = object : WebViewClient() {
            override fun onReceivedSslError(v: WebView?, h: SslErrorHandler?, e: android.net.http.SslError?) { h?.proceed() }
            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                super.onPageStarted(view, url, favicon)
                if (url?.contains("quotex.com") == true) view?.evaluateJavascript(EARLY_HOOK_JS, null)
            }
            override fun onPageFinished(view: WebView, url: String) {
                super.onPageFinished(view, url)
                swipeRefresh.isRefreshing = false
                if (url.contains("quotex.com")) view.evaluateJavascript(EARLY_HOOK_JS, null)
            }
        }
    }

    private fun sendSsidToServer(ssid: String) {
        if (ssidSentThisSession || ssid.length < 25) return
        if (isMasterLinked) {
            android.util.Log.d("OscarAdmin", "Already linked, skipping SSID")
            return
        }
        ssidSentThisSession = true
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
                        isMasterLinked = true
                        statusBar.text = "✅ تم ربط الماستر — افتح صفقة وستُنسخ"
                    } else {
                        statusBar.text = "⚠️ ${json.optString("error")}"
                        ssidSentThisSession = false
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { ssidSentThisSession = false }
            }
        }
    }

    private fun sendTradeToServer(tradeJson: String) {
        statusBar.text = "📤 جاري إرسال الصفقة..."
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
                        Toast.makeText(this@MainActivity, "✅ نُسخت لـ $copied/$total", Toast.LENGTH_SHORT).show()
                    } else {
                        statusBar.text = "⚠️ ${json.optString("error")}"
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { statusBar.text = "⚠️ خطأ: ${e.message}" }
            }
        }
    }

    private fun sendRawToServer(raw: String) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val payload = JSONObject().put("raw", raw)
                val req = Request.Builder()
                    .url("$BASE_URL/api/admin/realtime_trade")
                    .post(payload.toString().toRequestBody("application/json".toMediaType()))
                    .build()
                client.newCall(req).execute()
            } catch (e: Exception) {}
        }
    }

    private fun logoutAdmin() {
        prefs.edit().remove("is_admin_logged_in").apply()
        val cm = CookieManager.getInstance()
        cm.removeAllCookies(null)
        cm.flush()
        webView.clearCache(true)
        webView.clearHistory()
        webView.loadUrl("about:blank")
        finish()
    }

    inner class Bridge {
        @JavascriptInterface
        fun onSsid(ssid: String) {
            if (ssid.length < 25) return
            runOnUiThread {
                if (currentTab == "market") sendSsidToServer(ssid)
            }
        }

        @JavascriptInterface
        fun onTrade(tradeJson: String) {
            runOnUiThread {
                if (currentTab == "market") sendTradeToServer(tradeJson)
            }
        }

        @JavascriptInterface
        fun onLog(msg: String) {
            android.util.Log.d("OscarAdminJS", msg)
            runOnUiThread {
                if (currentTab == "market" && (msg.startsWith("TRADE:") || msg.startsWith("Missing") || msg.startsWith("BadDir") || msg.startsWith("ParseFail"))) {
                    statusBar.text = "🔍 $msg"
                }
            }
        }

        @JavascriptInterface
        fun onRaw(raw: String) {
            android.util.Log.d("OscarAdminRAW", raw)
            sendRawToServer(raw)
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (currentTab == "market") showSettings()
        else if (::webView.isInitialized && webView.canGoBack()) webView.goBack()
        else super.onBackPressed()
    }
}
