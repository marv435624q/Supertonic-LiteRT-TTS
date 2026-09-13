package audio.soniqo.speech.rules

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.regex.Matcher
import java.util.regex.Pattern

/** DevGitPit-compatible pronunciation/regex dictionary. */
object PronunciationRules {
    private const val PREFS = "supertonic_pronunciation"
    private const val KEY_RULES = "rules_json"
    private const val KEY_DEFAULTS_VERSION = "defaults_version"
    private const val DEFAULTS_VERSION = 4
    private const val NUMBER_TOKEN = "(?:[0-9]{1,3}(?:,[0-9]{3})+|[0-9]+)(?:\\.[0-9]+)?"
    private val KOREAN_NUMBER_MACRO = Pattern.compile("\\$\\{ko-number:(\\d+)\\}")
    private val VALID_NUMBER =
        Pattern.compile("^[+-]?(?:[0-9]{1,3}(?:,[0-9]{3})+|[0-9]+)(?:\\.[0-9]+)?$")
    private val DIGIT_NAMES = arrayOf("영", "일", "이", "삼", "사", "오", "육", "칠", "팔", "구")
    private val SMALL_UNITS = arrayOf("", "십", "백", "천")
    private val LARGE_UNITS = arrayOf("", "만", "억", "조", "경", "해", "자", "양", "구", "간", "정", "재", "극")

    data class Rule(
        val term: String,
        val replacement: String,
        val ignoreCase: Boolean = true,
        val isRegex: Boolean = false,
        val enabled: Boolean = true,
        val name: String = "",
    )

    data class ApplyResult(
        val text: String,
        val totalRules: Int,
        val enabledRules: Int,
    )

    private data class CompiledRule(
        val replacement: String,
        val pattern: Pattern,
        val useKoreanNumberMacro: Boolean,
    )

    private data class Snapshot(
        val raw: String,
        val rules: List<Rule>,
        val compiled: List<CompiledRule>,
    )

    @Volatile private var cached: Snapshot? = null

    /** NovelRegEx's speech-normalization defaults, in the original execution order. */
    fun defaults(): List<Rule> = listOf(
        Rule(
            name = "제로폭 문자 제거",
            term = "[\\u200B\\u200C\\u200D\\u2060\\uFEFF]",
            replacement = "",
            ignoreCase = false,
            isRegex = true,
        ),
        Rule(
            name = "특수 공백을 일반 공백으로",
            term = "[\\u00A0\\u2007\\u202F\\u3000]+",
            replacement = " ",
            ignoreCase = false,
            isRegex = true,
        ),
        Rule(
            name = "탭/줄바꿈을 공백으로",
            term = "[\\t\\r\\n\\u0085\\u2028\\u2029]+",
            replacement = " ",
            ignoreCase = false,
            isRegex = true,
        ),
        Rule(
            name = "쉼표 소수점 읽기 (기본 꺼짐)",
            term = "([0-9]+),([0-9]+)",
            replacement = "$1점$2",
            ignoreCase = false,
            isRegex = true,
            enabled = false,
        ),
        Rule(
            name = "분수 읽기",
            term = "(?<![0-9.,])($NUMBER_TOKEN)\\s*/\\s*($NUMBER_TOKEN)(?![0-9.,])",
            replacement = "\${ko-number:2}분의\${ko-number:1}",
            ignoreCase = false,
            isRegex = true,
        ),
        Rule(
            name = "퍼센트(%) 읽기",
            term = "(?<![0-9.,])($NUMBER_TOKEN)\\s*%",
            replacement = "\${ko-number:1}퍼센트",
            ignoreCase = false,
            isRegex = true,
        ),
        Rule(
            name = "달러(\$) 앞표기 읽기",
            term = "\\$\\s*($NUMBER_TOKEN)",
            replacement = "\${ko-number:1}달러",
            ignoreCase = false,
            isRegex = true,
        ),
        Rule(
            name = "달러(\$) 뒤표기 읽기",
            term = "(?<![0-9.,])($NUMBER_TOKEN)\\s*\\$",
            replacement = "\${ko-number:1}달러",
            ignoreCase = false,
            isRegex = true,
        ),
        Rule(
            name = "엔(¥) 앞표기 읽기",
            term = "¥\\s*($NUMBER_TOKEN)",
            replacement = "\${ko-number:1}엔",
            ignoreCase = false,
            isRegex = true,
        ),
        Rule(
            name = "엔(¥) 뒤표기 읽기",
            term = "(?<![0-9.,])($NUMBER_TOKEN)\\s*¥",
            replacement = "\${ko-number:1}엔",
            ignoreCase = false,
            isRegex = true,
        ),
        Rule(
            name = "유로(€) 앞표기 읽기",
            term = "€\\s*($NUMBER_TOKEN)",
            replacement = "\${ko-number:1}유로",
            ignoreCase = false,
            isRegex = true,
        ),
        Rule(
            name = "유로(€) 뒤표기 읽기",
            term = "(?<![0-9.,])($NUMBER_TOKEN)\\s*€",
            replacement = "\${ko-number:1}유로",
            ignoreCase = false,
            isRegex = true,
        ),
        unitRule("킬로그램(kg) 읽기", "kg", "킬로그램"),
        unitRule("킬로미터(km) 읽기", "km", "킬로미터"),
        unitRule("센티미터(cm) 읽기", "cm", "센티미터"),
        unitRule("밀리미터(mm) 읽기", "mm", "밀리미터"),
        unitRule("밀리리터(ml) 읽기", "ml", "밀리리터"),
        unitRule("미터(m) 읽기", "m", "미터"),
        unitRule("그램(g) 읽기", "g", "그램"),
        unitRule("리터(l) 읽기", "l", "리터"),
        Rule(
            name = "소수점 읽기",
            term = "(?<![0-9.\$¥€])((?:[0-9]{1,3}(?:,[0-9]{3})+|[0-9]+)\\.[0-9]+)(?![0-9.A-Za-z%\$¥€])",
            replacement = "\${ko-number:1}",
            ignoreCase = false,
            isRegex = true,
        ),
        Rule(
            name = "천 단위 숫자 읽기",
            term = "(?<![0-9,\$¥€])([0-9]{1,3}(?:,[0-9]{3})+)(?![0-9,A-Za-z%\$¥€])",
            replacement = "\${ko-number:1}",
            ignoreCase = false,
            isRegex = true,
        ),
        Rule(
            name = "연속 공백 정리",
            term = "[\\s\\p{Z}]{2,}",
            replacement = " ",
            ignoreCase = false,
            isRegex = true,
        ),
    )

