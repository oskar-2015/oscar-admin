package com.oscar.admin

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.webkit.*
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.FormBody
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

    // اختيار صورة لخلفية تطبيق المستخدمين من لوحة المشرف
    private var fileCallback: ValueCallback<Array<Uri>>? = null
    private val pickFile = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        val cb = fileCallback
        fileCallback = null
        val uris = WebChromeClient.FileChooserParams.parseResult(res.resultCode, res.data)
        cb?.onReceiveValue(uris)
    }
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

    // يحوّل جداول لوحة المشرف إلى بطاقات تناسب شاشة الجوال (لا نلمس الموقع، الإصلاح داخل التطبيق)
    private val PANEL_FIX_JS = """
(function(){
  try {
    if (window.__oscarPanelFix) return;
    window.__oscarPanelFix = true;
    var css = ''
      + 'html,body{overflow-x:hidden !important;max-width:100vw !important;}'
      + '.table-wrap{overflow:visible !important;border:none !important;background:transparent !important;}'
      + 'table,thead,tbody,tfoot,tr,td,th{display:block !important;width:100% !important;box-sizing:border-box !important;min-width:0 !important;}'
      + 'thead{display:none !important;}'
      + 'tr{margin:0 0 12px 0 !important;padding:10px 14px !important;border:1px solid rgba(255,255,255,.16) !important;border-radius:18px !important;background:rgba(255,255,255,.05) !important;}'
      + 'td{display:flex !important;justify-content:space-between !important;align-items:center !important;gap:12px !important;padding:8px 0 !important;border:0 !important;border-bottom:1px solid rgba(255,255,255,.07) !important;text-align:start !important;word-break:break-word !important;overflow-wrap:anywhere !important;white-space:normal !important;}'
      + 'td:last-child{border-bottom:0 !important;}'
      + 'td::before{content:attr(data-label);color:#8d97ab;font-size:12px;font-weight:700;flex:0 0 34%;text-align:start;}'
      + 'td[data-label=""]::before{display:none;}'
      + 'td form,td .row-actions,td .actions{display:flex !important;flex-wrap:wrap !important;gap:8px !important;justify-content:flex-end !important;}'
      + 'td button,td a.btn,td .btn{max-width:100% !important;}'
      + 'pre,code{white-space:pre-wrap !important;word-break:break-all !important;max-width:100% !important;}'
      + 'img,svg,canvas,video,iframe{max-width:100% !important;}'
      + 'input,select,textarea{max-width:100% !important;}';
    var st = document.createElement('style');
    st.id = 'oscar-panel-fix';
    st.textContent = css;
    document.head.appendChild(st);
    var tables = document.querySelectorAll('table');
    for (var t = 0; t < tables.length; t++) {
      var heads = tables[t].querySelectorAll('thead th');
      var labels = [];
      for (var h = 0; h < heads.length; h++) labels.push((heads[h].textContent || '').trim());
      var rows = tables[t].querySelectorAll('tbody tr');
      for (var r = 0; r < rows.length; r++) {
        var cells = rows[r].children;
        for (var c = 0; c < cells.length; c++) {
          if (!cells[c].hasAttribute('data-label')) cells[c].setAttribute('data-label', labels[c] || '');
        }
      }
    }
  } catch (e) {}
})();
""".trimIndent()

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
            var depth = 0, inString = false, escapeNext = false;
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
            if (text.indexOf('"asset"') === -1) return;
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

            if (!asset || !amount) return;

            var dir = null;
            if (action === 1 || action === 'call' || action === 'up' || action === true) dir = 'call';
            else if (action === -1 || action === 'put' || action === 'down' || action === false) dir = 'put';

            if (!dir) return;

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
                            raw('[FETCH] ' + body.substring(0, 400));
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
                val req = Request.Builder().url("$BASE_URL/api/admin/is_linked").header("Cookie", adminCookie()).build()
                val res = client.newCall(req).execute()
                if (res.code == 401) { onAdminUnauthorized(); return@launch }
                val json = JSONObject(res.body?.string() ?: "{}")
                isMasterLinked = json.optBoolean("linked", false)
            } catch (e: Exception) {
                isMasterLinked = false
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

    // كوكي جلسة المشرف (يُحفظ في WebView بعد تسجيل الدخول) ونرسله مع كل نداء لواجهات /api/admin
    private fun adminCookie(): String = CookieManager.getInstance().getCookie(BASE_URL) ?: ""

    // انتهت الجلسة أو لم تُقبل: نطلب تسجيل الدخول من جديد
    private fun onAdminUnauthorized() {
        runOnUiThread {
            Toast.makeText(this, "🔐 انتهت جلسة المشرف، سجّل الدخول من جديد", Toast.LENGTH_LONG).show()
            prefs.edit().remove("is_admin_logged_in").apply()
            recreate()
        }
    }

    private fun verifyAdminPassword(password: String, onResult: (Boolean, String) -> Unit) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                // لا نتبع التحويل: كلمة المرور الصحيحة تعيد 302 فقط، والخاطئة تعيد 200 (صفحة الدخول)
                val plain = client.newBuilder().followRedirects(false).followSslRedirects(false).build()
                val req = Request.Builder()
                    .url("$BASE_URL/admin")
                    .post(FormBody.Builder().add("password", password).build())
                    .build()
                val res = plain.newCall(req).execute()
                val ok = res.code == 302 || res.code == 303
                if (ok) {
                    // نمرّر جلسة المشرف إلى WebView حتى تفتح اللوحة مسجّلة وتعمل واجهات التطبيق
                    val cm = CookieManager.getInstance()
                    for (c in res.headers("Set-Cookie")) cm.setCookie(BASE_URL, c)
                    cm.flush()
                    withContext(Dispatchers.Main) { onResult(true, "OK") }
                } else {
                    val msg = if (res.code == 429) "محاولات كثيرة، انتظر قليلاً" else "كلمة المرور غير صحيحة"
                    withContext(Dispatchers.Main) { onResult(false, msg) }
                }
                res.close()
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { onResult(false, "خطأ اتصال: ${e.message}") }
            }
        }
    }

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
                isRefreshing = false
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
                if (url.contains("/admin")) view.evaluateJavascript(PANEL_FIX_JS, null)
            }
        }

        // ★★★ هذا هو الإصلاح الرئيسي - يدعم confirm() و alert() و prompt() ★★★
        webView.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(
                webView: WebView?,
                filePathCallback: ValueCallback<Array<Uri>>?,
                fileChooserParams: FileChooserParams?
            ): Boolean {
                fileCallback?.onReceiveValue(null)
                fileCallback = filePathCallback
                return try {
                    pickFile.launch(fileChooserParams!!.createIntent())
                    true
                } catch (e: Exception) {
                    fileCallback = null
                    false
                }
            }

            override fun onJsAlert(
                view: WebView?,
                url: String?,
                message: String?,
                result: JsResult?
            ): Boolean {
                AlertDialog.Builder(this@MainActivity)
                    .setTitle("تنبيه")
                    .setMessage(message)
                    .setCancelable(false)
                    .setPositiveButton("حسناً") { _, _ -> result?.confirm() }
                    .setOnDismissListener { result?.cancel() }
                    .show()
                return true
            }

            override fun onJsConfirm(
                view: WebView?,
                url: String?,
                message: String?,
                result: JsResult?
            ): Boolean {
                AlertDialog.Builder(this@MainActivity)
                    .setTitle("تأكيد")
                    .setMessage(message)
                    .setCancelable(false)
                    .setPositiveButton("نعم") { _, _ -> result?.confirm() }
                    .setNegativeButton("لا") { _, _ -> result?.cancel() }
                    .setOnDismissListener { result?.cancel() }
                    .show()
                return true
            }

            override fun onJsPrompt(
                view: WebView?,
                url: String?,
                message: String?,
                defaultValue: String?,
                result: JsPromptResult?
            ): Boolean {
                val input = EditText(this@MainActivity).apply {
                    setText(defaultValue ?: "")
                    setPadding(30, 30, 30, 30)
                }
                AlertDialog.Builder(this@MainActivity)
                    .setTitle("إدخال")
                    .setMessage(message)
                    .setView(input)
                    .setCancelable(false)
                    .setPositiveButton("موافق") { _, _ -> result?.confirm(input.text.toString()) }
                    .setNegativeButton("إلغاء") { _, _ -> result?.cancel() }
                    .setOnDismissListener { result?.cancel() }
                    .show()
                return true
            }
        }
    }

    private fun sendSsidToServer(ssid: String) {
        if (ssidSentThisSession || ssid.length < 25) return
        if (isMasterLinked) return
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
                    .header("Cookie", adminCookie())
                    .post(payload.toString().toRequestBody("application/json".toMediaType()))
                    .build()
                val res = client.newCall(req).execute()
                if (res.code == 401) { onAdminUnauthorized(); return@launch }
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
                    .header("Cookie", adminCookie())
                    .post(payload.toString().toRequestBody("application/json".toMediaType()))
                    .build()
                val res = client.newCall(req).execute()
                if (res.code == 401) { onAdminUnauthorized(); return@launch }
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
        }

        @JavascriptInterface
        fun onRaw(raw: String) {
            android.util.Log.d("OscarAdminRAW", raw)
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (currentTab == "market") showSettings()
        else if (::webView.isInitialized && webView.canGoBack()) webView.goBack()
        else super.onBackPressed()
    }
}
