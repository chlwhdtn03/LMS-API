package io.github.chlwhdtn03

import io.github.chlwhdtn03.data.Lms.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlin.random.Random
import kotlin.test.*
import kotlin.time.Duration.Companion.minutes
import kotlin.time.ExperimentalTime

/**
 * 실제 LMS 계정으로 [LmsApi]의 네트워크 호출과 completion 오버로드를 전부 확인합니다.
 *
 * IntelliJ 실행 설정의 환경변수 또는 JVM 시스템 속성에 아래 값을 지정하면 실행됩니다.
 *
 * - `LMS_TEST_ID`: LMS 아이디
 * - `LMS_TEST_PASSWORD`: LMS 비밀번호
 * - `LMS_TEST_TERM_ID`: 테스트할 학기 ID(선택)
 * - `LMS_TEST_YEAR`: 유세인트 조회 학년도(선택)
 * - `LMS_TEST_SEMESTER`: `FIRST`, `SECOND`, `090`, `092`, `1학기`, `2학기` 중 하나(선택)
 *
 * 계정 정보가 없으면 외부 서버를 호출하지 않습니다.
 */
@OptIn(ExperimentalTime::class)
class LmsApiFullIntegrationTest {
    @Test
    fun callsEveryLmsApiNetworkFunctionAndOverload() = runTest(timeout = 10.minutes) {
        val credentials = loadCredentials() ?: return@runTest

        runCatching { LmsApi.logout() }
        try {
            testLoginOverloads(credentials)

            val terms = testSessionApis(credentials.id)
            val term = selectTerm(terms)

            testSubjectAndTodoApis(term)
            testWebDynproApis(terms)
            testCallbackLogoutAndSuspendLogin(credentials)
        } finally {
            LmsApi.logout()
        }

        assertFalse(LmsApi.isLoggined)
    }

    private suspend fun testLoginOverloads(credentials: Credentials) {
        val callbackResult = awaitCallback<LmsLoginResult> { completion ->
            LmsApi.loginLMS(credentials.id, credentials.password, completion)
        }
        assertSuccess("loginLMS(completion)", callbackResult.success, callbackResult.errorMessage)
        assertTrue(LmsApi.isLoggined)

        assertTrue(LmsApi.loginLMS(credentials.id, credentials.password))
        assertTrue(LmsApi.isLoggined)
    }

    private suspend fun testSessionApis(expectedId: String): List<Term> {
        val terms = LmsApi.getTerms()
        assertTrue(terms.isNotEmpty(), "getTerms() returned no terms")

        val callbackTerms = awaitCallback<LmsTermsResult> { LmsApi.getTerms(it) }
        assertSuccess("getTerms(completion)", callbackTerms.success, callbackTerms.errorMessage)
        assertTrue(callbackTerms.terms.isNotEmpty())

        val loginInfo = LmsApi.getLoginInfo()
        assertTrue(loginInfo.user_login == expectedId, "getLoginInfo() returned another user")

        val callbackLoginInfo = awaitCallback<LmsLoginInfoResult> { LmsApi.getLoginInfo(it) }
        assertSuccess(
            "getLoginInfo(completion)",
            callbackLoginInfo.success,
            callbackLoginInfo.errorMessage,
        )
        assertNotNull(callbackLoginInfo.info)

        val cookies = LmsApi.getCookies()
        assertNotNull(cookies.lmsSession)

        val callbackCookies = awaitCallback<LmsCookiesResult> { LmsApi.getCookies(it) }
        assertSuccess(
            "getCookies(completion)",
            callbackCookies.success,
            callbackCookies.errorMessage,
        )
        assertNotNull(callbackCookies.lmsSession)

        return terms
    }

