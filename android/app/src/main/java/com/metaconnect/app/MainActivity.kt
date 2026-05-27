package com.metaconnect.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Base64
import android.view.WindowManager
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.*
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity(), TextToSpeech.OnInitListener {

    private lateinit var statusText: TextView
    private lateinit var responseText: TextView
    private lateinit var serverInput: EditText
    private lateinit var startBtn: Button
    private lateinit var stopBtn: Button
    private lateinit var testBtn: Button
    private lateinit var voiceSpinner: Spinner

    private val PERMISSIONS_REQUEST = 100
    private val PREFS_NAME = "metaconnect_prefs"
    private val KEY_SERVER_URL = "server_url"
    private val KEY_VOICE = "selected_voice"
    private val DEFAULT_URL = "https://livestock-avatar-late.ngrok-free.dev"

    private var speechRecognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var mediaPlayer: MediaPlayer? = null
    private var httpClient: OkHttpClient? = null
    private val handler = Handler(Looper.getMainLooper())

    private var alwaysOn = false
    private var isProcessing = false
    private var serverUrl = ""
    private var selectedVoice = "Ashley"
    private var voiceNames = mutableListOf<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.statusText)
        responseText = findViewById(R.id.responseText)
        serverInput = findViewById(R.id.serverInput)
        startBtn = findViewById(R.id.startBtn)
        stopBtn = findViewById(R.id.stopBtn)
        testBtn = findViewById(R.id.testBtn)
        voiceSpinner = findViewById(R.id.voiceSpinner)

        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        serverInput.setText(prefs.getString(KEY_SERVER_URL, DEFAULT_URL))
        selectedVoice = prefs.getString(KEY_VOICE, "Ashley") ?: "Ashley"

        httpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()

        tts = TextToSpeech(this, this)

        startBtn.setOnClickListener { startAlwaysOn() }
        stopBtn.setOnClickListener { stopAlwaysOn() }
        testBtn.setOnClickListener { testConnection() }

        voiceSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: android.view.View?, pos: Int, id: Long) {
                if (voiceNames.isNotEmpty()) {
                    selectedVoice = voiceNames[pos]
                    getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
                        .putString(KEY_VOICE, selectedVoice).apply()
                    log("[VOICE] Selected: $selectedVoice")
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        requestPermissions()
        loadVoices()
    }

    override fun onDestroy() {
        stopAlwaysOn()
        tts?.shutdown()
        mediaPlayer?.release()
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
            .edit().putString(KEY_SERVER_URL, url).apply()
        return url
    }

    private fun log(msg: String) {
        runOnUiThread {
            val current = responseText.text.toString()
            val lines = current.split("\n").takeLast(20)
            responseText.text = (lines + msg).joinToString("\n")
            // Auto-scroll
            val scrollView = responseText.parent as? ScrollView
            scrollView?.post { scrollView.fullScroll(ScrollView.FOCUS_DOWN) }
        }
    }

    // =====================================================
    // LOAD VOICES FROM SERVER
    // =====================================================
    private fun loadVoices() {
        val url = serverInput.text.toString().trim().trimEnd('/')
        if (url.isEmpty()) return

        Thread {
            try {
                val conn = java.net.URL("$url/api/voices").openConnection() as java.net.HttpURLConnection
                conn.setRequestProperty("ngrok-skip-browser-warning", "true")
                conn.connectTimeout = 5000
                conn.readTimeout = 5000

                val resp = conn.inputStream.bufferedReader().readText()
                val data = JSONObject(resp)
                val voices = data.getJSONArray("voices")
                val current = data.optString("current", "Ashley")

                voiceNames.clear()
                for (i in 0 until voices.length()) {
                    voiceNames.add(voices.getJSONObject(i).getString("name"))
                }

                runOnUiThread {
                    val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, voiceNames)
                    voiceSpinner.adapter = adapter

                    // Set selection to saved voice
                    val idx = voiceNames.indexOf(selectedVoice)
                    if (idx >= 0) voiceSpinner.setSelection(idx)

                    log("[VOICES] Loaded: ${voiceNames.joinToString(", ")}")
                }
            } catch (e: Exception) {
                runOnUiThread {
                    // Fallback voices
                    voiceNames = mutableListOf("Ashley", "Johnny Dep", "Kristine", "Linda", "MaKenzie", "Michael")
                    val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, voiceNames)
                    voiceSpinner.adapter = adapter
                    log("[VOICES] Using defaults (server offline)")
                }
            }
        }.start()
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
            log("[ERROR] Speech recognition not available")
            return
        }

        alwaysOn = true

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val lp = window.attributes
        lp.screenBrightness = 0.01f
        window.attributes = lp

        val intent = Intent(this, VoiceService::class.java).apply {
            putExtra("server_url", serverUrl)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }

        statusText.text = "ALWAYS-ON - Talk to Claude"
        statusText.setTextColor(ContextCompat.getColor(this, android.R.color.holo_green_light))
        log("[START] Voice: $selectedVoice")
        log("[START] Server: $serverUrl")
        log("[START] Listening...")

        startListening()
    }

    private fun stopAlwaysOn() {
        alwaysOn = false
        isProcessing = false
        speechRecognizer?.destroy()
        speechRecognizer = null

        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val lp = window.attributes
        lp.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        window.attributes = lp

        stopService(Intent(this, VoiceService::class.java))
        statusText.text = "Stopped"
        statusText.setTextColor(ContextCompat.getColor(this, android.R.color.holo_red_light))
        log("[STOP]")
    }

    // =====================================================
    // SPEECH RECOGNITION
    // =====================================================
    private fun startListening() {
        if (!alwaysOn || isProcessing) return

        speechRecognizer?.destroy()
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)
        speechRecognizer?.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                runOnUiThread {
                    statusText.text = "Listening..."
                    statusText.setTextColor(ContextCompat.getColor(this@MainActivity, android.R.color.holo_green_light))
                }
            }
            override fun onBeginningOfSpeech() {
                runOnUiThread { log("[HEARING]...") }
            }
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}

            override fun onError(error: Int) {
                val msg = when (error) {
                    SpeechRecognizer.ERROR_NO_MATCH -> "no match"
                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "timeout"
                    SpeechRecognizer.ERROR_AUDIO -> "audio error"
                    SpeechRecognizer.ERROR_NETWORK -> "network"
                    SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "net timeout"
                    SpeechRecognizer.ERROR_CLIENT -> "client"
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "no permission!"
                    SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "busy"
                    SpeechRecognizer.ERROR_SERVER -> "server"
                    else -> "err $error"
                }
                runOnUiThread { log("[ERR] $msg") }
                if (alwaysOn && !isProcessing) {
                    val delay = if (error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY) 1000L else 500L
                    handler.postDelayed({ startListening() }, delay)
                }
            }

            override fun onResults(results: Bundle?) {
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION) ?: return
                val heard = matches.firstOrNull()?.trim() ?: ""
                runOnUiThread { log("[HEARD] $heard") }

                if (heard.length >= 2) {
                    log(">> $heard")
                    sendToServer(heard)
                } else {
                    if (alwaysOn) handler.postDelayed({ startListening() }, 300)
                }
            }

            override fun onPartialResults(partial: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-US")
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 10000)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 3000)
        }

        try {
            speechRecognizer?.startListening(intent)
        } catch (e: Exception) {
            log("[ERR] startListening: ${e.message}")
            if (alwaysOn) handler.postDelayed({ startListening() }, 1000)
        }
    }

    // =====================================================
    // SERVER COMMUNICATION + AUDIO PLAYBACK
    // =====================================================
    private fun sendToServer(command: String) {
        isProcessing = true
        runOnUiThread {
            statusText.text = "Thinking..."
            statusText.setTextColor(ContextCompat.getColor(this, android.R.color.holo_blue_light))
        }

        val json = JSONObject().apply {
            put("message", command)
            put("voice", selectedVoice)
        }
        val body = json.toString().toRequestBody("application/json".toMediaType())
        val request = Request.Builder()
            .url("$serverUrl/api/send")
            .addHeader("ngrok-skip-browser-warning", "true")
            .post(body)
            .build()

        try {
            httpClient?.newCall(request)?.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    isProcessing = false
                    log("[FAIL] ${e.message}")
                    speak("Couldn't reach the server.")
                }

                override fun onResponse(call: Call, response: Response) {
                    isProcessing = false
                    val responseBody = response.body?.string() ?: "{}"
                    try {
                        val data = JSONObject(responseBody)
                        val reply = data.optString("response", "No response.")
                        val audio = data.optString("audio", "")

                        log("<< $reply")

                        if (audio.isNotEmpty()) {
                            // Play custom voice audio from server
                            playAudio(audio)
                        } else {
                            // Fallback to Android TTS
                            speak(reply)
                        }
                    } catch (e: Exception) {
                        log("[ERR] Parse: ${e.message}")
                        log("[RAW] ${responseBody.take(100)}")
                        speak("Got a weird response.")
                    }
                }
            })
        } catch (e: Exception) {
            isProcessing = false
            log("[ERR] send: ${e.message}")
        }
    }

    private fun playAudio(base64Audio: String) {
        try {
            val audioBytes = Base64.decode(base64Audio, Base64.DEFAULT)
            val tmpFile = File(cacheDir, "response.wav")
            FileOutputStream(tmpFile).use { it.write(audioBytes) }

            mediaPlayer?.release()
            mediaPlayer = MediaPlayer().apply {
                setDataSource(tmpFile.absolutePath)
                prepare()
                setOnCompletionListener {
                    log("[AUDIO] Done playing")
                    handler.postDelayed({ startListening() }, 500)
                }
                start()
            }
            log("[AUDIO] Playing ${selectedVoice}'s voice...")
        } catch (e: Exception) {
            log("[ERR] Audio: ${e.message}")
            handler.postDelayed({ startListening() }, 500)
        }
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
                conn.setRequestProperty("ngrok-skip-browser-warning", "true")
                conn.connectTimeout = 5000
                conn.readTimeout = 5000
                val resp = conn.inputStream.bufferedReader().readText()
                runOnUiThread {
                    statusText.text = "Connected!"
                    statusText.setTextColor(ContextCompat.getColor(this, android.R.color.holo_green_light))
                    log("[OK] $resp")
                }
                // Reload voices on successful connection
                loadVoices()
            } catch (e: Exception) {
                runOnUiThread {
                    statusText.text = "Failed: ${e.message?.take(40)}"
                    statusText.setTextColor(ContextCompat.getColor(this, android.R.color.holo_red_light))
                    log("[FAIL] ${e.message}")
                }
            }
        }.start()
    }
}
