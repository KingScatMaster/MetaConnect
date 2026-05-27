package com.metaconnect.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private lateinit var statusText: TextView
    private lateinit var responseText: TextView
    private lateinit var serverInput: EditText
    private lateinit var startBtn: Button
    private lateinit var stopBtn: Button
    private lateinit var testBtn: Button

    private val PERMISSIONS_REQUEST = 100
    private val PREFS_NAME = "metaconnect_prefs"
    private val KEY_SERVER_URL = "server_url"

    // Default to your ngrok URL
    private val DEFAULT_URL = "https://livestock-avatar-late.ngrok-free.dev"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.statusText)
        responseText = findViewById(R.id.responseText)
        serverInput = findViewById(R.id.serverInput)
        startBtn = findViewById(R.id.startBtn)
        stopBtn = findViewById(R.id.stopBtn)
        testBtn = findViewById(R.id.testBtn)

        // Load saved URL
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        serverInput.setText(prefs.getString(KEY_SERVER_URL, DEFAULT_URL))

        startBtn.setOnClickListener { startVoiceService() }
        stopBtn.setOnClickListener { stopVoiceService() }
        testBtn.setOnClickListener { testConnection() }

        requestPermissions()

        // Update UI based on service state
        updateServiceStatus()
    }

    override fun onResume() {
        super.onResume()
        updateServiceStatus()
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

    private fun startVoiceService() {
        val url = saveServerUrl()
        if (url.isEmpty()) {
            statusText.text = "Enter your server URL first"
            return
        }

        val intent = Intent(this, VoiceService::class.java).apply {
            putExtra("server_url", url)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }

        statusText.text = "Voice service starting..."
        statusText.setTextColor(ContextCompat.getColor(this, android.R.color.holo_green_light))
    }

    private fun stopVoiceService() {
        stopService(Intent(this, VoiceService::class.java))
        statusText.text = "Service stopped"
        statusText.setTextColor(ContextCompat.getColor(this, android.R.color.holo_red_light))
    }

    private fun testConnection() {
        val url = saveServerUrl()
        statusText.text = "Testing connection..."

        Thread {
            try {
                val connection = java.net.URL("$url/api/health").openConnection() as java.net.HttpURLConnection
                connection.requestMethod = "GET"
                connection.setRequestProperty("ngrok-skip-browser-warning", "true")
                connection.connectTimeout = 5000
                connection.readTimeout = 5000

                val response = connection.inputStream.bufferedReader().readText()
                runOnUiThread {
                    statusText.text = "Connected!"
                    statusText.setTextColor(ContextCompat.getColor(this, android.R.color.holo_green_light))
                    responseText.text = response
                }
            } catch (e: Exception) {
                runOnUiThread {
                    statusText.text = "Connection failed: ${e.message?.take(50)}"
                    statusText.setTextColor(ContextCompat.getColor(this, android.R.color.holo_red_light))
                }
            }
        }.start()
    }

    private fun updateServiceStatus() {
        if (VoiceService.isRunning) {
            statusText.text = "Voice service running - Say 'Hey Claude'"
            statusText.setTextColor(ContextCompat.getColor(this, android.R.color.holo_green_light))
        } else {
            statusText.text = "Service not running"
            statusText.setTextColor(ContextCompat.getColor(this, android.R.color.holo_red_light))
        }
    }
}