    private fun unitRule(name: String, symbol: String, koreanName: String): Rule = Rule(
        name = name,
        term = "(?<![0-9.,])($NUMBER_TOKEN)\\s*$symbol(?![A-Za-z])",
        replacement = "\${ko-number:1}$koreanName",
        ignoreCase = true,
        isRegex = true,
    )

    private fun patternFlags(ignoreCase: Boolean): Int =
        if (ignoreCase) Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE else Pattern.UNICODE_CASE

    private fun replaceWithKoreanNumber(
        pattern: Pattern,
        input: String,
        replacement: String,
    ): String {
        val matcher = pattern.matcher(input)
        val output = StringBuffer()
        while (matcher.find()) {
            val expanded = expandKoreanNumberMacros(replacement, matcher) ?: return input
            matcher.appendReplacement(output, expanded)
        }
        matcher.appendTail(output)
        return output.toString()
    }

    private fun expandKoreanNumberMacros(template: String, match: Matcher): String? {
        val macroMatcher = KOREAN_NUMBER_MACRO.matcher(template)
        val result = StringBuilder(template.length + 16)
        var cursor = 0
        while (macroMatcher.find()) {
            result.append(template, cursor, macroMatcher.start())
            val groupIndex = macroMatcher.group(1).toIntOrNull() ?: return null
            if (groupIndex < 0 || groupIndex > match.groupCount()) return null
            result.append(Matcher.quoteReplacement(toKoreanNumber(match.group(groupIndex).orEmpty())))
            cursor = macroMatcher.end()
        }
        result.append(template, cursor, template.length)
        return result.toString()
    }

    private fun toKoreanNumber(raw: String): String {
        val original = raw.trim()
        if (original.isEmpty() || !VALID_NUMBER.matcher(original).matches()) return raw

        var token = original
        val sign = when {
            token.startsWith("-") -> {
                token = token.substring(1)
                "마이너스"
            }
            token.startsWith("+") -> {
                token = token.substring(1)
                ""
            }
            else -> ""
        }

        val normalized = token.replace(",", "")
        val dotIndex = normalized.indexOf('.')
        val integerPart = if (dotIndex >= 0) normalized.substring(0, dotIndex) else normalized
        val fractionPart = if (dotIndex >= 0) normalized.substring(dotIndex + 1) else null
        val integerText = readKoreanInteger(integerPart)
        val fractionText = fractionPart?.let { digits ->
            buildString {
                append("점")
                digits.forEach { digit ->
                    if (digit !in '0'..'9') return raw
                    append(DIGIT_NAMES[digit - '0'])
                }
            }
        }.orEmpty()
        return sign + integerText + fractionText
    }

    private fun readKoreanInteger(rawDigits: String): String {
        val digits = rawDigits.trimStart('0').ifEmpty { "0" }
        if (digits == "0") return "영"
        if (digits.any { it !in '0'..'9' }) return rawDigits

        val chunks = mutableListOf<String>()
        var end = digits.length
        while (end > 0) {
            val start = (end - 4).coerceAtLeast(0)
            chunks += digits.substring(start, end)
            end = start
        }

        val highestNonZeroChunk = chunks.indexOfLast { chunk -> chunk.any { it != '0' } }
        if (highestNonZeroChunk !in LARGE_UNITS.indices) return rawDigits

        return buildString {
            for (index in highestNonZeroChunk downTo 0) {
                val chunk = chunks[index]
                if (chunk.all { it == '0' }) continue
                val chunkText = readFourKoreanDigits(chunk)
                val largeUnit = LARGE_UNITS[index]
                if (index == 1 && chunkText == "일" && index == highestNonZeroChunk) {
                    append(largeUnit)
                } else {
                    append(chunkText)
                    append(largeUnit)
                }
            }
        }
    }

