package com.metaconnect.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.view.WindowManager
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.*
import java.util.concurrent.TimeUnit

/**
 * MetaConnect — Always-on voice assistant
 * Runs speech recognition from the Activity (reliable)
 * with screen kept on but dimmed. Foreground service keeps process alive.
 */
class MainActivity : AppCompatActivity(), TextToSpeech.OnInitListener {

    private lateinit var statusText: TextView
    private lateinit var responseText: TextView
    private lateinit var serverInput: EditText
    private lateinit var startBtn: Button
    private lateinit var stopBtn: Button
    private lateinit var testBtn: Button

    private val PERMISSIONS_REQUEST = 100
    private val PREFS_NAME = "metaconnect_prefs"
    private val KEY_SERVER_URL = "server_url"
    private val DEFAULT_URL = "https://livestock-avatar-late.ngrok-free.dev"

    private val WAKE_WORDS = listOf(
        "hey claude", "hey claud", "hey clod", "hey cloud",
        "hey clawed", "ok claude", "okay claude", "a claude"
    )

    private var speechRecognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var httpClient: OkHttpClient? = null
    private val handler = Handler(Looper.getMainLooper())

    private var alwaysOn = false
    private var awaitingCommand = false
    private var isProcessing = false
    private var serverUrl = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.statusText)
        responseText = findViewById(R.id.responseText)
        serverInput = findViewById(R.id.serverInput)
        startBtn = findViewById(R.id.startBtn)
        stopBtn = findViewById(R.id.stopBtn)
        testBtn = findViewById(R.id.testBtn)

        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        serverInput.setText(prefs.getString(KEY_SERVER_URL, DEFAULT_URL))

        startBtn.setOnClickListener { startAlwaysOn() }
        stopBtn.setOnClickListener { stopAlwaysOn() }
        testBtn.setOnClickListener { testConnection() }

        httpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()

        tts = TextToSpeech(this, this)

        requestPermissions()
    }

    override fun onDestroy() {
        stopAlwaysOn()
        tts?.shutdown()
        httpClient?.dispatcher?.cancelAll()
        super.onDestroy()
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts?.language = Locale.US
            tts?.setSpeechRate(1.1f)
        }
    }

    private fun requestPermissions() {
        val perms = mutableListOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.INTERNET
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        val needed = perms.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (needed.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, needed.toTypedArray(), PERMISSIONS_REQUEST)
        }
    }

    private fun saveServerUrl(): String {
        val url = serverInput.text.toString().trim().trimEnd('/')
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            .edit()
            .putString(KEY_SERVER_URL, url)
            .apply()
        return url
    }

    private fun log(msg: String) {
        runOnUiThread {
            val current = responseText.text.toString()
            val lines = current.split("\n").takeLast(15)
            responseText.text = (lines + msg).joinToString("\n")
        }
    }

    // =====================================================
    // ALWAYS-ON MODE
    // =====================================================
    private fun startAlwaysOn() {
        serverUrl = saveServerUrl()
        if (serverUrl.isEmpty()) {
            statusText.text = "Enter server URL first"
            return
        }

        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            log("ERROR: Speech recognition not available. Install Google app.")
            return
        }

        alwaysOn = true

        // Keep screen on but dim
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val lp = window.attributes
        lp.screenBrightness = 0.01f  // Nearly off — saves battery
        window.attributes = lp

        // Start foreground service to keep process alive
        val intent = Intent(this, VoiceService::class.java).apply {
            putExtra("server_url", serverUrl)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }

        statusText.text = "ALWAYS-ON - Say 'Hey Claude'"
        statusText.setTextColor(ContextCompat.getColor(this, android.R.color.holo_green_light))
        log("Always-on started. Listening for 'Hey Claude'...")

        startListening()
    }

    private fun stopAlwaysOn() {
        alwaysOn = false
        awaitingCommand = false
        isProcessing = false

        speechRecognizer?.destroy()
        speechRecognizer = null

        // Restore screen brightness
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val lp = window.attributes
        lp.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        window.attributes = lp

        // Stop service
        stopService(Intent(this, VoiceService::class.java))

        statusText.text = "Stopped"
        statusText.setTextColor(ContextCompat.getColor(this, android.R.color.holo_red_light))
        log("Stopped.")
    }

    // =====================================================
    // SPEECH RECOGNITION — runs in Activity (reliable)
    // =====================================================
    private fun startListening() {
        if (!alwaysOn || isProcessing) return

        speechRecognizer?.destroy()
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)
        speechRecognizer?.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                runOnUiThread {
                    log("[MIC READY] Listening now...")
                    if (awaitingCommand) {
                        statusText.text = "LISTENING for command..."
                    } else {
                        statusText.text = "Say 'Hey Claude'..."
                    }
                }
            }
            override fun onBeginningOfSpeech() {
                runOnUiThread { log("[HEARING] Speech detected...") }
            }
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {
                runOnUiThread { log("[END] Processing speech...") }
            }

            override fun onError(error: Int) {
                val msg = when (error) {
                    SpeechRecognizer.ERROR_NO_MATCH -> "no match"
                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "timeout"
                    SpeechRecognizer.ERROR_AUDIO -> "audio error"
                    SpeechRecognizer.ERROR_NETWORK -> "network error"
                    SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "network timeout"
                    SpeechRecognizer.ERROR_CLIENT -> "client error"
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "no permission"
                    SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "busy"
                    SpeechRecognizer.ERROR_SERVER -> "server error"
                    else -> "error $error"
                }
                runOnUiThread { log("[ERROR] $msg - restarting...") }
                if (alwaysOn && !isProcessing) {
                    val delay = if (error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY) 1000L else 300L
                    handler.postDelayed({ startListening() }, delay)
                }
            }

            override fun onResults(results: Bundle?) {
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION) ?: return
                runOnUiThread { log("[HEARD] ${matches.firstOrNull()}") }
                processResults(matches)
            }

            override fun onPartialResults(partial: Bundle?) {
                val matches = partial?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val text = matches?.firstOrNull() ?: return
                runOnUiThread { log("[...] $text") }
            }
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-US")
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 10000)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 3000)
        }

        try {
            speechRecognizer?.startListening(intent)
        } catch (e: Exception) {
            log("Listener error: ${e.message}")
            if (alwaysOn) handler.postDelayed({ startListening() }, 1000)
        }
    }

    private fun processResults(matches: List<String>) {
        for (match in matches) {
            val lower = match.lowercase().trim()

            // Check for wake word
            for (wake in WAKE_WORDS) {
                if (lower.contains(wake)) {
                    val command = lower.substringAfter(wake).trim()
                    if (command.length > 1) {
                        log(">> $command")
                        sendToServer(command)
                        return
                    } else {
                        awaitingCommand = true
                        log("Wake word heard! Listening for command...")
                        handler.postDelayed({ startListening() }, 200)
                        return
                    }
                }
            }

            // If awaiting command after wake word
            if (awaitingCommand && lower.length > 1) {
                awaitingCommand = false
                log(">> $lower")
                sendToServer(lower)
                return
            }
        }

        // No wake word — keep listening
        if (alwaysOn) {
            handler.postDelayed({ startListening() }, 300)
        }
    }

    // =====================================================
    // SERVER COMMUNICATION
    // =====================================================
    private fun sendToServer(command: String) {
        isProcessing = true
        runOnUiThread {
            statusText.text = "Thinking..."
            statusText.setTextColor(ContextCompat.getColor(this, android.R.color.holo_blue_light))
        }

        val json = JSONObject().apply { put("message", command) }
        val body = json.toString().toRequestBody("application/json".toMediaType())
        val request = Request.Builder()
            .url("$serverUrl/api/send")
            .addHeader("ngrok-skip-browser-warning", "true")
            .post(body)
            .build()

        httpClient?.newCall(request)?.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                isProcessing = false
                log("Server error: ${e.message?.take(40)}")
                speak("Sorry, couldn't reach the server.")
            }

            override fun onResponse(call: Call, response: Response) {
                isProcessing = false
                val body = response.body?.string() ?: "{}"
                try {
                    val data = JSONObject(body)
                    val reply = data.optString("response", "No response.")
                    log("<< $reply")
                    speak(reply)
                } catch (e: Exception) {
                    log("Parse error")
                    speak("Got a weird response.")
                }
            }
        })
    }

    private fun speak(text: String) {
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "reply")
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
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
    // TEST CONNECTION
    // =====================================================
    private fun testConnection() {
        val url = saveServerUrl()
        statusText.text = "Testing..."

        Thread {
            try {
                val conn = java.net.URL("$url/api/health").openConnection() as java.net.HttpURLConnection
                conn.requestMethod = "GET"
                conn.setRequestProperty("ngrok-skip-browser-warning", "true")
                conn.connectTimeout = 5000
                conn.readTimeout = 5000
                val resp = conn.inputStream.bufferedReader().readText()
                runOnUiThread {
                    statusText.text = "Connected!"
                    statusText.setTextColor(ContextCompat.getColor(this, android.R.color.holo_green_light))
                    log("Server: $resp")
                }
            } catch (e: Exception) {
                runOnUiThread {
                    statusText.text = "Failed: ${e.message?.take(40)}"
                    statusText.setTextColor(ContextCompat.getColor(this, android.R.color.holo_red_light))
                }
            }
        }.start()
    }
}
