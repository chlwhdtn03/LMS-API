package io.github.chlwhdtn03

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

/**
 * 실제 사이버대학교(KCU) 계정으로 [CyberApi]의 로그인 -> 수강과목 -> 수강일람 흐름을 확인합니다.
 *
 * IntelliJ 실행 설정의 환경변수 또는 JVM 시스템 속성에 아래 값을 지정하면 실행됩니다.
 *
 * - `CYBER_TEST_ID`: 사이버대학교 포털 아이디
 * - `CYBER_TEST_PASSWORD`: 사이버대학교 포털 비밀번호
 *
 * 계정 정보가 없으면 외부 서버를 호출하지 않고 조용히 스킵합니다.
 *
 * 커맨드라인 실행 예:
 * ```
 * CYBER_TEST_ID=아이디 CYBER_TEST_PASSWORD=비밀번호 \
 *   ./gradlew :library:jvmTest --tests "io.github.chlwhdtn03.CyberApiFullIntegrationTest" -i
 * ```
 * `-i`(info) 로그를 켜야 아래 println 출력이 콘솔에 보입니다.
 */
class CyberApiFullIntegrationTest {
    @Test
    fun logsInAndFetchesSubjectsAndWeeklyLectures() = runTest(timeout = 5.minutes) {
        val id = testSetting("CYBER_TEST_ID")
        val password = testSetting("CYBER_TEST_PASSWORD")
        if (id.isNullOrBlank() || password.isNullOrBlank()) {
            println("[CyberApiFullIntegrationTest] CYBER_TEST_ID/CYBER_TEST_PASSWORD 미설정, 스킵합니다.")
            return@runTest
        }

        runCatching { CyberApi.logout() }
        try {
            println("[CyberApiFullIntegrationTest] 로그인 시도...")
            val loginSuccess = CyberApi.login(id, password)
            println("[CyberApiFullIntegrationTest] 로그인 결과: $loginSuccess")
            assertTrue(loginSuccess, "로그인에 실패했습니다.")
            assertTrue(CyberApi.isLoggined)

            val subjects = CyberApi.getSubjects()
            println("[CyberApiFullIntegrationTest] 수강과목 ${subjects.size}건")
            subjects.forEach { subject ->
                println(
                    "  - ${subject.name} / ${subject.professor} / ${subject.credit} / " +
                        "진도율 ${subject.progressPercent}% / " +
                        "year=${subject.year} smst=${subject.semesterCode} " +
                        "cose=${subject.courseCode} dept=${subject.deptCode} user=${subject.userNo}",
                )
            }

            val firstSubject = subjects.firstOrNull()
            if (firstSubject == null) {
                println("[CyberApiFullIntegrationTest] 수강과목이 없어 수강일람 조회는 건너뜁니다.")
                return@runTest
            }

            val weeks = CyberApi.getWeeklyLectures(firstSubject)
            println("[CyberApiFullIntegrationTest] '${firstSubject.name}' 주차 ${weeks.size}건")
            weeks.forEach { week ->
                println(
                    "  - ${week.weekNo}주 [${week.attendanceStatus}] ${week.topic} " +
                        "(${week.attendancePeriod})",
                )
                week.lectures.forEach { lecture ->
                    println(
                        "      ${lecture.lectureNo}강 ${lecture.statusText} " +
                            "진도율=${lecture.progressPercent}% 완료=${lecture.isCompleted} " +
                            "학습시간=${lecture.studyTime}/${lecture.baseTime} " +
                            "video=${lecture.videoFilePath} audio=${lecture.audioFilePath}",
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
