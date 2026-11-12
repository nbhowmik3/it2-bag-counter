package com.nil.it2bagfetcher

import android.Manifest
import android.annotation.SuppressLint
import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.*
import android.text.InputType
import android.view.ViewGroup
import android.webkit.*
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import java.util.Calendar

class MainActivity : AppCompatActivity() {

    companion object {
        var onOtpReceived: ((String) -> Unit)? = null
        private const val REPORT_URL = "https://app.indiapost.gov.in/bagging/bag-management/reports/expected-bag-po"
        private const val CHANNEL_ID = "it2_bag_alerts"
    }

    private lateinit var webView: WebView
    private lateinit var statusText: TextView
    private val mainHandler = Handler(Looper.getMainLooper())
    private var lastInjectedOtp = ""
    private var loginStartTimeMs = 0L

    inner class BagBridge {
        @JavascriptInterface
        fun updateStatus(msg: String) {
            runOnUiThread { statusText.text = msg }
        }

        @JavascriptInterface
        fun onBagCountReady(eTotal: Int, cTotal: Int, eBags: Int, cBags: Int) {
            runOnUiThread {
                val msg = "E Bags ($eBags): $eTotal articles | C Bags ($cBags): $cTotal articles | Total: ${eTotal + cTotal}"
                statusText.text = msg
                sendNotification("IT 2.0 Expected Bags (6:00 AM)", msg)
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestAppPermissions()
        createNotificationChannel()
        scheduleDaily6AM()

        val prefs = getSharedPreferences("it2_prefs", Context.MODE_PRIVATE)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 32, 24, 16)
        }
        val idInput = EditText(this).apply {
            hint = "8-Digit Employee ID"
            setText(prefs.getString("emp_id", ""))
        }
        val passInput = EditText(this).apply {
            hint = "Password"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setText(prefs.getString("emp_pass", ""))
        }
        val runBtn = Button(this).apply { text = "Save & Fetch E / C Bag Count Now" }
        statusText = TextView(this).apply {
            text = "Ready (6:00 AM daily trigger active)"
            setPadding(0, 12, 0, 12)
        }
        webView = WebView(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.useWideViewPort = true
            settings.loadWithOverviewMode = true
            settings.userAgentString = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
            addJavascriptInterface(BagBridge(), "AndroidBridge")
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) { startSmartWatcher() }
            }
        }

        root.addView(idInput)
        root.addView(passInput)
        root.addView(runBtn)
        root.addView(statusText)
        root.addView(webView)
        setContentView(root)

        runBtn.setOnClickListener {
            prefs.edit()
                .putString("emp_id", idInput.text.toString().trim())
                .putString("emp_pass", passInput.text.toString().trim())
                .apply()
            startAutomation()
        }
        onOtpReceived = { otp -> injectOtpIntoWebView(otp) }
        if (intent?.getBooleanExtra("AUTO_RUN", false) == true) startAutomation()
    }

    private fun startAutomation() {
        lastInjectedOtp = ""
        loginStartTimeMs = System.currentTimeMillis()
        statusText.text = "Opening Expected Bag PO report..."
        webView.loadUrl(REPORT_URL)
        startInboxOtpPoller()
    }

    private fun startInboxOtpPoller() {
        mainHandler.removeCallbacksAndMessages(null)
        val poller = object : Runnable {
            override fun run() {
                checkRecentInboxOtp()
                if (System.currentTimeMillis() - loginStartTimeMs < 180_000L) mainHandler.postDelayed(this, 1500L)
            }
        }
        mainHandler.postDelayed(poller, 2500L)
    }

    private fun checkRecentInboxOtp() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_SMS) != PackageManager.PERMISSION_GRANTED) return
        try {
            contentResolver.query(
                Uri.parse("content://sms/inbox"),
                arrayOf("body", "date"),
                "date >= ?",
                arrayOf((loginStartTimeMs - 5000L).toString()),
                "date DESC"
            )?.use {
                while (it.moveToNext()) {
                    val body = it.getString(0) ?: ""
                    if (body.contains("OTP", true) && (body.contains("INDIAPOST", true) || body.contains("INPOST", true))) {
                        val otp = Regex("OTP[^0-9]*([0-9]{6})", RegexOption.IGNORE_CASE).find(body)?.groupValues?.get(1)
                            ?: Regex("([0-9]{6})").find(body)?.groupValues?.get(1)
                        if (!otp.isNullOrEmpty() && otp != lastInjectedOtp) {
                            injectOtpIntoWebView(otp)
                            break
                        }
                    }
                }
            }
        } catch (_: Exception) {}
    }

    private fun injectOtpIntoWebView(otp: String) {
        if (otp.length != 6 || otp == lastInjectedOtp) return
        lastInjectedOtp = otp
        runOnUiThread {
            statusText.text = "Entering OTP $otp & clicking Verify OTP..."
            val otpJs = """
                (function() {
                    const setter = Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype, 'value').set;
                    const boxes = Array.from(document.querySelectorAll('input')).filter(i => i.offsetParent !== null && i.type !== 'hidden' && i.type !== 'password' && i.type !== 'radio' && i.type !== 'checkbox');
                    if (boxes.length >= 6) {
                        '$otp'.split('').forEach((d, idx) => {
                            boxes[idx].focus();
                            setter.call(boxes[idx], d);
                            boxes[idx].dispatchEvent(new Event('input', { bubbles: true }));
                            boxes[idx].dispatchEvent(new KeyboardEvent('keyup', { bubbles: true, key: d }));
                            boxes[idx].dispatchEvent(new Event('change', { bubbles: true }));
                        });
                    } else if (boxes.length === 1) {
                        setter.call(boxes[0], '$otp');
                        boxes[0].dispatchEvent(new Event('input', { bubbles: true }));
                        boxes[0].dispatchEvent(new Event('change', { bubbles: true }));
                    }
                    setTimeout(() => {
                        const btn = Array.from(document.querySelectorAll('button, a, input[type="submit"]')).find(b => b.offsetParent !== null && /verify otp|verify|submit/i.test((b.innerText || b.value || '').trim()));
                        if (btn) { btn.disabled = false; btn.click(); }
                    }, 500);
                })();
            """.trimIndent()
            webView.evaluateJavascript(otpJs, null)
        }
    }

    private fun startSmartWatcher() {
        val prefs = getSharedPreferences("it2_prefs", Context.MODE_PRIVATE)
        val empId = prefs.getString("emp_id", "") ?: ""
        val empPass = prefs.getString("emp_pass", "") ?: ""
        if (empId.isEmpty() || empPass.isEmpty()) return

        val js = """
            (function() {
                if (window.__it2Running) return;
                window.__it2Running = true;
                window.__signedIn = false;
                window.__authSeenAt = 0;
                window.__otpChosen = false;
                window.__typePicked = false;
                window.__fetched = false;
                window.__expanded = false;
                window.__waitUntil = 0;
                window.__bags = {};
                window.__done = false;

                function setVal(el, val) {
                    if (!el) return;
                    el.focus();
                    Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype, 'value').set.call(el, val);
                    el.dispatchEvent(new Event('input', { bubbles: true }));
                    el.dispatchEvent(new Event('change', { bubbles: true }));
                }
                function clickText(fn) {
                    const el = Array.from(document.querySelectorAll('button, a, div, span, li, p, label, input[type="button"], input[type="submit"]')).find(e => {
                        if (e.offsetParent === null) return false;
                        const t = (e.innerText || e.value || '').trim().toLowerCase();
                        return t.length > 0 && t.length < 45 && fn(t);
                    });
                    if (el) { el.click(); return true; }
                    return false;
                }

                setInterval(() => {
                    if (window.__done || Date.now() < window.__waitUntil) return;
                    const bodyTxt = (document.body.innerText || '').toLowerCase();
                    if (bodyTxt.includes('redirecting, please wait')) return;

                    const pass = Array.from(document.querySelectorAll('input[type="password"]')).find(i => i.offsetParent !== null);
                    if (pass) {
                        if (!window.__signedIn) {
                            const ins = Array.from(document.querySelectorAll('input')).filter(i => i.offsetParent !== null && i.type !== 'password' && i.type !== 'hidden' && i.type !== 'radio' && i.type !== 'checkbox');
                            if (ins.length > 0) {
                                window.__signedIn = true;
                                AndroidBridge.updateStatus('Entering Employee ID & Password...');
                                setVal(ins[0], '$empId');
                                setVal(pass, '$empPass');
                                setTimeout(() => clickText(t => t === 'sign in' || t === 'login' || t === 'continue'), 500);
                            }
                        }
                        return;
                    }

                    if (bodyTxt.includes('select authentication method')) {
                        if (!window.__authSeenAt) window.__authSeenAt = Date.now();
                        if (!window.__otpChosen) {
                            const elapsed = Math.floor((Date.now() - window.__authSeenAt) / 1000);
                            const radios = Array.from(document.querySelectorAll('input[type="radio"]')).filter(r => r.offsetParent !== null);
                            const opt2 = radios.length >= 2 ? radios[1] : null;
                            if ((bodyTxt.includes('available in') || (opt2 && opt2.disabled)) && elapsed < 32) {
                                AndroidBridge.updateStatus('Waiting for Mobile OTP unlock (' + Math.max(1, 30 - elapsed) + 's)...');
                                return;
                            }
                            window.__otpChosen = true;
                            AndroidBridge.updateStatus('Selecting 2nd option (Mobile OTP)...');
                            if (opt2) { opt2.click(); if (opt2.parentElement) opt2.parentElement.click(); }
                            clickText(t => t === 'mobile otp' || t.startsWith('mobile otp'));
                            setTimeout(() => { clickText(t => t === 'continue'); AndroidBridge.updateStatus('Waiting for SMS OTP...'); }, 600);
                        }
                        return;
                    }

                    if (bodyTxt.includes('enter otp') || bodyTxt.includes('verify otp')) return;

                    if (!window.location.href.includes('/reports/expected-bag-po')) {
                        AndroidBridge.updateStatus('Opening Expected Bag PO page...');
                        window.location.href = '$REPORT_URL';
                        return;
                    }

                    if (!window.__typePicked) {
                        for (const sel of Array.from(document.querySelectorAll('select')).filter(s => s.offsetParent !== null)) {
                            const opt = Array.from(sel.options).find(o => /close event/i.test(o.text));
                            if (opt) {
                                sel.value = opt.value;
                                sel.dispatchEvent(new Event('input', { bubbles: true }));
                                sel.dispatchEvent(new Event('change', { bubbles: true }));
                                window.__typePicked = true;
                                AndroidBridge.updateStatus('Selected: By Close Event');
                                window.__waitUntil = Date.now() + 800;
                                return;
                            }
                        }
                    }

                    if (window.__typePicked && !window.__fetched) {
                        if (clickText(t => t === 'fetch' || t.includes('fetch'))) {
                            window.__fetched = true;
                            AndroidBridge.updateStatus('Clicked Fetch — loading bags...');
                            window.__waitUntil = Date.now() + 2000;
                        }
                        return;
                    }

                    const tbl = Array.from(document.querySelectorAll('table')).filter(t => t.offsetParent !== null).find(t => /bag[ ]*number/i.test(t.innerText || ''));
                    if (!tbl) return;
                    const rows = Array.from(tbl.querySelectorAll('tr'));
                    if (rows.length < 2) return;

                    if (!window.__expanded) {
                        window.__expanded = true;
                        const pSel = Array.from(document.querySelectorAll('select')).find(s => s.offsetParent !== null && Array.from(s.options).some(o => o.text.trim() === '10') && Array.from(s.options).some(o => parseInt(o.text.trim(), 10) > 10));
                        if (pSel) {
                            const maxO = Array.from(pSel.options).sort((a, b) => (parseInt(b.text.trim(), 10) || 0) - (parseInt(a.text.trim(), 10) || 0))[0];
                            if (maxO && pSel.value !== maxO.value) {
                                pSel.value = maxO.value;
                                pSel.dispatchEvent(new Event('change', { bubbles: true }));
                                window.__waitUntil = Date.now() + 1500;
                                return;
                            }
                        }
                    }

                    const hdrs = Array.from(rows[0].querySelectorAll('th, td')).map(c => c.innerText.trim().toLowerCase());
                    let bCol = hdrs.findIndex(h => h.includes('bag') && (h.includes('number') || h.includes('no')));
                    let cCol = hdrs.findIndex(h => h.includes('content') || h.includes('count') || h.includes('article'));
                    if (bCol === -1) bCol = 1;
                    if (cCol === -1) cCol = Math.max(0, hdrs.length - 2);

                    rows.slice(1).forEach(r => {
                        const cells = Array.from(r.querySelectorAll('td')).map(c => c.innerText.trim());
                        if (cells.length <= Math.max(bCol, cCol)) return;
                        const bag = cells[bCol].toUpperCase();
                        if (/^[A-Z0-9]{5,}/.test(bag)) window.__bags[bag] = parseInt(cells[cCol].replace(/[^0-9]/g, ''), 10) || 0;
                    });

                    const rng = (document.body.innerText || '').match(/([0-9]+)[ ]*-[ ]*([0-9]+)[ ]+of[ ]+([0-9]+)/i);
                    if (rng && parseInt(rng[2], 10) < parseInt(rng[3], 10)) {
                        AndroidBridge.updateStatus('Scanned ' + rng[2] + ' of ' + rng[3] + ' rows...');
                        const nxt = Array.from(document.querySelectorAll('button, a, span, div')).find(e => e.offsetParent !== null && !e.disabled && ['>', '›', 'next', 'next page'].includes(((e.getAttribute('aria-label') || '') + ' ' + (e.innerText || '')).trim().toLowerCase()));
                        if (nxt) { nxt.click(); window.__waitUntil = Date.now() + 1500; return; }
                    }

                    let eSum = 0, cSum = 0, eCnt = 0, cCnt = 0;
                    Object.keys(window.__bags).forEach(b => {
                        if (b.startsWith('E')) { eSum += window.__bags[b]; eCnt++; }
                        else if (b.startsWith('C')) { cSum += window.__bags[b]; cCnt++; }
                    });
                    window.__done = true;
                    AndroidBridge.onBagCountReady(eSum, cSum, eCnt, cCnt);
                }, 1000);
            })();
        """.trimIndent()
        webView.evaluateJavascript(js, null)
    }

    private fun sendNotification(title: String, body: String) {
        val mgr = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        mgr.notify(2026, NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build())
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "IT 2.0 Bag Alerts", NotificationManager.IMPORTANCE_HIGH)
            )
        }
    }

    private fun scheduleDaily6AM() {
        val am = getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pi = PendingIntent.getBroadcast(this, 600, Intent(this, DailyAlarmReceiver::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val cal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 6); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0)
            if (before(Calendar.getInstance())) add(Calendar.DAY_OF_YEAR, 1)
        }
        am.setRepeating(AlarmManager.RTC_WAKEUP, cal.timeInMillis, AlarmManager.INTERVAL_DAY, pi)
    }

    private fun requestAppPermissions() {
        val list = mutableListOf(Manifest.permission.RECEIVE_SMS, Manifest.permission.READ_SMS)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) list.add(Manifest.permission.POST_NOTIFICATIONS)
        val missing = list.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) ActivityCompat.requestPermissions(this, missing.toTypedArray(), 101)
    }
}
// END_OF_FILE
