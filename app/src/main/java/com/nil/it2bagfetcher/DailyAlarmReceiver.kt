package com.nil.it2bagfetcher

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class DailyAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val launch = Intent(context, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            putExtra("AUTO_RUN", true)
        }
        context.startActivity(launch)
    }
}
