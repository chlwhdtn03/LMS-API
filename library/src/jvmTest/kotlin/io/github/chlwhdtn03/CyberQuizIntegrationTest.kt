package io.github.chlwhdtn03

import io.github.chlwhdtn03.data.Cyber.CyberEvaluationType
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

/**
 * 실제 사이버대학교(KCU) 계정으로 모든 수강과목의 퀴즈 목록만 출력합니다.
 *
 * 계정 정보는 [CyberApiFullIntegrationTest]와 같이 `CYBER_TEST_ID`/`CYBER_TEST_PASSWORD`
 * 환경변수로 지정하며, 없으면 조용히 스킵합니다.
 *
 * 커맨드라인 실행 예:
 * ```
 * CYBER_TEST_ID=아이디 CYBER_TEST_PASSWORD=비밀번호 \
 *   ./gradlew :library:jvmTest --tests "io.github.chlwhdtn03.CyberQuizIntegrationTest" -i
 * ```
 */
class CyberQuizIntegrationTest {
    @Test
    fun printsQuizzesOfAllSubjects() = runTest(timeout = 5.minutes) {
        val id = testSetting("CYBER_TEST_ID")
        val password = testSetting("CYBER_TEST_PASSWORD")
        if (id.isNullOrBlank() || password.isNullOrBlank()) {
            println("[CyberQuizIntegrationTest] CYBER_TEST_ID/CYBER_TEST_PASSWORD 미설정, 스킵합니다.")
            return@runTest
        }

        runCatching { CyberApi.logout() }
        try {
            assertTrue(CyberApi.login(id, password), "로그인에 실패했습니다.")

            val subjects = CyberApi.getSubjects()
            println("[CyberQuizIntegrationTest] 수강과목 ${subjects.size}건")
            subjects.forEach { subject ->
                val quizzes = CyberApi.getEvaluations(subject)
                    .filter { it.type == CyberEvaluationType.QUIZ }
                println("[CyberQuizIntegrationTest] '${subject.name}' 퀴즈 ${quizzes.size}건")
                quizzes.forEach { quiz ->
                    println(
                        "  - ${quiz.round ?: "-"}차 ${quiz.week.ifEmpty { "-" }} " +
                            "${quiz.title.ifEmpty { "(제목 없음)" }} " +
                            "기간=${quiz.startAt}~${quiz.endAt} 제한=${quiz.timeLimit} " +
                            "현황=${quiz.submitStatus} 비율=${quiz.ratio} 기간내=${quiz.isInPeriod}",
                    )
                }
            }
        } finally {
            CyberApi.logout()
        }
    }

    private fun testSetting(name: String): String? {
        return System.getenv(name)
            ?.takeIf { it.isNotBlank() }
            ?: System.getProperty(name)?.takeIf { it.isNotBlank() }
    }
}
