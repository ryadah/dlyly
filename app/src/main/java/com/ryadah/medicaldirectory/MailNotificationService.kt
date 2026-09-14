package com.ryadah.medicaldirectory

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class MailNotificationService : Service() {
    private var worker: Thread? = null
    @Volatile private var running = false

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startForeground(NOTIFICATION_ID_FOREGROUND, buildServiceNotification())
        running = true
        worker = Thread { pollLoop() }.also { it.start() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    private fun pollLoop() {
        var revision = 0L
        while (running) {
            try {
                val username = getSharedPreferences(MainActivity.NOTIFICATION_PREFS, MODE_PRIVATE)
                    .getString(MainActivity.NOTIFICATION_USERNAME, "")?.trim().orEmpty()
                if (username.isNotEmpty()) {
                    val result = fetchState(revision)
                    if (result != null) {
                        val rev = result.optLong("revision", revision)
                        if (rev > revision) {
                            revision = rev
                            val messages = result.optJSONArray("messages")
                            if (messages != null) {
                                for (i in 0 until messages.length()) {
                                    val m = messages.optJSONObject(i) ?: continue
                                    if (m.optString("to") == username) {
                                        observeMessage(this, m.optString("id"), m.optString("from"), m.optString("subject"), m.optString("body"))
                                    }
                                }
                            }
                        }
                    }
                }
            } catch (_: Exception) {}
            try { Thread.sleep(POLL_INTERVAL_MS) } catch (_: InterruptedException) { break }
        }
    }

    private fun fetchState(revision: Long): JSONObject? {
        val url = URL("http://ryadah.3utilities.com:8000/api/state?revision=$revision")
        val c = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 10000
            readTimeout = 20000
            instanceFollowRedirects = true
        }
        return try {
            c.connect()
            if (c.responseCode == 204) null
            else if (c.responseCode in 200..299) c.inputStream.bufferedReader(Charsets.UTF_8).use { JSONObject(it.readText()) }
            else null
        } finally { c.disconnect() }
    }

    private fun buildServiceNotification(): Notification = NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(android.R.drawable.ic_dialog_info)
        .setContentTitle("الدليل الطبي")
        .setContentText("مراقبة الرسائل الجديدة")
        .setOngoing(true)
        .setSilent(true)
        .setPriority(NotificationCompat.PRIORITY_LOW)
        .build()

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            if (manager.getNotificationChannel(CHANNEL_ID) == null) {
                val channel = android.app.NotificationChannel(CHANNEL_ID, "إشعارات البريد الداخلي", NotificationManager.IMPORTANCE_HIGH)
                channel.enableVibration(true)
                channel.setSound(android.provider.Settings.System.DEFAULT_NOTIFICATION_URI, android.media.AudioAttributes.Builder()
                    .setUsage(android.media.AudioAttributes.USAGE_NOTIFICATION).build())
                manager.createNotificationChannel(channel)
            }
        }
    }

    override fun onDestroy() {
        running = false
        worker?.interrupt()
        worker = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val CHANNEL_ID = "medical_directory_mail"
        private const val NOTIFICATION_ID_FOREGROUND = 7001
        private const val POLL_INTERVAL_MS = 30000L
        private const val SEEN_KEY = "seen_ids"
        private const val SEEN_SEPARATOR = "\u001f"
        private const val BASELINE_KEY = "baseline_user"

        fun initializeBaselineForUser(context: Context, username: String, baselineIds: String) {
            if (username.isBlank()) return
            val ids = baselineIds.split("\u001f").map { it.trim() }.filter { it.isNotBlank() }.takeLast(MAX_SEEN_IDS)
            val p = context.getSharedPreferences(MainActivity.NOTIFICATION_PREFS, MODE_PRIVATE)
            p.edit()
                .putString(BASELINE_KEY, username)
                .putString(SEEN_KEY, ids.joinToString(SEEN_SEPARATOR))
                .apply()
        }

        fun observeMessage(context: Context, id: String, from: String, subject: String, body: String) {
            if (id.isBlank()) return
            val p = context.getSharedPreferences(MainActivity.NOTIFICATION_PREFS, MODE_PRIVATE)
            val user = p.getString(MainActivity.NOTIFICATION_USERNAME, "").orEmpty()
            if (user.isBlank()) return
            val baselineUser = p.getString(BASELINE_KEY, "")
            val raw = p.getString(SEEN_KEY, null)
            if (baselineUser != user || raw == null) {
                p.edit().putString(BASELINE_KEY, user).putString(SEEN_KEY, id).apply()
                return
            }
            val seen = raw.split(SEEN_SEPARATOR).filter { it.isNotBlank() }.toMutableSet()
            if (seen.contains(id)) return
            showMessageNotification(context, id, from, subject, body)
            seen.add(id)
            val trimmed = seen.toList().takeLast(MAX_SEEN_IDS).joinToString(SEEN_SEPARATOR)
            p.edit().putString(SEEN_KEY, trimmed).apply()
        }

        private fun showMessageNotification(context: Context, id: String, from: String, subject: String, body: String) {
            if (Build.VERSION.SDK_INT >= 33 && androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) return
            val openIntent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val pending = PendingIntent.getActivity(context, id.hashCode(), openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0)
            val preview = body.replace("\n", " ").trim().let { if (it.length > 120) it.take(120) + "…" else it }
            val text = if (preview.isNotBlank()) preview else "وصلت رسالة جديدة"
            val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_email)
                .setContentTitle(if (from.isNotBlank()) "رسالة جديدة من $from" else "رسالة جديدة")
                .setContentText(if (subject.isNotBlank()) subject else text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setContentIntent(pending)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_MESSAGE)
                .setDefaults(NotificationCompat.DEFAULT_SOUND or NotificationCompat.DEFAULT_VIBRATE)
                .build()
            NotificationManagerCompat.from(context).notify((id.hashCode() and 0x7fffffff), notification)
        }

        private const val MAX_SEEN_IDS = 1000
    }
}
