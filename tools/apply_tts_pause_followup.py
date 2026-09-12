#!/usr/bin/env python3
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def replace_exact(path: str, old: str, new: str) -> None:
    p = ROOT / path
    text = p.read_text(encoding="utf-8")
    if new in text:
        return
    count = text.count(old)
    if count != 1:
        raise RuntimeError(f"{path}: expected one anchor for {old!r}, found {count}")
    p.write_text(text.replace(old, new, 1), encoding="utf-8")


rules = "sdk/src/main/kotlin/audio/soniqo/speech/rules/PronunciationRules.kt"
# Preserve intentional leading/trailing spaces in regex terms while retaining the
# old trim behavior for literal pronunciation entries.
replace_exact(
    rules,
    '            val term = o.optString("term", o.optString("word", "")).trim()\n',
    '            val isRegex = o.optBoolean("isRegex", false)\n'
    '            val rawTerm = o.optString("term", o.optString("word", ""))\n'
    '            val term = if (isRegex) rawTerm else rawTerm.trim()\n',
)
replace_exact(
    rules,
    '            if (term.isBlank()) continue\n',
    '            if (term.isEmpty()) continue\n',
)
replace_exact(
    rules,
    '                isRegex = o.optBoolean("isRegex", false),\n',
    '                isRegex = isRegex,\n',
)

service = "sdk/src/main/kotlin/audio/soniqo/speech/service/SpeechTextToSpeechService.kt"
# Ensure service warm-up cannot race the first real request and install an engine
# built from a different settings snapshot.
replace_exact(
    service,
    '        synchronized(synthesisLock) {\n            var terminalSignaled = false\n',
    '        synchronized(synthesisLock) {\n'
    '            val pendingWarm = warmThread\n'
    '            if (pendingWarm != null && pendingWarm !== Thread.currentThread() && pendingWarm.isAlive) {\n'
    '                val joinStartNs = SystemClock.elapsedRealtimeNanos()\n'
    '                try {\n'
    '                    pendingWarm.join()\n'
    '                } catch (_: InterruptedException) {\n'
    '                    Thread.currentThread().interrupt()\n'
    '                }\n'
    '                Log.i(TAG, "TTS_WARM_JOIN id=$requestId elapsed_ms=${String.format(Locale.US, "%.1f", (SystemClock.elapsedRealtimeNanos() - joinStartNs) / 1_000_000.0)}")\n'
    '            }\n'
    '            warmThread = null\n'
    '            var terminalSignaled = false\n',
)

print("TTS pause follow-up hardening applied successfully")