    private suspend fun testSubjectAndTodoApis(term: Term) {
        val subjects: List<Subject> = LmsApi.getSubjects(
            term = term,
            loadingState = { progress -> assertProgress("getSubjects()", progress) },
        )
        term.id?.let { expectedTermId ->
            assertTrue(
                subjects.all { it.termId == expectedTermId },
                "getSubjects() returned a subject from another term",
            )
        }

        val callbackSubjects = awaitCallback<LmsSubjectsResult> { completion ->
            LmsApi.getSubjects(
                term = term,
                loadingState = { progress -> assertProgress("getSubjects(completion)", progress) },
                completion = completion,
            )
        }
        assertSuccess(
            "getSubjects(completion)",
            callbackSubjects.success,
            callbackSubjects.errorMessage,
        )

        LmsApi.getTodoList(
            term = term,
            loadingState = { progress -> assertProgress("getTodoList()", progress) },
            postHogDistinctId = null,
        )

        val callbackTodos = awaitCallback<LmsSubjectsResult> { completion ->
            LmsApi.getTodoList(
                term = term,
                loadingState = { progress -> assertProgress("getTodoList(completion)", progress) },
                completion = completion,
            )
        }
        assertSuccess(
            "getTodoList(completion)",
            callbackTodos.success,
            callbackTodos.errorMessage,
        )

        val callbackTodosWithDistinctId = awaitCallback<LmsSubjectsResult> { completion ->
            LmsApi.getTodoList(
                term = term,
                loadingState = { progress ->
                    assertProgress("getTodoList(distinctId, completion)", progress)
                },
                postHogDistinctId = null,
                completion = completion,
            )
        }
        assertSuccess(
            "getTodoList(distinctId, completion)",
            callbackTodosWithDistinctId.success,
            callbackTodosWithDistinctId.errorMessage,
        )

        val stats = LmsApi.getUnsubmittedRatioStats(term) { progress ->
            assertProgress("getUnsubmittedRatioStats()", progress)
        }
        assertTrue(stats.totalCount >= 0)
        assertTrue(stats.unsubmittedCount in 0..stats.totalCount)
        assertTrue(stats.ratio in 0.0..1.0)
    }

