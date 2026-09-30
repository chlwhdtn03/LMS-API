package io.github.chlwhdtn03

import io.github.chlwhdtn03.data.Cyber.CyberEvaluationType
import io.github.chlwhdtn03.internal.CyberCourseService
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CyberEvaluationParseTest {
    private val service = CyberCourseService(cyberClient)

    @Test
    fun parsesEvaluationRows() {
        val html = """
            <table id="tblEvl">
            <tbody>
            <tr data-evl-type="06" data-tme="1" data-peri="0"
                data-is-add="0" data-wkend-cd="" data-ddln="0">
                <td class="txtL">시험</td>
                <td>
                    1<span class="mTxt">차수</span>
                </td>
                <td></td>
                <td class="txtL tdTit"></td>
                <td class="mTit">2026-12-11 19:00</td>
                <td class="mTit">2026-12-11 19:50</td>
                    <td class="mTit">
                    50분</td>
                <td class="mTit">
                    </td>
                <td class="mTit">
                    </td>
                <td class="mTit">
                    50%
                        </td>
                </tr>
            <tr data-evl-type="02" data-tme="1" data-peri="0"
                data-is-add="0" data-wkend-cd="" data-ddln="0">
                <td class="txtL">퀴즈</td>
                <td>
                    1<span class="mTxt">차수</span>
                </td>
                <td></td>
                <td class="txtL tdTit"></td>
                <td class="mTit"></td>
                <td class="mTit"></td>
                    <td class="mTit">
                    -</td>
                <td class="mTit">
                    </td>
                <td class="mTit">
                    </td>
                <td class="mTit">
                    10%
                        </td>
                </tr>
            <tr data-evl-type="02" data-tme="1" data-peri="1"
                data-is-add="0" data-wkend-cd="4" data-ddln="0">
                <td class="txtL">퀴즈</td>
                <td>
                    1<span class="mTxt">차수</span>
                </td>
                <td>4주차</td>
                <td class="txtL tdTit">퀴즈 4주차 본시험 </td>
                <td class="mTit">2026-09-28 21:57</td>
                <td class="mTit">2026-10-05 23:59</td>
                <td class="mTit">
                    60분</td>
                <td class="mTit">
                    <button type="button" class="btn lineGray btnEvlApyexm" >문제은행</button>
                </td>
                <td class="mTit">
                    미응시</td>
                <td class="mTit">
                    10%
                </td>
            </tr>
            <tr data-evl-type="03" data-tme="1" data-peri="1"
                data-is-add="1" data-wkend-cd="08" data-ddln="1">
                <td class="txtL">과제</td>
                <td>
                    1<span class="mTxt">차수</span>
                </td>
                <td>08주차</td>
                <td class="txtL tdTit">[중간과제] AI로 만든 결과물 &amp; 작업 과정 제출 (30%)</td>
                <td class="mTit">2026-10-19 01:00</td>
                <td class="mTit">
                    1차 : 2026-11-02 23:59<br/>
                    2차 : 2026-11-09 23:59</td>
                <td class="mTit">
                    14일</td>
                <td class="mTit">
                    <button type="button" class="btn lineGray btnEvlApyexm" >참여</button>
                </td>
                <td class="mTit">
                    미제출</td>
                <td class="mTit">
                    30%
                </td>
            </tr>
            <tr data-evl-type="03" data-tme="2" data-peri="0"
                data-is-add="" data-wkend-cd="" data-ddln="0">
                <td class="txtL">과제</td>
                <td>2<span class="mTxt">차수</span></td>
                <td></td>
                <td class="txtL tdTit">기말과제</td>
                <td class="mTit"></td>
                <td class="mTit"></td>
                <td class="mTit">-</td>
                <td class="mTit"></td>
                <td class="mTit"></td>
                <td class="mTit">10%</td>
            </tr>
            <tr data-evl-type="01" data-tme="1" data-peri="0"
                data-is-add="" data-wkend-cd="" data-ddln="0">
                <td class="txtL">출석</td>
                <td>-<span class="mTxt">차수</span></td>
                <td>-</td><td class="txtL tdTit">-</td><td>-</td><td>-</td><td>-</td><td></td><td>-</td><td>20%</td>
            </tr>
            </tbody>
            <tfoot><tr><td class="txtL" colspan="9">합계</td><td>100%</td></tr></tfoot>
            </table>
        """.trimIndent()

        val evaluations = service.parseEvaluations(html)
        assertEquals(
            listOf(CyberEvaluationType.EXAM, CyberEvaluationType.QUIZ, CyberEvaluationType.ASSIGNMENT),
            evaluations.map { it.type },
        )

        val exam = evaluations[0]
        assertEquals("", exam.title)
        assertEquals("50분", exam.timeLimit)
        assertEquals("50%", exam.ratio)

        val quiz = evaluations[1]
        assertEquals("퀴즈", quiz.typeName)
        assertEquals(1, quiz.round)
        assertEquals("4주차", quiz.week)
        assertEquals("퀴즈 4주차 본시험", quiz.title)
        assertEquals("2026-09-28 21:57", quiz.startAt)
        assertEquals("2026-10-05 23:59", quiz.endAt)
        assertEquals("60분", quiz.timeLimit)
        assertEquals("문제은행", quiz.applyText)
        assertEquals("미응시", quiz.submitStatus)
        assertTrue(quiz.isInPeriod)
        assertFalse(quiz.isResubmission)
        assertTrue(quiz.hasApplyButton)

        val assignment = evaluations[2]
        assertEquals(1, assignment.round)
        assertEquals("08주차", assignment.week)
        assertEquals("[중간과제] AI로 만든 결과물 & 작업 과정 제출 (30%)", assignment.title)
        assertEquals("2026-10-19 01:00", assignment.startAt)
        assertEquals("2026-11-02 23:59", assignment.endAt)
        assertEquals("14일", assignment.timeLimit)
        assertEquals("참여", assignment.applyText)
        assertEquals("미제출", assignment.submitStatus)
        assertEquals("30%", assignment.ratio)
        assertTrue(assignment.isInPeriod)
        assertTrue(assignment.isResubmission)
        assertEquals("1", assignment.rawDeadlineFlag)
        assertTrue(assignment.hasApplyButton)
    }

    @Test
    fun returnsEmptyListWhenNoEvaluations() {
        val html = """
            <table id="tblEvl"><tbody>
            <tr><td colspan="9">조회된 평가가 없습니다.</td></tr>
            </tbody></table>
        """.trimIndent()
        assertTrue(service.parseEvaluations(html).isEmpty())
    }
}
