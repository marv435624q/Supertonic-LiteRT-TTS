package audio.soniqo.speech.rules

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PronunciationRulesTest {
    @Test
    fun novelRegexDefaultsKeepOriginalOrderAndExcludeDestructiveRules() {
        val rules = PronunciationRules.defaults()

        assertEquals(23, rules.size)
        assertEquals(
            listOf(
                "제로폭 문자 제거",
                "특수 공백을 일반 공백으로",
                "탭/줄바꿈을 공백으로",
                "쉼표 소수점 읽기 (기본 꺼짐)",
                "분수 읽기",
                "퍼센트(%) 읽기",
                "달러(\$) 앞표기 읽기",
                "달러(\$) 뒤표기 읽기",
                "엔(¥) 앞표기 읽기",
                "엔(¥) 뒤표기 읽기",
                "유로(€) 앞표기 읽기",
                "유로(€) 뒤표기 읽기",
                "킬로그램(kg) 읽기",
                "킬로미터(km) 읽기",
                "센티미터(cm) 읽기",
                "밀리미터(mm) 읽기",
                "밀리리터(ml) 읽기",
                "미터(m) 읽기",
                "그램(g) 읽기",
                "리터(l) 읽기",
                "소수점 읽기",
                "천 단위 숫자 읽기",
                "연속 공백 정리",
            ),
            rules.map { it.name },
        )
        assertFalse(rules.first { it.name.startsWith("쉼표 소수점") }.enabled)
        assertTrue(rules.none { it.term.contains("3400") || it.term.contains("一-龥") })
        assertTrue(rules.none { it.term == "[a-zA-Z0-9]{15,}" })
        assertTrue(rules.none { it.term.contains("커버") })
    }

    @Test
    fun convertsNovelRegexReferenceInput() {
        val input = "3.14kg, 14.5km, 1,000원, 1,234,567원, 1\$, \$2, 72%, 3.1/100"

        val output = PronunciationRules.applyRulesForTest(PronunciationRules.defaults(), input)

        assertEquals(
            "삼점일사킬로그램, 십사점오킬로미터, 천원, " +
                "백이십삼만사천오백육십칠원, 일달러, 이달러, " +
                "칠십이퍼센트, 백분의삼점일",
            output,
        )
    }

    @Test
    fun supportsPrefixAndSuffixCurrencyAndAllUnits() {
        val cases = linkedMapOf(
            "\$1" to "일달러",
            "2\$" to "이달러",
            "¥3" to "삼엔",
            "4¥" to "사엔",
            "€5" to "오유로",
            "6€" to "육유로",
            "7kg" to "칠킬로그램",
            "8km" to "팔킬로미터",
            "9cm" to "구센티미터",
            "10mm" to "십밀리미터",
            "11ml" to "십일밀리리터",
            "12m" to "십이미터",
            "13g" to "십삼그램",
            "14l" to "십사리터",
        )
        cases.forEach { (input, expected) ->
            assertEquals(expected, PronunciationRules.applyRulesForTest(PronunciationRules.defaults(), input))
        }
    }

    @Test
    fun leavesInvalidGroupingAndOrdinaryCommaUntouched() {
        val input = "1,2와 abc,def"
        assertEquals(input, PronunciationRules.applyRulesForTest(PronunciationRules.defaults(), input))
    }

    @Test
    fun validatesKoreanNumberCaptureGroups() {
        assertNull(
            PronunciationRules.validationError(
                PronunciationRules.Rule("([0-9]+)", "\${ko-number:1}", false, true),
            ),
        )
        assertTrue(
            PronunciationRules.validationError(
                PronunciationRules.Rule("([0-9]+)", "\${ko-number:2}", false, true),
            )!!.contains("does not exist"),
        )
    }

    @Test
    fun keepsPlainReplacementDollarLiteral() {
        val rules = listOf(PronunciationRules.Rule("USD", "\$", ignoreCase = false))
        assertEquals("\$ 10", PronunciationRules.applyRulesForTest(rules, "USD 10"))
    }
}
