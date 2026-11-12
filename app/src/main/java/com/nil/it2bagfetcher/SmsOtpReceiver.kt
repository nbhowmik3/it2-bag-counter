package com.nil.it2bagfetcher

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony

class SmsOtpReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent)
        val fullBody = messages.joinToString(" ") { it.messageBody ?: "" }

        if (fullBody.contains("OTP", ignoreCase = true) ||
            fullBody.contains("India Post", ignoreCase = true) ||
            fullBody.contains("DOP", ignoreCase = true)
        ) {
            val otp = Regex("\\b(\\d{6})\\b").find(fullBody)?.groupValues?.get(1) ?: return
            MainActivity.onOtpReceived?.invoke(otp)
        }
    }
}
