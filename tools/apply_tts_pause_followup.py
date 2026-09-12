#!/usr/bin/env python3
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def replace_once(path: str, old: str, new: str) -> None:
    p = ROOT / path
    text = p.read_text(encoding="utf-8")
    if new in text:
        return
    count = text.count(old)
    if count != 1:
        raise RuntimeError(f"{path}: expected one anchor, found {count}")
    p.write_text(text.replace(old, new, 1), encoding="utf-8")


# Preserve intentional leading/trailing spaces in regex terms. This is required
# for user-editable whitespace rules; literal (non-regex) entries keep old trim behavior.
replace_once(
    "sdk/src/main/kotlin/audio/soniqo/speech/rules/PronunciationRules.kt",
    '''            val term = o.optString("term", o.optString("word", "")).trim()
            val replacement = o.optString(
                "replacement",
                o.optString("pronunciation", o.optString("ipa", ""))
            )
            if (term.isBlank()) continue
            out += Rule(
                term = term,
                replacement = replacement,
                ignoreCase = o.optBoolean("ignoreCase", true),
                isRegex = o.optBoolean("isRegex", false),
                enabled = o.optBoolean("enabled", true)
            )
''',
    '''            val isRegex = o.optBoolean("isRegex", false)
            val rawTerm = o.optString("term", o.optString("word", ""))
            val term = if (isRegex) rawTerm else rawTerm.trim()
            val replacement = o.optString(
                "replacement",
                o.optString("pronunciation", o.optString("ipa", ""))
            )
            if (term.isEmpty()) continue
            out += Rule(
                term = term,
                replacement = replacement,
                ignoreCase = o.optBoolean("ignoreCase", true),
                isRegex = isRegex,
                enabled = o.optBoolean("enabled", true)
            )
''',
)

# A background service warm-up and the first real request must not race to install
# engines built from different setting snapshots. Join the warm-up before the request
# snapshots/validates loaded engine settings. This does not add work: a cold first
# request would have to wait for the same engine construction anyway.
replace_once(
    "sdk/src/main/kotlin/audio/soniqo/speech/service/SpeechTextToSpeechService.kt",
    '''        stopped = false
        acquireSynthesisWakeLock()
        synchronized(synthesisLock) {
            var terminalSignaled = false
''',
    '''        stopped = false
        acquireSynthesisWakeLock()
        synchronized(synthesisLock) {
            val pendingWarm = warmThread
            if (pendingWarm != null && pendingWarm !== Thread.currentThread() && pendingWarm.isAlive) {
                val joinStartNs = SystemClock.elapsedRealtimeNanos()
                try {
                    pendingWarm.join()
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                }
                Log.i(TAG, "TTS_WARM_JOIN id=$requestId elapsed_ms=${String.format(Locale.US, "%.1f", (SystemClock.elapsedRealtimeNanos() - joinStartNs) / 1_000_000.0)}")
            }
            warmThread = null
            var terminalSignaled = false
''',
)

print("TTS pause follow-up hardening applied successfully")
