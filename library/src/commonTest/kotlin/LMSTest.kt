package io.github.kotlin.fibonacci

import io.github.chlwhdtn03.getSubjects
import io.github.chlwhdtn03.isLoggined
import io.github.chlwhdtn03.loginLMS
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.time.ExperimentalTime
import kotlin.time.measureTimedValue

class LMSTest {

    // 주의 : Common Ktor 엔진 없음
    @Test
    @ExperimentalTime
    fun testLMS() = runTest {
        loginLMS("20222908", "if(login==6)")
        println("로그인 성공 여부 : $isLoggined")

        val (subjects, timeTaken) = measureTimedValue {
            getSubjects()
        }
        println("LMS 불러오기 소요시간 : ${timeTaken.inWholeMilliseconds} ms")
        println("과목 ${subjects.size}개를 불러왔습니다.")
        for (subject in subjects)
            println(subject.name)
    }
}