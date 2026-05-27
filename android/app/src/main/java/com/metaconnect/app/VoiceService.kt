package com.metaconnect.app

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.*
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.util.Log
import androidx.core.app.NotificationCompat
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.*
import java.util.concurrent.TimeUnit

/**
 * MetaConnect Always-On Voice Service
 *
 * Runs as a foreground service so Android won't kill it.
 * Continuously listens for "Hey Claude" wake word.
 * When detected, captures the command and sends to server.
 * Speaks the response back via TTS.
 * Works with screen locked.
 */
class VoiceService : Service(), TextToSpeech.OnInitListener {

    companion object {
        const val TAG = "MetaConnect"
        const val CHANNEL_ID = "metaconnect_voice"
        const val NOTIFICATION_ID = 1
        var isRunning = false
    }

    // Wake words
    private val WAKE_WORDS = listOf(
        "hey claude", "hey claud", "hey clod", "hey cloud",
        "hey clawed", "ok claude", "okay claude", "a claude"
    )

    private var serverUrl = ""
    private var speechRecognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var httpClient: OkHttpClient? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var isListening = false
    private var awaitingCommand = false
    private val handler = Handler(Looper.getMainLooper())

    // =====================================================
    // SERVICE LIFECYCLE
    // =====================================================

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "VoiceService created")

        httpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()

        tts = TextToSpeech(this, this)

        // Acquire wake lock to keep CPU running
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "MetaConnect::VoiceWakeLock"
        )
        wakeLock?.acquire()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        serverUrl = intent?.getStringExtra("server_url") ?: ""

        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification("Listening for 'Hey Claude'..."),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)

        isRunning = true
        startListening()

        return START_STICKY // Restart if killed
    }

    override fun onDestroy() {
        isRunning = false
        isListening = false

        speechRecognizer?.destroy()
        tts?.shutdown()
        wakeLock?.release()
        httpClient?.dispatcher?.cancelAll()

        Log.d(TAG, "VoiceService destroyed")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // =====================================================
    // TEXT-TO-SPEECH
    // =====================================================

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts?.language = Locale.US
            tts?.setSpeechRate(1.1f) // Slightly faster for natural feel
            Log.d(TAG, "TTS initialized")
        }
    }

    private fun speak(text: String) {
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "response")

        // Resume listening after speech finishes
        tts?.setOnUtteranceProgressListener(object : android.speech.tts.UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onError(utteranceId: String?) {
                handler.postDelayed({ startListening() }, 500)
            }
            override fun onDone(utteranceId: String?) {
                handler.postDelayed({ startListening() }, 500)
            }
        })
    }

    // =====================================================
    // SPEECH RECOGNITION — Always-on with wake word
    // =====================================================

    private fun startListening() {
        if (!isRunning) return

        handler.post {
            try {
                speechRecognizer?.destroy()
                speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)
                speechRecognizer?.setRecognitionListener(VoiceListener())

                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-US")
                    putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
                    putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                    // Keep listening longer
                    putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 10000)
                    putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 3000)
                    putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 3000)
                }

                speechRecognizer?.startListening(intent)
                isListening = true
                Log.d(TAG, "Listening started")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start listening: ${e.message}")
                // Retry after delay
                handler.postDelayed({ startListening() }, 2000)
            }
        }
    }

    inner class VoiceListener : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            Log.d(TAG, "Ready for speech")
        }

        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}

        override fun onEndOfSpeech() {
            Log.d(TAG, "End of speech")
        }

        override fun onError(error: Int) {
            val errorMsg = when (error) {
                SpeechRecognizer.ERROR_NO_MATCH -> "no match"
                SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "timeout"
                SpeechRecognizer.ERROR_AUDIO -> "audio error"
                SpeechRecognizer.ERROR_NETWORK -> "network error"
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "busy"
                else -> "error $error"
            }
            Log.d(TAG, "Speech error: $errorMsg")

            // Auto-restart listening (the key to always-on)
            if (isRunning) {
                val delay = if (error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY) 1000L else 300L
                handler.postDelayed({ startListening() }, delay)
            }
        }

        override fun onResults(results: Bundle?) {
            val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION) ?: return
            processResults(matches, isFinal = true)
        }

        override fun onPartialResults(partialResults: Bundle?) {
            val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION) ?: return
            processResults(matches, isFinal = false)
        }

        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    private fun processResults(matches: List<String>, isFinal: Boolean) {
        for (match in matches) {
            val lower = match.lowercase().trim()
            Log.d(TAG, "Heard: '$lower' (final=$isFinal)")

            // Check for wake word
            var wakeWordFound = false
            var command = ""

            for (wake in WAKE_WORDS) {
                if (lower.contains(wake)) {
                    wakeWordFound = true
                    val idx = lower.indexOf(wake)
                    command = lower.substring(idx + wake.length).trim()
                    break
                }
            }

            if (wakeWordFound && isFinal) {
                if (command.length > 1) {
                    // Got wake word + command: "Hey Claude what time is it"
                    Log.d(TAG, "Wake word + command: '$command'")
                    updateNotification("Processing: $command")
                    sendToServer(command)
                    return
                } else {
                    // Just wake word — wait for next utterance
                    awaitingCommand = true
                    updateNotification("Listening for command...")
                    // Restart to capture the command
                    handler.postDelayed({ startListening() }, 200)
                    return
                }
            }

            if (awaitingCommand && isFinal && lower.length > 1) {
                // This is the command after the wake word
                awaitingCommand = false
                Log.d(TAG, "Command after wake: '$lower'")
                updateNotification("Processing: $lower")
                sendToServer(lower)
                return
            }
        }

        // No wake word — restart listening
        if (isFinal && isRunning) {
            handler.postDelayed({ startListening() }, 300)
        }
    }

    // =====================================================
    // SERVER COMMUNICATION
    // =====================================================

    private fun sendToServer(command: String) {
        isListening = false

        val json = JSONObject().apply {
            put("message", command)
        }

        val body = json.toString().toRequestBody("application/json".toMediaType())
        val request = Request.Builder()
            .url("$serverUrl/api/send")
            .addHeader("ngrok-skip-browser-warning", "true")
            .post(body)
            .build()

        httpClient?.newCall(request)?.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.e(TAG, "Server error: ${e.message}")
                handler.post {
                    speak("Sorry, I couldn't reach the server.")
                    updateNotification("Listening for 'Hey Claude'...")
                }
            }

            override fun onResponse(call: Call, response: Response) {
                val responseBody = response.body?.string() ?: "{}"
                try {
                    val data = JSONObject(responseBody)
                    val reply = data.optString("response", "I didn't get a response.")

                    Log.d(TAG, "Server reply: $reply")
                    handler.post {
                        speak(reply)
                        updateNotification("Listening for 'Hey Claude'...")
                    }
                } catch (e: Exception) {
                    handler.post {
                        speak("Got a weird response, try again.")
                        updateNotification("Listening for 'Hey Claude'...")
                    }
                }
            }
        })
    }

    // =====================================================
    // NOTIFICATION (required for foreground service)
    // =====================================================

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "MetaConnect Voice",
                NotificationManager.IMPORTANCE_LOW // Low = no sound, just persistent icon
            ).apply {
                description = "Always-on voice assistant"
                setShowBadge(false)
            }

            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(text: String): Notification {
        val openIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("MetaConnect")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    private fun updateNotification(text: String) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIFICATION_ID, buildNotification(text))
    }
}