    private fun readFourKoreanDigits(chunk: String): String {
        val padded = chunk.padStart(4, '0')
        return buildString {
            padded.forEachIndexed { index, char ->
                val digit = char - '0'
                if (digit == 0) return@forEachIndexed
                val unitIndex = 3 - index
                if (!(digit == 1 && unitIndex > 0)) append(DIGIT_NAMES[digit])
                append(SMALL_UNITS[unitIndex])
            }
        }
    }

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
        val out = applyCompiled(current.compiled, text)
        return ApplyResult(
            text = out,
            totalRules = current.rules.size,
            enabledRules = current.compiled.size,
        )
    }

    internal fun applyRulesForTest(rules: List<Rule>, text: String): String =
        applyCompiled(compileRules(rules), text)

    private fun applyCompiled(rules: List<CompiledRule>, text: String): String {
        var out = text
        for (rule in rules) {
            try {
                out = if (rule.useKoreanNumberMacro) {
                    replaceWithKoreanNumber(rule.pattern, out, rule.replacement)
                } else {
                    rule.pattern.matcher(out).replaceAll(rule.replacement)
                }
            } catch (_: Exception) {
                // Invalid imported replacement backreferences are ignored.
            }
        }
        return out
    }

    /** Returns null when a rule can be saved safely, otherwise a user-facing error. */
    fun validationError(rule: Rule): String? {
        if (rule.term.isEmpty()) return "Pattern cannot be empty."
        return try {
            val patternText = if (rule.isRegex) rule.term else Pattern.quote(rule.term)
            val flags = patternFlags(rule.ignoreCase)
            val compiled = Pattern.compile(patternText, flags)
            if (!rule.isRegex) return null

            val macroMatcher = KOREAN_NUMBER_MACRO.matcher(rule.replacement)
            val groupCount = compiled.matcher("").groupCount()
            while (macroMatcher.find()) {
                val groupIndex = macroMatcher.group(1).toIntOrNull()
                    ?: return "Invalid Korean-number capture group."
                if (groupIndex < 0 || groupIndex > groupCount) {
                    return "Korean-number capture group $groupIndex does not exist."
                }
            }

            // Remove the custom macro before asking Java to validate ordinary
            // replacement references such as $1 and ${name}.
            val replacement = KOREAN_NUMBER_MACRO.matcher(rule.replacement).replaceAll("영")
            Pattern.compile("(?:$patternText)|(?:)", flags)
                .matcher("")
                .replaceFirst(replacement)
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

        // Replace only exact defaults shipped by earlier versions. Never append
        // defaults to or rewrite a user's custom dictionary.
        val rules = parse(raw)
        val legacyWhitespace = legacyWhitespaceDefaults()
        val migratedRaw = if (
            rules == legacyDefaults() ||
            rules == legacyWhitespace ||
            rules == legacyDefaults() + legacyWhitespace ||
            rules == previousDefaultsV2() ||
            rules == previousDefaultsV3()
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
        return Snapshot(raw, rules, compileRules(rules))
    }

    private fun compileRules(rules: List<Rule>): List<CompiledRule> {
        val compiled = ArrayList<CompiledRule>(rules.size)
        for (rule in rules) {
            if (!rule.enabled) continue
            if (validationError(rule) != null) continue
            try {
                val patternText = if (rule.isRegex) rule.term else Pattern.quote(rule.term)
                val replacement = if (rule.isRegex) rule.replacement else Matcher.quoteReplacement(rule.replacement)
                compiled += CompiledRule(
                    replacement = replacement,
                    pattern = Pattern.compile(patternText, patternFlags(rule.ignoreCase)),
                    useKoreanNumberMacro = rule.isRegex && KOREAN_NUMBER_MACRO.matcher(rule.replacement).find(),
                )
            } catch (_: IllegalArgumentException) {
                // Invalid imported patterns are visible in the editor but skipped by TTS.
            }
        }
        return compiled
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

    private fun previousDefaultsV3(): List<Rule> = listOf(
        Rule("[\\u00AD\\u200B\\u2060\\uFEFF]", "", false, true),
        Rule("(?<=\\p{N}),(?=\\p{N}{3}(?:\\D|$))", "", false, true),
        Rule("(?<=\\p{N})\\s*/\\s*(?=\\p{N})", " / ", false, true),
        Rule("(?<=\\p{N})(?=(?i:kg|km|cm|mm|ml|g|m|l)\\b)", " ", false, true),
        Rule("(?<=\\p{N})(?=[%％])", " ", false, true),
        Rule("(?<=[\\$¥€₩￦£])(?=\\p{N})|(?<=\\p{N})(?=[\\$¥€₩￦£])", " ", false, true),
        Rule("[\\s\\u00A0\\u1680\\u2000-\\u200A\\u202F\\u205F\\u3000]+", " ", false, true),
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
            val rawTerm = o.optString("term", o.optString("pattern", o.optString("word", "")))
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
                name = o.optString("name", ""),
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
                if (r.name.isNotBlank()) put("name", r.name)
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
