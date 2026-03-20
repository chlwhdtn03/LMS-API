package io.github.kotlin.fibonacci

import com.yourssu.m.getSubjects
import com.yourssu.m.isLogin
import com.yourssu.m.loginLMS
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.time.ExperimentalTime
import kotlin.time.measureTimedValue

class IosLMSTest {

    @Test
    @ExperimentalTime
    fun testIOSLMS() = runTest {
        loginLMS("20222908", "if(login==6)")
        println("로그인 성공 여부 : $isLogin")

        val (subjects, timeTaken) = measureTimedValue {
            getSubjects()
        }
        println("LMS 불러오기 소요시간 : ${timeTaken.inWholeMilliseconds} ms")
        println("과목 ${subjects.size}개를 불러왔습니다.")
        for (subject in subjects)
            println(subject.name)
    }
}