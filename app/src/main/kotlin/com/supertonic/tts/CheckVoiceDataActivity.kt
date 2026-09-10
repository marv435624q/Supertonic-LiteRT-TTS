package com.supertonic.tts

import android.app.Activity
import android.os.Bundle
import android.speech.tts.TextToSpeech
import audio.soniqo.speech.ModelManager
import audio.soniqo.speech.TtsSettings

class CheckVoiceDataActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val selectedModel = TtsSettings.ttsModel(applicationContext)
        val ready = ModelManager.areTtsModelsReady(applicationContext, selectedModel)
        val result = intent
            .putStringArrayListExtra(
                TextToSpeech.Engine.EXTRA_AVAILABLE_VOICES,
                ArrayList(if (ready) AVAILABLE_VOICES else emptyList()),
            )
            .putStringArrayListExtra(
                TextToSpeech.Engine.EXTRA_UNAVAILABLE_VOICES,
                ArrayList(if (ready) emptyList() else AVAILABLE_VOICES),
            )
        setResult(
            if (ready) TextToSpeech.Engine.CHECK_VOICE_DATA_PASS
            else TextToSpeech.Engine.CHECK_VOICE_DATA_FAIL,
            result,
        )
        finish()
    }

    companion object {
        private val AVAILABLE_VOICES = listOf(
            "eng", "kor", "jpn", "zho", "ara", "bul", "ces", "dan", "deu", "ell",
            "spa", "est", "fin", "fra", "hin", "hrv", "hun", "ind", "ita", "lit",
            "lav", "nld", "pol", "por", "ron", "rus", "slk", "slv", "swe", "tur",
            "ukr", "vie"
        )
    }
}
