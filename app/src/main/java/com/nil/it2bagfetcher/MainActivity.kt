package com.nil.it2bagfetcher

import android.Manifest
import android.annotation.SuppressLint
import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
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
        private const val PORTAL_URL = "https://app.indiapost.gov.in/employeeportal/home"
        private const val CHANNEL_ID = "it2_bag_alerts"
    }

    private lateinit var webView: WebView
    private lateinit var statusText: TextView

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
            addJavascriptInterface(BagBridge(), "AndroidBridge")
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    injectAutomationScript()
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
            runOnUiThread {
                statusText.text = "OTP $otp detected — verifying..."
                val otpJs = """
                    (function() {
                        const inputs = Array.from(document.querySelectorAll('input'));
                        const otpBox = inputs.find(i =>
                            (i.placeholder || '').toLowerCase().includes('otp') ||
                            (i.name || '').toLowerCase().includes('otp') ||
                            i.maxLength === 6
                        );
                        if (otpBox) {
                            const setter = Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype, 'value').set;
                            setter.call(otpBox, '$otp');
                            otpBox.dispatchEvent(new Event('input', { bubbles: true }));
                            otpBox.dispatchEvent(new Event('change', { bubbles: true }));
                        }
                        const verifyBtn = Array.from(document.querySelectorAll('button, a'))
                            .find(b => /verify|submit|login/i.test(b.innerText));
                        if (verifyBtn) verifyBtn.click();
                    })();
                """.trimIndent()
                webView.evaluateJavascript(otpJs, null)
            }
        }

        if (intent?.getBooleanExtra("AUTO_RUN", false) == true) {
            startAutomation()
        }
    }

    private fun startAutomation() {
        statusText.text = "Opening IT 2.0 portal..."
        webView.loadUrl(PORTAL_URL)
    }

    private fun injectAutomationScript() {
        val prefs = getSharedPreferences("it2_prefs", Context.MODE_PRIVATE)
        val empId = prefs.getString("emp_id", "") ?: ""
        val empPass = prefs.getString("emp_pass", "") ?: ""

        val js = """
            (function() {
                function setNativeValue(el, val) {
                    if (!el) return;
                    const setter = Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype, 'value').set;
                    setter.call(el, val);
                    el.dispatchEvent(new Event('input', { bubbles: true }));
                    el.dispatchEvent(new Event('change', { bubbles: true }));
                }
                function clickByText(regex) {
                    const el = Array.from(document.querySelectorAll('button, a, div, span, li'))
                        .find(e => e.children.length === 0 && regex.test((e.innerText || '').trim()));
                    if (el) { el.click(); return true; }
                    return false;
                }

                const passField = document.querySelector('input[type="password"]');
                const textFields = Array.from(document.querySelectorAll('input[type="text"], input:not([type])'));
                if (passField && textFields.length > 0 && '$empId' !== '') {
                    setNativeValue(textFields[0], '$empId');
                    setNativeValue(passField, '$empPass');
                    clickByText(/mobile otp/i);
                    setTimeout(() => clickByText(/continue|sign in|login/i), 600);
                    return;
                }

                const navSteps = [
                    /bag management/i,
                    /next page/i,
                    /^report$/i,
                    /expected bag/i,
                    /bag close/i,
                    /^fetch$/i
                ];
                let stepIdx = 0;
                const navTimer = setInterval(() => {
                    if (stepIdx < navSteps.length) {
                        if (clickByText(navSteps[stepIdx])) stepIdx++;
                    } else {
                        clearInterval(navTimer);
                        setTimeout(parseBagTable, 2500);
                    }
                }, 1500);

                function parseBagTable() {
                    const rows = Array.from(document.querySelectorAll('table tr'));
                    if (rows.length < 2) return;

                    let bagCol = -1, countCol = -1;
                    const headers = Array.from(rows[0].querySelectorAll('th, td')).map(c => c.innerText.trim().toLowerCase());
                    headers.forEach((h, idx) => {
                        if (h.includes('bag') && (h.includes('no') || h.includes('id') || h.includes('number'))) bagCol = idx;
                        if (h.includes('article') || h.includes('count') || h.includes('qty')) countCol = idx;
                    });
                    if (bagCol === -1) bagCol = 0;
                    if (countCol === -1) countCol = headers.length - 1;

                    let eTotal = 0, cTotal = 0, eBags = 0, cBags = 0;
                    rows.slice(1).forEach(r => {
                        const cells = Array.from(r.querySelectorAll('td')).map(c => c.innerText.trim());
                        if (cells.length <= Math.max(bagCol, countCol)) return;
                        const bagNo = cells[bagCol].toUpperCase();
                        const articles = parseInt(cells[countCol].replace(/[^0-9]/g, ''), 10) || 0;
                        if (bagNo.startsWith('E')) { eTotal += articles; eBags++; }
                        else if (bagNo.startsWith('C')) { cTotal += articles; cBags++; }
                    });

                    AndroidBridge.onBagCountReady(eTotal, cTotal, eBags, cBags);
                }
            })();
        """.trimIndent()

        webView.evaluateJavascript(js, null)
    }

    inner class BagBridge {
        @JavascriptInterface
        fun onBagCountReady(eTotal: Int, cTotal: Int, eBags: Int, cBags: Int) {
            runOnUiThread {
                val grandTotal = eTotal + cTotal
                val msg = "E Bags ($eBags): $eTotal articles | C Bags ($cBags): $cTotal articles | Total: $grandTotal"
                statusText.text = msg
                sendNotification("IT 2.0 Expected Bags (6:00 AM)", msg)
            }
        }
    }

    private fun sendNotification(title: String, body: String) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        manager.notify(2026, notification)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "IT 2.0 Bag Alerts", NotificationManager.IMPORTANCE_HIGH
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun scheduleDaily6AM() {
        val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(this, DailyAlarmReceiver::class.java)
        val pending = PendingIntent.getBroadcast(
            this, 600, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val cal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 6)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            if (before(Calendar.getInstance())) add(Calendar.DAY_OF_YEAR, 1)
        }
        alarmManager.setRepeating(
            AlarmManager.RTC_WAKEUP, cal.timeInMillis, AlarmManager.INTERVAL_DAY, pending
        )
    }

    private fun requestAppPermissions() {
        val needed = mutableListOf(Manifest.permission.RECEIVE_SMS, Manifest.permission.READ_SMS)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            needed.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        val missing = needed.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, missing.toTypedArray(), 101)
        }
    }
}