    private suspend fun testWebDynproApis(terms: List<Term>) {
        val period = selectRegularPeriod(terms)

        val rawHtml = LmsApi.fetchWebDynproHtml(
            url = GRADUATE_TABLE_URL,
            appName = GRADUATE_TABLE_APP_NAME,
        )
        assertTrue(rawHtml.isNotBlank(), "fetchWebDynproHtml() returned an empty response")

        LmsApi.getTimetable()
        LmsApi.getTimetable(period.year, period.semester)

        val callbackTimetable = awaitCallback<LmsTimetableResult> { LmsApi.getTimetable(it) }
        assertSuccess(
            "getTimetable(completion)",
            callbackTimetable.success,
            callbackTimetable.errorMessage,
        )
        assertNotNull(callbackTimetable.timetable)

        val callbackTimetableByPeriod = awaitCallback<LmsTimetableResult> { completion ->
            LmsApi.getTimetable(period.year, period.semester, completion)
        }
        assertSuccess(
            "getTimetable(year, semester, completion)",
            callbackTimetableByPeriod.success,
            callbackTimetableByPeriod.errorMessage,
        )
        assertNotNull(callbackTimetableByPeriod.timetable)

        LmsApi.getGraduateTable()
        val callbackGraduate = awaitCallback<LmsGraduateTableResult> {
            LmsApi.getGraduateTable(it)
        }
        assertSuccess(
            "getGraduateTable(completion)",
            callbackGraduate.success,
            callbackGraduate.errorMessage,
        )
        assertNotNull(callbackGraduate.graduateTable)

        val tuition = LmsApi.getTuitionTable()
        val callbackTuition = awaitCallback<LmsTuitionResult> { LmsApi.getTuitionTable(it) }
        assertSuccess(
            "getTuitionTable(completion)",
            callbackTuition.success,
            callbackTuition.errorMessage,
        )
        assertNotNull(callbackTuition.tuitionTable)

        val scholarship = LmsApi.getScholarshipHistoryTable()
        val callbackScholarship = awaitCallback<LmsScholarshipHistoryResult> {
            LmsApi.getScholarshipHistoryTable(it)
        }
        assertSuccess(
            "getScholarshipHistoryTable(completion)",
            callbackScholarship.success,
            callbackScholarship.errorMessage,
        )
        assertNotNull(callbackScholarship.scholarshipHistoryTable)

        val preRegistration = LmsApi.getPreRegistrationTable()
        val callbackPreRegistration = awaitCallback<LmsPreRegistrationResult> {
            LmsApi.getPreRegistrationTable(it)
        }
        assertSuccess(
            "getPreRegistrationTable(completion)",
            callbackPreRegistration.success,
            callbackPreRegistration.errorMessage,
        )
        assertNotNull(callbackPreRegistration.preRegistrationTable)
        preRegistration.items.firstOrNull()?.let { course ->
            val plan = LmsApi.getPreRegistrationPlanUrl(course.subjectCode, course.section)
            assertTrue(plan.startsWith("https://office.ssu.ac.kr/"))
        }

        val catalogQuery = CourseCatalogQuery(
            year = period.year,
            semester = period.semester,
            category = CourseCatalogCategory.SUBJECT,
            keyword = "컴퓨터",
        )
        val catalogOptions = LmsApi.getCourseCatalogSearchOptions(catalogQuery)
        assertTrue(catalogOptions.acceptsKeyword)
        val callbackCatalogOptions = awaitCallback<LmsCourseCatalogOptionsResult> {
            LmsApi.getCourseCatalogSearchOptions(catalogQuery, it)
        }
        assertSuccess(
            "getCourseCatalogSearchOptions(completion)",
            callbackCatalogOptions.success,
            callbackCatalogOptions.errorMessage,
        )
        assertNotNull(callbackCatalogOptions.options)

        val catalog = LmsApi.getCourseCatalogTable(catalogQuery)
        val callbackCatalog = awaitCallback<LmsCourseCatalogResult> {
            LmsApi.getCourseCatalogTable(catalogQuery, it)
        }
        assertSuccess(
            "getCourseCatalogTable(completion)",
            callbackCatalog.success,
            callbackCatalog.errorMessage,
        )
        assertNotNull(callbackCatalog.courseCatalogTable)
        catalog.items.firstOrNull()?.let { course ->
            val callbackPlan = awaitCallback<LmsPlanResult> { completion ->
                LmsApi.getCourseCatalogPlanUrl(
                    catalogQuery,
                    course.subjectCode,
                    course.section,
                    completion,
                )
            }
            assertSuccess(
                "getCourseCatalogPlanUrl(completion)",
                callbackPlan.success,
                callbackPlan.errorMessage,
            )
            assertTrue(callbackPlan.plan.startsWith("https://office.ssu.ac.kr/"))
        }

        testRepeatedFinancialHistoryCalls(
            expectedTuitionCount = tuition.items.size,
            expectedScholarshipCount = scholarship.items.size,
        )

        LmsApi.getGradeTable()
        LmsApi.getGradeTable(period.year, period.semester)

        val callbackGrade = awaitCallback<LmsGradeResult> { LmsApi.getGradeTable(it) }
        assertSuccess(
            "getGradeTable(completion)",
            callbackGrade.success,
            callbackGrade.errorMessage,
        )
        assertNotNull(callbackGrade.gradeTable)

        val callbackGradeByPeriod = awaitCallback<LmsGradeResult> { completion ->
            LmsApi.getGradeTable(period.year, period.semester, completion)
        }
        assertSuccess(
            "getGradeTable(year, semester, completion)",
            callbackGradeByPeriod.success,
            callbackGradeByPeriod.errorMessage,
        )
        assertNotNull(callbackGradeByPeriod.gradeTable)

        LmsApi.getSemesterGradeSummaryTable()
        val callbackSummary = awaitCallback<LmsSemesterGradeSummaryResult> {
            LmsApi.getSemesterGradeSummaryTable(it)
        }
        assertSuccess(
            "getSemesterGradeSummaryTable(completion)",
            callbackSummary.success,
            callbackSummary.errorMessage,
        )
        assertNotNull(callbackSummary.summaryTable)

        LmsApi.getChapelTable()
        LmsApi.getChapelTable(period.year, period.semester)

        val callbackChapel = awaitCallback<LmsChapelResult> { LmsApi.getChapelTable(it) }
        assertSuccess(
            "getChapelTable(completion)",
            callbackChapel.success,
            callbackChapel.errorMessage,
        )
        assertNotNull(callbackChapel.chapelInformation)

        val callbackChapelByPeriod = awaitCallback<LmsChapelResult> { completion ->
            LmsApi.getChapelTable(period.year, period.semester, completion)
        }
        assertSuccess(
            "getChapelTable(year, semester, completion)",
            callbackChapelByPeriod.success,
            callbackChapelByPeriod.errorMessage,
        )
        assertNotNull(callbackChapelByPeriod.chapelInformation)
    }

    private suspend fun testRepeatedFinancialHistoryCalls(
        expectedTuitionCount: Int,
        expectedScholarshipCount: Int,
    ) {
        val callOrder = List(6) { index -> index % 2 }
            .shuffled(Random(REPEATED_CALL_SEED))

        callOrder.forEachIndexed { index, target ->
            when (target) {
                0 -> assertEquals(
                    expectedTuitionCount,
                    LmsApi.getTuitionTable().items.size,
                    "반복 호출 ${index + 1}회차에 등록금 조회 결과가 달라졌습니다.",
                )

                else -> assertEquals(
                    expectedScholarshipCount,
                    LmsApi.getScholarshipHistoryTable().items.size,
                    "반복 호출 ${index + 1}회차에 장학금 조회 결과가 달라졌습니다.",
                )
            }
        }
    }

