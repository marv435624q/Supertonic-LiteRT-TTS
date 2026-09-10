package audio.soniqo.speech.rules

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.regex.PatternSyntaxException

/** DevGitPit-compatible pronunciation/regex dictionary. */
object PronunciationRules {
    private const val PREFS = "supertonic_pronunciation"
    private const val KEY_RULES = "rules_json"

    data class Rule(
        val term: String,
        val replacement: String,
        val ignoreCase: Boolean = true,
        val isRegex: Boolean = false,
        val enabled: Boolean = true,
    )

    fun load(context: Context): List<Rule> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_RULES, "[]") ?: "[]"
        return parse(raw)
    }

    fun count(context: Context): Int = load(context).size

    fun save(context: Context, rules: List<Rule>): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_RULES, toJson(rules).toString()).commit()

    fun importJson(context: Context, raw: String): Int {
        val incoming = parse(raw)
        val merged = LinkedHashMap<String, Rule>()
        for (r in load(context)) merged[key(r)] = r
        for (r in incoming) merged[key(r)] = r
        save(context, merged.values.toList())
        return incoming.size
    }

    fun toJson(context: Context): JSONArray = toJson(load(context))

    fun apply(context: Context, text: String): String {
        var out = text
        for (rule in load(context).filter { it.enabled }) {
            try {
                val regex = if (rule.isRegex) rule.term else Regex.escape(rule.term)
                val options = if (rule.ignoreCase) setOf(RegexOption.IGNORE_CASE) else emptySet()
                out = Regex(regex, options).replace(out, rule.replacement)
            } catch (_: PatternSyntaxException) {
                // Invalid imported rules are ignored rather than breaking TTS.
            } catch (_: IllegalArgumentException) {
                // Invalid replacement backreferences or patterns are ignored.
            }
        }
        return out
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

    private fun parse(raw: String): List<Rule> {
        return try {
            val root = JSONArray(raw)
            parseArray(root)
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
            val term = o.optString("term", o.optString("word", "")).trim()
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
