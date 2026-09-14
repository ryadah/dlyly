package com.ryadah.medicaldirectory

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ContentValues
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Build
import android.os.Environment
import android.util.Base64
import android.webkit.JavascriptInterface
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.provider.MediaStore
import android.widget.Toast
import android.Manifest
import android.content.pm.PackageManager
import androidx.core.content.FileProvider
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.io.OutputStream
import java.io.FileInputStream
import java.io.BufferedInputStream
import java.io.BufferedOutputStream

class MainActivity : Activity() {
    private lateinit var webView: WebView
    private var filePathCallback: ValueCallback<Array<Uri>>? = null
    private var pendingDownloadName = "attachment"
    private var pendingDownloadMime = "application/octet-stream"
    private var pendingDownloadBytes: ByteArray? = null
    private var pendingAdminUploadType = ""

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        webView = WebView(this)
        setContentView(webView)
        createNotificationChannel()
        requestNotificationPermissionIfNeeded()

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            allowFileAccess = true
            allowContentAccess = true
            allowFileAccessFromFileURLs = true
            allowUniversalAccessFromFileURLs = true
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            cacheMode = WebSettings.LOAD_DEFAULT
            builtInZoomControls = false
            displayZoomControls = false
        }

        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                webView.evaluateJavascript("window.RYADAH_ANDROID_APP=true;", null)
            }
        }
        webView.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(
                view: WebView?,
                filePath: ValueCallback<Array<Uri>>?,
                fileChooserParams: FileChooserParams?
            ): Boolean {
                this@MainActivity.filePathCallback?.onReceiveValue(null)
                this@MainActivity.filePathCallback = filePath
                return try {
                    val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                        addCategory(Intent.CATEGORY_OPENABLE)
                        type = "*/*"
                        putExtra(Intent.EXTRA_ALLOW_MULTIPLE, false)
                    }
                    startActivityForResult(intent, FILE_CHOOSER_REQUEST)
                    true
                } catch (_: Exception) {
                    this@MainActivity.filePathCallback = null
                    Toast.makeText(this@MainActivity, "تعذر فتح اختيار المرفق", Toast.LENGTH_SHORT).show()
                    false
                }
            }
        }

        // Native Android support only: sharing and file handling.
        // Synchronization/server behavior is left entirely to the HTML page.
        webView.addJavascriptInterface(AndroidBridge(), "Android")
        prepareWebAppFiles()
        webView.loadUrl(webAppHtmlFile().toURI().toString())
        webView.evaluateJavascript("window.RYADAH_ANDROID_APP=true;", null)
    }

    private fun webAppDir(): File = File(filesDir, "webapp")
    private fun webAppHtmlFile(): File = File(webAppDir(), "الدليل_الطبي.html")
    private fun webAppCssFile(): File = File(webAppDir(), "bootstrap.min.css")
    private fun webAppVersionFile(): File = File(webAppDir(), "version.txt")

    private fun prepareWebAppFiles() {
        val dir = webAppDir()
        if (!dir.exists()) dir.mkdirs()
        if (!webAppHtmlFile().exists()) copyAsset("الدليل_الطبي.html", webAppHtmlFile())
        if (!webAppCssFile().exists()) copyAsset("bootstrap.min.css", webAppCssFile())
        if (!webAppVersionFile().exists()) webAppVersionFile().writeText("1")
    }

    private fun copyAsset(name: String, target: File) {
        assets.open(name).use { input -> target.outputStream().use { output -> input.copyTo(output) } }
    }

    private fun downloadToFile(urlString: String, target: File) {
        val connection = (URL(urlString).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 15000
            readTimeout = 60000
            instanceFollowRedirects = true
        }
        try {
            connection.connect()
            if (connection.responseCode !in 200..299) throw IllegalStateException("HTTP ${connection.responseCode}")
            connection.inputStream.use { input -> target.outputStream().use { output -> input.copyTo(output) } }
            if (!target.exists() || target.length() == 0L) throw IllegalStateException("empty_file")
        } finally { connection.disconnect() }
    }

    inner class AndroidBridge {
        @JavascriptInterface
        fun getAppVersionCode(): Int = try {
            packageManager.getPackageInfo(packageName, 0).longVersionCode.toInt()
        } catch (_: Exception) { 0 }

        @JavascriptInterface
        fun getAppVersionName(): String = try {
            packageManager.getPackageInfo(packageName, 0).versionName ?: ""
        } catch (_: Exception) { "" }

        @JavascriptInterface
        fun openExternalUrl(url: String) {
            runOnUiThread {
                try {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                    startActivity(intent)
                } catch (_: Exception) {
                    Toast.makeText(this@MainActivity, "تعذر فتح الرابط", Toast.LENGTH_SHORT).show()
                }
            }
        }

        @JavascriptInterface
        fun shareApplication() {
            runOnUiThread {
                try {
                    val source = File(applicationInfo.sourceDir)
                    val dir = File(cacheDir, "updates")
                    if (!dir.exists()) dir.mkdirs()
                    val shareFile = File(dir, "الدليل_الطبي.apk")
                    source.inputStream().use { input -> shareFile.outputStream().use { output -> input.copyTo(output) } }
                    val shareUri = FileProvider.getUriForFile(this@MainActivity, "${packageName}.fileprovider", shareFile)
                    val send = Intent(Intent.ACTION_SEND).apply {
                        type = "application/vnd.android.package-archive"
                        putExtra(Intent.EXTRA_STREAM, shareUri)
                        putExtra(Intent.EXTRA_TEXT, "تطبيق الدليل الطبي")
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    startActivity(Intent.createChooser(send, "مشاركة تطبيق الدليل الطبي"))
                } catch (_: Exception) {
                    Toast.makeText(this@MainActivity, "تعذر مشاركة التطبيق", Toast.LENGTH_SHORT).show()
                }
            }
        }

        @JavascriptInterface
        fun getWebVersion(): Int = try { webAppVersionFile().readText().trim().toInt() } catch (_: Exception) { 1 }

        @JavascriptInterface
        fun updateWebAssets(htmlUrl: String, cssUrl: String, version: Int) {
            if (htmlUrl.isBlank()) return
            Thread {
                try {
                    prepareWebAppFiles()
                    val dir = webAppDir()
                    val htmlTmp = File(dir, "الدليل_الطبي.html.download")
                    val cssTmp = File(dir, "bootstrap.min.css.download")
                    downloadToFile(htmlUrl, htmlTmp)
                    if (cssUrl.isNotBlank()) downloadToFile(cssUrl, cssTmp)
                    val htmlTarget = webAppHtmlFile()
                    val cssTarget = webAppCssFile()
                    if (!htmlTmp.renameTo(htmlTarget)) {
                        htmlTmp.copyTo(htmlTarget, overwrite = true); htmlTmp.delete()
                    }
                    if (cssUrl.isNotBlank()) {
                        if (!cssTmp.renameTo(cssTarget)) {
                            cssTmp.copyTo(cssTarget, overwrite = true); cssTmp.delete()
                        }
                    }
                    webAppVersionFile().writeText(version.toString())
                    runOnUiThread {
                        Toast.makeText(this@MainActivity, "تم تحديث واجهة التطبيق بنجاح", Toast.LENGTH_SHORT).show()
                        webView.loadUrl(webAppHtmlFile().toURI().toString() + "?v=" + version)
                    }
                } catch (_: Exception) {
                    runOnUiThread { Toast.makeText(this@MainActivity, "تعذر تنزيل تحديث واجهة التطبيق", Toast.LENGTH_LONG).show() }
                }
            }.start()
        }

        @JavascriptInterface
        fun startAdminUpdateUpload(kind: String) {
            val normalized = kind.trim().lowercase()
            if (normalized !in setOf("html", "css", "apk")) return
            pendingAdminUploadType = normalized
            runOnUiThread {
                try {
                    val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                        addCategory(Intent.CATEGORY_OPENABLE)
                        type = when (normalized) {
                            "html" -> "text/html"
                            "css" -> "text/css"
                            else -> "application/vnd.android.package-archive"
                        }
                        putExtra(Intent.EXTRA_ALLOW_MULTIPLE, false)
                    }
                    startActivityForResult(intent, ADMIN_UPDATE_UPLOAD_REQUEST)
                } catch (_: Exception) {
                    pendingAdminUploadType = ""
                    webView.evaluateJavascript("try{onAdminUpdateUploadResult(false,'تعذر فتح اختيار ملف التحديث');}catch(e){}", null)
                }
            }
        }

        @JavascriptInterface
        fun installUpdate(apkUrl: String) {
            if (apkUrl.isBlank()) return
            Thread {
                try {
                    val dir = File(cacheDir, "updates")
                    if (!dir.exists()) dir.mkdirs()
                    val apk = File(dir, "app-update.apk")
                    val connection = (URL(apkUrl).openConnection() as HttpURLConnection).apply {
                        requestMethod = "GET"
                        connectTimeout = 15000
                        readTimeout = 60000
                        instanceFollowRedirects = true
                    }
                    connection.connect()
                    if (connection.responseCode !in 200..299) throw IllegalStateException("HTTP ${connection.responseCode}")
                    connection.inputStream.use { input -> apk.outputStream().use { output -> input.copyTo(output) } }
                    connection.disconnect()
                    val uri = FileProvider.getUriForFile(this@MainActivity, "${packageName}.fileprovider", apk)
                    runOnUiThread {
                        try {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !packageManager.canRequestPackageInstalls()) {
                                val settingsIntent = Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                                    data = Uri.parse("package:$packageName")
                                }
                                startActivity(settingsIntent)
                                Toast.makeText(this@MainActivity, "فعّل السماح بتثبيت التطبيقات من هذا المصدر ثم اضغط تحديث مرة أخرى", Toast.LENGTH_LONG).show()
                                return@runOnUiThread
                            }
                            val intent = Intent(Intent.ACTION_VIEW).apply {
                                setDataAndType(uri, "application/vnd.android.package-archive")
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                            startActivity(intent)
                        } catch (_: Exception) {
                            Toast.makeText(this@MainActivity, "تعذر فتح مثبت التحديث", Toast.LENGTH_LONG).show()
                        }
                    }
                } catch (_: Exception) {
                    runOnUiThread { Toast.makeText(this@MainActivity, "تعذر تنزيل حزمة التحديث", Toast.LENGTH_LONG).show() }
                }
            }.start()
        }

        @JavascriptInterface
        fun setNotificationUser(username: String, baselineIds: String) {
            getSharedPreferences(NOTIFICATION_PREFS, MODE_PRIVATE).edit()
                .putString(NOTIFICATION_USERNAME, username.trim())
                .apply()
            MailNotificationService.initializeBaselineForUser(this@MainActivity, username.trim(), baselineIds)
        }

        @JavascriptInterface
        fun clearNotificationUser() {
            getSharedPreferences(NOTIFICATION_PREFS, MODE_PRIVATE).edit().clear().apply()
        }

        @JavascriptInterface
        fun observeIncomingMessage(id: String, from: String, subject: String, body: String) {
            MailNotificationService.observeMessage(this@MainActivity, id, from, subject, body)
        }

        @JavascriptInterface
        fun shareWhatsApp(text: String) {
            runOnUiThread {
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, text)
                }
                try {
                    // فتح واتساب مباشرة إن كان مثبتًا، ثم الرجوع لقائمة المشاركة عند عدم توفره.
                    val whatsapp = Intent(send).apply { setPackage("com.whatsapp") }
                    try {
                        startActivity(whatsapp)
                        return@runOnUiThread
                    } catch (_: ActivityNotFoundException) { }
                    val business = Intent(send).apply { setPackage("com.whatsapp.w4b") }
                    try {
                        startActivity(business)
                        return@runOnUiThread
                    } catch (_: ActivityNotFoundException) { }
                    startActivity(Intent.createChooser(send, "مشاركة الخدمة"))
                } catch (_: Exception) {
                    Toast.makeText(this@MainActivity, "لا يوجد تطبيق مناسب للمشاركة", Toast.LENGTH_SHORT).show()
                }
            }
        }

        @JavascriptInterface
        fun sendSms(text: String) {
            runOnUiThread {
                try {
                    val intent = Intent(Intent.ACTION_SENDTO).apply {
                        data = Uri.parse("smsto:")
                        putExtra("sms_body", text)
                    }
                    startActivity(intent)
                } catch (_: Exception) {
                    try {
                        val intent = Intent(Intent.ACTION_VIEW).apply {
                            data = Uri.parse("sms:")
                            putExtra("sms_body", text)
                        }
                        startActivity(intent)
                    } catch (_: Exception) {
                        Toast.makeText(this@MainActivity, "لا يوجد تطبيق رسائل متاح", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }

        @JavascriptInterface
        fun downloadBase64File(fileName: String, dataUrl: String, mimeType: String?) {
            // Decode off the UI thread so large attachments do not freeze the app.
            Thread {
                val clean = dataUrl.substringAfter(",", dataUrl)
                val bytes = try {
                    Base64.decode(clean, Base64.DEFAULT)
                } catch (_: Exception) {
                    runOnUiThread {
                        Toast.makeText(this@MainActivity, "تعذر قراءة المرفق", Toast.LENGTH_SHORT).show()
                    }
                    return@Thread
                }

                pendingDownloadName = fileName.ifBlank { "attachment" }
                pendingDownloadMime = mimeType?.takeIf { it.isNotBlank() } ?: "application/octet-stream"
                pendingDownloadBytes = bytes

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    try {
                        val values = ContentValues().apply {
                            put(MediaStore.Downloads.DISPLAY_NAME, pendingDownloadName)
                            put(MediaStore.Downloads.MIME_TYPE, pendingDownloadMime)
                            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                            put(MediaStore.Downloads.IS_PENDING, 1)
                        }
                        val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                        if (uri == null) throw IllegalStateException("تعذر إنشاء ملف التنزيل")
                        contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
                            ?: throw IllegalStateException("تعذر فتح ملف التنزيل")
                        val done = ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }
                        contentResolver.update(uri, done, null, null)
                        pendingDownloadBytes = null
                        runOnUiThread { Toast.makeText(this@MainActivity, "تم تنزيل المرفق إلى مجلد التنزيلات", Toast.LENGTH_SHORT).show() }
                    } catch (_: Exception) {
                        runOnUiThread { Toast.makeText(this@MainActivity, "تعذر تنزيل المرفق", Toast.LENGTH_SHORT).show() }
                    }
                } else {
                    runOnUiThread {
                        try {
                            val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                                addCategory(Intent.CATEGORY_OPENABLE)
                                type = pendingDownloadMime
                                putExtra(Intent.EXTRA_TITLE, pendingDownloadName)
                            }
                            startActivityForResult(intent, CREATE_DOCUMENT_REQUEST)
                        } catch (_: Exception) {
                            Toast.makeText(this@MainActivity, "تعذر فتح نافذة حفظ المرفق", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }.start()
        }
    }

    private fun uploadAdminUpdateFile(uri: Uri, kind: String) {
        try {
            val base = try { webView.url?.let { URL(it).protocol } } catch (_: Exception) { null }
            val serverBase = if (base == "http" || base == "https") "" else "http://ryadah.3utilities.com:8000"
            val endpoint = "$serverBase/api/admin/upload-update?type=${Uri.encode(kind)}"
            val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                connectTimeout = 15000
                readTimeout = 120000
                setRequestProperty("Content-Type", "application/octet-stream")
                setRequestProperty("X-Update-Type", kind)
                setRequestProperty("X-Update-Filename", when(kind){"html"->"الدليل_الطبي.html";"css"->"bootstrap.min.css";else->"app-update.apk"})
            }
            contentResolver.openInputStream(uri)?.use { input ->
                BufferedInputStream(input).use { source ->
                    BufferedOutputStream(connection.outputStream).use { output ->
                        val buffer = ByteArray(1024 * 64)
                        while (true) {
                            val n = source.read(buffer)
                            if (n <= 0) break
                            output.write(buffer, 0, n)
                        }
                        output.flush()
                    }
                }
            } ?: throw IllegalStateException("تعذر قراءة الملف")
            val code = connection.responseCode
            val responseText = try { connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() } } catch (_: Exception) { "" }
            connection.disconnect()
            if (code !in 200..299) throw IllegalStateException(responseText.ifBlank { "HTTP $code" })
            val safeMessage = responseText.replace("\\", "\\\\").replace("'", "\\'").replace("\n", " ").replace("\r", " ")
            runOnUiThread { webView.evaluateJavascript("try{onAdminUpdateUploadResult(true,'$safeMessage');}catch(e){}", null) }
        } catch (e: Exception) {
            val msg = (e.message ?: "تعذر رفع التحديث").replace("\\", "\\\\").replace("'", "\\'").replace("\n", " ").replace("\r", " ")
            runOnUiThread { webView.evaluateJavascript("try{onAdminUpdateUploadResult(false,'فشل رفع التحديث: $msg');}catch(e){}", null) }
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode == FILE_CHOOSER_REQUEST) {
            val callback = filePathCallback
            filePathCallback = null
            val result = if (resultCode == RESULT_OK && data?.data != null) {
                arrayOf(data.data!!)
            } else {
                null
            }
            callback?.onReceiveValue(result)
            return
        }

        if (requestCode == ADMIN_UPDATE_UPLOAD_REQUEST) {
            val kind = pendingAdminUploadType
            pendingAdminUploadType = ""
            val uri = if (resultCode == RESULT_OK) data?.data else null
            if (uri == null || kind.isBlank()) {
                webView.evaluateJavascript("try{onAdminUpdateUploadResult(false,'تم إلغاء اختيار ملف التحديث');}catch(e){}", null)
                return
            }
            Thread { uploadAdminUpdateFile(uri, kind) }.start()
            return
        }

        if (requestCode == CREATE_DOCUMENT_REQUEST) {
            val bytes = pendingDownloadBytes
            pendingDownloadBytes = null
            if (resultCode == RESULT_OK && data?.data != null && bytes != null) {
                Thread {
                    try {
                        val out: OutputStream? = contentResolver.openOutputStream(data.data!!)
                        out.use { stream -> stream?.write(bytes) }
                        runOnUiThread {
                            Toast.makeText(this, "تم حفظ المرفق بنجاح", Toast.LENGTH_SHORT).show()
                        }
                    } catch (_: Exception) {
                        runOnUiThread {
                            Toast.makeText(this, "تعذر حفظ المرفق", Toast.LENGTH_SHORT).show()
                        }
                    }
                }.start()
            }
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = android.app.NotificationChannel(
                MailNotificationService.CHANNEL_ID,
                "إشعارات البريد الداخلي",
                android.app.NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "تنبيه عند وصول رسالة جديدة إلى تطبيق الدليل الطبي"
                enableVibration(true)
                setSound(android.provider.Settings.System.DEFAULT_NOTIFICATION_URI, android.media.AudioAttributes.Builder()
                    .setUsage(android.media.AudioAttributes.USAGE_NOTIFICATION)
                    .build())
            }
            getSystemService(android.app.NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), NOTIFICATION_PERMISSION_REQUEST)
        }
    }

    private fun startMailNotificationService() {
        val username = getSharedPreferences(NOTIFICATION_PREFS, MODE_PRIVATE)
            .getString(NOTIFICATION_USERNAME, "")?.trim().orEmpty()
        if (username.isBlank()) return
        val intent = Intent(this, MailNotificationService::class.java)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent) else startService(intent)
        } catch (_: Exception) {}
    }

    private fun stopMailNotificationService() {
        try { stopService(Intent(this, MailNotificationService::class.java)) } catch (_: Exception) {}
    }

    override fun onPause() {
        super.onPause()
        if (!isFinishing) startMailNotificationService()
    }

    override fun onResume() {
        super.onResume()
        stopMailNotificationService()
        webView.evaluateJavascript(
            "try{if(typeof pollLanServer==='function'){pollLanServer();}}catch(e){}",
            null
        )
    }

    override fun onBackPressed() {
        if (webView.canGoBack()) webView.goBack() else super.onBackPressed()
    }


    companion object {
        private const val FILE_CHOOSER_REQUEST = 4101
        private const val CREATE_DOCUMENT_REQUEST = 4102
        private const val NOTIFICATION_PERMISSION_REQUEST = 4103
        private const val ADMIN_UPDATE_UPLOAD_REQUEST = 4104
        const val NOTIFICATION_PREFS = "medicalDirectoryNotifications"
        const val NOTIFICATION_USERNAME = "username"
    }
}
