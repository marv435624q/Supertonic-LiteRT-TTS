package com.supertonic.tts

import android.app.Activity
import android.os.Bundle
import android.speech.tts.TextToSpeech

class GetSampleTextActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val sample = when (intent.getStringExtra("language")?.lowercase()) {
            "kor" -> "안녕하세요. Supertonic-3 LiteRT 시스템 TTS 엔진 테스트입니다."
            "jpn" -> "こんにちは。Supertonic TTS のテストです。"
            "zho", "cmn" -> "你好，这是 Supertonic TTS 的测试。"
            else -> "Hello. This is a Supertonic TTS engine test."
        }
        setResult(RESULT_OK, intent.putExtra(TextToSpeech.Engine.EXTRA_SAMPLE_TEXT, sample))
        finish()
    }
}
