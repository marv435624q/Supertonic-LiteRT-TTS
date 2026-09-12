package com.supertonic.tts

import android.app.AlertDialog
import android.content.Intent
import android.content.IntentFilter
import android.media.MediaPlayer
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.net.Uri
import android.os.Bundle
import android.os.Build
import android.os.BatteryManager
import android.os.PowerManager
import android.graphics.Color
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.TouchDelegate
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import android.util.Log
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import audio.soniqo.speech.ModelManager
import audio.soniqo.speech.InferenceBackend
import audio.soniqo.speech.SpeechSynthesizer
import audio.soniqo.speech.SpeechSynthesizerConfig
import audio.soniqo.speech.TtsSettings
import audio.soniqo.speech.TtsModel
import audio.soniqo.speech.rules.PronunciationRules
import audio.soniqo.speech.audio.AudioSpeedProcessor
import audio.soniqo.speech.audio.InternalSilenceCompressor
import audio.soniqo.speech.service.SpeechTextToSpeechService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import kotlin.math.roundToInt
import kotlin.time.TimeSource

class MainActivity : AppCompatActivity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var modelJob: Job? = null
    private var synthJob: Job? = null
    private var qnnCacheJob: Job? = null
    private var cpuDiagnosticsJob: Job? = null
    private var modelPreloadJob: Job? = null
    private var modelPreloadGeneration = 0L
    private var modelPreloadKey: String? = null
    private val modelPreloadMutex = Mutex()
    private val synthesizerLock = Any()
    private var player: MediaPlayer? = null
    @Volatile private var previewAudioTrack: AudioTrack? = null
    private var synthesizer: SpeechSynthesizer? = null
    @Volatile private var qnnCacheSynthesizer: SpeechSynthesizer? = null
    @Volatile private var cpuDiagnosticsSynthesizer: SpeechSynthesizer? = null
    private var synthesizerStale = false
    private var suppressPregenSpinnerCallback = false
    private var engineInitSeconds = 0.0
    private var advancedDialog: AlertDialog? = null

    private fun failureMessage(failure: Throwable): String {
        val messages = ArrayList<String>()
        val seen = HashSet<Throwable>()
        var current: Throwable? = failure
        while (current != null && seen.add(current)) {
            val message = current.message?.trim()?.takeIf { it.isNotEmpty() }
                ?: current.javaClass.simpleName
            if (messages.lastOrNull() != message) messages += message
            current = current.cause
        }
        return messages.joinToString(" ← ").replace('\n', ' ').take(700)
    }

    private lateinit var status: TextView
    private lateinit var speedLabel: TextView
    private lateinit var speedBar: SeekBar
    private lateinit var voiceSpinner: Spinner
    private lateinit var stepsSpinner: Spinner
    private lateinit var threadsSpinner: Spinner
    private lateinit var backendSpinner: Spinner
    private lateinit var modelSpinner: Spinner
    private lateinit var originalShapeSection: LinearLayout
    private lateinit var originalShapeSpinner: Spinner
    private lateinit var originalShapeCustomRow: LinearLayout
    private lateinit var originalFixedTInput: EditText
    private lateinit var originalFixedLInput: EditText
    private lateinit var chunkSizeSection: LinearLayout
    private lateinit var chunkSpinner: Spinner
    private lateinit var chunkManualInput: EditText
    private lateinit var preGenerationSpinner: Spinner
    private lateinit var chunkGapMinInput: EditText
    private lateinit var chunkGapMaxInput: EditText
    private lateinit var trailingTrimInput: EditText
    private lateinit var internalSilenceSpinner: Spinner
    private lateinit var internalSilenceMaxInput: EditText
    private lateinit var languageSpinner: Spinner
    private lateinit var allowNaCheck: android.widget.CheckBox
    private lateinit var textInput: EditText
    private lateinit var rtfView: TextView
    private lateinit var ruleStatus: TextView
    private lateinit var startButton: Button
    private lateinit var playButton: Button

    private val builtinVoices = listOf("F1", "F2", "F3", "F4", "F5", "M1", "M2", "M3", "M4", "M5")
    // Expose the full practical range instead of a few presets.
    private val steps = (1..64).toList()
    private val threadCounts = (1..8).toList()
    private val sm6350Device: Boolean by lazy { detectSm6350Hta() }
    private val qualcommNpuAvailable: Boolean by lazy {
        detectQualcommNpu() && !sm6350Device
    }
    private val liteRtBackends: List<Pair<String, InferenceBackend>> by lazy {
        // REV26: LiteRT is CPU/XNNPACK-only. The GPU and NNAPI experiments were
        // removed from both UI and runtime after repeated numerical/driver
        // failures. Qualcomm acceleration is now ONNX-only.
        listOf("CPU" to InferenceBackend.CPU_XNNPACK)
    }
    private val onnxBackends: List<Pair<String, InferenceBackend>> by lazy {
        buildList {
            add("CPU (ORT)" to InferenceBackend.CPU_ORT)
            if (BuildConfig.ORT_XNNPACK_AVAILABLE) add("CPU XNN" to InferenceBackend.ONNX_XNNPACK)
            if (qualcommNpuAvailable) add("NPU" to InferenceBackend.QUALCOMM_NPU)
        }
    }
    private var activeBackends: List<Pair<String, InferenceBackend>> = emptyList()
    private val modelChoices: List<Pair<String, TtsModel>> by lazy {
        listOf(
            "ONNX FP32" to TtsModel.SUPERTONIC_ORIGINAL_ONNX,
            "ONNX W8A16" to TtsModel.SUPERTONIC_ONNX_W8A16_QDQ,
            "LiteRT FP32" to TtsModel.SUPERTONIC,
            "LiteRT W8-AFP32" to TtsModel.SUPERTONIC_LITERT_WI8_AFP32,
            "LiteRT Multi-P" to TtsModel.SUPERTONIC_LITERT_STATIC_MULTIPRESET_GELU,
            "LiteRT Multi-P W8-AFP32" to TtsModel.SUPERTONIC_LITERT_STATIC_MULTIPRESET_GELU_WI8_AFP32,
        )
    }
    private val originalShapePresets = listOf(
        "Soniqo Compatible · T=128 / L=64" to TtsSettings.ORIGINAL_SHAPE_SONIQO,
        "Fast · T=160 / L=128" to TtsSettings.ORIGINAL_SHAPE_FAST,
        "Balanced · T=160 / L=160" to TtsSettings.ORIGINAL_SHAPE_BALANCED,
        "Long · T=160 / L=192" to TtsSettings.ORIGINAL_SHAPE_LONG,
        "Extra Long · T=192 / L=192" to TtsSettings.ORIGINAL_SHAPE_EXTRA_LONG,
        "Custom T / L" to TtsSettings.ORIGINAL_SHAPE_CUSTOM,
    )

    // Legacy widget backing only; the user-facing chunk-size control is retired.
    private val chunkModes = listOf(
        "Auto · T/L buckets" to TtsSettings.CHUNK_BALANCED,
    )
    private val languages = listOf(
        "Auto (na)" to "na",
        "Korean (ko)" to "ko", "English (en)" to "en", "Japanese (ja)" to "ja", "Chinese (zh)" to "zh",
        "German (de)" to "de", "French (fr)" to "fr", "Spanish (es)" to "es", "Italian (it)" to "it",
        "Portuguese (pt)" to "pt", "Russian (ru)" to "ru"
    )

    private var voiceIds: MutableList<String> = builtinVoices.toMutableList()
    private var voiceLabels: MutableList<String> = builtinVoices.toMutableList()

    private val voiceImport = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) importCustomVoice(uri)
    }

    private val ruleImport = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) importRules(uri)
    }
    private val ruleExport = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) exportRules(uri)
    }

    private val saveWavLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("audio/wav")) { uri ->
        if (uri != null) {
            val wav = File(filesDir, "generated.wav")
            if (wav.exists()) contentResolver.openOutputStream(uri)?.use { out -> wav.inputStream().use { it.copyTo(out) } }
        }
    }

    private fun detectQualcommNpu(): Boolean {
        val socVendor = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Build.SOC_MANUFACTURER else ""
        val haystack = listOf(
            socVendor, Build.HARDWARE, Build.BOARD, Build.DEVICE, Build.PRODUCT, Build.MANUFACTURER,
        ).joinToString(" ").lowercase(Locale.US)
        return haystack.contains("qualcomm") ||
            haystack.contains("qcom") ||
            Regex("\\b(msm|sdm|sm)[0-9a-z_-]+\\b").containsMatchIn(haystack)
    }

    private fun detectSm6350Hta(): Boolean {
        val soc = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Build.SOC_MODEL else ""
        val haystack = listOf(soc, Build.BOARD, Build.HARDWARE, Build.DEVICE, Build.PRODUCT)
            .joinToString(" ").lowercase(Locale.US)
        return haystack.contains("sm6350") || Regex("\\blito\\b").containsMatchIn(haystack)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.i(
            "MainActivity",
            "[ORT-RUNTIME-VARIANT] variant=${BuildConfig.ORT_RUNTIME_VARIANT} xnnpack_available=${if (BuildConfig.ORT_XNNPACK_AVAILABLE) 1 else 0}",
        )
        ModelManager.migrateAndSyncCustomVoices(applicationContext)
        ModelManager.cleanupRetiredModels(applicationContext)
        buildUi()
        restoreSettings()
        bindSettingPersistence()
        refreshRuleStatus()
        ensureModel()
    }

    private fun buildUi() {
        val purple = Color.rgb(103, 58, 183)
        val purpleDark = Color.rgb(75, 35, 145)
        val purpleSoft = Color.rgb(246, 241, 255)
        val page = Color.rgb(250, 248, 253)
        val surface = Color.WHITE
        val border = Color.rgb(224, 217, 232)
        val textPrimary = Color.rgb(35, 31, 40)
        val textSecondary = Color.rgb(104, 96, 112)
        val danger = Color.rgb(220, 50, 47)

        window.statusBarColor = surface
        window.navigationBarColor = surface
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR

        fun dp(v: Int): Int = (v * resources.displayMetrics.density).roundToInt()
        fun rounded(fill: Int, radius: Float = 14f, strokeColor: Int? = border, strokeWidth: Int = 1) =
            GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(fill)
                cornerRadius = dp(radius.toInt()).toFloat()
                if (strokeColor != null && strokeWidth > 0) setStroke(dp(strokeWidth), strokeColor)
            }
        fun label(text: String) = TextView(this).apply {
            this.text = text
            textSize = 12f
            setTextColor(textSecondary)
            setTypeface(typeface, Typeface.BOLD)
            setPadding(dp(2), 0, dp(2), dp(1))
        }
        fun sectionCard(): LinearLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(6), dp(2), dp(6), dp(2))
            background = rounded(surface)
        }
        fun addCard(parent: LinearLayout, view: View, top: Int = 5) {
            parent.addView(view, LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, dp(top.coerceAtMost(3)), 0, 0) })
        }
        fun weightedRow(vararg views: View): LinearLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            views.forEachIndexed { index, view ->
                addView(view, LinearLayout.LayoutParams(0, -2, 1f).apply {
                    if (index > 0) setMargins(dp(3), 0, 0, 0)
                })
            }
        }
        fun weightedRowWithWeights(vararg views: Pair<View, Float>): LinearLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            views.forEachIndexed { index, (view, weight) ->
                addView(view, LinearLayout.LayoutParams(0, -2, weight).apply {
                    if (index > 0) setMargins(dp(3), 0, 0, 0)
                })
            }
        }
        fun smallButton(text: String, dangerStyle: Boolean = false, action: () -> Unit) = Button(this).apply {
            this.text = text
            textSize = 12f
            isAllCaps = false
            setTextColor(if (dangerStyle) danger else purpleDark)
            background = rounded(if (dangerStyle) Color.rgb(255, 248, 248) else surface, 11f, if (dangerStyle) Color.rgb(245, 190, 190) else border)
            setPadding(dp(4), 0, dp(4), 0)
            minWidth = 0
            minimumWidth = 0
            minHeight = dp(32)
            minimumHeight = dp(32)
            setOnClickListener { action() }
        }
        fun spinner(items: List<String>) = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_item, items).also {
                it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            }
            minimumHeight = dp(32)
            setPadding(0, 0, dp(18), 0)
        }
        fun compactField(title: String, control: View): LinearLayout = sectionCard().apply {
            addView(label(title))
            addView(control, LinearLayout.LayoutParams(-1, dp(32)))
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(7), dp(2), dp(7), dp(8))
            setBackgroundColor(page)
        }

        // App header from the approved mockup.
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(2), 0, 0, dp(2))
        }
        header.addView(TextView(this).apply {
            text = "Supertonic LiteRT"
            textSize = 22f
            setTextColor(purpleDark)
            setTypeface(typeface, Typeface.BOLD)
        }, LinearLayout.LayoutParams(0, dp(38), 1f).apply { gravity = Gravity.CENTER_VERTICAL })
        val menuButton = TextView(this).apply {
            text = "⋮"
            textSize = 25f
            gravity = Gravity.CENTER
            setTextColor(purpleDark)
            background = null
            setPadding(dp(4), 0, dp(1), 0)
            isClickable = true
            isFocusable = true
        }
        header.addView(menuButton, LinearLayout.LayoutParams(dp(30), dp(34)).apply {
            // Keep the visual size unchanged, but move the glyph a few dp left from the
            // extreme screen edge.  This is easier to hit on e-ink touch panels.
            setMargins(dp(2), 0, dp(4), 0)
        })
        root.addView(header)
        root.post {
            // Expand only the hit target; do not make the visible overflow button bulky.
            // Use the root as delegate parent so the target can extend vertically beyond
            // the compact header itself; effective target is about 50x50 dp.
            val hit = Rect()
            menuButton.getHitRect(hit) // coordinates in header
            hit.offset(header.left, header.top)
            hit.left = (hit.left - dp(12)).coerceAtLeast(0)
            hit.top = (hit.top - dp(8)).coerceAtLeast(0)
            hit.right = (hit.right + dp(8)).coerceAtMost(root.width)
            hit.bottom = (hit.bottom + dp(8)).coerceAtMost(root.height)
            root.touchDelegate = TouchDelegate(hit, menuButton)
        }

        modelSpinner = spinner(modelChoices.map { it.first })
        backendSpinner = spinner(liteRtBackends.map { it.first })
        addCard(root, weightedRowWithWeights(
            compactField("Model", modelSpinner) to 1.65f,
            compactField("Backend", backendSpinner) to 0.75f,
        ), 2)

        voiceSpinner = spinner(voiceLabels)
        val voiceCard = sectionCard().apply {
            addView(label("Voice"))
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(voiceSpinner, LinearLayout.LayoutParams(0, dp(32), 1f))
                addView(
                    smallButton("Import") { ensureModelAnd { voiceImport.launch(arrayOf("application/json", "text/plain", "*/*")) } },
                    LinearLayout.LayoutParams(dp(72), dp(32)).apply { setMargins(dp(4), 0, 0, 0) },
                )
                addView(
                    smallButton("Remove", true) { showCustomVoiceManager() },
                    LinearLayout.LayoutParams(dp(72), dp(32)).apply { setMargins(dp(4), 0, 0, 0) },
                )
            }, LinearLayout.LayoutParams(-1, dp(32)))
        }
        addCard(root, voiceCard)

        speedLabel = TextView(this).apply {
            textSize = 13f
            setTextColor(textPrimary)
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
        }
        speedBar = SeekBar(this).apply {
            max = 55
            progress = 15
            progressTintList = android.content.res.ColorStateList.valueOf(purple)
            thumbTintList = android.content.res.ColorStateList.valueOf(purple)
        }
        addCard(root, sectionCard().apply {
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(label("Speed"), LinearLayout.LayoutParams(0, -2, 1f))
                addView(speedLabel, LinearLayout.LayoutParams(-2, -2))
            })
            addView(speedBar, LinearLayout.LayoutParams(-1, dp(28)))
        })

        stepsSpinner = spinner(steps.map { "$it steps" })
        threadsSpinner = spinner(threadCounts.map { "$it threads" })
        addCard(root, weightedRow(
            compactField("Steps (Flow Matching)", stepsSpinner),
            compactField("CPU Threads", threadsSpinner),
        ))

        preGenerationSpinner = spinner(listOf("OFF", "ON"))
        languageSpinner = spinner(languages.map { it.first })
        addCard(root, weightedRow(
            compactField("Pre-chunk Generation", preGenerationSpinner),
            compactField("Test Language", languageSpinner),
        ))

        // ONNX + Qualcomm NPU only. REV24 uses a fixed adaptive bucket grid; manual T/L tuning is retired.
        originalShapeSpinner = spinner(originalShapePresets.map { it.first }) // internal compatibility only
        originalFixedTInput = EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setSingleLine(true)
            hint = "T"
            textSize = 14f
            gravity = Gravity.CENTER
            background = rounded(surface, 10f, Color.rgb(210, 194, 248))
            setPadding(dp(6), 0, dp(6), 0)
        }
        originalFixedLInput = EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setSingleLine(true)
            hint = "L"
            textSize = 14f
            gravity = Gravity.CENTER
            background = rounded(surface, 10f, Color.rgb(210, 194, 248))
            setPadding(dp(6), 0, dp(6), 0)
        }
        fun npuValueField(title: String, input: EditText) = sectionCard().apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(label(title), LinearLayout.LayoutParams(0, -2, 0.72f))
            addView(input, LinearLayout.LayoutParams(0, dp(30), 1.28f))
        }
        originalShapeCustomRow = weightedRow(
            npuValueField("Fixed T", originalFixedTInput),
            npuValueField("Fixed L", originalFixedLInput),
        )
        originalShapeSection = sectionCard().apply {
            background = rounded(purpleSoft, 14f, Color.rgb(197, 174, 244))
            addView(TextView(this@MainActivity).apply {
                text = "NPU Inference"
                textSize = 12f
                setTextColor(purpleDark)
                setTypeface(typeface, Typeface.BOLD)
            })
            addView(TextView(this@MainActivity).apply {
                text = "Adaptive buckets · T 80 / 112 / 144 / 160 · L 96 / 128 / 160 / 192"
                textSize = 13f
                setTextColor(textPrimary)
                setPadding(0, dp(6), 0, 0)
            })
            visibility = View.GONE
        }
        chunkGapMinInput = EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setSingleLine(true)
            hint = "Min ms"
            background = rounded(Color.rgb(252, 251, 253), 10f)
            setPadding(dp(8), 0, dp(8), 0)
        }
        chunkGapMaxInput = EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setSingleLine(true)
            hint = "Max ms"
            background = rounded(Color.rgb(252, 251, 253), 10f)
            setPadding(dp(8), 0, dp(8), 0)
        }
        val chunkGapCard = sectionCard().apply {
            addView(label("Chunk Gap (min / max)"))
            addView(weightedRow(chunkGapMinInput, chunkGapMaxInput), LinearLayout.LayoutParams(-1, dp(32)))
        }

        trailingTrimInput = EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setSingleLine(true)
            hint = "0–500"
            background = rounded(Color.rgb(252, 251, 253), 10f)
            setPadding(dp(8), 0, dp(8), 0)
        }
        addCard(root, weightedRow(
            chunkGapCard,
            compactField("Silence Trim (ms)", trailingTrimInput),
        ))

        internalSilenceSpinner = spinner(listOf("OFF", "ON"))
        internalSilenceMaxInput = EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setSingleLine(true)
            hint = "100–500"
            background = rounded(Color.rgb(252, 251, 253), 10f)
            setPadding(dp(8), 0, dp(8), 0)
        }
        addCard(root, weightedRow(
            compactField("Internal Silence", internalSilenceSpinner),
            compactField("Max Pause @1x (ms)", internalSilenceMaxInput),
        ))

        // NPU-specific controls come after the common two-column rows.
        // NPU now shares the same automatic 7x7 T/L grid as LiteRT; no manual shape card.

        // LiteRT-only chunk-size control. ONNX keeps the same common layout without an empty spacer.
        chunkSizeSection = sectionCard().apply {
            addView(label("LiteRT Chunk Size"))
            chunkSpinner = spinner(chunkModes.map { it.first })
            addView(chunkSpinner, LinearLayout.LayoutParams(-1, dp(32)))
            chunkManualInput = EditText(this@MainActivity).apply {
                inputType = android.text.InputType.TYPE_CLASS_NUMBER
                setSingleLine(true)
                hint = "24–96"
                setText(TtsSettings.manualChunkCap(this@MainActivity).toString())
                visibility = View.GONE
                background = rounded(Color.rgb(252, 251, 253), 10f)
                setPadding(dp(8), 0, dp(8), 0)
            }
            addView(chunkManualInput, LinearLayout.LayoutParams(-1, dp(32)).apply { setMargins(0, dp(2), 0, 0) })
        }
        // Manual LiteRT chunk-size UI retired; AutoBucket owns chunk admission.

        allowNaCheck = android.widget.CheckBox(this).apply {
            text = "Allow mixed-language auto mode (na)"
            textSize = 12f
            isChecked = TtsSettings.allowNa(this@MainActivity)
            buttonTintList = android.content.res.ColorStateList.valueOf(purple)
            setTextColor(textSecondary)
            setPadding(0, 0, 0, 0)
            setOnCheckedChangeListener { _, checked -> TtsSettings.setAllowNa(this@MainActivity, checked) }
        }
        root.addView(allowNaCheck, LinearLayout.LayoutParams(-1, -2).apply { setMargins(dp(1), dp(1), 0, 0) })

        textInput = EditText(this).apply {
            setSingleLine(false)
            minLines = 2
            maxLines = 2
            setHorizontallyScrolling(false)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
            textSize = 13f
            gravity = Gravity.TOP or Gravity.START
            hint = "Enter test text…"
            setText("안녕하세요. Supertonic-3 LiteRT TTS 엔진 테스트입니다.")
            setPadding(dp(8), dp(5), dp(8), dp(5))
            background = rounded(surface, 12f)
        }
        addCard(root, sectionCard().apply {
            addView(label("Text"))
            addView(textInput, LinearLayout.LayoutParams(-1, dp(58)))
        })

        startButton = Button(this).apply {
            text = "START"
            minHeight = dp(44)
            minimumHeight = dp(44)
            setTextColor(Color.WHITE)
            background = rounded(purple, 12f, null, 0)
            setOnClickListener { startSynthesis() }
        }
        playButton = Button(this).apply {
            text = "PLAY"
            minHeight = dp(44)
            minimumHeight = dp(44)
            isEnabled = false
            setTextColor(purpleDark)
            background = rounded(surface, 12f, purple)
            setOnClickListener { playLast() }
        }
        val stop = Button(this).apply {
            text = "STOP"
            minHeight = dp(44)
            minimumHeight = dp(44)
            setTextColor(danger)
            background = rounded(surface, 12f, Color.rgb(235, 126, 126))
            setOnClickListener { stopAll() }
        }
        addCard(root, weightedRow(startButton, playButton, stop), 6)

        status = TextView(this).apply {
            text = "●  Checking model…"
            textSize = 12f
            maxLines = 1
            isSingleLine = true
            ellipsize = android.text.TextUtils.TruncateAt.END
            setTextColor(textSecondary)
            setPadding(dp(3), dp(4), dp(3), 0)
        }
        root.addView(status)

        // These remain functional, but no longer consume main-screen height.
        ruleStatus = TextView(this)
        rtfView = TextView(this)

        menuButton.setOnClickListener { anchor ->
            PopupMenu(this, anchor).apply {
                menu.add("Pronunciation Rules")
                menu.add("Import Rules")
                menu.add("Export Rules")
                menu.add("Save WAV")
                menu.add("Share Audio")
                menu.add("Verify Model Files")
                menu.add("Manage Models")
                if (qualcommNpuAvailable) menu.add("QNN cache pre-gen")
                val cpuDiagnostics = menu.addSubMenu("CPU Diagnostics")
                cpuDiagnostics.add("Pregen Benchmark")
                cpuDiagnostics.add("Force Pregen Test")
                menu.add("Performance Profile")
                menu.add(if (TtsSettings.deepProfiler(this@MainActivity)) "Deep Profiler: ON" else "Deep Profiler: OFF")
                setOnMenuItemClickListener { item ->
                    when (item.title.toString()) {
                        "Pronunciation Rules" -> startActivity(Intent(this@MainActivity, PronunciationRulesActivity::class.java))
                        "Import Rules" -> ruleImport.launch(arrayOf("application/json", "text/plain", "*/*"))
                        "Export Rules" -> ruleExport.launch("supertonic-pronunciation-rules.json")
                        "Save WAV" -> saveLast()
                        "Share Audio" -> shareLast()
                        "Verify Model Files" -> verifyModelFiles()
                        "Manage Models" -> showModelManager()
                        "QNN cache pre-gen" -> preGenerateQnnCaches()
                        "Pregen Benchmark" -> runPregenBenchmark()
                        "Force Pregen Test" -> runForcePregenTest()
                        "Performance Profile" -> AlertDialog.Builder(this@MainActivity)
                            .setTitle("Performance Profile")
                            .setMessage(rtfView.text.takeIf { it.isNotBlank() } ?: "No synthesis profile yet.")
                            .setPositiveButton("CLOSE", null)
                            .show()
                        "Deep Profiler: ON", "Deep Profiler: OFF" -> {
                            val enabled = !TtsSettings.deepProfiler(this@MainActivity)
                            TtsSettings.setDeepProfiler(this@MainActivity, enabled)
                            invalidateSynthesizer()
                            Toast.makeText(
                                this@MainActivity,
                                if (enabled) "Deep Profiler ON: next engine run records ORT/QNN/LiteRT profiles; timing includes profiler overhead."
                                else "Deep Profiler OFF",
                                Toast.LENGTH_LONG,
                            ).show()
                        }
                    }
                    true
                }
                show()
            }
        }

        val contentFrame = android.widget.FrameLayout(this).apply {
            setBackgroundColor(page)
            val screenWidthDp = resources.configuration.screenWidthDp
            val contentWidth = if (screenWidthDp > 700) dp(700) else -1
            addView(root, android.widget.FrameLayout.LayoutParams(contentWidth, -2).apply {
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            })
        }
        setContentView(android.widget.ScrollView(this).apply {
            isFillViewport = true
            setBackgroundColor(page)
            addView(contentFrame, android.widget.FrameLayout.LayoutParams(-1, -2))
        })
        updateSpeedLabel()
    }

    private fun restoreSettings() {
        val savedModel = TtsSettings.ttsModel(this)
        val restoredModelIndex = modelChoices.indexOfFirst { it.second == savedModel }.takeIf { it >= 0 } ?: 0
        modelSpinner.setSelection(restoredModelIndex)
        val restoredModel = modelChoices[restoredModelIndex].second
        if (restoredModel != savedModel) TtsSettings.setTtsModel(this, restoredModel)
        val savedVoice = TtsSettings.voice(this)
        voiceSpinner.setSelection(voiceIds.indexOf(savedVoice).takeIf { it >= 0 } ?: 0)
        stepsSpinner.setSelection(steps.indexOf(TtsSettings.steps(this)).takeIf { it >= 0 } ?: steps.indexOf(4))
        threadsSpinner.setSelection(threadCounts.indexOf(TtsSettings.threads(this)).takeIf { it >= 0 } ?: threadCounts.indexOf(4))
        val savedBackend = TtsSettings.backend(this, restoredModel)
        val restoredBackend = backendChoicesFor(restoredModel)
            .firstOrNull { it.second == savedBackend }?.second
            ?: backendChoicesFor(restoredModel).firstOrNull {
                it.second == if (restoredModel.isOnnx) InferenceBackend.CPU_ORT else InferenceBackend.CPU_XNNPACK
            }?.second
            ?: if (restoredModel.isOnnx) InferenceBackend.CPU_ORT else InferenceBackend.CPU_XNNPACK
        refreshBackendChoices(restoredModel, restoredBackend)
        backendSpinner.isEnabled = activeBackends.size > 1
        preGenerationSpinner.isEnabled = true
        chunkSpinner.setSelection(chunkModes.indexOfFirst { it.second == TtsSettings.chunkMode(this) }.takeIf { it >= 0 } ?: 1)
        chunkManualInput.setText(TtsSettings.manualChunkCap(this).toString())
        chunkManualInput.visibility = if (TtsSettings.chunkMode(this) == TtsSettings.CHUNK_MANUAL) android.view.View.VISIBLE else android.view.View.GONE
        syncPregenUiForBackend(restoredBackend)
        chunkGapMinInput.setText(TtsSettings.chunkGapMinMs(this).toString())
        chunkGapMaxInput.setText(TtsSettings.chunkGapMaxMs(this).toString())
        trailingTrimInput.setText(TtsSettings.trailingSilenceTrimMs(this).toString())
        internalSilenceSpinner.setSelection(if (TtsSettings.internalSilenceCompression(this)) 1 else 0)
        internalSilenceMaxInput.setText(TtsSettings.internalSilenceMaxPauseMs(this).toString())
        syncInternalSilenceUi()
        originalShapeSpinner.setSelection(originalShapePresets.indexOfFirst { it.second == TtsSettings.ORIGINAL_SHAPE_CUSTOM }.coerceAtLeast(0))
        originalFixedTInput.setText("160")
        originalFixedLInput.setText("192")
        updateModelSpecificUi(restoredModel)
        val savedSpeed = TtsSettings.speed(this).coerceIn(0.25f, 3.0f)
        speedBar.progress = ((savedSpeed - 0.25f) / 0.05f).roundToInt().coerceIn(0, speedBar.max)
        // Listeners are intentionally attached only after restoration, so a
        // programmatic progress change above does not trigger onProgressChanged.
        // Refresh the visible value explicitly; otherwise 1.00 can be applied
        // internally while the first-render label remains at 0.25.
        updateSpeedLabel()
        val savedTestLanguage = TtsSettings.testLanguage(this)
        languageSpinner.setSelection(languages.indexOfFirst { it.second == savedTestLanguage }.takeIf { it >= 0 } ?: 0)
    }

    private fun refreshVoices(selected: String? = TtsSettings.voice(this)) {
        val custom = ModelManager.customVoiceFiles(applicationContext)
            .asSequence()
            .map { it.nameWithoutExtension }
            .filter { it !in builtinVoices }
            .sortedWith(String.CASE_INSENSITIVE_ORDER)
            .toList()
        voiceIds = (builtinVoices + custom).toMutableList()
        voiceLabels = (builtinVoices.map { it } + custom.map { "Custom · ${it.removePrefix("custom_")}" }).toMutableList()
        voiceSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, voiceLabels)
        voiceSpinner.setSelection(voiceIds.indexOf(selected ?: "F1").takeIf { it >= 0 } ?: 0)
    }

    private fun bindSettingPersistence() {
        voiceSpinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
                val voice = currentVoice()
                TtsSettings.save(this@MainActivity, voice, currentSpeed(), currentSteps(), currentThreads(), currentChunkMode(), currentManualChunkCap())
                if (synthJob?.isActive != true) runCatching { synthesizer?.setVoice(voice) }
            }
        }
        stepsSpinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
                val stepCount = currentSteps()
                TtsSettings.save(this@MainActivity, currentVoice(), currentSpeed(), stepCount, currentThreads(), currentChunkMode(), currentManualChunkCap())
                if (synthJob?.isActive != true) synthesizer?.setTotalSteps(stepCount)
            }
        }
        threadsSpinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
                val threads = currentThreads()
                TtsSettings.save(this@MainActivity, currentVoice(), currentSpeed(), currentSteps(), threads, currentChunkMode(), currentManualChunkCap())
                // Thread count is a graph/interpreter creation option. Do not close a
                // live ORT session from the UI thread while synthesis is running.
                invalidateSynthesizer()
                scheduleSelectedModelPreload("threads-change")
            }
        }
        modelSpinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit

            override fun onItemSelected(
                parent: android.widget.AdapterView<*>?,
                view: android.view.View?,
                position: Int,
                id: Long,
            ) {
                val model = currentTtsModel()
                TtsSettings.setTtsModel(this@MainActivity, model)

                val savedBackendForModel = TtsSettings.backend(this@MainActivity, model)
                refreshBackendChoices(model, savedBackendForModel)
                backendSpinner.isEnabled = activeBackends.size > 1
                updateModelSpecificUi(model, currentBackend())
                syncPregenUiForBackend(currentBackend())

                invalidateSynthesizer()
                refreshVoices()
                ensureModel()
            }
        }

        val commitOriginalCustomShape = {
            val t = currentOriginalFixedT()
            val l = currentOriginalFixedL()
            originalFixedTInput.setText(t.toString())
            originalFixedLInput.setText(l.toString())
            TtsSettings.setOriginalShapeSettings(this@MainActivity, TtsSettings.ORIGINAL_SHAPE_CUSTOM, t, l)
            invalidateSynthesizer()
        }
        originalFixedTInput.setOnFocusChangeListener { _, hasFocus -> if (!hasFocus) commitOriginalCustomShape() }
        originalFixedLInput.setOnFocusChangeListener { _, hasFocus -> if (!hasFocus) commitOriginalCustomShape() }

        backendSpinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
                val model = currentTtsModel()
                val backend = currentBackend()
                val persisted = TtsSettings.backend(this@MainActivity, model)
                updateModelSpecificUi(model, backend)
                syncPregenUiForBackend(backend)
                // Adapter replacement/setSelection during model restoration emits
                // onItemSelected. If it merely reflects the already-persisted
                // backend, it is not a user backend change and must not invalidate
                // a model-ready preload that is already in flight.
                if (backend == persisted) {
                    Log.i("MainActivity", "[BACKEND-SELECT] programmatic-noop model=${model.name} backend=${backend.name}")
                    return
                }
                TtsSettings.setBackend(this@MainActivity, model, backend)
                if (synthJob?.isActive != true) {
                    runCatching { synthesizer?.setPreGeneration(effectivePregenForBackend(backend)) }
                }
                invalidateSynthesizer()
                scheduleSelectedModelPreload("backend-change")
            }
        }
        chunkSpinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
                val mode = chunkModes[position.coerceIn(0, chunkModes.lastIndex)].second
                chunkManualInput.visibility = if (mode == TtsSettings.CHUNK_MANUAL) android.view.View.VISIBLE else android.view.View.GONE
                TtsSettings.setChunkSettings(this@MainActivity, mode, currentManualChunkCap())
                invalidateSynthesizer()
            }
        }
        chunkManualInput.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) {
                val cap = currentManualChunkCap()
                chunkManualInput.setText(cap.toString())
                TtsSettings.setChunkSettings(this@MainActivity, currentChunkMode(), cap)
            }
        }
        val commitStreamingControls = {
            val minGap = currentChunkGapMin()
            val maxGap = currentChunkGapMax().coerceAtLeast(minGap)
            val trail = currentTrailingTrim()
            chunkGapMinInput.setText(minGap.toString())
            chunkGapMaxInput.setText(maxGap.toString())
            trailingTrimInput.setText(trail.toString())
            TtsSettings.setStreamingControls(this@MainActivity, preGenerationQueue(), minGap, maxGap, trail)
            if (synthJob?.isActive != true) {
                runCatching {
                    synthesizer?.setPreGenerationQueue(preGenerationQueue())
                    synthesizer?.setChunkGap(minGap, maxGap)
                    synthesizer?.setTrailingSilenceTrimMs(trail)
                }
            }
        }
        chunkGapMinInput.setOnFocusChangeListener { _, hasFocus -> if (!hasFocus) commitStreamingControls() }
        chunkGapMaxInput.setOnFocusChangeListener { _, hasFocus -> if (!hasFocus) commitStreamingControls() }
        trailingTrimInput.setOnFocusChangeListener { _, hasFocus -> if (!hasFocus) commitStreamingControls() }

        val commitInternalSilence = {
            val enabled = internalSilenceSpinner.selectedItemPosition == 1
            val maxPause = currentInternalSilenceMax()
            internalSilenceMaxInput.setText(maxPause.toString())
            TtsSettings.setInternalSilenceControls(this@MainActivity, enabled, maxPause)
            syncInternalSilenceUi()
        }
        internalSilenceSpinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
                commitInternalSilence()
            }
        }
        internalSilenceMaxInput.setOnFocusChangeListener { _, hasFocus -> if (!hasFocus) commitInternalSilence() }

        preGenerationSpinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
                if (suppressPregenSpinnerCallback) return
                val backend = currentBackend()
                if (backend == InferenceBackend.QUALCOMM_NPU) {
                    if (position != 0) {
                        Toast.makeText(this@MainActivity, "NPU uses Pregen OFF.", Toast.LENGTH_SHORT).show()
                        syncPregenUiForBackend(backend)
                    }
                    if (synthJob?.isActive != true) runCatching { synthesizer?.setPreGeneration(false) }
                    return
                }
                val enabled = position == 1
                TtsSettings.setPreGeneration(this@MainActivity, enabled)
                TtsSettings.setStreamingControls(
                    this@MainActivity,
                    1,
                    currentChunkGapMin(),
                    currentChunkGapMax(),
                    currentTrailingTrim(),
                )
                if (synthJob?.isActive != true) {
                    runCatching {
                        synthesizer?.setPreGeneration(enabled)
                        synthesizer?.setPreGenerationQueue(1)
                    }
                }
            }
        }

        languageSpinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
                TtsSettings.setTestLanguage(this@MainActivity, currentLanguage())
            }
        }

        speedBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                updateSpeedLabel()
                // Do not synchronously commit SharedPreferences for every slider tick.
                // Native synthesis remains fixed at 1x; the selected speed is persisted once
                // when the user releases the thumb and applied later by Sonic post-process.
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                TtsSettings.save(
                    this@MainActivity, currentVoice(), currentSpeed(), currentSteps(), currentThreads(),
                    currentChunkMode(), currentManualChunkCap(),
                )
            }
        })
    }

    private fun updateSpeedLabel() {
        val value = 0.25f + speedBar.progress * 0.05f
        speedLabel.text = "${String.format(Locale.US, "%.2f", value)}x"
    }

    private fun currentVoice(): String = voiceIds[voiceSpinner.selectedItemPosition.coerceIn(0, voiceIds.lastIndex)]
    private fun currentSteps(): Int = steps[stepsSpinner.selectedItemPosition.coerceIn(0, steps.lastIndex)]
    private fun currentThreads(): Int = threadCounts[threadsSpinner.selectedItemPosition.coerceIn(0, threadCounts.lastIndex)]
    private fun currentTtsModel(): TtsModel =
        modelChoices[
            modelSpinner.selectedItemPosition.coerceIn(0, modelChoices.lastIndex)
        ].second

    private fun backendChoicesFor(model: TtsModel): List<Pair<String, InferenceBackend>> = when (model) {
        TtsModel.SUPERTONIC,
        TtsModel.SUPERTONIC_LITERT_WI8_AFP32,
        TtsModel.SUPERTONIC_LITERT_STATIC_MULTIPRESET_GELU,
        TtsModel.SUPERTONIC_LITERT_STATIC_MULTIPRESET_GELU_WI8_AFP32 -> liteRtBackends
        TtsModel.SUPERTONIC_ORIGINAL_ONNX -> onnxBackends
        TtsModel.SUPERTONIC_ONNX_W8A16_QDQ -> onnxBackends
    }

    private fun refreshBackendChoices(
        model: TtsModel,
        preferred: InferenceBackend = TtsSettings.backend(this, model),
    ) {
        activeBackends = backendChoicesFor(model)
        require(activeBackends.isNotEmpty()) { "No supported backend for ${ModelManager.modelLabel(model)} on this device" }
        backendSpinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            activeBackends.map { it.first },
        ).also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        // User selection is authoritative. Do not auto-benchmark CPU vs CPU XNN
        // and do not auto-promote W8A16 to NPU.
        val modelCpuDefault = if (model.isOnnx) InferenceBackend.CPU_ORT else InferenceBackend.CPU_XNNPACK
        val safePreferred = if (
            !BuildConfig.ORT_XNNPACK_AVAILABLE && preferred == InferenceBackend.ONNX_XNNPACK
        ) modelCpuDefault else preferred
        val selected = activeBackends.indexOfFirst { it.second == safePreferred }
            .takeIf { it >= 0 }
            ?: activeBackends.indexOfFirst { it.second == modelCpuDefault }.coerceAtLeast(0)
        backendSpinner.setSelection(selected)
        // Do not persist here: adapter/setSelection is UI restoration, not an
        // explicit backend choice. The listener persists only real changes.
    }

    private fun currentBackend(): InferenceBackend =
        activeBackends[
            backendSpinner.selectedItemPosition.coerceIn(0, activeBackends.lastIndex)
        ].second

    private fun backendDisplayName(backend: InferenceBackend): String = when (backend) {
        InferenceBackend.CPU_XNNPACK, InferenceBackend.CPU_XNNPACK_FP16 -> "CPU"
        InferenceBackend.CPU_ORT -> "CPU (ORT)"
        InferenceBackend.ONNX_XNNPACK -> "CPU XNN"
        InferenceBackend.QUALCOMM_NPU -> "NPU"
    }

    private fun invalidateSynthesizer() {
        // Closing ORT/QNN from a spinner callback raced a live worker in the
        // uploaded log and produced pthread_mutex_lock on a destroyed mutex.
        // Mark it stale and rebuild only on a worker. Also invalidate any model
        // preload that was captured with the previous model/backend/thread setup.
        synthesizerStale = true
        modelPreloadGeneration++
        modelPreloadJob?.cancel()
    }
    private fun currentChunkMode(): String = chunkModes[chunkSpinner.selectedItemPosition.coerceIn(0, chunkModes.lastIndex)].second
    private fun currentManualChunkCap(): Int = chunkManualInput.text?.toString()?.toIntOrNull()?.coerceIn(TtsSettings.MIN_CHUNK_CAP, TtsSettings.MAX_CHUNK_CAP) ?: TtsSettings.manualChunkCap(this)
    private fun currentChunkCap(): Int = 0
    private fun preGenerationQueue(): Int = 1
    private fun effectivePregenForBackend(backend: InferenceBackend = currentBackend()): Boolean =
        backend != InferenceBackend.QUALCOMM_NPU && TtsSettings.preGeneration(this)
    private fun syncPregenUiForBackend(backend: InferenceBackend = currentBackend()) {
        val npuLocked = backend == InferenceBackend.QUALCOMM_NPU
        // NPU pre-generation is deliberately unsupported. Keep the control in place
        // so the layout does not jump, but make it visibly disabled just like the
        // LiteRT backend selector and force the displayed value to OFF.
        preGenerationSpinner.isEnabled = !npuLocked
        preGenerationSpinner.alpha = if (npuLocked) 0.45f else 1.0f
        val selection = if (npuLocked) 0 else if (TtsSettings.preGeneration(this)) 1 else 0
        if (preGenerationSpinner.selectedItemPosition == selection) return
        suppressPregenSpinnerCallback = true
        try {
            preGenerationSpinner.setSelection(selection, false)
        } finally {
            suppressPregenSpinnerCallback = false
        }
    }
    private fun currentChunkGapMin(): Int = chunkGapMinInput.text?.toString()?.toIntOrNull()?.coerceIn(TtsSettings.MIN_GAP_MS, TtsSettings.MAX_GAP_MS) ?: TtsSettings.DEFAULT_GAP_MIN_MS
    private fun currentChunkGapMax(): Int = chunkGapMaxInput.text?.toString()?.toIntOrNull()?.coerceIn(TtsSettings.MIN_GAP_MS, TtsSettings.MAX_GAP_MS) ?: TtsSettings.DEFAULT_GAP_MAX_MS
    private fun currentTrailingTrim(): Int = trailingTrimInput.text?.toString()?.toIntOrNull()?.coerceIn(TtsSettings.MIN_TRAILING_TRIM_MS, TtsSettings.MAX_TRAILING_TRIM_MS) ?: TtsSettings.DEFAULT_TRAILING_TRIM_MS
    private fun currentInternalSilenceMax(): Int = internalSilenceMaxInput.text?.toString()?.toIntOrNull()
        ?.coerceIn(TtsSettings.MIN_INTERNAL_SILENCE_MAX_MS, TtsSettings.MAX_INTERNAL_SILENCE_MAX_MS)
        ?: TtsSettings.DEFAULT_INTERNAL_SILENCE_MAX_MS
    private fun syncInternalSilenceUi() {
        val enabled = internalSilenceSpinner.selectedItemPosition == 1
        internalSilenceMaxInput.isEnabled = enabled
        internalSilenceMaxInput.alpha = if (enabled) 1.0f else 0.45f
    }
    private fun currentSpeed(): Float = 0.25f + speedBar.progress * 0.05f
    private fun currentLanguage(): String = languages[languageSpinner.selectedItemPosition.coerceIn(0, languages.lastIndex)].second
    private fun currentOriginalShapePreset(): String = TtsSettings.ORIGINAL_SHAPE_CUSTOM
    private fun currentOriginalFixedT(): Int = 128
    private fun currentOriginalFixedL(): Int = 128
    private fun updateModelSpecificUi(
        model: TtsModel = currentTtsModel(),
        backend: InferenceBackend = currentBackend(),
    ) {
        chunkSizeSection.visibility = View.GONE
        originalShapeSection.visibility = View.GONE
        originalShapeCustomRow.visibility = View.GONE
    }
    private fun persistUiSettings() {
        TtsSettings.save(this, currentVoice(), currentSpeed(), currentSteps(), currentThreads(), currentChunkMode(), currentManualChunkCap())
        if (currentBackend() != InferenceBackend.QUALCOMM_NPU) {
            TtsSettings.setPreGeneration(this, preGenerationSpinner.selectedItemPosition == 1)
        }
        TtsSettings.setStreamingControls(this, 1, currentChunkGapMin(), currentChunkGapMax(), currentTrailingTrim())
        TtsSettings.setInternalSilenceControls(this, internalSilenceSpinner.selectedItemPosition == 1, currentInternalSilenceMax())
        TtsSettings.setTestLanguage(this, currentLanguage())
        TtsSettings.setBackend(this, currentTtsModel(), currentBackend())
        TtsSettings.setTtsModel(this, currentTtsModel())
        TtsSettings.setOriginalShapeSettings(this, currentOriginalShapePreset(), currentOriginalFixedT(), currentOriginalFixedL())
    }

    private fun preGenerateQnnCaches() {
        if (!qualcommNpuAvailable) {
            Toast.makeText(this, "Qualcomm NPU is not available.", Toast.LENGTH_SHORT).show()
            return
        }
        if (detectSm6350Hta()) {
            Toast.makeText(this, "QNN cache pre-gen is disabled for the SM6350 HTA backend.", Toast.LENGTH_LONG).show()
            return
        }
        if (qnnCacheJob?.isActive == true) {
            Toast.makeText(this, "QNN cache pre-gen is already running.", Toast.LENGTH_SHORT).show()
            return
        }
        if (synthJob?.isActive == true || modelJob?.isActive == true) {
            Toast.makeText(this, "Finish synthesis/model setup first.", Toast.LENGTH_SHORT).show()
            return
        }

        val models = listOf(
            TtsModel.SUPERTONIC_ORIGINAL_ONNX,
            TtsModel.SUPERTONIC_ONNX_W8A16_QDQ,
        ).filter { ModelManager.areTtsModelsReady(this, it) }
        if (models.isEmpty()) {
            Toast.makeText(this, "No installed ONNX model is ready.", Toast.LENGTH_SHORT).show()
            return
        }

        val threads = currentThreads()
        val totalAll = models.sumOf { SpeechSynthesizer.expectedQnnContextCount(it) }
        modelPreloadGeneration++
        modelPreloadJob?.cancel()
        val old = synthesizer
        synthesizer = null
        synthesizerStale = false
        runCatching { old?.close() }
        startButton.isEnabled = false

        qnnCacheJob = scope.launch {
            var completedAll = 0
            var generatedAll = 0
            var hitsAll = 0
            status.text = "QNN cache pre-gen… 0/$totalAll"
            runCatching {
                withContext(Dispatchers.Default) {
                    for (model in models) {
                        val label = when (model) {
                            TtsModel.SUPERTONIC_ORIGINAL_ONNX -> "FP32"
                            TtsModel.SUPERTONIC_ONNX_W8A16_QDQ -> "W8A16"
                            else -> model.name
                        }
                        val modelTotal = SpeechSynthesizer.expectedQnnContextCount(model)
                        var modelCompletedBase = completedAll
                        val temp = SpeechSynthesizer(
                            SpeechSynthesizerConfig(
                                modelDir = ModelManager.modelDir(applicationContext, model).absolutePath,
                                useNnapi = false,
                                backend = InferenceBackend.QUALCOMM_NPU,
                                ttsModel = model,
                                voiceId = "F1",
                                speed = 1.0f,
                                totalSteps = 8,
                                numThreads = threads,
                                chunkCap = 64,
                                nativeLibraryDir = applicationInfo.nativeLibraryDir,
                                acceleratorCacheDir = File(cacheDir, "accelerator_cache").apply { mkdirs() }.absolutePath,
                            )
                        )
                        qnnCacheSynthesizer = temp
                        try {
                            val summary = temp.preGenerateQnnContexts { progress ->
                                val globalDone = modelCompletedBase + progress.completed
                                runOnUiThread {
                                    status.text =
                                        "QNN cache pre-gen… $globalDone/$totalAll · $label ${progress.completed}/$modelTotal · " +
                                            "${progress.graph} ${progress.shape} · ${progress.contextState}"
                                }
                            }
                            completedAll += summary.total
                            generatedAll += summary.generated
                            hitsAll += summary.hits
                        } finally {
                            qnnCacheSynthesizer = null
                            runCatching { temp.close() }
                        }
                    }
                }
            }.onSuccess {
                status.text = "QNN cache pre-gen complete · $completedAll/$totalAll · new $generatedAll · hit $hitsAll"
                Toast.makeText(this@MainActivity, "QNN cache pre-gen complete", Toast.LENGTH_SHORT).show()
            }.onFailure { e ->
                val cancelled = e is kotlinx.coroutines.CancellationException || qnnCacheJob?.isCancelled == true
                status.text = if (cancelled) {
                    "QNN cache pre-gen stopped · $completedAll/$totalAll"
                } else {
                    "QNN cache pre-gen failed · ${e.message ?: e.javaClass.simpleName}"
                }
            }
            qnnCacheSynthesizer = null
            startButton.isEnabled = true
        }
    }

    private fun verifyModelFiles() {
        if (modelJob?.isActive == true) return
        val model = currentTtsModel()
        modelJob = scope.launch {
            status.text = "Verifying model SHA-256…"
            val result = runCatching {
                ModelManager.verifyTtsModels(applicationContext, model) { checked, total, file ->
                    runOnUiThread { status.text = "Verifying model SHA-256… $checked/$total · $file" }
                }
            }
            result.onSuccess { report ->
                val message = buildString {
                    append(if (report.ok) "PASS" else "FAIL").append("\n\n")
                    append("Checked: ").append(report.checked).append('/').append(report.total).append("\n")
                    if (report.manifestInitialized) append("SHA manifest: initialized from current verified-size bundle\n")
                    if (report.missing.isNotEmpty()) append("Missing: ").append(report.missing.joinToString()).append("\n")
                    if (report.mismatched.isNotEmpty()) append("SHA mismatch: ").append(report.mismatched.joinToString()).append("\n")
                    append("\nFuture Verify runs compare every model byte against this local SHA-256 manifest.")
                }
                status.text = if (report.ok) "${ModelManager.modelLabel(model)} SHA-256 verified" else "Model verification failed"
                AlertDialog.Builder(this@MainActivity)
                    .setTitle("Verify Model Files")
                    .setMessage(message)
                    .setPositiveButton("CLOSE", null)
                    .show()
            }.onFailure { e ->
                status.text = "Model verification failed · ${e.message ?: e.javaClass.simpleName}"
                AlertDialog.Builder(this@MainActivity)
                    .setTitle("Verify Model Files")
                    .setMessage("FAILED\n\n${e.stackTraceToString()}")
                    .setPositiveButton("CLOSE", null)
                    .show()
            }
        }
    }

    private fun showModelManager() {
        val rows = modelChoices.map { (label, model) ->
            val bytes = ModelManager.installedSizeBytes(applicationContext, model)
            val size = if (bytes > 0L) {
                android.text.format.Formatter.formatFileSize(this, bytes)
            } else {
                "Not downloaded"
            }
            "$label  ·  $size"
        }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle("Manage Models")
            .setItems(rows) { _, which ->
                val model = modelChoices[which].second
                val bytes = ModelManager.installedSizeBytes(applicationContext, model)
                if (bytes <= 0L) {
                    Toast.makeText(this, "${ModelManager.modelLabel(model)} is not downloaded.", Toast.LENGTH_SHORT).show()
                } else {
                    confirmRemoveModel(model, bytes)
                }
            }
            .setNegativeButton("CLOSE", null)
            .show()
    }

    private fun confirmRemoveModel(model: TtsModel, bytes: Long) {
        val label = ModelManager.modelLabel(model)
        val size = android.text.format.Formatter.formatFileSize(this, bytes)
        AlertDialog.Builder(this)
            .setTitle("Remove $label?")
            .setMessage(
                "Only this model ($size) will be deleted. Other models and imported custom voices will be kept."
            )
            .setNegativeButton("CANCEL", null)
            .setPositiveButton("REMOVE") { _, _ -> removeModel(model) }
            .show()
    }

    private fun removeModel(model: TtsModel) {
        if (synthJob?.isActive == true || qnnCacheJob?.isActive == true || cpuDiagnosticsJob?.isActive == true) {
            Toast.makeText(this, "Stop synthesis or diagnostics before removing a model.", Toast.LENGTH_LONG).show()
            return
        }
        scope.launch {
            startButton.isEnabled = false
            status.text = "Removing ${ModelManager.modelLabel(model)}…"
            modelPreloadGeneration++
            modelPreloadJob?.cancel()
            modelJob?.cancel()
            runCatching { modelPreloadJob?.join() }
            runCatching { modelJob?.join() }

            if (model == currentTtsModel()) {
                val old = synchronized(synthesizerLock) {
                    val current = synthesizer
                    synthesizer = null
                    synthesizerStale = true
                    modelPreloadKey = null
                    current
                }
                withContext(Dispatchers.Default) {
                    runCatching { old?.stop() }
                    runCatching { old?.close() }
                }
            }

            val result = runCatching {
                withContext(Dispatchers.Default) {
                    SpeechTextToSpeechService.releaseActiveModel(model)
                }
                ModelManager.removeTtsModel(applicationContext, model)
            }
            result.onSuccess { report ->
                val removed = android.text.format.Formatter.formatFileSize(this@MainActivity, report.removedBytes)
                status.text = if (model == currentTtsModel()) {
                    "${report.label} removed · START to download again"
                } else {
                    "${report.label} removed"
                }
                Toast.makeText(
                    this@MainActivity,
                    "Removed ${report.label} ($removed). Other models were kept.",
                    Toast.LENGTH_LONG,
                ).show()
            }.onFailure { e ->
                status.text = "Model removal failed · ${failureMessage(e)}"
            }
            startButton.isEnabled = true
        }
    }

    private fun scheduleSelectedModelPreload(reason: String) {
        if (synthJob?.isActive == true || qnnCacheJob?.isActive == true ||
            cpuDiagnosticsJob?.isActive == true
        ) return
        val model = currentTtsModel()
        if (!ModelManager.areTtsModelsReady(this, model)) return

        val backend = currentBackend()
        val voice = currentVoice()
        val steps = currentSteps()
        val threads = currentThreads()
        val chunkCap = currentChunkCap()
        val preQueue = 1
        val gapMin = currentChunkGapMin()
        val gapMax = currentChunkGapMax()
        val trailingTrim = currentTrailingTrim()
        val fixedT = currentOriginalFixedT()
        val fixedL = currentOriginalFixedL()
        val pregen = effectivePregenForBackend(backend)
        val preloadKey = listOf(
            model.name, backend.name, voice, steps, threads, chunkCap,
            gapMin, gapMax, trailingTrim, fixedT, fixedL, pregen,
        ).joinToString("|")

        if (modelPreloadKey == preloadKey) {
            if (modelPreloadJob?.isActive == true) {
                Log.i("MainActivity", "[MODEL-PRELOAD] coalesce reason=$reason key=$preloadKey")
                return
            }
            if (synthesizer != null && !synthesizerStale) {
                Log.i("MainActivity", "[MODEL-PRELOAD] already-ready reason=$reason key=$preloadKey")
                return
            }
        }

        modelPreloadJob?.cancel()
        val token = ++modelPreloadGeneration
        modelPreloadKey = preloadKey
        val modelDir = ModelManager.modelDir(applicationContext, model).absolutePath
        val nativeDir = applicationInfo.nativeLibraryDir
        val accelCache = File(cacheDir, "accelerator_cache").apply { mkdirs() }.absolutePath

        modelPreloadJob = scope.launch {
            val result = runCatching {
                modelPreloadMutex.withLock {
                    if (token != modelPreloadGeneration) {
                        Log.i("MainActivity", "[MODEL-PRELOAD] skip stale-before-start=1 token=$token current=$modelPreloadGeneration")
                        return@withLock
                    }
                    withContext(Dispatchers.Default) {
                        val createStart = System.nanoTime()
                        var temp: SpeechSynthesizer? = null
                        try {
                        Log.i(
                            "MainActivity",
                            "[MODEL-PRELOAD] begin reason=$reason model=${model.name} backend=${backend.name} threads=$threads",
                        )
                        temp = SpeechSynthesizer(
                            SpeechSynthesizerConfig(
                                modelDir = modelDir,
                                voiceId = voice,
                                speed = 1.0f,
                                totalSteps = steps,
                                numThreads = threads,
                                chunkCap = chunkCap,
                                useNnapi = false,
                                backend = backend,
                                ttsModel = model,
                                preGenerationQueue = preQueue,
                                chunkGapMinMs = gapMin,
                                chunkGapMaxMs = gapMax,
                                trailingSilenceTrimMs = trailingTrim,
                                nativeLibraryDir = nativeDir,
                                acceleratorCacheDir = accelCache,
                                originalFixedTextT = fixedT,
                                originalFixedLatentL = fixedL,
                            )
                        )
                        val createdMs = (System.nanoTime() - createStart) / 1_000_000.0

                        // Creation removes most delegate/session cold start. A hidden
                        // one-step inference also forces lazy graph/session creation and
                        // XNNPACK weight packing before the user presses START.
                        val warmStart = System.nanoTime()
                        temp.setVoice(voice)
                        temp.setSpeed(1.0f)
                        temp.setPreGeneration(false)
                        temp.setTotalSteps(1)
                        // Multi-P uses this existing hidden one-step synth as the
                        // cheapest small-signature preload. A one-phoneme input
                        // selects the minimum T/L bucket in practice, warming the
                        // stage-shared packed-weight cache after construction instead
                        // of eagerly creating four delegated graphs in native ctor.
                        Log.i(
                            "MainActivity",
                            "[MODEL-PRELOAD][SMALL-SIGNATURE] text=a language=en steps=1 purpose=shared-weight-warm",
                        )
                        val warm = temp.synthesize("a", "en")
                        temp.setTotalSteps(steps)
                        temp.setPreGeneration(pregen)
                        val warmMs = (System.nanoTime() - warmStart) / 1_000_000.0

                        synchronized(synthesizerLock) {
                            if (token != modelPreloadGeneration) {
                                Log.i(
                                    "MainActivity",
                                    "[MODEL-PRELOAD] discard stale=1 token=$token current=$modelPreloadGeneration",
                                )
                            } else {
                                val old = synthesizer
                                synthesizer = temp
                                temp = null
                                synthesizerStale = false
                                engineInitSeconds = createdMs / 1000.0
                                runCatching { old?.close() }
                                Log.i(
                                    "MainActivity",
                                    "[MODEL-PRELOAD] ready model=${model.name} backend=${backend.name} " +
                                        "create_ms=${String.format(Locale.US, "%.1f", createdMs)} " +
                                        "warm_ms=${String.format(Locale.US, "%.1f", warmMs)} warm_bytes=${warm.pcm16.size}",
                                )
                            }
                        }
                        } finally {
                            runCatching { temp?.close() }
                        }
                    }
                }
            }
            if (token == modelPreloadGeneration) {
                result.onSuccess {
                    if (synthJob?.isActive != true && synthesizer != null && !synthesizerStale) {
                        status.text = "${ModelManager.modelLabel(model)} ready · preloaded"
                    }
                }.onFailure { e ->
                    if (e !is kotlinx.coroutines.CancellationException) {
                        Log.w("MainActivity", "[MODEL-PRELOAD] failed model=${model.name} backend=${backend.name}", e)
                        status.text = "${ModelManager.modelLabel(model)} preload failed · ${failureMessage(e)}"
                    }
                }
            }
        }
    }

    private fun ensureModel(force: Boolean = false) {
        if (modelJob?.isActive == true) return
        if (!force && ModelManager.areTtsModelsReady(this, currentTtsModel())) {
            ModelManager.syncSharedCustomVoices(applicationContext, currentTtsModel())
            status.text = "${ModelManager.modelLabel(currentTtsModel())} ready"
            refreshVoices()
            scheduleSelectedModelPreload("model-ready")
            return
        }
        modelJob = scope.launch {
            status.text = "Checking/downloading model… 0%"
            runCatching {
                ModelManager.ensureTtsModels(
                    applicationContext,
                    currentTtsModel(),
                ) { done, total, file ->
                    val percent = ((done * 100.0) / total.coerceAtLeast(1L)).coerceIn(0.0, 100.0).roundToInt()
                    runOnUiThread { status.text = "Checking/downloading model… $percent% · $file" }
                }
            }.onSuccess {
                ModelManager.syncSharedCustomVoices(applicationContext, currentTtsModel())
                status.text = "${ModelManager.modelLabel(currentTtsModel())} ready"
                refreshVoices()
                scheduleSelectedModelPreload("model-download-complete")
            }.onFailure { e -> status.text = "Model setup failed · ${e.message ?: e.javaClass.simpleName}" }
        }
    }

    private fun ensureModelAnd(action: () -> Unit) {
        if (ModelManager.areTtsModelsReady(this, currentTtsModel())) {
            ModelManager.syncSharedCustomVoices(applicationContext, currentTtsModel())
            action()
        } else {
            ensureModel()
            Toast.makeText(this, "Model files are not ready.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showCustomVoiceManager() {
        val files = ModelManager.customVoiceFiles(applicationContext)
        if (files.isEmpty()) { Toast.makeText(this, "No custom voices to remove.", Toast.LENGTH_SHORT).show(); return }
        val names = files.map { it.nameWithoutExtension.removePrefix("custom_") }.toTypedArray()
        AlertDialog.Builder(this).setTitle("Remove Custom Voice").setItems(names) { _, which ->
            val target = files[which]
            AlertDialog.Builder(this).setTitle("Confirm removal").setMessage("${target.nameWithoutExtension}?")
                .setNegativeButton("CANCEL", null).setPositiveButton("REMOVE") { _, _ ->
                    val wasSelected = currentVoice() == target.nameWithoutExtension
                    ModelManager.removeSharedCustomVoice(applicationContext, target.nameWithoutExtension)
                    if (wasSelected) TtsSettings.save(this, "F1", currentSpeed(), currentSteps(), currentThreads(), currentChunkMode(), currentManualChunkCap())
                    invalidateSynthesizer()
                    refreshVoices(if (wasSelected) "F1" else currentVoice())
                    Toast.makeText(this, "Removed", Toast.LENGTH_SHORT).show()
                }.show()
        }.setNegativeButton("CLOSE", null).show()
    }

    private fun importCustomVoice(uri: Uri) {
        val ttsModel = currentTtsModel()
        scope.launch(Dispatchers.IO) {
            try {
                val dir = ModelManager.sharedCustomVoiceDir(applicationContext)
                val rawName = contentResolver.query(uri, null, null, null, null)?.use { c ->
                    val idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0 && c.moveToFirst()) c.getString(idx) else null
                } ?: "custom_voice.json"
                val stem = rawName.substringBeforeLast('.').replace(Regex("[^A-Za-z0-9._-]+"), "_").trim('_').ifBlank { "voice" }
                val id = "custom_${stem.take(48)}"
                val out = File(dir, "$id.json")
                val text = contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
                    ?: throw IllegalArgumentException("Cannot read JSON.")
                val obj = JSONObject(text)
                if (!obj.has("style_ttl") || !obj.has("style_dp")) {
                    throw IllegalArgumentException("Invalid Supertonic-3 voice JSON: style_ttl/style_dp required.")
                }
                fun requireDims(key: String, expected: List<Int>) {
                    val dims = obj.getJSONObject(key).getJSONArray("dims")
                    val actual = List(dims.length()) { i -> dims.getInt(i) }
                    require(actual == expected) {
                        "Invalid $key dims: expected=${expected.joinToString("x")} actual=${actual.joinToString("x")}"
                    }
                }
                requireDims("style_ttl", listOf(1, 50, 256))
                requireDims("style_dp", listOf(1, 8, 16))
                out.writeText(text, Charsets.UTF_8)
                ModelManager.syncSharedCustomVoices(applicationContext)
                withContext(Dispatchers.Main) {
                    invalidateSynthesizer()
                    refreshVoices(id)
                    TtsSettings.save(this@MainActivity, id, currentSpeed(), currentSteps(), currentThreads(), currentChunkMode(), currentManualChunkCap())
                    Toast.makeText(this@MainActivity, "Custom Voice imported: $id", Toast.LENGTH_LONG).show()
                }
            } catch (t: Throwable) {
                withContext(Dispatchers.Main) { Toast.makeText(this@MainActivity, "Voice JSON import failed: ${t.message}", Toast.LENGTH_LONG).show() }
            }
        }
    }

    private fun importRules(uri: Uri) {
        scope.launch(Dispatchers.IO) {
            try {
                val text = contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
                    ?: throw IllegalArgumentException("Cannot read JSON.")
                val count = PronunciationRules.importJson(this@MainActivity, text)
                withContext(Dispatchers.Main) {
                    refreshRuleStatus()
                    Toast.makeText(this@MainActivity, "Pronunciation rules: ${count} imported", Toast.LENGTH_LONG).show()
                }
            } catch (t: Throwable) {
                withContext(Dispatchers.Main) { Toast.makeText(this@MainActivity, "Rule import failed: ${t.message}", Toast.LENGTH_LONG).show() }
            }
        }
    }

    private fun exportRules(uri: Uri) {
        runCatching {
            contentResolver.openOutputStream(uri)?.use { out ->
                out.write(PronunciationRules.toJson(this).toString(2).toByteArray(Charsets.UTF_8))
            }
            refreshRuleStatus()
            Toast.makeText(this, "Rules JSON saved", Toast.LENGTH_SHORT).show()
        }.onFailure { Toast.makeText(this, "Rule export failed: ${it.message}", Toast.LENGTH_LONG).show() }
    }

    private fun refreshRuleStatus() {
        if (::ruleStatus.isInitialized) ruleStatus.text = "Pronunciation rules: ${PronunciationRules.count(this)}"
    }


    private data class CpuDiagnosticResult(
        val name: String,
        val speed: Float,
        val pregen: Boolean,
        val totalMs: Double,
        val ttfaMs: Double,
        val chunks: Int,
        val pregenUsed: Int,
        val pregenWaitMs: Double,
        val maxChunkGapMs: Double,
        val workerThreads: Int,
        val veStepAvgMs: Double,
        val speedProcessMs: Double,
        val outputBytes: Long,
        val thermalStart: Int,
        val thermalEnd: Int,
        val batteryStartC: Double,
        val batteryEndC: Double,
        val profileRaw: String,
    )

    private data class ThermalSnapshot(val status: Int, val batteryC: Double)

    private val pregenBenchmarkText =
        "This fixed diagnostic passage is long enough to create multiple synthesis chunks. " +
        "Every run uses the same words so pre generation off and on are directly comparable."

    private val forcePregenText =
        "This deterministic pre generation trigger uses a small chunk cap to create several chunks every time."

    private fun cpuDiagnosticsBusy(): Boolean =
        cpuDiagnosticsJob?.isActive == true || synthJob?.isActive == true ||
            modelJob?.isActive == true || qnnCacheJob?.isActive == true

    private fun prepareCpuDiagnostics(model: TtsModel): Boolean {
        if (cpuDiagnosticsJob?.isActive == true) {
            Toast.makeText(this, "CPU diagnostics are already running.", Toast.LENGTH_SHORT).show()
            return false
        }
        if (synthJob?.isActive == true || modelJob?.isActive == true || qnnCacheJob?.isActive == true) {
            Toast.makeText(this, "Finish the current synthesis or setup first.", Toast.LENGTH_SHORT).show()
            return false
        }
        if (!ModelManager.areTtsModelsReady(this, model)) {
            Toast.makeText(this, "The selected model is not ready.", Toast.LENGTH_SHORT).show()
            return false
        }
        // Avoid keeping the normal engine plus a full-thread pregen worker pool resident at once.
        modelPreloadGeneration++
        modelPreloadJob?.cancel()
        val old = synthesizer
        synthesizer = null
        synthesizerStale = false
        runCatching { old?.close() }
        return true
    }

    private fun runPregenBenchmark() {
        val model = currentTtsModel()
        if (!prepareCpuDiagnostics(model)) return
        val voice = currentVoice()
        val steps = currentSteps()
        val threads = currentThreads()
        val gapMin = currentChunkGapMin()
        val gapMax = currentChunkGapMax()
        val trailingTrim = currentTrailingTrim()
        val fixedT = currentOriginalFixedT()
        val fixedL = currentOriginalFixedL()
        val modelDir = ModelManager.modelDir(applicationContext, model).absolutePath

        status.text = "CPU Diagnostics · Pregen Benchmark…"
        startButton.isEnabled = false
        cpuDiagnosticsJob = scope.launch {
            val result = runCatching {
                withContext(Dispatchers.Default) {
                    val synth = SpeechSynthesizer(
                        SpeechSynthesizerConfig(
                            modelDir = modelDir,
                            useNnapi = false,
                            backend = InferenceBackend.CPU_XNNPACK,
                            ttsModel = model,
                            voiceId = voice,
                            speed = 1.0f,
                            totalSteps = steps,
                            numThreads = threads,
                            chunkCap = 64,
                            preGenerationQueue = 1,
                            chunkGapMinMs = gapMin,
                            chunkGapMaxMs = gapMax,
                            trailingSilenceTrimMs = trailingTrim,
                            nativeLibraryDir = applicationInfo.nativeLibraryDir,
                            acceleratorCacheDir = File(cacheDir, "accelerator_cache").apply { mkdirs() }.absolutePath,
                            originalFixedTextT = fixedT,
                            originalFixedLatentL = fixedL,
                        )
                    )
                    cpuDiagnosticsSynthesizer = synth
                    try {
                        // One unreported OFF run warms model/delegate caches so A/B is not dominated by first-use setup.
                        runCpuDiagnosticCase(synth, "warmup", pregenBenchmarkText, 1.0f, false, 64, voice, steps, gapMin, gapMax, trailingTrim)
                        val rows = listOf(
                            runCpuDiagnosticCase(synth, "1.0x OFF", pregenBenchmarkText, 1.0f, false, 64, voice, steps, gapMin, gapMax, trailingTrim),
                            runCpuDiagnosticCase(synth, "1.0x ON cold", pregenBenchmarkText, 1.0f, true, 64, voice, steps, gapMin, gapMax, trailingTrim),
                            runCpuDiagnosticCase(synth, "1.0x ON warm", pregenBenchmarkText, 1.0f, true, 64, voice, steps, gapMin, gapMax, trailingTrim),
                            runCpuDiagnosticCase(synth, "1.5x OFF", pregenBenchmarkText, 1.5f, false, 64, voice, steps, gapMin, gapMax, trailingTrim),
                            runCpuDiagnosticCase(synth, "1.5x ON warm", pregenBenchmarkText, 1.5f, true, 64, voice, steps, gapMin, gapMax, trailingTrim),
                        )
                        formatPregenBenchmarkReport(model, rows)
                    } finally {
                        cpuDiagnosticsSynthesizer = null
                        runCatching { synth.close() }
                    }
                }
            }
            result.onSuccess { report ->
                status.text = "CPU Diagnostics · Pregen Benchmark complete"
                rtfView.text = report
                AlertDialog.Builder(this@MainActivity)
                    .setTitle("Pregen Benchmark")
                    .setMessage(report)
                    .setPositiveButton("CLOSE", null)
                    .show()
            }.onFailure { e ->
                status.text = "Pregen Benchmark failed · ${e.message ?: e.javaClass.simpleName}"
                AlertDialog.Builder(this@MainActivity)
                    .setTitle("Pregen Benchmark")
                    .setMessage("FAILED\n\n${e.stackTraceToString()}")
                    .setPositiveButton("CLOSE", null)
                    .show()
            }
            startButton.isEnabled = true
        }
    }

    private fun runForcePregenTest() {
        val model = currentTtsModel()
        if (!prepareCpuDiagnostics(model)) return
        val voice = currentVoice()
        val steps = currentSteps()
        val threads = currentThreads()
        val gapMin = currentChunkGapMin()
        val gapMax = currentChunkGapMax()
        val trailingTrim = currentTrailingTrim()
        val fixedT = currentOriginalFixedT()
        val fixedL = currentOriginalFixedL()
        val modelDir = ModelManager.modelDir(applicationContext, model).absolutePath

        status.text = "CPU Diagnostics · Force Pregen Test…"
        startButton.isEnabled = false
        cpuDiagnosticsJob = scope.launch {
            val result = runCatching {
                withContext(Dispatchers.Default) {
                    val synth = SpeechSynthesizer(
                        SpeechSynthesizerConfig(
                            modelDir = modelDir,
                            useNnapi = false,
                            backend = InferenceBackend.CPU_XNNPACK,
                            ttsModel = model,
                            voiceId = voice,
                            speed = 1.0f,
                            totalSteps = steps,
                            numThreads = threads,
                            chunkCap = 24,
                            preGenerationQueue = 1,
                            chunkGapMinMs = gapMin,
                            chunkGapMaxMs = gapMax,
                            trailingSilenceTrimMs = trailingTrim,
                            nativeLibraryDir = applicationInfo.nativeLibraryDir,
                            acceleratorCacheDir = File(cacheDir, "accelerator_cache").apply { mkdirs() }.absolutePath,
                            originalFixedTextT = fixedT,
                            originalFixedLatentL = fixedL,
                        )
                    )
                    cpuDiagnosticsSynthesizer = synth
                    try {
                        val row = runCpuDiagnosticCase(
                            synth = synth,
                            name = "FORCE 1.5x ON",
                            text = forcePregenText,
                            speed = 1.5f,
                            pregen = true,
                            chunkCap = 24,
                            voice = voice,
                            steps = steps,
                            gapMin = gapMin,
                            gapMax = gapMax,
                            trailingTrim = trailingTrim,
                        )
                        val pass = row.chunks >= 2 && row.workerThreads > 0 && row.pregenUsed > 0
                        buildString {
                            append("FORCE PREGEN TEST\n\n")
                            append("Result              ").append(if (pass) "PASS" else "FAIL").append('\n')
                            append("Model               ").append(ModelManager.modelLabel(model)).append('\n')
                            append("Backend             CPU\n")
                            append("Chunk cap           24 (diagnostic override)\n")
                            append("Speed               1.50x post-process\n")
                            append("Model speed         1.00x\n")
                            append("Chunks              ").append(row.chunks).append('\n')
                            append("Pregen used         ").append(row.pregenUsed).append('\n')
                            append("Worker threads      ").append(row.workerThreads).append('\n')
                            append("Pregen wait         ").append(fmtMs(row.pregenWaitMs)).append('\n')
                            append("Max chunk gap       ").append(fmtMs(row.maxChunkGapMs)).append('\n')
                            append("TTFA                ").append(fmtMs(row.ttfaMs)).append('\n')
                            append("Total               ").append(fmtMs(row.totalMs)).append('\n')
                            append("Sonic processing    ").append(fmtMs(row.speedProcessMs)).append('\n')
                            append("Thermal              ").append(thermalStatusName(row.thermalStart)).append(" -> ").append(thermalStatusName(row.thermalEnd)).append('\n')
                            append("Battery temp         ").append(String.format(Locale.US, "%.1f -> %.1f C", row.batteryStartC, row.batteryEndC)).append('\n')
                            if (maxOf(row.thermalStart, row.thermalEnd) >= PowerManager.THERMAL_STATUS_MODERATE) {
                                append("THERMAL WARNING     MODERATE or higher\n")
                            }
                            if (!pass) append("\nExpected chunks>=2, worker_threads>0 and pregen_used_chunks>0.\n")
                        }
                    } finally {
                        cpuDiagnosticsSynthesizer = null
                        runCatching { synth.close() }
                    }
                }
            }
            result.onSuccess { report ->
                status.text = "CPU Diagnostics · Force Pregen Test complete"
                rtfView.text = report
                AlertDialog.Builder(this@MainActivity)
                    .setTitle("Force Pregen Test")
                    .setMessage(report)
                    .setPositiveButton("CLOSE", null)
                    .show()
            }.onFailure { e ->
                status.text = "Force Pregen Test failed · ${e.message ?: e.javaClass.simpleName}"
                AlertDialog.Builder(this@MainActivity)
                    .setTitle("Force Pregen Test")
                    .setMessage("FAILED\n\n${e.stackTraceToString()}")
                    .setPositiveButton("CLOSE", null)
                    .show()
            }
            startButton.isEnabled = true
        }
    }

    private fun runCpuDiagnosticCase(
        synth: SpeechSynthesizer,
        name: String,
        text: String,
        speed: Float,
        pregen: Boolean,
        chunkCap: Int,
        voice: String,
        steps: Int,
        gapMin: Int,
        gapMax: Int,
        trailingTrim: Int,
    ): CpuDiagnosticResult {
        synth.setVoice(voice)
        synth.setSpeed(1.0f) // Never change model speed: direct accelerated generation can swallow syllables/words.
        synth.setTotalSteps(steps)
        synth.setChunkCap(chunkCap)
        synth.setPreGenerationQueue(1)
        synth.setPreGeneration(pregen)
        synth.setChunkGap(gapMin, gapMax)
        synth.setTrailingSilenceTrimMs(trailingTrim)

        val speedStream = if (kotlin.math.abs(speed - 1.0f) >= 0.001f) {
            AudioSpeedProcessor.Stream(synth.sampleRate, speed)
        } else null
        val thermalStart = thermalSnapshot()
        val started = System.nanoTime()
        var firstAudioNs = 0L
        var outputBytes = 0L
        var finalSeen = false
        synth.synthesizeStreaming(text, "en") { pcm, final ->
            var output = if (speedStream == null) pcm else speedStream.process(pcm, final = false)
            if (output.isNotEmpty()) {
                if (firstAudioNs == 0L) firstAudioNs = System.nanoTime()
                outputBytes += output.size
                // audioAvailable() in the real Android TTS service can block while its
                // playback queue consumes the submitted PCM.  Simulate that headroom
                // here so native pregen overlaps audio consumption instead of being
                // benchmarked against an unrealistically instantaneous callback.
                sleepForPcmPlayback(output.size, synth.sampleRate)
            }
            if (final && !finalSeen) {
                if (speedStream != null) {
                    output = speedStream.process(ByteArray(0), final = true)
                    if (output.isNotEmpty()) {
                        if (firstAudioNs == 0L) firstAudioNs = System.nanoTime()
                        outputBytes += output.size
                        sleepForPcmPlayback(output.size, synth.sampleRate)
                    }
                }
                finalSeen = true
            }
        }
        val finished = System.nanoTime()
        val profileRaw = synth.lastProfile()
        val profile = parseProfile(profileRaw)
        fun number(key: String): Double = (profile[key] as? Double) ?: 0.0
        fun integer(key: String): Int = number(key).roundToInt()
        val stepsList = profile["ve_steps"] as? List<*> ?: emptyList<Any>()
        val chunkCount = integer("chunks").coerceAtLeast(1)
        // Native profile merges per-step VE times across semantic chunks.  Divide the
        // eight merged step totals by semantic chunk count before reporting ms/step/chunk.
        val veAvg = stepsList.mapNotNull { it as? Double }.let { values ->
            if (values.isEmpty()) 0.0 else values.average() / chunkCount
        }
        val thermalEnd = thermalSnapshot()
        val row = CpuDiagnosticResult(
            name = name,
            speed = speed,
            pregen = pregen,
            totalMs = (finished - started) / 1_000_000.0,
            ttfaMs = if (firstAudioNs != 0L) (firstAudioNs - started) / 1_000_000.0 else 0.0,
            chunks = integer("chunks"),
            pregenUsed = integer("pregen_used_chunks"),
            pregenWaitMs = number("pregen_wait"),
            maxChunkGapMs = number("max_chunk_gap_ms"),
            workerThreads = integer("pregen_worker_threads"),
            veStepAvgMs = veAvg,
            speedProcessMs = speedStream?.processingMs ?: 0.0,
            outputBytes = outputBytes,
            thermalStart = thermalStart.status,
            thermalEnd = thermalEnd.status,
            batteryStartC = thermalStart.batteryC,
            batteryEndC = thermalEnd.batteryC,
            profileRaw = profileRaw,
        )
        Log.i(
            "CpuDiagnostics",
            "[CPU-DIAG] name=${row.name} speed=${row.speed} pregen=${row.pregen} total_ms=${String.format(Locale.US, "%.3f", row.totalMs)} " +
                "ttfa_ms=${String.format(Locale.US, "%.3f", row.ttfaMs)} chunks=${row.chunks} pregen_used=${row.pregenUsed} " +
                "pregen_wait_ms=${String.format(Locale.US, "%.3f", row.pregenWaitMs)} max_gap_ms=${String.format(Locale.US, "%.3f", row.maxChunkGapMs)} " +
                "worker_threads=${row.workerThreads} ve_step_avg_ms=${String.format(Locale.US, "%.3f", row.veStepAvgMs)} " +
                "model_speed=1.0 speed_stream=${if (speedStream == null) 0 else 1} speed_process_ms=${String.format(Locale.US, "%.3f", row.speedProcessMs)} output_bytes=${row.outputBytes}"
        )
        Log.i(
            "CpuDiagnostics",
            "[CPU-DIAG-THERMAL] name=${row.name} start=${thermalStatusName(row.thermalStart)} end=${thermalStatusName(row.thermalEnd)} " +
                "battery_start_c=${String.format(Locale.US, "%.1f", row.batteryStartC)} battery_end_c=${String.format(Locale.US, "%.1f", row.batteryEndC)} " +
                "warning=${if (maxOf(row.thermalStart, row.thermalEnd) >= PowerManager.THERMAL_STATUS_MODERATE) 1 else 0}",
        )
        return row
    }

    private fun formatPregenBenchmarkReport(model: TtsModel, rows: List<CpuDiagnosticResult>): String {
        val byName = rows.associateBy { it.name }
        fun line(row: CpuDiagnosticResult): String = String.format(
            Locale.US,
            "%-13s total %7.1f  TTFA %6.1f  gap %6.1f  wait %6.1f  VE %5.1f  chunks %d  used %d  wt %d  thermal %s→%s",
            row.name, row.totalMs, row.ttfaMs, row.maxChunkGapMs, row.pregenWaitMs,
            row.veStepAvgMs, row.chunks, row.pregenUsed, row.workerThreads,
            thermalStatusName(row.thermalStart), thermalStatusName(row.thermalEnd),
        )
        fun delta(on: CpuDiagnosticResult?, off: CpuDiagnosticResult?): String {
            if (on == null || off == null || off.totalMs <= 0.0) return "n/a"
            val totalPct = (on.totalMs - off.totalMs) * 100.0 / off.totalMs
            val gapPct = if (off.maxChunkGapMs > 0.0) (on.maxChunkGapMs - off.maxChunkGapMs) * 100.0 / off.maxChunkGapMs else 0.0
            return String.format(Locale.US, "total %+6.1f%% / max-gap %+6.1f%%", totalPct, gapPct)
        }
        return buildString {
            append("PREGEN BENCHMARK\n\n")
            append("Model: ").append(ModelManager.modelLabel(model)).append("\n")
            append("Backend: CPU\n")
            append("Model speed: fixed 1.00x\n")
            append("Chunk cap: 64\n\n")
            rows.forEach { append(line(it)).append('\n') }
            append("\nA/B\n")
            append("1.0x warm ON vs OFF  ").append(delta(byName["1.0x ON warm"], byName["1.0x OFF"])).append('\n')
            append("1.5x warm ON vs OFF  ").append(delta(byName["1.5x ON warm"], byName["1.5x OFF"])).append('\n')
            append("\nPASS signals: ON rows should have chunks>=2, used>=1 and worker threads>0.\n")
            val thermalWarn = rows.any { maxOf(it.thermalStart, it.thermalEnd) >= PowerManager.THERMAL_STATUS_MODERATE }
            if (thermalWarn) append("\nTHERMAL WARNING: at least one row reached MODERATE or higher.\n")
            append("REV32.6 bad reference: pregen wait about 968-992 ms.\n")
        }
    }

    private fun thermalSnapshot(): ThermalSnapshot {
        val thermal = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            getSystemService(PowerManager::class.java)?.currentThermalStatus ?: PowerManager.THERMAL_STATUS_NONE
        } else PowerManager.THERMAL_STATUS_NONE
        val battery = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val tempTenths = battery?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE) ?: Int.MIN_VALUE
        val batteryC = if (tempTenths == Int.MIN_VALUE) Double.NaN else tempTenths / 10.0
        return ThermalSnapshot(thermal, batteryC)
    }

    private fun thermalStatusName(status: Int): String = when (status) {
        PowerManager.THERMAL_STATUS_NONE -> "NONE"
        PowerManager.THERMAL_STATUS_LIGHT -> "LIGHT"
        PowerManager.THERMAL_STATUS_MODERATE -> "MODERATE"
        PowerManager.THERMAL_STATUS_SEVERE -> "SEVERE"
        PowerManager.THERMAL_STATUS_CRITICAL -> "CRITICAL"
        PowerManager.THERMAL_STATUS_EMERGENCY -> "EMERGENCY"
        PowerManager.THERMAL_STATUS_SHUTDOWN -> "SHUTDOWN"
        else -> "UNKNOWN($status)"
    }

    private fun sleepForPcmPlayback(byteCount: Int, sampleRate: Int) {
        if (byteCount <= 0 || sampleRate <= 0) return
        val nanos = (byteCount.toLong() / 2L) * 1_000_000_000L / sampleRate.toLong()
        val ms = nanos / 1_000_000L
        val ns = (nanos % 1_000_000L).toInt()
        if (ms > 0L || ns > 0) Thread.sleep(ms, ns)
    }

    private fun fmtMs(value: Double): String = String.format(Locale.US, "%.1f ms", value)

    private data class UiSynthesisOutput(
        val sampleRate: Int,
        val pcm16: ByteArray,
        val profile: String,
        val nativePcmBytes: Long,
        val speedAlreadyApplied: Boolean,
        val speedProcessMs: Double,
        val streamedPreview: Boolean,
    )

    /**
     * Direct START preview for CPU Pregen ON.
     *
     * Pregen only has a real overlap window when the streaming callback applies
     * playback/backpressure.  Merely collecting callback bytes instantly would make
     * the full-thread look-ahead worker race the foreground and can be slower than
     * non-streaming.  Feed an AudioTrack in blocking stream mode instead: this matches
     * the Android TTS service closely enough for the native one-chunk look-ahead to
     * overlap actual audio consumption, while still collecting exactly what the user
     * heard into generated.wav.
     */
    private fun synthesizeStreamingPreview(
        synth: SpeechSynthesizer,
        text: String,
        language: String,
        speed: Float,
    ): UiSynthesisOutput {
        val sampleRate = synth.sampleRate
        require(sampleRate > 0) { "Invalid synthesizer sample rate: $sampleRate" }

        val minBuffer = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        require(minBuffer > 0) { "AudioTrack min buffer query failed: $minBuffer" }
        // A small bounded playback queue is intentional.  WRITE_BLOCKING then gives
        // native pregen genuine playback headroom instead of an instant callback.
        val bufferBytes = maxOf(minBuffer, sampleRate / 4 * 2) // about 250 ms mono PCM16
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build(),
            )
            .setBufferSizeInBytes(bufferBytes)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        check(track.state == AudioTrack.STATE_INITIALIZED) { "AudioTrack failed to initialize" }

        val silenceCompressor = if (TtsSettings.internalSilenceCompression(this)) {
            InternalSilenceCompressor(sampleRate, TtsSettings.internalSilenceMaxPauseMs(this))
        } else null
        val speedStream = if (kotlin.math.abs(speed - 1.0f) >= 0.001f) {
            AudioSpeedProcessor.Stream(sampleRate, speed)
        } else null
        val chunks = ArrayList<ByteArray>()
        var outputBytes = 0
        var nativeBytes = 0L
        var finalSeen = false
        var framesWritten = 0L

        fun appendOutput(bytes: ByteArray) {
            if (bytes.isEmpty()) return
            check(outputBytes <= Int.MAX_VALUE - bytes.size) { "Preview PCM is too large" }
            chunks.add(bytes)
            outputBytes += bytes.size
            var offset = 0
            while (offset < bytes.size) {
                val written = track.write(bytes, offset, bytes.size - offset, AudioTrack.WRITE_BLOCKING)
                if (written < 0) throw IllegalStateException("AudioTrack write failed: $written")
                if (written == 0) {
                    if (previewAudioTrack !== track || track.playState != AudioTrack.PLAYSTATE_PLAYING) {
                        throw IllegalStateException("Streaming preview was stopped")
                    }
                    Thread.yield()
                    continue
                }
                offset += written
                framesWritten += written / 2L
            }
        }

        previewAudioTrack = track
        Log.i("MainActivity", "[UI-PREGEN-STREAM] begin speed=$speed sample_rate=$sampleRate buffer_bytes=$bufferBytes")
        try {
            track.play()
            synth.synthesizeStreaming(text, language) { pcm, final ->
                nativeBytes += pcm.size.toLong()
                val nativePost = silenceCompressor?.process(pcm, final = false) ?: pcm
                val output = speedStream?.process(nativePost, final = false) ?: nativePost
                appendOutput(output)
                if (final && !finalSeen) {
                    val silenceTail = silenceCompressor?.process(ByteArray(0), final = true) ?: ByteArray(0)
                    if (silenceTail.isNotEmpty()) {
                        appendOutput(speedStream?.process(silenceTail, final = false) ?: silenceTail)
                    }
                    if (speedStream != null) appendOutput(speedStream.process(ByteArray(0), final = true))
                    finalSeen = true
                }
            }

            // WRITE_BLOCKING leaves at most the AudioTrack queue pending.  Let the
            // last buffered speech drain before releasing the device so final phonemes
            // are not clipped.  The loop is bounded to avoid hanging on vendor audio bugs.
            val deadline = android.os.SystemClock.elapsedRealtime() + 1500L
            while (track.playState == AudioTrack.PLAYSTATE_PLAYING &&
                track.playbackHeadPosition.toLong() < framesWritten &&
                android.os.SystemClock.elapsedRealtime() < deadline
            ) {
                Thread.sleep(8L)
            }

            val merged = ByteArray(outputBytes)
            var dst = 0
            for (chunk in chunks) {
                chunk.copyInto(merged, dst)
                dst += chunk.size
            }
            val finalProfile = synth.lastProfile()
            Log.i("MainActivity", "[UI-PREGEN-STREAM] end native_bytes=$nativeBytes output_bytes=$outputBytes frames_written=$framesWritten profile=$finalProfile")
            return UiSynthesisOutput(
                sampleRate = sampleRate,
                pcm16 = merged,
                profile = finalProfile,
                nativePcmBytes = nativeBytes,
                speedAlreadyApplied = true,
                speedProcessMs = speedStream?.processingMs ?: 0.0,
                streamedPreview = true,
            )
        } finally {
            if (previewAudioTrack === track) previewAudioTrack = null
            runCatching { track.stop() }
            runCatching { track.flush() }
            track.release()
        }
    }

    private fun startSynthesis() {
        if (qnnCacheJob?.isActive == true) {
            Toast.makeText(this, "QNN cache pre-gen is running. Press STOP first.", Toast.LENGTH_SHORT).show()
            return
        }
        if (!ModelManager.areTtsModelsReady(this, currentTtsModel())) { ensureModel(); Toast.makeText(this, "Model files are not ready.", Toast.LENGTH_SHORT).show(); return }
        ModelManager.syncSharedCustomVoices(applicationContext, currentTtsModel())
        if (synthJob?.isActive == true) {
            Toast.makeText(this, "Synthesis is already running. Press STOP first.", Toast.LENGTH_SHORT).show()
            return
        }
        persistUiSettings(); player?.stop(); player?.release(); player = null
        val original = textInput.text?.toString()?.trim().orEmpty()
        if (original.isBlank()) { Toast.makeText(this, "Enter text first.", Toast.LENGTH_SHORT).show(); return }
        val text = PronunciationRules.apply(this, original)
        // Snapshot all engine-creation/runtime settings on the UI thread. Spinner
        // changes during a request may mark the engine stale for the *next* request,
        // but must never change the configuration of the request already running.
        val voice = currentVoice()
        val speed = currentSpeed()
        val stepCount = currentSteps()
        val threads = currentThreads()
        val chunkCap = currentChunkCap()
        val preQueue = 1
        val gapMin = currentChunkGapMin()
        val gapMax = currentChunkGapMax()
        val trailingTrim = currentTrailingTrim()
        val fixedT = currentOriginalFixedT()
        val fixedL = currentOriginalFixedL()
        val ttsModel = currentTtsModel()
        val backend = currentBackend()
        val preGeneration = effectivePregenForBackend(backend)
        val selectedLang = currentLanguage()
        val lang = if (selectedLang == "na" ||
            (allowNaCheck.isChecked && PronunciationRules.isMixedScript(text))) "na" else selectedLang
        status.text = "Synthesizing… ${ModelManager.modelLabel(ttsModel)} · ${backendDisplayName(backend)}"
        rtfView.text = ""
        startButton.isEnabled = false
        synthJob = scope.launch {
            // If model selection already started a warm preload, reuse that fully
            // initialized engine instead of racing it with a second cold creation.
            modelPreloadJob?.takeIf { it.isActive }?.join()
            val requestStartNs = System.nanoTime()
            var workerDispatchWaitMs = 0.0
            var engineAcquireMs = 0.0
            var nativeSettingsMs = 0.0
            var sdkSynthesizeMs = 0.0
            var speedDispatchWaitMs = 0.0
            runCatching {
                val value = withContext(Dispatchers.Default) {
                    workerDispatchWaitMs = (System.nanoTime() - requestStartNs) / 1_000_000.0
                    val engineStartNs = System.nanoTime()
                    val synth = getOrCreateSynthesizer(
                        dir = ModelManager.modelDir(applicationContext, ttsModel).absolutePath,
                        ttsModel = ttsModel,
                        backend = backend,
                        voice = voice,
                        steps = stepCount,
                        threads = threads,
                        chunkCap = chunkCap,
                        preQueue = preQueue,
                        gapMin = gapMin,
                        gapMax = gapMax,
                        trailingTrim = trailingTrim,
                        fixedT = fixedT,
                        fixedL = fixedL,
                    )
                    engineAcquireMs = (System.nanoTime() - engineStartNs) / 1_000_000.0

                    val settingsStartNs = System.nanoTime()
                    synth.setVoice(voice)
                    synth.setSpeed(1.0f)
                    synth.setTotalSteps(stepCount)
                    synth.setPreGeneration(preGeneration)
                    synth.setPreGenerationQueue(preQueue)
                    synth.setChunkGap(gapMin, gapMax)
                    synth.setTrailingSilenceTrimMs(trailingTrim)
                    nativeSettingsMs = (System.nanoTime() - settingsStartNs) / 1_000_000.0

                    val sdkStartNs = System.nanoTime()
                    val result = if (preGeneration && backend != InferenceBackend.QUALCOMM_NPU) {
                        synthesizeStreamingPreview(synth, text, lang, speed)
                    } else {
                        val whole = synth.synthesize(text, lang)
                        val postSilencePcm = if (TtsSettings.internalSilenceCompression(this@MainActivity)) {
                            InternalSilenceCompressor(
                                whole.sampleRate,
                                TtsSettings.internalSilenceMaxPauseMs(this@MainActivity),
                            ).process(whole.pcm16, final = true)
                        } else whole.pcm16
                        UiSynthesisOutput(
                            sampleRate = whole.sampleRate,
                            pcm16 = postSilencePcm,
                            profile = whole.profile,
                            nativePcmBytes = whole.pcm16.size.toLong(),
                            speedAlreadyApplied = false,
                            speedProcessMs = 0.0,
                            streamedPreview = false,
                        )
                    }
                    sdkSynthesizeMs = (System.nanoTime() - sdkStartNs) / 1_000_000.0
                    result
                }

                val nativeAudioDuration = value.nativePcmBytes / 2.0 / value.sampleRate
                val speedDispatchStartNs = System.nanoTime()
                val adjusted = if (value.speedAlreadyApplied) {
                    AudioSpeedProcessor.Result(value.pcm16, value.speedProcessMs)
                } else {
                    withContext(Dispatchers.Default) {
                        speedDispatchWaitMs = (System.nanoTime() - speedDispatchStartNs) / 1_000_000.0
                        AudioSpeedProcessor.apply(value.pcm16, value.sampleRate, speed)
                    }
                }
                val preWavElapsed = (System.nanoTime() - requestStartNs) / 1_000_000_000.0
                val duration = adjusted.pcm16.size / 2.0 / value.sampleRate
                val profile = parseProfile(value.profile)
                val fallback = (profile["accelerator_fallback"] as? String)
                    ?.takeUnless { it == "none" }
                val nativeTotal = (profile["total"] as? Double)?.div(1000.0) ?: preWavElapsed
                val nativeRtf = if (nativeAudioDuration > 0.0) nativeTotal / nativeAudioDuration else 0.0
                // Authoritative standalone-app performance marker. The old SD690 logger
                // only captured SpeechTextToSpeechService SYNTH_PROFILE lines, so UI tests
                // could show an RTF on screen while leaving SYNTH_PROFILES.txt empty.
                Log.i(
                    "MainActivity",
                    String.format(
                        Locale.US,
                        "[UI-SYNTH-PROFILE] model=%s backend=%s steps=%d threads=%d chunk=%d " +
                            "native_total_ms=%.3f native_audio_sec=%.4f native_rtf=%.4f " +
                            "sdk_synth_ms=%.3f pre_wav_ms=%.3f profile=%s",
                        ttsModel.name,
                        backend.name,
                        stepCount,
                        threads,
                        chunkCap,
                        nativeTotal * 1000.0,
                        nativeAudioDuration,
                        nativeRtf,
                        sdkSynthesizeMs,
                        preWavElapsed * 1000.0,
                        value.profile,
                    ),
                )
                val wav = File(filesDir, "generated.wav")
                val wavStartNs = System.nanoTime()
                writeWav(wav, adjusted.pcm16, value.sampleRate, 1)
                val wavWriteMs = (System.nanoTime() - wavStartNs) / 1_000_000.0
                val totalToWav = (System.nanoTime() - requestStartNs) / 1_000_000_000.0
                withContext(Dispatchers.Main) {
                    status.text = if (fallback == null) {
                        String.format(
                            Locale.US,
                            "Done · RTF %.3f · %.2fs synth · %.2fs audio",
                            nativeRtf,
                            nativeTotal,
                            nativeAudioDuration,
                        )
                    } else {
                        String.format(
                            Locale.US,
                            "CPU fallback · RTF %.3f · %.2fs synth · %.2fs audio",
                            nativeRtf,
                            nativeTotal,
                            nativeAudioDuration,
                        )
                    }
                    rtfView.text = formatProfile(
                        profile = profile,
                        elapsed = preWavElapsed,
                        rtf = if (duration > 0) preWavElapsed / duration else 0.0,
                        duration = duration,
                        nativeAudioDuration = nativeAudioDuration,
                        nativeTotal = nativeTotal,
                        speedProcessMs = adjusted.processingMs,
                        appliedSpeed = speed,
                        appliedVoice = voice,
                        appliedSteps = stepCount,
                        lang = lang,
                        workerDispatchWaitMs = workerDispatchWaitMs,
                        engineAcquireMs = engineAcquireMs,
                        nativeSettingsMs = nativeSettingsMs,
                        sdkSynthesizeMs = sdkSynthesizeMs,
                        speedDispatchWaitMs = speedDispatchWaitMs,
                        wavWriteMs = wavWriteMs,
                        totalToWav = totalToWav,
                    )
                    playButton.isEnabled = true
                    // Pregen ON already previewed the utterance through AudioTrack while
                    // it was generated.  Keep PLAY available for replay, but do not play
                    // the complete WAV a second time automatically.
                    if (!value.streamedPreview) playLast()
                }
            }.onFailure { e ->
                Log.e("MainActivity", "TTS synthesis failed", e)
                withContext(Dispatchers.Main) {
                    status.text = "Synthesis failed · ${failureMessage(e)}"
                }
            }
            withContext(Dispatchers.Main) { startButton.isEnabled = true }
        }
    }

    private fun getOrCreateSynthesizer(
        dir: String,
        ttsModel: TtsModel,
        backend: InferenceBackend,
        voice: String,
        steps: Int,
        threads: Int,
        chunkCap: Int,
        preQueue: Int,
        gapMin: Int,
        gapMax: Int,
        trailingTrim: Int,
        fixedT: Int,
        fixedL: Int,
    ): SpeechSynthesizer = synchronized(synthesizerLock) {
        synthesizer?.takeIf { !synthesizerStale }?.let { return@synchronized it }
        if (synthesizerStale) {
            val old = synthesizer
            synthesizer = null
            synthesizerStale = false
            // This runs on the synthesis worker before a new request starts, never
            // concurrently with the old request. It fixes the destroyed ORT mutex crash.
            runCatching { old?.close() }
        }
        val started = TimeSource.Monotonic.markNow()
        SpeechSynthesizer(SpeechSynthesizerConfig(
            modelDir = dir, voiceId = voice, speed = 1.0f, totalSteps = steps,
            numThreads = threads, chunkCap = chunkCap, useNnapi = false,
            backend = backend, ttsModel = ttsModel,
            preGenerationQueue = preQueue, chunkGapMinMs = gapMin,
            chunkGapMaxMs = gapMax, trailingSilenceTrimMs = trailingTrim,
            nativeLibraryDir = applicationInfo.nativeLibraryDir,
            acceleratorCacheDir = File(cacheDir, "accelerator_cache").apply { mkdirs() }.absolutePath,
            enableDeepProfiler = TtsSettings.deepProfiler(this@MainActivity),
            originalFixedTextT = fixedT,
            originalFixedLatentL = fixedL,
        )).also {
            engineInitSeconds = started.elapsedNow().inWholeMilliseconds / 1000.0
            synthesizer = it
            synthesizerStale = false
        }
    }

    private fun parseProfile(profile: String): Map<String, Any> {
        val out = mutableMapOf<String, Any>()
        if (profile.isBlank()) return out
        for (entry in profile.split(';')) {
            val parts = entry.split('=', limit = 2); if (parts.size != 2) continue
            out[parts[0]] = parts[1].toDoubleOrNull() ?: parts[1]
        }
        val stepsString = profile.substringAfter("ve_steps=", "").substringBefore(';')
        if (stepsString.isNotBlank()) out["ve_steps"] = stepsString.split(',').mapNotNull { it.toDoubleOrNull() }
        return out
    }

    private fun formatProfile(profile: Map<String, Any>, elapsed: Double, rtf: Double, duration: Double,
                              nativeAudioDuration: Double, nativeTotal: Double,
                              speedProcessMs: Double, appliedSpeed: Float, appliedVoice: String, appliedSteps: Int, lang: String,
                              workerDispatchWaitMs: Double, engineAcquireMs: Double, nativeSettingsMs: Double,
                              sdkSynthesizeMs: Double, speedDispatchWaitMs: Double, wavWriteMs: Double,
                              totalToWav: Double): String {
        fun ms(key: String) = String.format(Locale.US, "%.1f ms", (profile[key] as? Double ?: 0.0))
        val stepValues = profile["ve_steps"] as? List<*> ?: emptyList<Any>()
        val sb = StringBuilder("PERFORMANCE PROFILE\n")
        sb.append("Engine init            ").append(String.format(Locale.US, "%.3f s", engineInitSeconds)).append('\n')
        val durationProfileMs = (profile["dp"] as? Double) ?: (profile["duration"] as? Double) ?: 0.0
        sb.append("Duration Predictor     ").append(String.format(Locale.US, "%.1f ms", durationProfileMs)).append('\n')
        sb.append("Text Encoder           ").append(ms("encoder")).append('\n')
        stepValues.forEachIndexed { i, v -> sb.append("VE Step ${i + 1}".padEnd(23)).append(String.format(Locale.US, "%.1f ms", (v as? Double ?: 0.0))).append('\n') }
        if (stepValues.isEmpty() && profile.containsKey("vector")) {
            sb.append("Vector Estimator total ").append(ms("vector")).append('\n')
        }
        sb.append("Vocoder                ").append(ms("vocoder")).append('\n')
        sb.append("Tensor buffer/copy     ").append(ms("tensor_copy")).append('\n')
        sb.append("Core chunking          ").append(ms("chunking")).append('\n')
        sb.append("Core token process     ").append(ms("token_process")).append('\n')
        sb.append("Core latent setup      ").append(ms("latent_setup")).append('\n')
        sb.append("Core append/crossfade  ").append(ms("append")).append('\n')
        sb.append("Core stream emit       ").append(ms("stream_emit")).append('\n')
        sb.append("Core pregen setup      ").append(ms("pregen_setup")).append('\n')
        sb.append("Core pregen launch     ").append(ms("pregen_launch")).append('\n')
        sb.append("Core pregen wait       ").append(ms("pregen_wait")).append('\n')
        sb.append("Core pregen cleanup    ").append(ms("pregen_cleanup")).append('\n')
        sb.append("Duration packing probe ").append(ms("packing_probe")).append('\n')
        sb.append("Core final postprocess ").append(ms("final_postprocess")).append('\n')
        sb.append("Native total           ").append(String.format(Locale.US, "%.3f s", nativeTotal)).append('\n')
        sb.append("End-to-end              ").append(String.format(Locale.US, "%.3f s", elapsed)).append('\n')
        sb.append("Audio duration (final)  ").append(String.format(Locale.US, "%.3f s", duration)).append('\n')
        sb.append("Audio duration (model)  ").append(String.format(Locale.US, "%.3f s", nativeAudioDuration)).append('\n')
        sb.append("Native RTF              ").append(String.format(Locale.US, "%.3f", if (nativeAudioDuration > 0) nativeTotal / nativeAudioDuration else 0.0)).append('\n')
        sb.append("End-to-end RTF          ").append(String.format(Locale.US, "%.3f", rtf)).append('\n')
        sb.append("--- DIAGNOSTIC BREAKDOWN ---\n")
        sb.append("Worker dispatch wait   ").append(String.format(Locale.US, "%.1f ms", workerDispatchWaitMs)).append('\n')
        sb.append("Engine acquire/init     ").append(String.format(Locale.US, "%.1f ms", engineAcquireMs)).append('\n')
        sb.append("Native settings JNI     ").append(String.format(Locale.US, "%.1f ms", nativeSettingsMs)).append('\n')
        sb.append("SDK synthesize call     ").append(String.format(Locale.US, "%.1f ms", sdkSynthesizeMs)).append('\n')
        sb.append("JNI mutex wait          ").append(ms("jni_lock_wait")).append('\n')
        sb.append("JNI arg conversion      ").append(ms("jni_arg_convert")).append('\n')
        sb.append("JNI core call           ").append(ms("jni_core")).append('\n')
        sb.append("JNI PCM f32->s16        ").append(ms("jni_pcm_convert")).append('\n')
        sb.append("JNI ByteArray alloc     ").append(ms("jni_bytearray_alloc")).append('\n')
        sb.append("JNI ByteArray copy      ").append(ms("jni_bytearray_copy")).append('\n')
        sb.append("JNI total               ").append(ms("jni_total")).append('\n')
        sb.append("JNI PCM samples         ").append(profile["jni_pcm_samples"] ?: "?").append('\n')
        sb.append("Speed dispatch wait     ").append(String.format(Locale.US, "%.1f ms", speedDispatchWaitMs)).append('\n')
        sb.append("WAV write               ").append(String.format(Locale.US, "%.1f ms", wavWriteMs)).append('\n')
        sb.append("Total incl. WAV         ").append(String.format(Locale.US, "%.3f s", totalToWav)).append('\n')
        val profileTtfa = (profile["ttfa_ms"] as? Double) ?: (profile["ttfa"] as? Double) ?: 0.0
        sb.append("TTFA (engine stream)    ").append(String.format(Locale.US, "%.1f ms", profileTtfa)).append('\n')
        sb.append("Speed processing        ").append(String.format(Locale.US, "%.1f ms", speedProcessMs)).append('\n')
        sb.append("Internal silence        ").append(
            if (TtsSettings.internalSilenceCompression(this)) "ON · ${TtsSettings.internalSilenceMaxPauseMs(this)} ms @1x" else "OFF"
        ).append('\n')
        sb.append("Applied voice           ").append(appliedVoice).append('\n')
        sb.append("Applied speed           ").append(String.format(Locale.US, "%.2f", appliedSpeed)).append('\n')
        sb.append("Applied steps           ").append(appliedSteps).append('\n')
        sb.append("Language                ").append(lang).append('\n')
        sb.append("Chunk cap               ").append(
            if (currentTtsModel().isOnnx)
                "Auto (ONNX internal)"
            else profile["chunk_cap"] ?: currentChunkCap()
        ).append('\n')
        sb.append("Chunks                  ").append(profile["chunks"] ?: "0").append('\n')
        sb.append("Truncated chunks        ").append(profile["truncated_chunks"] ?: "0").append('\n')
        sb.append("Chunk silence           ").append(profile["chunk_silence_ms"] ?: "?").append(" ms\n")
        sb.append("Max chunk gap           ").append(profile["max_chunk_gap_ms"] ?: "?").append(" ms\n")
        sb.append("Avg chunk gap           ").append(profile["avg_chunk_gap_ms"] ?: "?").append(" ms\n")
        sb.append("Pre-generation         ").append(profile["pregen"] ?: if (TtsSettings.preGeneration(this)) "on" else "off").append("\n")
        sb.append("Pre-gen                 ").append(if (effectivePregenForBackend(currentBackend())) "ON · 1 look-ahead" else "OFF").append("\n")
        sb.append("Gap min / max target    ").append(profile["chunk_gap_min_ms"] ?: currentChunkGapMin()).append(" / ").append(profile["chunk_gap_max_ms"] ?: currentChunkGapMax()).append(" ms\n")
        sb.append("Gap over max count      ").append(profile["chunk_gap_over_max_count"] ?: "0").append("\n")
        sb.append("Pre-generated chunks   ").append(profile["pregen_used_chunks"] ?: "0").append("\n")
        sb.append("Pre-gen worker threads ").append(profile["pregen_worker_threads"] ?: "0").append("\n")
        sb.append("Pre-gen worker budget  ").append(profile["pregen_worker_budget"] ?: "?").append("\n")
        sb.append("Pre-gen discarded      ").append(profile["pregen_discarded_chunks"] ?: "0").append("\n")
        if (profile.containsKey("packing_original_chunks") || profile.containsKey("packing_final_chunks")) {
            sb.append("Packing chunks          ").append(profile["packing_original_chunks"] ?: "?")
                .append(" -> ").append(profile["packing_final_chunks"] ?: "?").append("\n")
            sb.append("Packing probes/splits   ").append(profile["packing_probe_count"] ?: "?")
                .append(" / ").append(profile["packing_splits"] ?: "?").append("\n")
            sb.append("Latent fill             ").append(profile["latent_fill_pct"] ?: "?").append(" %\n")
        }
        sb.append("Audio peak              ").append(profile["peak"] ?: "?").append('\n')
        sb.append("Audio RMS               ").append(profile["rms"] ?: "?").append('\n')
        sb.append("Leading silence         ").append(profile["lead_silence_ms"] ?: "?").append(" ms\n")
        sb.append("Trailing silence        ").append(profile["trail_silence_ms"] ?: "?").append(" ms\n")
        sb.append("Model                   ").append(profile["model"] ?: currentTtsModel().name).append('\n')
        sb.append("Backend report          ").append(profile["backend"] ?: currentBackend().name).append('\n')
        sb.append("Requested backend       ").append(profile["requested_backend"] ?: currentBackend().name).append('\n')
        sb.append("Active backend          ").append(profile["active_backend"] ?: currentBackend().name).append('\n')
        sb.append("Fallback reason         ").append(profile["accelerator_fallback"] ?: "none").append('\n')
        if (profile.containsKey("original_fixed_t")) {
            sb.append("NPU Max T / L           ").append(profile["original_fixed_t"]).append(" / ").append(profile["original_fixed_l"]).append('\n')
            if (profile.containsKey("original_bucket_t_grid")) {
                sb.append("NPU T buckets           ").append(profile["original_bucket_t_grid"]).append('\n')
                sb.append("NPU L buckets           ").append(profile["original_bucket_l_grid"]).append('\n')
                sb.append("Buckets used            ").append(profile["original_used_buckets"] ?: "?").append('\n')
            }
            sb.append("QNN ctx DP / Encoder    ").append(profile["original_qnn_context_duration"] ?: "?").append(" / ").append(profile["original_qnn_context_encoder"] ?: "?").append('\n')
            sb.append("QNN ctx VE / Vocoder    ").append(profile["original_qnn_context_vector"] ?: "?").append(" / ").append(profile["original_qnn_context_vocoder"] ?: "?").append('\n')
            if (profile.containsKey("vector_tensor_reuse")) {
                sb.append("VE tensor reuse         ").append(if (profile["vector_tensor_reuse"] == "1") "ON" else profile["vector_tensor_reuse"]).append('\n')
            }
            if (profile.containsKey("qnn_shared_memory_allocator")) {
                sb.append("QNN shared / I-O QDQ    ").append(profile["qnn_shared_memory_allocator"]).append(" / ").append(profile["qnn_graph_io_qdq"] ?: "?").append('\n')
                sb.append("QNN finalize mode       ").append(profile["qnn_finalization_mode"] ?: "?").append('\n')
            }
            sb.append("Original actual T max   ").append(profile["original_max_actual_t"] ?: "?").append('\n')
            sb.append("Original actual L max   ").append(profile["original_max_actual_l"] ?: "?").append('\n')
            sb.append("Original T padding      ").append(profile["original_t_padding_pct"] ?: "?").append(" %\n")
            sb.append("Original L padding      ").append(profile["original_l_padding_pct"] ?: "?").append(" %\n")
            sb.append("Original fixed chunks   ").append(profile["original_chunks"] ?: "?").append('\n')
        }
        sb.append("CPU threads             ").append(profile["threads"] ?: currentThreads()).append('\n')
        sb.append("Stage threads D/E/V/Voc ").append(profile["threads_duration"] ?: "?").append(" / ")
            .append(profile["threads_encoder"] ?: "?").append(" / ")
            .append(profile["threads_vector"] ?: "?").append(" / ")
            .append(profile["threads_vocoder"] ?: "?").append('\n')
        sb.append("ORT shared pool         ").append(profile["ort_shared_pool"] ?: "?").append('\n')
        sb.append("XNN weight cache        ").append(profile["xnnpack_weight_cache"] ?: "?").append('\n')
        sb.append("CPU affinity            ").append(profile["cpu_affinity"] ?: "?")
        return sb.toString()
    }

    private fun playLast() {
        val wav = File(filesDir, "generated.wav")
        if (!wav.exists()) { Toast.makeText(this, "Generate audio first.", Toast.LENGTH_SHORT).show(); return }
        player?.stop(); player?.release()
        player = MediaPlayer().apply {
            setDataSource(wav.absolutePath)
            setOnCompletionListener { it.release(); if (player === it) player = null }
            prepare(); start()
        }
    }

    private fun stopAll() {
        synthJob?.cancel()
        modelPreloadGeneration++
        modelPreloadJob?.cancel()
        synthesizer?.stop()
        qnnCacheJob?.cancel()
        cpuDiagnosticsJob?.cancel()
        cpuDiagnosticsSynthesizer?.stop()
        qnnCacheSynthesizer?.stop()
        player?.stop()
        player?.release()
        player = null
        val preview = previewAudioTrack
        previewAudioTrack = null
        runCatching { preview?.pause() }
        runCatching { preview?.flush() }
        runCatching { preview?.stop() }
    }
    private fun saveLast() {
        val wav = File(filesDir, "generated.wav")
        if (!wav.exists()) { Toast.makeText(this, "Generate audio first.", Toast.LENGTH_SHORT).show(); return }
        saveWavLauncher.launch("supertonic_tts.wav")
    }
    private fun shareLast() {
        val wav = File(filesDir, "generated.wav")
        if (!wav.exists()) { Toast.makeText(this, "Generate audio first.", Toast.LENGTH_SHORT).show(); return }
        val uri = androidx.core.content.FileProvider.getUriForFile(this, "com.supertonic.tts.fileprovider", wav)
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
            type = "audio/wav"; putExtra(Intent.EXTRA_STREAM, uri); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }, "Share audio"))
    }
    private fun writeWav(file: File, pcm16: ByteArray, sampleRate: Int, channels: Int) {
        val byteRate = sampleRate * channels * 2; val blockAlign = channels * 2
        FileOutputStream(file).use { out ->
            fun ascii(s: String) = out.write(s.toByteArray(Charsets.US_ASCII))
            fun int32(v: Int) = out.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(v).array())
            fun int16(v: Int) = out.write(ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(v.toShort()).array())
            ascii("RIFF"); int32(36 + pcm16.size); ascii("WAVE"); ascii("fmt "); int32(16); int16(1); int16(channels); int32(sampleRate); int32(byteRate); int16(blockAlign); int16(16); ascii("data"); int32(pcm16.size); out.write(pcm16)
        }
    }
    override fun onDestroy() {
        player?.stop(); player?.release(); player = null
        modelPreloadGeneration++
        modelPreloadJob?.cancel()
        val old = synthesizer
        synthesizer = null
        runCatching { old?.stop() }
        val running = synthJob
        running?.cancel()
        if (running?.isActive == true) {
            running.invokeOnCompletion { runCatching { old?.close() } }
        } else {
            runCatching { old?.close() }
        }
        qnnCacheJob?.cancel()
        cpuDiagnosticsJob?.cancel()
        val diagSynth = cpuDiagnosticsSynthesizer
        cpuDiagnosticsSynthesizer = null
        runCatching { diagSynth?.stop() }
        runCatching { diagSynth?.close() }
        val cacheSynth = qnnCacheSynthesizer
        qnnCacheSynthesizer = null
        runCatching { cacheSynth?.stop() }
        runCatching { cacheSynth?.close() }
        scope.cancel()
        super.onDestroy()
    }
}
