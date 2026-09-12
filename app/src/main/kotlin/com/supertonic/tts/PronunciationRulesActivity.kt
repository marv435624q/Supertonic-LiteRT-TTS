package com.supertonic.tts

import android.app.AlertDialog
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import audio.soniqo.speech.rules.PronunciationRules
import kotlin.math.roundToInt

/** Compact in-app pronunciation/regex manager matching the main screen. */
class PronunciationRulesActivity : AppCompatActivity() {
    private val purple = Color.rgb(103, 58, 183)
    private val purpleDark = Color.rgb(75, 35, 145)
    private val purpleSoft = Color.rgb(246, 241, 255)
    private val page = Color.rgb(250, 248, 253)
    private val surface = Color.WHITE
    private val border = Color.rgb(224, 217, 232)
    private val textPrimary = Color.rgb(35, 31, 40)
    private val textSecondary = Color.rgb(104, 96, 112)
    private val danger = Color.rgb(220, 50, 47)

    private lateinit var ruleList: LinearLayout
    private lateinit var summary: TextView

    private val exportRules = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        if (uri == null) return@registerForActivityResult
        runCatching {
            contentResolver.openOutputStream(uri)?.use {
                it.write(PronunciationRules.toJson(this).toString(2).toByteArray(Charsets.UTF_8))
            } ?: error("Could not open output file")
        }.onSuccess {
            Toast.makeText(this, "Rules exported", Toast.LENGTH_SHORT).show()
        }.onFailure {
            Toast.makeText(this, "Export failed: ${it.message}", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        refresh()
    }

    override fun onResume() {
        super.onResume()
        if (::ruleList.isInitialized) refresh()
    }

    private fun buildUi() {
        window.statusBarColor = surface
        window.navigationBarColor = surface
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(7), dp(2), dp(7), dp(10))
            setBackgroundColor(page)
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(2))
        }
        header.addView(TextView(this).apply {
            text = "‹"
            textSize = 34f
            gravity = Gravity.CENTER
            setTextColor(purpleDark)
            isClickable = true
            isFocusable = true
            setOnClickListener { finish() }
        }, LinearLayout.LayoutParams(dp(38), dp(42)))
        header.addView(TextView(this).apply {
            text = "Pronunciation Rules"
            textSize = 21f
            setTextColor(purpleDark)
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER_VERTICAL
        }, LinearLayout.LayoutParams(0, dp(42), 1f))
        header.addView(actionButton("+ Add", primary = true) { editRule(-1, null) },
            LinearLayout.LayoutParams(dp(76), dp(34)))
        root.addView(header)

        val intro = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(8), dp(10), dp(8))
            background = rounded(surface)
        }
        summary = TextView(this).apply {
            textSize = 14f
            setTextColor(textPrimary)
            setTypeface(typeface, Typeface.BOLD)
        }
        intro.addView(summary)
        intro.addView(TextView(this).apply {
            text = "Applied from top to bottom to TTS input. Disabled or invalid rules are skipped."
            textSize = 12f
            setTextColor(textSecondary)
            setPadding(0, dp(3), 0, dp(7))
        })
        intro.addView(horizontalRow(
            actionButton("Restore defaults") { confirmReset() },
            actionButton("Export JSON") { exportRules.launch("supertonic-pronunciation-rules.json") },
        ))
        root.addView(intro, LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, 0, 0, dp(4)) })

        ruleList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(ruleList)

        val contentFrame = android.widget.FrameLayout(this).apply {
            setBackgroundColor(page)
            val contentWidth = if (resources.configuration.screenWidthDp > 700) dp(700) else -1
            addView(root, android.widget.FrameLayout.LayoutParams(contentWidth, -2).apply {
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            })
        }
        setContentView(ScrollView(this).apply {
            isFillViewport = true
            setBackgroundColor(page)
            addView(contentFrame, android.widget.FrameLayout.LayoutParams(-1, -2))
        })
    }

    private fun refresh() {
        val rules = PronunciationRules.load(this)
        val enabled = rules.count { it.enabled }
        summary.text = "${rules.size} ${if (rules.size == 1) "rule" else "rules"} · $enabled enabled"
        ruleList.removeAllViews()

        if (rules.isEmpty()) {
            ruleList.addView(TextView(this).apply {
                text = "No rules yet. Add a text replacement or regular expression."
                textSize = 14f
                gravity = Gravity.CENTER
                setTextColor(textSecondary)
                setPadding(dp(16), dp(30), dp(16), dp(30))
                background = rounded(surface)
            }, LinearLayout.LayoutParams(-1, -2))
            return
        }

        rules.forEachIndexed { index, rule ->
            ruleList.addView(ruleCard(index, rule), LinearLayout.LayoutParams(-1, -2).apply {
                setMargins(0, dp(3), 0, dp(3))
            })
        }
    }

    private fun ruleCard(index: Int, rule: PronunciationRules.Rule): View {
        val invalid = PronunciationRules.validationError(rule)
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(8), dp(10), dp(8))
            background = rounded(
                if (rule.enabled) surface else Color.rgb(247, 245, 249),
                strokeColor = if (invalid == null) border else Color.rgb(235, 126, 126),
            )

            addView(LinearLayout(this@PronunciationRulesActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(TextView(this@PronunciationRulesActivity).apply {
                    text = "${index + 1}"
                    textSize = 12f
                    gravity = Gravity.CENTER
                    setTextColor(Color.WHITE)
                    setTypeface(typeface, Typeface.BOLD)
                    background = rounded(purple, 9f, null)
                }, LinearLayout.LayoutParams(dp(24), dp(24)))
                addView(TextView(this@PronunciationRulesActivity).apply {
                    text = if (rule.isRegex) "Regular expression" else "Text replacement"
                    textSize = 14f
                    setTextColor(textPrimary)
                    setTypeface(typeface, Typeface.BOLD)
                    setPadding(dp(7), 0, 0, 0)
                }, LinearLayout.LayoutParams(0, -2, 1f))
                addView(actionButton(if (rule.enabled) "Enabled" else "Disabled") {
                    PronunciationRules.update(this@PronunciationRulesActivity, index, rule.copy(enabled = !rule.enabled))
                    refresh()
                }, LinearLayout.LayoutParams(dp(78), dp(30)))
            })

            addView(fieldLabel("Pattern"))
            addView(codeValue(rule.term))
            addView(fieldLabel("Replacement"))
            addView(codeValue(if (rule.replacement.isEmpty()) "Delete match" else rule.replacement))

            if (invalid != null) {
                addView(TextView(this@PronunciationRulesActivity).apply {
                    text = "Invalid · $invalid"
                    textSize = 11f
                    setTextColor(danger)
                    setPadding(dp(2), dp(4), dp(2), 0)
                })
            } else {
                addView(TextView(this@PronunciationRulesActivity).apply {
                    text = buildString {
                        append(if (rule.ignoreCase) "Ignore case" else "Case sensitive")
                        append(" · ")
                        append(if (rule.enabled) "Active" else "Not applied")
                    }
                    textSize = 11f
                    setTextColor(textSecondary)
                    setPadding(dp(2), dp(4), dp(2), 0)
                })
            }

            addView(horizontalRow(
                actionButton("↑") {
                    PronunciationRules.move(this@PronunciationRulesActivity, index, -1)
                    refresh()
                }.apply { isEnabled = index > 0 },
                actionButton("↓") {
                    PronunciationRules.move(this@PronunciationRulesActivity, index, 1)
                    refresh()
                }.apply { isEnabled = index < PronunciationRules.count(this@PronunciationRulesActivity) - 1 },
                actionButton("Edit") { editRule(index, rule) },
                actionButton("Delete", dangerStyle = true) { confirmDelete(index) },
            ), LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, dp(7), 0, 0) })
        }
    }

    private fun editRule(index: Int, existing: PronunciationRules.Rule?) {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(4), dp(20), 0)
        }
        val term = EditText(this).apply {
            hint = "Text or regex pattern"
            setSingleLine(false)
            minLines = 2
            maxLines = 5
            setText(existing?.term.orEmpty())
            setPadding(dp(8), dp(6), dp(8), dp(6))
            background = rounded(surface, 10f)
        }
        val replacement = EditText(this).apply {
            hint = "Replacement (empty = delete)"
            setSingleLine(false)
            maxLines = 3
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            setText(existing?.replacement.orEmpty())
            setPadding(dp(8), dp(6), dp(8), dp(6))
            background = rounded(surface, 10f)
        }
        val regex = CheckBox(this).apply {
            text = "Regular expression"
            isChecked = existing?.isRegex ?: false
            buttonTintList = android.content.res.ColorStateList.valueOf(purple)
        }
        val ignoreCase = CheckBox(this).apply {
            text = "Ignore case"
            isChecked = existing?.ignoreCase ?: false
            buttonTintList = android.content.res.ColorStateList.valueOf(purple)
        }
        val error = TextView(this).apply {
            textSize = 12f
            setTextColor(danger)
            visibility = View.GONE
        }
        box.addView(fieldLabel("Pattern"))
        box.addView(term, LinearLayout.LayoutParams(-1, -2))
        box.addView(fieldLabel("Replacement"))
        box.addView(replacement, LinearLayout.LayoutParams(-1, -2))
        box.addView(regex)
        box.addView(ignoreCase)
        box.addView(error)

        val dialog = AlertDialog.Builder(this)
            .setTitle(if (index < 0) "Add rule" else "Edit rule")
            .setView(box)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Save", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val rawTerm = term.text.toString()
                val rule = PronunciationRules.Rule(
                    term = if (regex.isChecked) rawTerm else rawTerm.trim(),
                    replacement = replacement.text.toString(),
                    ignoreCase = ignoreCase.isChecked,
                    isRegex = regex.isChecked,
                    enabled = existing?.enabled ?: true,
                )
                val validation = PronunciationRules.validationError(rule)
                if (validation != null) {
                    error.text = validation
                    error.visibility = View.VISIBLE
                    return@setOnClickListener
                }
                val saved = if (index < 0) {
                    PronunciationRules.add(this, rule)
                } else {
                    PronunciationRules.update(this, index, rule)
                }
                if (!saved) {
                    error.text = "Save failed."
                    error.visibility = View.VISIBLE
                    return@setOnClickListener
                }
                dialog.dismiss()
                refresh()
            }
        }
        dialog.show()
    }

    private fun confirmDelete(index: Int) {
        AlertDialog.Builder(this)
            .setTitle("Delete rule?")
            .setMessage("This cannot be undone.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Delete") { _, _ ->
                PronunciationRules.delete(this, index)
                refresh()
            }
            .show()
    }

    private fun confirmReset() {
        AlertDialog.Builder(this)
            .setTitle("Restore defaults?")
            .setMessage("All current rules will be replaced with the safe whitespace-normalization rule.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Restore") { _, _ ->
                PronunciationRules.save(this, PronunciationRules.defaults())
                refresh()
            }
            .show()
    }

    private fun fieldLabel(value: String) = TextView(this).apply {
        text = value
        textSize = 11f
        setTextColor(textSecondary)
        setTypeface(typeface, Typeface.BOLD)
        setPadding(dp(2), dp(6), dp(2), dp(2))
    }

    private fun codeValue(value: String) = TextView(this).apply {
        text = value
        textSize = 13f
        setTextColor(textPrimary)
        typeface = Typeface.MONOSPACE
        setTextIsSelectable(true)
        setPadding(dp(8), dp(5), dp(8), dp(5))
        background = rounded(purpleSoft, 9f, Color.rgb(230, 221, 242))
    }

    private fun horizontalRow(vararg views: View) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        views.forEachIndexed { index, view ->
            addView(view, LinearLayout.LayoutParams(0, dp(32), 1f).apply {
                if (index > 0) setMargins(dp(4), 0, 0, 0)
            })
        }
    }

    private fun actionButton(
        label: String,
        primary: Boolean = false,
        dangerStyle: Boolean = false,
        action: () -> Unit,
    ) = Button(this).apply {
        text = label
        textSize = 12f
        isAllCaps = false
        minWidth = 0
        minimumWidth = 0
        minHeight = dp(30)
        minimumHeight = dp(30)
        setPadding(dp(4), 0, dp(4), 0)
        setTextColor(
            when {
                primary -> Color.WHITE
                dangerStyle -> danger
                else -> purpleDark
            },
        )
        background = rounded(
            when {
                primary -> purple
                dangerStyle -> Color.rgb(255, 248, 248)
                else -> surface
            },
            10f,
            when {
                primary -> null
                dangerStyle -> Color.rgb(245, 190, 190)
                else -> border
            },
        )
        setOnClickListener { action() }
    }

    private fun rounded(
        fill: Int,
        radius: Float = 14f,
        strokeColor: Int? = border,
        strokeWidth: Int = 1,
    ) = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        setColor(fill)
        cornerRadius = dp(radius.toInt()).toFloat()
        if (strokeColor != null && strokeWidth > 0) setStroke(dp(strokeWidth), strokeColor)
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).roundToInt()
}
