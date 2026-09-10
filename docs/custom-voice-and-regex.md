# Custom Voice + Pronunciation/Regex

## Custom Voice JSON

Use a Supertonic-3-compatible voice-style JSON containing both `style_ttl` and `style_dp`.
Voice Builder exports and compatible voice-style JSONs are accepted. The app copies imported files into its Supertonic model `voice_styles` directory and exposes them as `Custom · <name>` in the app. The Android system TTS service also publishes them as `supertonic-custom-<name>` voices.

A custom voice can be created with projects such as `saurabhv749/supertonic3-voice-clone`. The generated JSON is intended for Supertonic-3 style inference.

## Pronunciation / Regex JSON

The importer follows the JSON format used by DevGitPit/supertonic-android:

```json
[
  {
    "term": "LLMs",
    "replacement": "L L Ems",
    "ignoreCase": true,
    "isRegex": false
  },
  {
    "word": "RTX\\s*(\\d+)",
    "pronunciation": "알티엑스 $1",
    "ignoreCase": true,
    "isRegex": true
  }
]
```

`term`/`word` is required. `replacement`/`pronunciation`/`ipa` is accepted. `ignoreCase` defaults to true. `isRegex` defaults to false. Regex replacements are applied before Supertonic synthesis, both in the standalone app and the Android system TTS service.

See `examples/pronunciation_rules_example.json` for a ready-to-edit example.

## Long text / speed behavior

The model always synthesizes at 1.0x. User-selected speech rate is applied afterward with Android's AOSP Sonic time-scale processor. This avoids shortening the Supertonic duration prediction at high speed, which could clip words.

Long-text chunking checks the *post-NFKD* token length before emitting a chunk. This is required for Korean because Hangul syllables decompose into multiple Unicode codepoints during Supertonic preprocessing. Chunks are trimmed and crossfaded before the merged PCM is returned to non-streaming TTS calls.
