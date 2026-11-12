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
                if (System.currentTimeMillis() - loginStartTimeMs < 180_000L) {
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
                window.__signInClicked = false;
                window.__authScreenSeenAt = 0;
                window.__mobileOtpChosen = false;
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

                    const pageText = (document.body.innerText || '').toLowerCase();

                    // 0. Wait while initial splash redirects
                    if (pageText.includes('redirecting, please wait')) {
                        return;
                    }

                    // 1. Sign In screen (Employee ID + Password)
                    const passField = Array.from(document.querySelectorAll('input[type="password"]'))
                        .find(i => i.offsetParent !== null);
                    if (passField) {
                        if (!window.__signInClicked) {
                            const visibleInputs = Array.from(document.querySelectorAll('input'))
                                .filter(i => i.offsetParent !== null && i.type !== 'password' && i.type !== 'hidden' && i.type !== 'radio' && i.type
