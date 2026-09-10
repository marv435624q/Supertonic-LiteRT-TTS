package com.supertonic.tts

import android.app.Activity
import android.content.Intent
import android.os.Bundle

/** Android's legacy TTS data-install entry point. Our model is downloaded by the app itself. */
class InstallVoiceDataActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }
}