    private suspend fun testCallbackLogoutAndSuspendLogin(credentials: Credentials) {
        awaitUnitCallback { LmsApi.logout(it) }
        assertFalse(LmsApi.isLoggined)

        assertTrue(LmsApi.loginLMS(credentials.id, credentials.password))
        assertTrue(LmsApi.isLoggined)
    }

    private fun selectTerm(terms: List<Term>): Term {
        val configuredId = testSetting("LMS_TEST_TERM_ID")?.toIntOrNull()
        if (configuredId != null) {
            return terms.firstOrNull { it.id == configuredId }
                ?: error("LMS_TEST_TERM_ID=$configuredId was not returned by getTerms()")
        }
        return terms.firstOrNull { (it.id ?: -1) > 0 }
            ?: error("getTerms() returned no selectable term")
    }

    private fun selectRegularPeriod(terms: List<Term>): AcademicPeriod {
        val configuredYear = testSetting("LMS_TEST_YEAR")
        val configuredSemester = testSetting("LMS_TEST_SEMESTER")?.toSemester()
        if (configuredYear != null || configuredSemester != null) {
            require(!configuredYear.isNullOrBlank()) {
                "LMS_TEST_SEMESTER를 지정할 때 LMS_TEST_YEAR도 함께 지정해야 합니다."
            }
            require(configuredSemester != null) {
                "LMS_TEST_YEAR를 지정할 때 LMS_TEST_SEMESTER도 함께 지정해야 합니다."
            }
            require(configuredSemester == Semester.FIRST || configuredSemester == Semester.SECOND) {
                "채플 테스트를 위해 정규학기(FIRST 또는 SECOND)를 지정해야 합니다."
            }
            return AcademicPeriod(configuredYear, configuredSemester)
        }

        for (term in terms) {
            val name = term.name.orEmpty()
            val year = Regex("""20\d{2}""").find(name)?.value ?: continue
            val semester = Semester.fromName(name) ?: continue
            if (semester == Semester.FIRST || semester == Semester.SECOND) {
                return AcademicPeriod(year, semester)
            }
        }
        error(
            "정규학기를 자동으로 찾지 못했습니다. " +
                "LMS_TEST_YEAR와 LMS_TEST_SEMESTER 환경변수를 지정해주세요.",
        )
    }

    private fun String.toSemester(): Semester? {
        return Semester.fromCode(this)
            ?: Semester.fromName(this)
            ?: runCatching { Semester.valueOf(uppercase()) }.getOrNull()
    }

    private fun loadCredentials(): Credentials? {
        val id = testSetting("LMS_TEST_ID")
        val password = testSetting("LMS_TEST_PASSWORD")
        if (id.isNullOrBlank() || password.isNullOrBlank()) {
            return null
        }
        return Credentials(id, password)
    }

    private fun testSetting(name: String): String? {
        return System.getenv(name)
            ?.takeIf { it.isNotBlank() }
            ?: System.getProperty(name)?.takeIf { it.isNotBlank() }
    }

    private suspend fun <T> awaitCallback(register: ((T) -> Unit) -> Unit): T {
        val result = CompletableDeferred<T>()
        register { value -> result.complete(value) }
        return withTimeout(CALLBACK_TIMEOUT) { result.await() }
    }

    private suspend fun awaitUnitCallback(register: (() -> Unit) -> Unit) {
        val result = CompletableDeferred<Unit>()
        register { result.complete(Unit) }
        withTimeout(CALLBACK_TIMEOUT) { result.await() }
    }

    private fun assertSuccess(label: String, success: Boolean, errorMessage: String?) {
        assertTrue(success, "$label failed: ${errorMessage ?: "unknown error"}")
    }

    private fun assertProgress(label: String, progress: Float) {
        assertTrue(progress in 0f..1.0001f, "$label returned invalid progress: $progress")
    }

    private data class Credentials(
        val id: String,
        val password: String,
    )

    private data class AcademicPeriod(
        val year: String,
        val semester: Semester,
    )

    private companion object {
        val CALLBACK_TIMEOUT = 3.minutes
        const val REPEATED_CALL_SEED = 75_306_520
        const val GRADUATE_TABLE_APP_NAME = "ZCMW8015"
        const val GRADUATE_TABLE_URL =
            "https://ecc.ssu.ac.kr:8443/sap/bc/webdynpro/SAP/$GRADUATE_TABLE_APP_NAME"
    }
}
