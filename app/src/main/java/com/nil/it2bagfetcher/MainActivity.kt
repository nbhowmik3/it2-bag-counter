package com.nil.it2bagfetcher

import android.Manifest
import android.annotation.SuppressLint
import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
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
        private const val TARGET_REPORT_URL =
            "https://app.indiapost.gov.in/bagging/bag-management/reports/expected-bag-po"
        private const val CHANNEL_ID = "it2_bag_alerts"
    }

    private lateinit var webView: WebView
    private lateinit var statusText: TextView
    private val mainHandler = Handler(Looper.getMainLooper())
    private var lastInjectedOtp = ""
    private var loginStartTimeMs = 0L

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
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                    android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            setText(prefs.getString("emp_pass", ""))
        }
        val runBtn = Button(this).apply {
            text = "Save & Fetch E / C Bag Count Now"
        }
        statusText = TextView(this).apply {
            text = "Ready (6:00 AM daily trigger active)"
            setPadding(0, 12, 0, 12)
        }

        webView = WebView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
            )
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.useWideViewPort = true
            settings.loadWithOverviewMode = true
            settings.userAgentString =
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
            addJavascriptInterface(BagBridge(), "AndroidBridge")
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    startSmartWatcher()
                }
            }
        }

        root.addView(idInput)
        root.addView(passInput)
        root.addView(runBtn)
        root.addView(statusText)
        root.addView(webView)
        setContentView(root)

        runBtn.setOnClickListener {
            val empId = idInput.text.toString().trim()
            val empPass = passInput.text.toString().trim()
            prefs.edit().putString("emp_id", empId).putString("emp_pass", empPass).apply()
            startAutomation()
        }

        onOtpReceived = { otp ->
            injectOtpIntoWebView(otp)
        }

        if (intent?.getBooleanExtra("AUTO_RUN", false) == true) {
            startAutomation()
        }
    }

    private fun startAutomation() {
        lastInjectedOtp = ""
        loginStartTimeMs = System.currentTimeMillis()
        statusText.text = "Opening Expected Bag PO report..."
        webView.loadUrl(TARGET_REPORT_URL)
        startInboxOtpPoller()
    }

    private fun startInboxOtpPoller() {
        mainHandler.removeCallbacksAndMessages(null)
        val poller = object : Runnable {
            override fun run() {
                checkRecentInboxOtp()
                if (System.currentTimeMillis() - loginStartTimeMs < 120_000L) {
                    mainHandler.postDelayed(this, 1500L)
                }
            }
        }
        mainHandler.postDelayed(poller, 2500L)
    }

    private fun checkRecentInboxOtp() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_SMS)
            != PackageManager.PERMISSION_GRANTED
        ) return

        try {
            val cursor = contentResolver.query(
                Uri.parse("content://sms/inbox"),
                arrayOf("body", "date"),
                "date >= ?",
                arrayOf((loginStartTimeMs - 5000L).toString()),
                "date DESC"
            )
            cursor?.use {
                while (it.moveToNext()) {
                    val body = it.getString(0) ?: ""
                    if (body.contains("OTP", ignoreCase = true) &&
                        (body.contains("INDIAPOST", ignoreCase = true) ||
                                body.contains("INPOST", ignoreCase = true))
                    ) {
                        val otp = Regex("OTP[^0-9]*([0-9]{6})", RegexOption.IGNORE_CASE)
                            .find(body)?.groupValues?.get(1)
                            ?: Regex("([0-9]{6})").find(body)?.groupValues?.get(1)
                        if (!otp.isNullOrEmpty() && otp != lastInjectedOtp) {
                            injectOtpIntoWebView(otp)
                            break
                        }
                    }
                }
            }
        } catch (_: Exception) {
        }
    }

    private fun injectOtpIntoWebView(otp: String) {
        if (otp.length != 6 || otp == lastInjectedOtp) return
        lastInjectedOtp = otp
        runOnUiThread {
            statusText.text = "Entering OTP $otp & clicking Verify OTP..."
            val otpJs = """
                (function() {
                    const setter = Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype, 'value').set;
                    const visibleInputs = Array.from(document.querySelectorAll('input'))
                        .filter(i => i.offsetParent !== null && i.type !== 'hidden' && i.type !== 'password' && i.type !== 'radio' && i.type !== 'checkbox');

                    if (visibleInputs.length >= 6) {
                        const digits = '$otp'.split('');
                        for (let idx = 0; idx < 6; idx++) {
                            const box = visibleInputs[idx];
                            box.focus();
                            setter.call(box, digits[idx]);
                            box.dispatchEvent(new Event('input', { bubbles: true }));
                            box.dispatchEvent(new KeyboardEvent('keyup', { bubbles: true, key: digits[idx] }));
                            box.dispatchEvent(new Event('change', { bubbles: true }));
                        }
                    } else if (visibleInputs.length === 1) {
                        setter.call(visibleInputs[0], '$otp');
                        visibleInputs[0].dispatchEvent(new Event('input', { bubbles: true }));
                        visibleInputs[0].dispatchEvent(new Event('change', { bubbles: true }));
                    }

                    setTimeout(() => {
                        const verifyBtn = Array.from(document.querySelectorAll('button, a, input[type="submit"]'))
                            .find(b => b.offsetParent !== null && /verify otp|verify|submit/i.test((b.innerText || b.value || '').trim()));
                        if (verifyBtn) {
                            verifyBtn.disabled = false;
                            verifyBtn.click();
                        }
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
                if (window.__it2WatcherRunning) return;
                window.__it2WatcherRunning = true;
                window.__loginSubmitted = false;
                window.__reportTypeSelected = false;
                window.__fetchClicked = false;
                window.__rowsExpanded = false;
                window.__pagingWaitUntil = 0;
                window.__collectedBags = {};
                window.__done = false;

                function setNativeValue(el, val) {
                    if (!el) return;
                    el.focus();
                    const setter = Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype, 'value').set;
                    setter.call(el, val);
                    el.dispatchEvent(new Event('input', { bubbles: true }));
                    el.dispatchEvent(new Event('change', { bubbles: true }));
                }

                function findAndClick(matcherFn) {
                    const candidates = Array.from(document.querySelectorAll('button, a, div, span, li, p, label, input[type="button"], input[type="submit"]'));
                    const match = candidates.find(el => {
                        if (el.offsetParent === null) return false;
                        const txt = (el.innerText || el.value || '').trim().toLowerCase();
                        return txt.length > 0 && txt.length < 45 && matcherFn(txt, el);
                    });
                    if (match) {
                        match.click();
                        return true;
                    }
                    return false;
                }

                setInterval(() => {
                    if (window.__done) return;
                    if (Date.now() < window.__pagingWaitUntil) return;

                    const passField = Array.from(document.querySelectorAll('input[type="password"]'))
                        .find(i => i.offsetParent !== null);
                    if (passField) {
                        if (!window.__loginSubmitted) {
                            const visibleInputs = Array.from(document.querySelectorAll('input'))
                                .filter(i => i.offsetParent !== null && i.type !== 'password' && i.type !== 'hidden' && i.type !== 'radio' && i.type !== 'checkbox');
                            if (visibleInputs.length > 0) {
                                window.__loginSubmitted = true;
                                AndroidBridge.updateStatus('Entering Employee ID & Password...');
                                setNativeValue(visibleInputs[0], '$empId');
                                setNativeValue(passField, '$empPass');
                                setTimeout(() => {
                                    findAndClick(t => t.includes('mobile otp') || t === 'otp');
                                    setTimeout(() => {
                                        findAndClick(t => /send otp|get otp|generate otp|continue|sign in|login/.test(t));
                                        AndroidBridge.updateStatus('Waiting for India Post SMS OTP...');
                                    }, 500);
                                }, 400);
                            }
                        }
                        return;
                    }

                    const pageText = (document.body.innerText || '').toLowerCase();
                    if (pageText.includes('enter otp') || pageText.includes('verify otp')) {
                        return;
                    }

                    if (!window.location.href.includes('/reports/expected-bag-po')) {
                        AndroidBridge.updateStatus('Logged in — opening Expected Bag PO page...');
                        window.location.href = '$TARGET_REPORT_URL';
                        return;
                    }

                    if (!window.__reportTypeSelected) {
                        const selects = Array.from(document.querySelectorAll('select')).filter(s => s.offsetParent !== null);
                        for (const sel of selects) {
                            const closeOpt = Array.from(sel.options).find(o => /close event/i.test(o.text));
                            if (closeOpt) {
                                sel.value = closeOpt.value;
                                sel.dispatchEvent(new Event('input', { bubbles: true }));
                                sel.dispatchEvent(new Event('change', { bubbles: true }));
                                window.__reportTypeSelected = true;
                                AndroidBridge.updateStatus('Selected: By Close Event');
                                window.__pagingWaitUntil = Date.now() + 600;
                                return;
                            }
                        }
                    }

                    if (window.__reportTypeSelected && !window.__fetchClicked) {
                        if (findAndClick(t => t === 'fetch')) {
                            window.__fetchClicked = true;
                            AndroidBridge.updateStatus('Clicked Fetch — loading bags...');
                            window.__pagingWaitUntil = Date.now() + 2000;
                        }
                        return;
                    }

                    const tables = Array.from(document.querySelectorAll('table')).filter(t => t.offsetParent !== null);
                    const targetTable = tables.find(t => /bag[ ]*number/i.test(t.innerText || '')) || tables[0];
                    if (!targetTable) return;

                    const rows = Array.from(targetTable.querySelectorAll('tr'));
                    if (rows.length < 2) return;

                    if (!window.__rowsExpanded) {
                        window.__rowsExpanded = true;
                        const pageSelect = Array.from(document.querySelectorAll('select')).find(s =>
                            s.offsetParent !== null &&
                            Array.from(s.options).some(o => o.text.trim() === '10') &&
                            Array.from(s.options).some(o => parseInt(o.text.trim(), 10) > 10)
                        );
                        if (pageSelect) {
                            const maxOpt = Array.from(pageSelect.options)
                                .sort((a, b) => (parseInt(b.text.trim(), 10) || 0) - (parseInt(a.text.trim(), 10) || 0))[0];
                            if (maxOpt && pageSelect.value !== maxOpt.value) {
                                pageSelect.value = maxOpt.value;
                                pageSelect.dispatchEvent(new Event('change', { bubbles: true }));
                                AndroidBridge.updateStatus('Expanding rows per page to ' + maxOpt.text.trim() + '...');
                                window.__pagingWaitUntil = Date.now() + 1500;
                                return;
                            }
                        }
                    }

                    const headers = Array.from(rows[0].querySelectorAll('th, td')).map(c => c.innerText.trim().toLowerCase());
                    let bagCol = headers.findIndex(h => h.includes('bag') && (h.includes('number') || h.includes('no')));
                    let countCol = headers.findIndex(h => h.includes('content') || h.includes('count') || h.includes('article'));
                    if (bagCol === -1) bagCol = 1;
                    if (countCol === -1) countCol = Math.max(0, headers.length - 2);

                    rows.slice(1).forEach(r => {
                        const cells = Array.from(r.querySelectorAll('td')).map(c => c.innerText.trim());
                        if (cells.length <= Math.max(bagCol, countCol)) return;
                        const bagNo = cells[bagCol].toUpperCase();
                        if (!/^[A-Z0-9]{5,}/.test(bagNo)) return;
                        const cnt = parseInt(cells[countCol].replace(/[^0-9]/g, ''), 10) || 0;
                        window.__collectedBags[bagNo] = cnt;
                    });

                    const rangeMatch = (document.body.innerText || '').match(/([0-9]+)[ ]*-[ ]*([0-9]+)[ ]+of[ ]+([0-9]+)/i);
                    if (rangeMatch) {
                        const endRow = parseInt(rangeMatch[2], 10);
                        const totalRows = parseInt(rangeMatch[3], 10);
                        AndroidBridge.updateStatus('Scanned ' + endRow + ' of ' + totalRows + ' rows...');
                        if (endRow < totalRows) {
                            const nextBtn = Array.from(document.querySelectorAll('button, a, span, div')).find(el => {
                                if (el.offsetParent === null || el.disabled) return false;
                                const label = ((el.getAttribute('aria-label') || '') + ' ' + (el.getAttribute('title') || '') + ' ' + (el.innerText || '')).trim().toLowerCase();
                                return label === '>' || label === '›' || label.includes('next page') || label === 'next';
                            });
                            if (nextBtn) {
                                nextBtn.click();
                                window.__pagingWaitUntil = Date.now() + 1500;
                                return;
                            }
                        }
                    }

                    let eTotal = 0, cTotal = 0, eBags = 0, cBags = 0;
                    Object.keys(window.__collectedBags).forEach(bagNo => {
                        const cnt = window.__collectedBags[bagNo];
                        if (bagNo.startsWith('E')) { eTotal += cnt; eBags++; }
                        else if (bagNo.startsWith('C')) { cTotal += cnt; cBags++; }
                    });

                    window.__done = true;
                    AndroidBridge.onBagCountReady(eTotal, cTotal, eBags, cBags);
                }, 1000);
            })();
        """.trimIndent()

        webView.evaluateJavascript(js, null)
    }

    inner class BagBridge {
        @JavascriptInterface
        fun updateStatus(msg: String) {
            runOnUiThread {
                statusText.text = msg
            }
        }

        @JavascriptInterface
        fun onBagCountReady(eTotal: Int, cTotal: Int, eBags: Int, cBags: Int) {
            runOnUiThread {
                val grandTotal = eTotal + cTotal
                val msg = "E Bags ($eBags):$eTotal articles | C Bags ($cBags):$cTotal articles
