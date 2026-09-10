SD690 TEST LOGGER

Recommended for performance comparison:
  CAPTURE_SD690_RTF_LOG.bat

This records:
- full Android logcat for the test interval
- SYNTH_APPLIED / SYNTH_TTFA / SYNTH_PROFILE summary
- app backend/model settings before and after the test
- device/SoC/build identity
- CPU frequency policy and thermal snapshots before and after
- app process/memory/power/thermal-service snapshots
- Deep Profiler files automatically when Deep Profiler was enabled
- a ZIP of the completed capture

RTF mode deliberately does NOT poll CPU/thermal data during synthesis, so the
logger itself has minimal effect on the RTF measurement.

For thermal/throttling diagnosis:
  CAPTURE_SD690_FULL_DIAG.bat

FULL mode additionally polls CPU frequencies, relevant thermal zones, and the
app process every 2 seconds. Because that means repeated ADB traffic, use FULL
mode for diagnosis rather than final authoritative RTF numbers.

Workflow:
1. Connect exactly the SD690 device by ADB.
2. Run the desired BAT.
3. Perform the TTS tests while the recorder window remains open.
4. Return to the recorder and press ENTER.
5. The recorder STOPS logcat first, then writes all snapshots/profiles, creates
   SD690-TEST-LOGS\SD690-<MODE>-<timestamp>\ and a matching ZIP.
6. LAST_SD690_LOG.txt always points to the newest capture directory.

The logs are written BEFORE the final close prompt. Closing the window after
the "LOGS SAVED" banner therefore does not discard the capture.

Authoritative standalone-app timing marker:
- MainActivity now writes [UI-SYNTH-PROFILE] for every successful Generate action.
- UI_SYNTH_PROFILES.txt contains only those lines, including model/backend/steps/threads,
  effective chunk value, native total time, native audio seconds, native RTF, and full profile.
- SYNTH_PROFILES.txt also retains service SYNTH_PROFILE, ONNX [SYNTH-END], and native
  LiteRT [LITERT-SYNTH-END] markers.
- The raw settings XML can contain the retired legacy chunk_cap value (for example 64).
  Do not treat that raw key as the effective LiteRT cap: currentChunkCap/TtsSettings.chunkCap
  are Auto (0). The [UI-SYNTH-PROFILE] chunk= field is the authoritative applied value.
