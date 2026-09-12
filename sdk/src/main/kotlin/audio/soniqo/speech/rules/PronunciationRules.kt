package audio.soniqo.speech.rules

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** DevGitPit-compatible pronunciation/regex dictionary. */
object PronunciationRules {
    private const val PREFS = "supertonic_pronunciation"
    private const val KEY_RULES = "rules_json"
    private const val KEY_DEFAULTS_VERSION = "defaults_version"
    private const val DEFAULTS_VERSION = 3

    data class Rule(
        val term: String,
        val replacement: String,
        val ignoreCase: Boolean = true,
        val isRegex: Boolean = false,
        val enabled: Boolean = true,
    )

    data class ApplyResult(
        val text: String,
        val totalRules: Int,
        val enabledRules: Int,
    )

    private data class CompiledRule(
        val replacement: String,
        val regex: Regex,
    )

    private data class Snapshot(
        val raw: String,
        val rules: List<Rule>,
        val compiled: List<CompiledRule>,
    )

    @Volatile private var cached: Snapshot? = null

    /**
     * Conservative, language-neutral TTS cleanup derived from NovelRegEx's useful
     * default categories. These rules preserve words and symbols: they remove only
     * invisible formatting characters, normalize numeric grouping/spacing, and
     * give the tokenizer boundaries around compact units and symbols.
     *
     * Korean-only rewrites such as "3.14 -> 삼점일사" or "$ -> 달러" do not belong
     * in an engine-wide default because this TTS also serves English, Japanese,
     * Chinese, and other languages.
     */
    fun defaults(): List<Rule> = listOf(
        Rule(
            // Do not remove ZWNJ/ZWJ: they can be meaningful in Persian and Indic text.
            term = "[\\u00AD\\u200B\\u2060\\uFEFF]",
            replacement = "",
            ignoreCase = false,
            isRegex = true,
        ),
        Rule(
            // 1,234,567 -> 1234567, without touching ordinary prose commas.
            term = "(?<=\\p{N}),(?=\\p{N}{3}(?:\\D|$))",
            replacement = "",
            ignoreCase = false,
            isRegex = true,
        ),
        Rule(
            // 3/4 -> 3 / 4. Keep operand order language-neutral.
            term = "(?<=\\p{N})\\s*/\\s*(?=\\p{N})",
            replacement = " / ",
            ignoreCase = false,
            isRegex = true,
        ),
        Rule(
            // 12kg, 3.14km, 500ml: preserve the unit and add a tokenizer boundary.
            term = "(?<=\\p{N})(?=(?i:kg|km|cm|mm|ml|g|m|l)\\b)",
            replacement = " ",
            ignoreCase = false,
            isRegex = true,
        ),
        Rule(
            term = "(?<=\\p{N})(?=[%％])",
            replacement = " ",
            ignoreCase = false,
            isRegex = true,
        ),
        Rule(
            // Support both prefix and suffix currency notation without translating it.
            term = "(?<=[\\$¥€₩￦£])(?=\\p{N})|(?<=\\p{N})(?=[\\$¥€₩￦£])",
            replacement = " ",
            ignoreCase = false,
            isRegex = true,
        ),
        Rule(
            term = "[\\s\\u00A0\\u1680\\u2000-\\u200A\\u202F\\u205F\\u3000]+",
            replacement = " ",
            ignoreCase = false,
            isRegex = true,
        ),
    )

    fun load(context: Context): List<Rule> = snapshot(context).rules

    fun count(context: Context): Int = snapshot(context).rules.size

