package com.nil.it2bagfetcher

import android.Manifest
import android.annotation.SuppressLint
import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.*
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
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
        private const val PORTAL_URL = "https://app.indiapost.gov.in/employeeportal/home"
        private const val BAGGING_HOME_URL = "https://app.indiapost.gov.in/bagging/bag-management"
        private const val REPORT_URL = "https://app.indiapost.gov.in/bagging/bag-management/reports/expected-bag-po"
        private const val CHANNEL_ID = "it2_bag_alerts"
    }

    private lateinit var webView: WebView
    private lateinit var statusText: TextView
    private lateinit var credCard: LinearLayout
    private lateinit var compactBar: LinearLayout
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
                val msg = "E ($eBags): $eTotal  |  C ($cBags): $cTotal  |  Total: ${eTotal + cTotal}"
                statusText.text = msg
                sendNotification(
                    "IT 2.0 Expected Bags (6:00 AM)",
                    "E Bags ($eBags): $eTotal articles | C Bags ($cBags): $cTotal articles | Total: ${eTotal + cTotal}"
                )
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
        val savedId = prefs.getString("emp_id", "") ?: ""
        val savedPass = prefs.getString("emp_pass", "") ?: ""

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#F5F5F5"))
        }

        compactBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.parseColor("#B71C1C"))
            setPadding(24, 16, 16, 16)
        }
        statusText = TextView(this).apply {
            text = "Ready (6:00 AM auto-fetch active)"
            setTextColor(Color.WHITE)
            textSize = 13f
            setTypeface(null, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val fetchBtn = Button(this).apply {
            text = "Fetch"
            textSize = 12f
            setTextColor(Color.parseColor("#1A1A1A"))
            setBackgroundColor(Color.parseColor("#FFB300"))
            setPadding(20, 4, 20, 4)
        }
        val editBtn = Button(this).apply {
            text = "ID"
            textSize = 12f
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#8E0000"))
            setPadding(16, 4, 16, 4)
        }
        compactBar.addView(statusText)
        compactBar.addView(fetchBtn)
        compactBar.addView(editBtn)

        credCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
            setPadding(28, 24, 28, 24)
        }
        val idInput = EditText(this).apply {
            hint = "8-Digit Employee ID"
            setText(savedId)
        }
        val passInput = EditText(this).apply {
            hint = "Password"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setText(savedPass)
        }
        val saveBtn = Button(this).apply {
            text = "Save & Fetch E / C Bag Count"
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#B71C1C"))
        }
        credCard.addView(idInput)
        credCard.addView(passInput)
        credCard.addView(saveBtn)

        val hasCreds = savedId.isNotEmpty() && savedPass.isNotEmpty()
        credCard.visibility = if (hasCreds) View.GONE else View.VISIBLE
        compactBar.visibility = if (hasCreds) View.VISIBLE else View.GONE

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

        root.addView(compactBar)
        root.addView(credCard)
        root.addView(webView)
        setContentView(root)

        saveBtn.setOnClickListener {
            val id = idInput.text.toString().trim()
            val pw = passInput.text.toString().trim()
            if (id.isEmpty() || pw.isEmpty()) return@setOnClickListener
            prefs.edit().putString("emp_id", id).putString("emp_pass", pw).apply()
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.hideSoftInputFromWindow(passInput.windowToken, 0)
            credCard.visibility = View.GONE
            compactBar.visibility = View.VISIBLE
            startAutomation()
        }
        fetchBtn.setOnClickListener { startAutomation() }
        editBtn.setOnClickListener {
            credCard.visibility = if (credCard.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }
        onOtpReceived = { otp -> injectOtpIntoWebView(otp) }
        if (intent?.getBooleanExtra("AUTO_RUN", false) == true) startAutomation()
    }

    private fun startAutomation() {
        lastInjectedOtp = ""
        loginStartTimeMs = System.currentTimeMillis()
        statusText.text = "Opening Bag Management..."
        webView.evaluateJavascript("window.__it2State = null;", null)
        webView.loadUrl(BAGGING_HOME_URL)
        startInboxOtpPoller()
    }

    private fun startInboxOtpPoller() {
        mainHandler.removeCallbacksAndMessages(null)
        val poller = object : Runnable {
            override fun run() {
                checkRecentInboxOtp()
                if (System.currentTimeMillis() - loginStartTimeMs < 180_000L) mainHandler.postDelayed(this, 1200L)
            }
        }
        mainHandler.postDelayed(poller, 2000L)
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
                    if (body.contains("OTP", true) || body.contains("INDIAPOST", true) || body.contains("INPOST", true)) {
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
            statusText.text = "Verifying OTP $otp..."
            val otpJs = """
                (function() {
                    const setter = Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype, 'value').set;
                    const boxes = Array.from(document.querySelectorAll('input')).filter(i => {
                        const r = i.getBoundingClientRect();
                        return r.width > 0 && r.height > 0 && i.type !== 'hidden' && i.type !== 'password' && i.type !== 'radio' && i.type !== 'checkbox';
                    });
                    if (boxes.length >= 6) {
                        '$otp'.split('').forEach((d, idx) => {
                            boxes[idx].focus();
                            setter.call(boxes[idx], d);
                            boxes[idx].dispatchEvent(new Event('input', { bubbles: true }));
                            boxes[idx].dispatchEvent(new KeyboardEvent('keyup', { bubbles: true, key: d }));
                            boxes[idx].dispatchEvent(new Event('change', { bubbles: true }));
                            boxes[idx].dispatchEvent(new Event('blur', { bubbles: true }));
                        });
                    } else if (boxes.length === 1) {
                        boxes[0].focus();
                        setter.call(boxes[0], '$otp');
                        boxes[0].dispatchEvent(new Event('input', { bubbles: true }));
                        boxes[0].dispatchEvent(new Event('change', { bubbles: true }));
                        boxes[0].dispatchEvent(new Event('blur', { bubbles: true }));
                    }
                    setTimeout(() => {
                        const btn = Array.from(document.querySelectorAll('button, a, input[type="submit"]')).find(b => {
                            const r = b.getBoundingClientRect();
                            return r.width > 0 && r.height > 0 && /verify otp|verify|submit/i.test((b.innerText || b.value || '').trim());
                        });
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
                if (!window.__it2State) {
                    window.__it2State = {
                        signedInAt: 0,
                        authSeenAt: 0,
                        otpClickedAt: 0,
                        baggingSeenAt: 0,
                        typePicked: false,
                        dropdownOpenedAt: 0,
                        fetchClickedAt: 0,
                        expanded: false,
                        waitUntil: 0,
                        bags: {},
                        done: false
                    };
                }
                const S = window.__it2State;
                const NL = String.fromCharCode(10);

                function isVis(el) {
                    if (!el) return false;
                    const r = el.getBoundingClientRect();
                    return r.width > 0 && r.height > 0;
                }
                function fireClick(el) {
                    if (!el) return false;
                    ['pointerdown', 'mousedown', 'pointerup', 'mouseup', 'click'].forEach(ev => {
                        el.dispatchEvent(new MouseEvent(ev, { view: window, bubbles: true, cancelable: true }));
                    });
                    if (typeof el.click === 'function') el.click();
                    return true;
                }
                function setVal(el, val) {
                    if (!el) return;
                    el.focus();
                    Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype, 'value').set.call(el, val);
                    el.dispatchEvent(new Event('input', { bubbles: true }));
                    el.dispatchEvent(new Event('change', { bubbles: true }));
                    el.dispatchEvent(new Event('blur', { bubbles: true }));
                }
                function clickByText(fn) {
                    const els = Array.from(document.querySelectorAll('button, a, div, span, li, p, label, h1, h2, h3, h4, h5, h6, mat-option, [role="option"], input[type="button"], input[type="submit"]'))
                        .filter(e => isVis(e));
                    const match = els.find(e => {
                        const t = (e.innerText || e.value || '').trim().toLowerCase();
                        return t.length > 0 && t.length < 70 && fn(t, e);
                    });
                    if (!match) return false;
                    const target = match.closest('mat-option, [role="option"], button, a, [role="button"], [routerlink]') || match;
                    return fireClick(target);
                }

                if (window.__it2Timer) clearInterval(window.__it2Timer);
                window.__it2Timer = setInterval(() => {
                    if (S.done || Date.now() < S.waitUntil) return;
                    const bodyTxt = (document.body.innerText || '').toLowerCase();
                    if (bodyTxt.includes('redirecting, please wait') || bodyTxt.includes('validating permissions')) return;

                    // 1. Login Screen
                    const pass = Array.from(document.querySelectorAll('input[type="password"]')).find(i => isVis(i));
                    if (pass) {
                        if (Date.now() - S.signedInAt > 4000) {
                            const ins = Array.from(document.querySelectorAll('input')).filter(i => isVis(i) && i.type !== 'password' && i.type !== 'hidden' && i.type !== 'radio' && i.type !== 'checkbox');
                            if (ins.length > 0) {
                                S.signedInAt = Date.now();
                                AndroidBridge.updateStatus('Signing in...');
                                setVal(ins[0], '$empId');
                                setVal(pass, '$empPass');
                                setTimeout(() => clickByText(t => t === 'sign in' || t === 'login' || t === 'continue'), 450);
                            }
                        }
                        return;
                    }

                    // 2. Select Authentication Method (Option 2: Mobile OTP unlocks after 30s)
                    if (bodyTxt.includes('select authentication method')) {
                        if (!S.authSeenAt) S.authSeenAt = Date.now();
                        const elapsed = Math.floor((Date.now() - S.authSeenAt) / 1000);
                        const radios = Array.from(document.querySelectorAll('input[type="radio"]')).filter(r => isVis(r));
                        const opt2 = radios.length >= 2 ? radios[1] : null;
                        const locked = bodyTxt.includes('available in') || (opt2 && (opt2.disabled || opt2.getAttribute('aria-disabled') === 'true'));
                        if (locked && elapsed < 32) {
                            AndroidBridge.updateStatus('Mobile OTP unlocks in ' + Math.max(1, 30 - elapsed) + 's...');
                            return;
                        }
                        if (Date.now() - S.otpClickedAt > 2000) {
                            S.otpClickedAt = Date.now();
                            AndroidBridge.updateStatus('Selecting Mobile OTP...');
                            if (opt2) fireClick(opt2);
                            clickByText(t => t === 'mobile otp' || t.startsWith('mobile otp'));
                            setTimeout(() => {
                                clickByText(t => t === 'continue' || t === 'send otp');
                                AndroidBridge.updateStatus('Waiting for SMS OTP...');
                            }, 500);
                        }
                        return;
                    }

                    // 3. Enter OTP screen
                    if (bodyTxt.includes('enter otp') || bodyTxt.includes('verify otp')) return;

                    // 4. Handle 401 by returning to Employee Portal home
                    if (bodyTxt.includes('401') || bodyTxt.includes('unauthorized')) {
                        AndroidBridge.updateStatus('Refreshing portal role...');
                        window.location.href = '$PORTAL_URL';
                        S.waitUntil = Date.now() + 2500;
                        return;
                    }

                    // 5. Navigate to Bag Management -> Reports -> Expected Bags
                    if (!window.location.href.includes('/reports/expected-bag-po')) {
                        if (window.location.href.includes('/bagging/bag-management')) {
                            if (!S.baggingSeenAt) S.baggingSeenAt = Date.now();
                            if (clickByText(t => t === 'expected bags' || t === 'expected bag po')) {
                                AndroidBridge.updateStatus('Opening Expected Bags...');
                                S.waitUntil = Date.now() + 1500;
                                return;
                            }
                            if (clickByText(t => t.split(NL)[0].trim() === 'reports' || t.includes('bagging management reports'))) {
                                AndroidBridge.updateStatus('Opening Reports menu...');
                                S.waitUntil = Date.now() + 1200;
                                return;
                            }
                            if (bodyTxt.includes('page 1 of')) {
                                const pageEl = Array.from(document.querySelectorAll('div, span, p')).find(e => isVis(e) && /^page[ ]+1[ ]+of[ ]+[0-9]+$/i.test((e.innerText || '').trim()));
                                if (pageEl) {
                                    let box = pageEl.parentElement;
                                    while (box && box.getBoundingClientRect().width < 120) box = box.parentElement;
                                    if (box) {
                                        const btns = Array.from(box.querySelectorAll('button, a, [role="button"], mat-icon, svg, i')).filter(b => isVis(b) && b !== pageEl);
                                        if (btns.length > 0) {
                                            const last = btns[btns.length - 1];
                                            fireClick(last.closest('button, a, [role="button"]') || last);
                                            AndroidBridge.updateStatus('Bag Mgmt -> Page 2...');
                                            S.waitUntil = Date.now() + 1200;
                                            return;
                                        }
                                    }
                                }
                            }
                            if (Date.now() - S.baggingSeenAt > 4000) {
                                AndroidBridge.updateStatus('Opening Expected Bag PO...');
                                window.location.href = '$REPORT_URL';
                                S.waitUntil = Date.now() + 2000;
                            }
                            return;
                        }

                        AndroidBridge.updateStatus('Opening Bag Management...');
                        window.location.href = '$BAGGING_HOME_URL';
                        S.waitUntil = Date.now() + 2500;
                        return;
                    }

                    // 6. Select "By Close Event" (native <select> or Angular <mat-select>)
                    if (!S.typePicked) {
                        for (const sel of Array.from(document.querySelectorAll('select')).filter(s => isVis(s))) {
                            const opt = Array.from(sel.options).find(o => /close event/i.test(o.text));
                            if (opt) {
                                sel.value = opt.value;
                                sel.dispatchEvent(new Event('input', { bubbles: true }));
                                sel.dispatchEvent(new Event('change', { bubbles: true }));
                                S.typePicked = true;
                                AndroidBridge.updateStatus('Selected: By Close Event');
                                S.waitUntil = Date.now() + 700;
                                return;
                            }
                        }
                        const panelOpen = document.querySelector('mat-option, [role="option"], .mat-mdc-select-panel, .p-dropdown-panel');
                        if (panelOpen) {
                            if (clickByText(t => t.includes('close event'))) {
                                S.typePicked = true;
                                AndroidBridge.updateStatus('Selected: By Close Event');
                                S.waitUntil = Date.now() + 700;
                            }
                            return;
                        }
                        if (bodyTxt.includes('by close event')) {
                            S.typePicked = true;
                            AndroidBridge.updateStatus('Selected: By Close Event');
                            return;
                        }
                        if (Date.now() - S.dropdownOpenedAt > 1500) {
                            S.dropdownOpenedAt = Date.now();
                            const matSel = Array.from(document.querySelectorAll('mat-select, [role="combobox"], .p-dropdown')).find(e => isVis(e));
                            if (matSel) {
                                fireClick(matSel);
                                S.waitUntil = Date.now() + 600;
                            }
                        }
                        return;
                    }

                    // 7. Click "Fetch" button
                    const hasDataRows = Array.from(document.querySelectorAll('td, mat-cell, [role="cell"]'))
                        .some(c => isVis(c) && /^[EC][A-Z0-9]{6,}$/i.test((c.innerText || '').replace(/[ ]+/g, '').trim()));
                    if (!hasDataRows && Date.now() - S.fetchClickedAt > 2500) {
                        const fetchBtn = Array.from(document.querySelectorAll('button, a, [role="button"], input[type="button"], input[type="submit"]'))
                            .find(b => {
                                if (!isVis(b) || b.disabled) return false;
                                const txt = (b.innerText || b.textContent || b.value || '').toLowerCase();
                                return txt.includes('fetch') && !txt.includes('fetching') && txt.length < 35;
                            });
                        if (fetchBtn) {
                            S.fetchClickedAt = Date.now();
                            AndroidBridge.updateStatus('Clicking Fetch...');
                            fireClick(fetchBtn);
                            S.waitUntil = Date.now() + 2500;
                            return;
                        }
                    }

                    // 8. Parse table (HTML <table> or Angular <mat-table>) and scan all pages
                    const allRows = Array.from(document.querySelectorAll('tr, mat-row, [role="row"]')).filter(r => isVis(r));
                    if (allRows.length < 2) return;

                    if (!S.expanded) {
                        S.expanded = true;
                        const pSel = Array.from(document.querySelectorAll('select')).find(s => isVis(s) && Array.from(s.options).some(o => o.text.trim() === '10') && Array.from(s.options).some(o => parseInt(o.text.trim(), 10) > 10));
                        if (pSel) {
                            const maxO = Array.from(pSel.options).sort((a, b) => (parseInt(b.text.trim(), 10) || 0) - (parseInt(a.text.trim(), 10) || 0))[0];
                            if (maxO && pSel.value !== maxO.value) {
                                pSel.value = maxO.value;
                                pSel.dispatchEvent(new Event('change', { bubbles: true }));
                                S.waitUntil = Date.now() + 1800;
                                return;
                            }
                        }
                    }

                    let bCol = -1;
                    let cCol = -1;
                    for (const r of allRows) {
                        const hCells = Array.from(r.querySelectorAll('th, td, mat-header-cell, mat-cell, [role="columnheader"], [role="cell"]'))
                            .map(c => (c.innerText || c.textContent || '').trim().toLowerCase());
                        const bi = hCells.findIndex(h => h.includes('bag') && (h.includes('no') || h.includes('num') || h.includes('id') || h === 'bag'));
                        const ci = hCells.findIndex(h => h.includes('content') || h.includes('count') || h.includes('article') || h.includes('qty'));
                        if (bi !== -1 || ci !== -1) {
                            if (bi !== -1) bCol = bi;
                            if (ci !== -1) cCol = ci;
                            break;
                        }
                    }

                    let parsedThisPage = 0;
                    allRows.forEach(r => {
                        const cells = Array.from(r.querySelectorAll('td, mat-cell, [role="cell"]'))
                            .map(c => (c.innerText || c.textContent || '').trim());
                        if (cells.length < 2) return;
                        let bi = bCol;
                        if (bi < 0 || bi >= cells.length || !/^[EC][A-Z0-9]{6,}$/i.test(cells[bi].replace(/[ ]+/g, ''))) {
                            bi = cells.findIndex(v => /^[EC][A-Z0-9]{6,}$/i.test(v.replace(/[ ]+/g, '')));
                        }
                        if (bi === -1) return;
                        const ci = (cCol >= 0 && cCol < cells.length) ? cCol : Math.max(0, cells.length - 2);
                        const bag = cells[bi].replace(/[ ]+/g, '').toUpperCase();
                        const cnt = parseInt((cells[ci] || '').replace(/[^0-9]/g, ''), 10) || 0;
                        S.bags[bag] = cnt;
                        parsedThisPage++;
                    });

                    if (parsedThisPage === 0 && !bodyTxt.includes('no record') && !bodyTxt.includes('no data')) return;

                    let eSum = 0, cSum = 0, eCnt = 0, cCnt = 0;
                    Object.keys(S.bags).forEach(b => {
                        if (b.startsWith('E')) { eSum += S.bags[b]; eCnt++; }
                        else if (b.startsWith('C')) { cSum += S.bags[b]; cCnt++; }
                    });

                    const rng = (document.body.innerText || '').match(/([0-9]+)[ ]*[-–—][ ]*([0-9]+)[ ]+of[ ]+([0-9]+)/i);
                    const pgMatch = (document.body.innerText || '').match(/page[ ]+([0-9]+)[ ]+of[ ]+([0-9]+)/i);
                    const hasMoreRows = rng && parseInt(rng[2], 10) < parseInt(rng[3], 10);
                    const hasMorePages = !rng && pgMatch && parseInt(pgMatch[1], 10) < parseInt(pgMatch[2], 10);

                    if (hasMoreRows || hasMorePages) {
                        const stepLabel = rng ? (rng[2] + '/' + rng[3] + ' rows') : ('p.' + pgMatch[1] + '/' + pgMatch[2]);
                        AndroidBridge.updateStatus('Scanning (' + stepLabel + ') | E: ' + eSum + ', C: ' + cSum);
                        const nxt = Array.from(document.querySelectorAll('button, a, [role="button"], span, div')).find(e => {
                            if (!isVis(e) || e.disabled || e.getAttribute('aria-disabled') === 'true') return false;
                            const aria = (e.getAttribute('aria-label') || e.getAttribute('title') || '').toLowerCase();
                            const cls = (e.className || '').toString().toLowerCase();
                            const txt = (e.innerText || '').trim().toLowerCase();
                            if (cls.includes('disabled')) return false;
                            return aria.includes('next') || cls.includes('next') || txt === '>' || txt === '›' || txt === 'next' || txt === 'chevron_right' || txt === 'navigate_next';
                        });
                        if (nxt) {
                            fireClick(nxt.closest('button, a, [role="button"]') || nxt);
                            S.waitUntil = Date.now() + 1800;
                            return;
                        }
                    }

                    S.done = true;
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
