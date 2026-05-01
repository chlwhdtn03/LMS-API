package io.github.kotlin.fibonacci

import io.github.chlwhdtn03.LmsApi.getLoginInfo
import io.github.chlwhdtn03.LmsApi.getSubjects
import io.github.chlwhdtn03.LmsApi.getTerms
import io.github.chlwhdtn03.LmsApi.isLoggined
import io.github.chlwhdtn03.LmsApi.loginLMS
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.time.ExperimentalTime
import kotlin.time.measureTimedValue

private const val LMS_TEST_ID = ""
private const val LMS_TEST_PASSWORD = ""

class JvmLMSTest {

    // Fill this ignored file locally before running the test.
    @Test
    @ExperimentalTime
    fun testJvmLMS() = runTest {
        if (LMS_TEST_ID.isBlank() || LMS_TEST_PASSWORD.isBlank()) {
            println("Set LMS_TEST_ID and LMS_TEST_PASSWORD in this ignored file before running.")
            return@runTest
        }

        loginLMS(LMS_TEST_ID, LMS_TEST_PASSWORD)
        println("로그인 성공 여부 : $isLoggined")
        val terms = getTerms()
        println(terms[0].name)
        println(getLoginInfo())

        val (subjects, timeTaken) = measureTimedValue {
            getSubjects(terms[0], {
                println("진행률 ${it*100}%")
            })
        }
        println("LMS 불러오기 소요시간 : ${timeTaken.inWholeMilliseconds} ms")
        println("과목 ${subjects.size}개를 불러왔습니다.")
        for (subject in subjects)
            subject.scoredAssignments.forEach {
                println("${subject.name} - ${it.groupName} : ${it.name} : ${it.score} / ${it.maxScore}")
            }
    }
}