    fun save(context: Context, rules: List<Rule>): Boolean {
        val raw = toJson(rules).toString()
        val saved = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_RULES, raw)
            .putInt(KEY_DEFAULTS_VERSION, DEFAULTS_VERSION)
            .commit()
        if (saved) cached = null
        return saved
    }

    fun importJson(context: Context, raw: String): Int {
        val incoming = parse(raw)
        val merged = LinkedHashMap<String, Rule>()
        for (r in load(context)) merged[key(r)] = r
        for (r in incoming) merged[key(r)] = r
        save(context, merged.values.toList())
        return incoming.size
    }

    fun toJson(context: Context): JSONArray = toJson(load(context))

    fun apply(context: Context, text: String): String = applyAndCount(context, text).text

    /** Apply a single cached rules snapshot so synthesis does not parse it twice for logging. */
    fun applyAndCount(context: Context, text: String): ApplyResult {
        val current = snapshot(context)
        var out = text
        for (rule in current.compiled) {
            try {
                out = rule.regex.replace(out, rule.replacement)
            } catch (_: IllegalArgumentException) {
                // Invalid imported replacement backreferences are ignored.
            }
        }
        return ApplyResult(
            text = out,
            totalRules = current.rules.size,
            enabledRules = current.compiled.size,
        )
    }

    /** Returns null when a rule can be saved safely, otherwise a user-facing error. */
    fun validationError(rule: Rule): String? {
        if (rule.term.isEmpty()) return "Pattern cannot be empty."
        return try {
            val pattern = if (rule.isRegex) rule.term else Regex.escape(rule.term)
            val options = if (rule.ignoreCase) setOf(RegexOption.IGNORE_CASE) else emptySet()
            Regex(pattern, options)
            // The empty alternative guarantees a match while preserving the original
            // capture numbering, so invalid $1/${name} replacements are caught too.
            Regex("(?:$pattern)|(?:)", options).replaceFirst("", rule.replacement)
            null
        } catch (t: IllegalArgumentException) {
            t.message?.takeIf { it.isNotBlank() } ?: "Invalid regular expression or replacement."
        }
    }

    fun isMixedScript(text: String): Boolean {
        var hasHangul = false
        var hasLatin = false
        var hasCjk = false
        for (c in text) {
            when {
                c in '\uAC00'..'\uD7A3' || c in '\u1100'..'\u11FF' -> hasHangul = true
                c in 'A'..'Z' || c in 'a'..'z' -> hasLatin = true
                c in '\u3040'..'\u30FF' || c in '\u4E00'..'\u9FFF' -> hasCjk = true
            }
        }
        return (hasHangul && hasLatin) || (hasHangul && hasCjk) || (hasLatin && hasCjk)
    }

    private fun snapshot(context: Context): Snapshot {
        val raw = currentRaw(context)
        cached?.takeIf { it.raw == raw }?.let { return it }
        return synchronized(this) {
            cached?.takeIf { it.raw == raw } ?: buildSnapshot(raw).also { cached = it }
        }
    }

    private fun currentRaw(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!prefs.contains(KEY_RULES)) {
            val raw = toJson(defaults()).toString()
            check(prefs.edit()
                .putString(KEY_RULES, raw)
                .putInt(KEY_DEFAULTS_VERSION, DEFAULTS_VERSION)
                .commit()) { "Failed to seed pronunciation defaults" }
            return raw
        }

        val raw = prefs.getString(KEY_RULES, "[]") ?: "[]"
        if (prefs.getInt(KEY_DEFAULTS_VERSION, 0) >= DEFAULTS_VERSION) return raw

        // Migrate only the exact harmful legacy reset set. Never append defaults to
        // or rewrite a user's custom dictionary.
        val rules = parse(raw)
        val legacyWhitespace = legacyWhitespaceDefaults()
        val migratedRaw = if (
            rules == legacyDefaults() ||
            rules == legacyWhitespace ||
            rules == legacyDefaults() + legacyWhitespace ||
            rules == previousDefaultsV2()
        ) {
            toJson(defaults()).toString()
        } else {
            raw
        }
        check(prefs.edit()
            .putString(KEY_RULES, migratedRaw)
            .putInt(KEY_DEFAULTS_VERSION, DEFAULTS_VERSION)
            .commit()) { "Failed to migrate pronunciation defaults" }
        cached = null
        return migratedRaw
    }

    private fun buildSnapshot(raw: String): Snapshot {
        val rules = parse(raw)
        val compiled = ArrayList<CompiledRule>(rules.size)
        for (rule in rules) {
            if (!rule.enabled) continue
            if (validationError(rule) != null) continue
            try {
                val pattern = if (rule.isRegex) rule.term else Regex.escape(rule.term)
                val options = if (rule.ignoreCase) setOf(RegexOption.IGNORE_CASE) else emptySet()
                compiled += CompiledRule(rule.replacement, Regex(pattern, options))
            } catch (_: IllegalArgumentException) {
                // Invalid imported patterns are visible in the editor but skipped by TTS.
            }
        }
        return Snapshot(raw, rules, compiled)
    }

    private fun legacyDefaults(): List<Rule> = listOf(
        Rule("커버\\s*(?:접기/보기)", "", true, true),
        Rule("[一-龥]", "", true, true),
        Rule("[a-zA-Z0-9]{15,}", "", true, true),
    )

    private fun legacyWhitespaceDefaults(): List<Rule> = listOf(
        Rule("[\r\n\t]+", " ", false, true),
        Rule("[\u00A0\u2007\u202F]+", " ", false, true),
        Rule(" {2,}", " ", false, true),
    )

    private fun previousDefaultsV2(): List<Rule> = listOf(
        Rule(
            term = "[\\s\\u00A0\\u1680\\u2000-\\u200A\\u202F\\u205F\\u3000]+",
            replacement = " ",
            ignoreCase = false,
            isRegex = true,
        ),
    )

    private fun parse(raw: String): List<Rule> {
        return try {
            parseArray(JSONArray(raw))
        } catch (_: Exception) {
            try {
                val obj = JSONObject(raw)
                parseArray(obj.optJSONArray("rules") ?: JSONArray())
            } catch (_: Exception) {
                emptyList()
            }
        }
    }

    private fun parseArray(arr: JSONArray): List<Rule> {
        val out = mutableListOf<Rule>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val isRegex = o.optBoolean("isRegex", false)
            val rawTerm = o.optString("term", o.optString("word", ""))
            // Leading/trailing whitespace can be semantically significant in regex.
            val term = if (isRegex) rawTerm else rawTerm.trim()
            val replacement = o.optString(
                "replacement",
                o.optString("pronunciation", o.optString("ipa", "")),
            )
            if (term.isEmpty()) continue
            out += Rule(
                term = term,
                replacement = replacement,
                ignoreCase = o.optBoolean("ignoreCase", true),
                isRegex = isRegex,
                enabled = o.optBoolean("enabled", true),
            )
        }
        return out
    }

    private fun toJson(rules: List<Rule>): JSONArray = JSONArray().apply {
        rules.forEach { r ->
            put(JSONObject().apply {
                put("term", r.term)
                put("replacement", r.replacement)
                put("ignoreCase", r.ignoreCase)
                put("isRegex", r.isRegex)
                put("enabled", r.enabled)
            })
        }
    }

    private fun key(r: Rule): String = "${r.term}\u0000${r.replacement}\u0000${r.ignoreCase}\u0000${r.isRegex}"

    fun delete(context: Context, index: Int): Boolean {
        val rules = load(context).toMutableList()
        if (index !in rules.indices) return false
        rules.removeAt(index)
        return save(context, rules)
    }

    fun update(context: Context, index: Int, rule: Rule): Boolean {
        val rules = load(context).toMutableList()
        if (index !in rules.indices) return false
        rules[index] = rule
        return save(context, rules)
    }

    fun move(context: Context, index: Int, delta: Int): Boolean {
        val rules = load(context).toMutableList()
        val target = index + delta
        if (index !in rules.indices || target !in rules.indices) return false
        val item = rules.removeAt(index)
        rules.add(target, item)
        return save(context, rules)
    }

    fun add(context: Context, rule: Rule): Boolean = save(context, load(context) + rule)
}
