package io.github.chlwhdtn03

import io.github.chlwhdtn03.data.Lms.*
import io.github.chlwhdtn03.internal.*
import io.ktor.client.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.plugins.cookies.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlin.jvm.JvmSynthetic
import kotlin.random.Random
import kotlin.time.ExperimentalTime

private val lmsJson = Json {
    ignoreUnknownKeys = true
    isLenient = true
    coerceInputValues = true
}

private class ResettableCookiesStorage : CookiesStorage {
    private val mutex = Mutex()
    private var delegate: CookiesStorage = AcceptAllCookiesStorage()

    override suspend fun get(requestUrl: Url): List<Cookie> {
        mutex.lock()
        return try {
            delegate.get(requestUrl)
        } finally {
            mutex.unlock()
        }
    }

    override suspend fun addCookie(requestUrl: Url, cookie: Cookie) {
        mutex.lock()
        try {
            delegate.addCookie(requestUrl, cookie)
        } finally {
            mutex.unlock()
        }
    }

    suspend fun clear() {
        mutex.lock()
        try {
            delegate.close()
            delegate = AcceptAllCookiesStorage()
        } finally {
            mutex.unlock()
        }
    }

    override fun close() {
        delegate.close()
    }
}

private val lmsCookiesStorage = ResettableCookiesStorage()

internal val client = HttpClient() {
    install(HttpCookies) {
        storage = lmsCookiesStorage
    }
    install(ContentNegotiation) {
        json(lmsJson)
    }
    followRedirects = true
}

private val apiScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
internal const val TODO_SNAPSHOT_SAMPLE_RATE = 0.2

internal fun shouldSendTodoSnapshot(sample: Double = Random.nextDouble()): Boolean {
    return sample < TODO_SNAPSHOT_SAMPLE_RATE
}

internal class GradeCache {
    var latestYear: String? = null
    var latestSemester: Semester? = null

    fun clear() {
        latestYear = null
        latestSemester = null
    }
}

internal class ChapelCache {
    var latestYear: String? = null
    var latestSemester: Semester? = null
    var information: ChapelInformation? = null

    fun clear() {
        latestYear = null
        latestSemester = null
        information = null
    }
}

/**
 * LMS-API의 안정적인 공개 진입점입니다.
 *
 * 기존 사용처와의 호환성을 위해 공개 메서드는 이 싱글톤에 유지합니다. 로그인 상태와
 * 사용자별 캐시도 이 객체가 소유하며, 실제 요청 생성과 파싱은 기능별 internal 서비스에
 * 위임합니다.
 */
object LmsApi {
    var isLoggined = false
        private set
    private var lmsId = ""
    private var apiBearerToken = ""
    private val gradeCache = GradeCache()
    private val chapelCache = ChapelCache()
    private val sessionMutex = Mutex()
    private val planLoadMutex = Mutex()
    private val trackedTodoSnapshotDatesByDistinctId = mutableMapOf<String, String>()

    // 구현 클래스는 상태를 소유하지 않고 LmsApi가 전달한 캐시와 세션을 사용합니다.
    private val authService = LmsAuthService(client, lmsJson)
    private val courseClient = LmsCourseClient(client) { apiBearerToken }
    private val todoService = TodoService(
        client = client,
        courseClient = courseClient,
        backgroundScope = apiScope,
        trackedSnapshotDates = trackedTodoSnapshotDatesByDistinctId,
        ensureLoggedIn = ::checkLoggedIn,
    )
    private val courseService = LmsCourseService(
        courseClient = courseClient,
        todoService = todoService,
        ensureLoggedIn = ::checkLoggedIn,
    )
    private val webDynproService = WebDynproService(client, ::checkLoggedIn)
    private val timetableService = TimetableService(webDynproService)
    private val graduateTableService = GraduateTableService(webDynproService)
    private val tuitionTableService = TuitionTableService(webDynproService)
    private val scholarshipHistoryService = ScholarshipHistoryService(webDynproService)
    private val gradeService = GradeService(webDynproService, gradeCache)
    private val chapelService = ChapelService(webDynproService, chapelCache)
    private val preRegistrationService = PreRegistrationService(webDynproService)
    private val courseCatalogService = CourseCatalogService(WebDynproService(client) {})

    internal data class UnsubmittedStats(
        val totalCount: Int = 0,
        val unsubmittedCount: Int = 0,
    ) {
        val ratio: Double
            get() = if (totalCount == 0) 0.0 else unsubmittedCount.toDouble() / totalCount
    }

    private fun checkLoggedIn() {
        if (!isLoggined || lmsId.isBlank()) {
            throw IllegalStateException("LMS 로그인이 되어있지 않습니다.")
        }
    }

    private fun clearCachedUserData() {
        gradeCache.clear()
        chapelCache.clear()
    }

    private suspend fun resetSession() {
        isLoggined = false
        lmsId = ""
        apiBearerToken = ""
        clearCachedUserData()
        lmsCookiesStorage.clear()
    }

    private fun Throwable.toResultMessage(): String {
        return message ?: "알 수 없는 오류가 발생했습니다."
    }

    private fun launchLoginResult(
        completion: (LmsLoginResult) -> Unit,
        block: suspend () -> Boolean,
    ) {
        apiScope.launch {
            val result = try {
                LmsLoginResult(success = block())
            } catch (throwable: Throwable) {
                LmsLoginResult(success = false, errorMessage = throwable.toResultMessage())
            }
            completion(result)
        }
    }

    @OptIn(ExperimentalTime::class)
    private fun launchTermsResult(
        completion: (LmsTermsResult) -> Unit,
        block: suspend () -> List<Term>,
    ) {
        apiScope.launch {
            val result = try {
                LmsTermsResult(success = true, terms = block())
            } catch (throwable: Throwable) {
                LmsTermsResult(success = false, errorMessage = throwable.toResultMessage())
            }
            completion(result)
        }
    }

    private fun launchLoginInfoResult(
        completion: (LmsLoginInfoResult) -> Unit,
        block: suspend () -> Info,
    ) {
        apiScope.launch {
            val result = try {
                LmsLoginInfoResult(success = true, info = block())
            } catch (throwable: Throwable) {
                LmsLoginInfoResult(success = false, errorMessage = throwable.toResultMessage())
            }
            completion(result)
        }
    }

    private fun launchCookiesResult(
        completion: (LmsCookiesResult) -> Unit,
        block: suspend () -> LmsSessionResponse,
    ) {
        apiScope.launch {
            val result = try {
                LmsCookiesResult(success = true, lmsSession = block().lmsSession)
            } catch (throwable: Throwable) {
                LmsCookiesResult(success = false, errorMessage = throwable.toResultMessage())
            }
            completion(result)
        }
    }

    private fun launchSubjectsResult(
        completion: (LmsSubjectsResult) -> Unit,
        block: suspend () -> List<Subject>,
    ) {
        apiScope.launch {
            val result = try {
                LmsSubjectsResult(success = true, subjects = block())
            } catch (throwable: Throwable) {
                LmsSubjectsResult(success = false, errorMessage = throwable.toResultMessage())
            }
            completion(result)
        }
    }

    /**
     * LMS에 로그인합니다. 로그인에 성공하면 학번 정보와 토큰 정보가 캐싱되어 이후 요청들에 사용됩니다.
     *
     * @param id LMS 아이디
     * @param password LMS 비밀번호
     * @return LMS로그인에 성공하면 true, 실패하면 false를 반환합니다.
     */
    @Throws(Exception::class)
    internal suspend fun loginLMS(id: String, password: String): Boolean {
        sessionMutex.lock()
        try {
            resetSession()
            return try {
                val session = authService.login(id, password)
                lmsId = session.userId
                apiBearerToken = session.bearerToken
                isLoggined = true
                true
            } catch (throwable: Throwable) {
                withContext(NonCancellable) {
                    resetSession()
                }
                throw throwable
            }
        } finally {
            sessionMutex.unlock()
        }
    }

    /**
     * 로그인된 사용자의 수강 학기 목록을 가져옵니다.
     *
     * @return 학기 목록
     */
    @Throws(Exception::class)
    @OptIn(ExperimentalTime::class)
    internal suspend fun getTerms(): List<Term> {
        checkLoggedIn()
        return authService.getTerms(lmsId, apiBearerToken)
    }

    /**
     * 로그인된 사용자의 개인 정보(이름, 학과 등)를 가져옵니다.
     *
     * @return 사용자 정보
     */
    @Throws(Exception::class)
    internal suspend fun getLoginInfo(): Info {
        checkLoggedIn()
        return authService.getLoginInfo(lmsId)
    }

    /**
     * 현재 로그인 세션의 LMS 쿠키 목록을 가져옵니다. 외부 세션 연동 시 사용됩니다.
     *
     * @return LMS 쿠키를 담은 세션 응답
     */
    @Throws(Exception::class)
    internal suspend fun getCookies(): LmsSessionResponse {
        checkLoggedIn()
        return authService.getSession()
    }

    internal suspend fun logout() {
        sessionMutex.lock()
        try {
            resetSession()
        } finally {
            sessionMutex.unlock()
        }
    }

    /**
     * LMS 로그인을 비동기 방식으로 수행하고 그 결과를 completion 콜백으로 전달합니다.
     *
     * @param id LMS 아이디
     * @param password LMS 비밀번호
     * @param completion 결과 수신 콜백
     */
    fun loginLMS(id: String, password: String, completion: (LmsLoginResult) -> Unit) {
        launchLoginResult(completion) {
            loginLMS(id, password)
        }
    }

    /**
     * 로그인된 사용자의 학기 목록을 비동기 방식으로 조회하고 그 결과를 completion 콜백으로 전달합니다.
     *
     * @param completion 결과 수신 콜백
     */
    @OptIn(ExperimentalTime::class)
    fun getTerms(completion: (LmsTermsResult) -> Unit) {
        launchTermsResult(completion) {
            getTerms()
        }
    }

    /**
     * 로그인된 사용자의 개인 정보 조회를 비동기 방식으로 수행하고 그 결과를 completion 콜백으로 전달합니다.
     *
     * @param completion 결과 수신 콜백
     */
    fun getLoginInfo(completion: (LmsLoginInfoResult) -> Unit) {
        launchLoginInfoResult(completion) {
            getLoginInfo()
        }
    }

    /**
     * 현재 로그인 세션의 LMS 쿠키 목록 조회를 비동기 방식으로 수행하고 그 결과를 completion 콜백으로 전달합니다.
     *
     * @param completion 결과 수신 콜백
     */
    fun getCookies(completion: (LmsCookiesResult) -> Unit) {
        launchCookiesResult(completion) {
            getCookies()
        }
    }

    /**
     * 현재 로그인 세션과 사용자별 캐시를 제거한 뒤 completion 콜백을 호출합니다.
     *
     * @param completion 로그아웃 완료 콜백
     */
    fun logout(completion: () -> Unit) {
        apiScope.launch {
            logout()
            completion()
        }
    }

    /**
     * 특정 학기의 수강 과목 상세 정보(할 일, 출석, 공지, 과제 등 전체 정보)를 비동기 방식으로 조회하고 그 결과를 completion 콜백으로 전달합니다.
     *
     * @param term 학기 정보
     * @param loadingState 조회 진행률 콜백 (0.0f ~ 1.0f)
     * @param completion 결과 수신 콜백
     */
    @ExperimentalTime
    fun getSubjects(
        term: Term,
        loadingState: (Float) -> Unit = {},
        completion: (LmsSubjectsResult) -> Unit,
    ) {
        launchSubjectsResult(completion) {
            courseService.getSubjects(term, loadingState)
        }
    }

    /**
     * 제출해야 할 과제, 동영상 시청 정보 등 할 일 중심 정보를 비동기 방식으로 빠르게 조회하고 그 결과를 completion 콜백으로 전달합니다.
     *
     * @param term 학기 정보
     * @param loadingState 조회 진행률 콜백 (0.0f ~ 1.0f)
     * @param completion 결과 수신 콜백
     */
    @ExperimentalTime
    fun getTodoList(
        term: Term,
        loadingState: (Float) -> Unit = {},
        completion: (LmsSubjectsResult) -> Unit,
    ) {
        getTodoList(
            term = term,
            loadingState = loadingState,
            postHogDistinctId = null,
            completion = completion,
        )
    }

    /**
     * 제출해야 할 과제, 동영상 시청 정보 등 할 일 중심 정보를 분석 식별자 정보와 함께 비동기 방식으로 조회하고 그 결과를 completion 콜백으로 전달합니다.
     *
     * @param term 학기 정보
     * @param loadingState 조회 진행률 콜백 (0.0f ~ 1.0f)
     * @param postHogDistinctId 분석용 식별자
     * @param completion 결과 수신 콜백
     */
    @ExperimentalTime
    fun getTodoList(
        term: Term,
        loadingState: (Float) -> Unit = {},
        postHogDistinctId: String?,
        completion: (LmsSubjectsResult) -> Unit,
    ) {
        launchSubjectsResult(completion) {
            todoService.getTodoList(
                term = term,
                loadingState = loadingState,
                postHogDistinctId = postHogDistinctId,
            )
        }
    }

    /**
     * 특정 학기의 수강 과목 상세 정보(할 일, 출석, 공지, 과제 등 전체 정보)를 가져옵니다.
     *
     * @param term 학기 정보
     * @param loadingState 진행률 콜백 (0.0f ~ 1.0f)
     * @return 수강 과목 목록
     */
    @Throws(Exception::class)
    @ExperimentalTime
    internal suspend fun getSubjects(term: Term, loadingState: (Float) -> Unit = {}): List<Subject> {
        return courseService.getSubjects(term, loadingState)
    }

    /**
     * 제출해야 할 과제, 동영상 시청 정보 등 할 일 정보만 빠르게 가져옵니다. (SSU-Time 전용)
     *
     * @param term 학기 정보
     * @param loadingState 진행률 콜백 (0.0f ~ 1.0f)
     * @param postHogDistinctId 분석용 식별자
     * @return 할 일 정보가 포함된 수강 과목 목록
     */
    @Throws(Exception::class)
    @ExperimentalTime
    internal suspend fun getTodoList(
        term: Term,
        loadingState: (Float) -> Unit = {},
        postHogDistinctId: String? = null,
    ): List<Subject> {
        return todoService.getTodoList(
            term = term,
            loadingState = loadingState,
            postHogDistinctId = postHogDistinctId,
        )
    }

    /**
     * 미제출 과제/동영상 비율 통계를 계산하여 가져옵니다.
     *
     * @param term 학기 정보
     * @param loadingState 진행률 콜백 (0.0f ~ 1.0f)
     * @return 미제출 통계 정보
     */
    @Throws(Exception::class)
    @ExperimentalTime
    internal suspend fun getUnsubmittedRatioStats(
        term: Term,
        loadingState: (Float) -> Unit = {},
    ): UnsubmittedStats {
        return todoService.getUnsubmittedRatioStats(term, loadingState)
    }

    @Throws(Exception::class)
    suspend fun getTimetable(): Timetable = getTimetable(null, null)

    @Throws(Exception::class)
    suspend fun getTimetable(year: String?, semester: Semester?): Timetable {
        return timetableService.getTimetable(year, semester)
    }

    fun getTimetable(year: String?, semester: Semester?, completion: (LmsTimetableResult) -> Unit) {
        apiScope.launch {
            val result = try {
                LmsTimetableResult(success = true, timetable = getTimetable(year, semester))
            } catch (throwable: Throwable) {
                LmsTimetableResult(success = false, errorMessage = throwable.toResultMessage())
            }
            completion(result)
        }
    }

    fun getTimetable(completion: (LmsTimetableResult) -> Unit) {
        getTimetable(null, null, completion)
    }

    @JvmSynthetic
    internal fun parseTimetable(html: String): Timetable {
        return timetableService.parseTimetable(html)
    }

    @JvmSynthetic
    internal suspend fun fetchWebDynproHtml(url: String, appName: String): String {
        return webDynproService.fetchHtml(url, appName)
    }

    /**
     * 유세인트 졸업사정표 정보를 조회하여 가져옵니다.
     *
     * @return 유세인트 졸업사정표 정보
     */
    @Throws(Exception::class)
    suspend fun getGraduateTable(): GraduateTable {
        checkLoggedIn()
        return graduateTableService.getGraduateTable()
    }

    /**
     * 유세인트 졸업사정표 정보를 비동기 방식으로 조회하고 그 결과를 completion 콜백으로 전달합니다.
     *
     * @param completion 결과 수신 콜백
     */
    fun getGraduateTable(completion: (LmsGraduateTableResult) -> Unit) {
        apiScope.launch {
            val result = try {
                LmsGraduateTableResult(success = true, graduateTable = getGraduateTable())
            } catch (throwable: Throwable) {
                LmsGraduateTableResult(success = false, errorMessage = throwable.toResultMessage())
            }
            completion(result)
        }
    }

    @JvmSynthetic
    internal fun parseGraduateTable(html: String): GraduateTable {
        return graduateTableService.parseGraduateTable(html)
    }

    /**
     * 유세인트 등록금 납부 이력 정보를 조회하여 가져옵니다.
     *
     * @return 유세인트 등록금 납부 내역 데이터
     */
    @Throws(Exception::class)
    suspend fun getTuitionTable(): TuitionTable {
        checkLoggedIn()
        return tuitionTableService.getTuitionTable()
    }

    /**
     * 유세인트 등록금 납부 이력 정보를 비동기 방식으로 조회하고 그 결과를 completion 콜백으로 전달합니다.
     *
     * @param completion 결과 수신 콜백
     */
    fun getTuitionTable(completion: (LmsTuitionResult) -> Unit) {
        apiScope.launch {
            val result = try {
                LmsTuitionResult(success = true, tuitionTable = getTuitionTable())
            } catch (throwable: Throwable) {
                LmsTuitionResult(success = false, errorMessage = throwable.toResultMessage())
            }
            completion(result)
        }
    }

    @JvmSynthetic
    internal fun parseTuitionTable(html: String): TuitionTable {
        return tuitionTableService.parseTuitionTable(html)
    }

    /**
     * 유세인트 장학 수혜 이력 정보를 조회하여 가져옵니다.
     *
     * @return 유세인트 장학 수혜 내역 데이터
     */
    @Throws(Exception::class)
    suspend fun getScholarshipHistoryTable(): ScholarshipHistoryTable {
        checkLoggedIn()
        return scholarshipHistoryService.getScholarshipHistoryTable()
    }

    /**
     * 유세인트 장학 수혜 이력 정보를 비동기 방식으로 조회하고 그 결과를 completion 콜백으로 전달합니다.
     *
     * @param completion 결과 수신 콜백
     */
    fun getScholarshipHistoryTable(completion: (LmsScholarshipHistoryResult) -> Unit) {
        apiScope.launch {
            val result = try {
                LmsScholarshipHistoryResult(success = true, scholarshipHistoryTable = getScholarshipHistoryTable())
            } catch (throwable: Throwable) {
                LmsScholarshipHistoryResult(success = false, errorMessage = throwable.toResultMessage())
            }
            completion(result)
        }
    }

    @JvmSynthetic
    internal fun parseScholarshipHistoryTable(html: String): ScholarshipHistoryTable {
        return scholarshipHistoryService.parseScholarshipHistoryTable(html)
    }

    /**
     * 유세인트 성적 조회 정보를 가져옵니다.
     * 특정 학년도와 학기를 지정하여 조회하거나, null 지정 시 캐싱된 최신 성적을 가져옵니다.
     *
     * @param year 학년도 (예: "2026")
     * @param semester 학기 정보
     * @return 유세인트 성적 데이터 테이블
     */
    @Throws(Exception::class)
    suspend fun getGradeTable(year: String? = null, semester: Semester? = null): GradeTable {
        checkLoggedIn()
        return gradeService.getGradeTable(year, semester)
    }

    /**
     * 특정 학기의 유세인트 성적 조회 정보를 비동기 방식으로 조회하고 그 결과를 completion 콜백으로 전달합니다.
     *
     * @param year 학년도 (예: "2026")
     * @param semester 학기 정보
     * @param completion 결과 수신 콜백
     */
    fun getGradeTable(year: String?, semester: Semester?, completion: (LmsGradeResult) -> Unit) {
        apiScope.launch {
            val result = try {
                LmsGradeResult(success = true, gradeTable = getGradeTable(year, semester))
            } catch (throwable: Throwable) {
                LmsGradeResult(success = false, errorMessage = throwable.toResultMessage())
            }
            completion(result)
        }
    }

    /**
     * 최신 학기의 유세인트 성적 조회 정보를 비동기 방식으로 조회하고 그 결과를 completion 콜백으로 전달합니다.
     *
     * @param completion 결과 수신 콜백
     */
    fun getGradeTable(completion: (LmsGradeResult) -> Unit) {
        getGradeTable(null, null, completion)
    }

    /**
     * 유세인트 학기별 성적 요약 정보를 조회하여 가져옵니다.
     *
     * @return 유세인트 학기별 성적 요약 테이블 데이터
     */
    @Throws(Exception::class)
    suspend fun getSemesterGradeSummaryTable(): SemesterGradeSummaryTable {
        checkLoggedIn()
        return gradeService.getSemesterGradeSummaryTable()
    }

    @JvmSynthetic
    internal fun findSemesterGradeSummaryTableId(html: String): String? {
        return gradeService.findSemesterGradeSummaryTableId(html)
    }

    @JvmSynthetic
    internal fun mergeSemesterGradeSummaryTables(
        first: SemesterGradeSummaryTable,
        second: SemesterGradeSummaryTable,
    ): SemesterGradeSummaryTable {
        return gradeService.mergeSemesterGradeSummaryTables(first, second)
    }

    /**
     * 유세인트 학기별 성적 요약 정보를 비동기 방식으로 조회하고 그 결과를 completion 콜백으로 전달합니다.
     *
     * @param completion 결과 수신 콜백
     */
    fun getSemesterGradeSummaryTable(completion: (LmsSemesterGradeSummaryResult) -> Unit) {
        apiScope.launch {
            val result = try {
                LmsSemesterGradeSummaryResult(success = true, summaryTable = getSemesterGradeSummaryTable())
            } catch (throwable: Throwable) {
                LmsSemesterGradeSummaryResult(success = false, errorMessage = throwable.toResultMessage())
            }
            completion(result)
        }
    }

    @JvmSynthetic
    internal fun parseSemesterGradeSummaryTable(html: String): SemesterGradeSummaryTable {
        return gradeService.parseSemesterGradeSummaryTable(html)
    }

    @JvmSynthetic
    internal fun parseGradeTable(
        html: String,
        defaultYear: String? = null,
        defaultSemester: Semester? = null,
    ): GradeTable {
        return gradeService.parseGradeTable(html, defaultYear, defaultSemester)
    }

    /**
     * 유세인트 채플 정보를 조회하여 가져옵니다.
     * 특정 학년도와 학기를 지정하여 조회하거나, null 지정 시 캐싱된 최신 채플 내역을 가져옵니다.
     * 계절학기 조회는 불가능합니다.
     *
     * @param year 학년도 (예: "2026")
     * @param semester 학기 정보
     * @return 유세인트 채플 정보 (좌석 현황, 출결 상태, 결석계 내역 포함)
     */
    @Throws(Exception::class)
    suspend fun getChapelTable(
        year: String? = null,
        semester: Semester? = null,
    ): ChapelInformation {
        checkLoggedIn()
        return chapelService.getChapelInformation(year, semester)
    }

    /**
     * 특정 학기의 유세인트 채플 정보를 비동기 방식으로 조회하고 그 결과를 completion 콜백으로 전달합니다.
     *
     * @param year 학년도 (예: "2026")
     * @param semester 학기 정보
     * @param completion 결과 수신 콜백
     */
    fun getChapelTable(year: String?, semester: Semester?, completion: (LmsChapelResult) -> Unit) {
        apiScope.launch {
            val result = try {
                LmsChapelResult(success = true, chapelInformation = getChapelTable(year, semester))
            } catch (throwable: Throwable) {
                LmsChapelResult(success = false, errorMessage = throwable.toResultMessage())
            }
            completion(result)
        }
    }

    /**
     * 최신 학기의 유세인트 채플 정보를 비동기 방식으로 조회하고 그 결과를 completion 콜백으로 전달합니다.
     *
     * @param completion 결과 수신 콜백
     */
    fun getChapelTable(completion: (LmsChapelResult) -> Unit) {
        getChapelTable(null, null, completion)
    }

    @JvmSynthetic
    internal fun parseChapelInformation(
        html: String,
        defaultYear: String? = null,
        defaultSemester: Semester? = null,
    ): ChapelInformation {
        return chapelService.parseChapelInformation(html, defaultYear, defaultSemester)
    }

    @JvmSynthetic
    internal suspend fun loadCourseCatalogPlan(course: CourseCatalogCourse): ByteArray {
        val semester = requireNotNull(course.semester) {
            "강의계획서를 조회할 학기 정보가 없습니다. 수강편람에서 받은 과목을 사용해 주세요."
        }
        require(course.year.matches(Regex("""\d{4}"""))) {
            "강의계획서를 조회할 학년도 정보가 없습니다. 수강편람에서 받은 과목을 사용해 주세요."
        }
        return planLoadMutex.withLock {
            val query = CourseCatalogQuery(
                year = course.year,
                semester = semester,
                category = CourseCatalogCategory.SUBJECT,
                keyword = course.subjectCode,
            )
            val viewerUrl = courseCatalogService.getPlanUrl(
                query = query,
                subjectCode = course.subjectCode,
                section = course.section,
            )
            loadPlanPdf(viewerUrl)
        }
    }

    @JvmSynthetic
    internal suspend fun loadPreRegistrationPlan(course: PreRegistrationCourse): ByteArray {
        checkLoggedIn()
        return planLoadMutex.withLock {
            val viewerUrl = preRegistrationService.getPlanUrl(
                subjectCode = course.subjectCode,
                section = course.section,
            )
            loadPlanPdf(viewerUrl)
        }
    }

    private suspend fun loadPlanPdf(viewerUrl: String): ByteArray {
        require(viewerUrl.isNotBlank()) { "이 과목에는 조회할 수 있는 강의계획서가 없습니다." }
        val cookieUrls = listOf(
            Url(viewerUrl),
            Url("https://ecc.ssu.ac.kr:8443/"),
            Url("https://ecc.ssu.ac.kr:8443/sap/bc/webdynpro/SAP/ZCMW2100"),
            Url("https://ecc.ssu.ac.kr:8443/sap/bc/webdynpro/SAP/ZCMW2240"),
        )
        val cookies = cookieUrls.flatMap { cookieUrl ->
            lmsCookiesStorage.get(cookieUrl).map { cookie -> cookieUrl to cookie }
        }.distinctBy { (cookieUrl, cookie) ->
            listOf(cookie.domain ?: cookieUrl.host, cookie.path ?: "/", cookie.name)
        }.map { (cookieUrl, cookie) ->
            OzPlanCookie(
                name = cookie.name,
                value = cookie.value,
                domain = cookie.domain ?: cookieUrl.host,
                path = cookie.path ?: "/",
                secure = cookie.secure,
                httpOnly = cookie.httpOnly,
            )
        }
        return requirePdf(loadOzPlanPdf(viewerUrl, cookies))
    }

    /**
     * 유세인트 예비수강신청 장바구니 내역을 조회하여 가져옵니다.
     *
     * 강의계획서 PDF는 목록 조회 중 만들지 않습니다. 사용자가 과목을 선택한 시점에
     * 해당 [PreRegistrationCourse.loadPlan]을 호출합니다.
     *
     * @return 예비수강신청 과목 및 신청 가능 학점 정보
     */
    @Throws(Exception::class)
    suspend fun getPreRegistrationTable(): PreRegistrationTable {
        checkLoggedIn()
        return preRegistrationService.getPreRegistrationTable()
    }

    /**
     * 유세인트 예비수강신청 장바구니 내역을 비동기 방식으로 조회하고 결과를 completion 콜백으로 전달합니다.
     *
     * @param completion 결과 수신 콜백
     */
    fun getPreRegistrationTable(completion: (LmsPreRegistrationResult) -> Unit) {
        apiScope.launch {
            val result = try {
                LmsPreRegistrationResult(
                    success = true,
                    preRegistrationTable = getPreRegistrationTable(),
                )
            } catch (throwable: Throwable) {
                LmsPreRegistrationResult(success = false, errorMessage = throwable.toResultMessage())
            }
            completion(result)
        }
    }

    /**
     * 예비수강신청 장바구니의 과목 한 건에 대해 아직 접근하지 않은 계획서 URL을 발급합니다.
     *
     * 같은 과목번호가 여러 분반에 존재하면 [section]으로 구분할 수 있습니다. 빈 문자열을
     * 전달하면 처음 일치하는 과목을 사용합니다. 반환된 주소는 서버 정책상 일회성이므로
     * 발급 직후 열어야 하며, 다음 계획서 URL을 발급하면 기존 주소가 무효화될 수 있습니다.
     */
    @Deprecated(
        message = "OZ Viewer URL은 다른 브라우저에서 재사용할 수 없습니다. 과목의 loadPlan()을 사용하세요.",
    )
    @Throws(Exception::class)
    suspend fun getPreRegistrationPlanUrl(
        subjectCode: String,
        section: String = "",
    ): String {
        checkLoggedIn()
        return preRegistrationService.getPlanUrl(subjectCode, section)
    }

    @Deprecated(
        message = "OZ Viewer URL은 다른 브라우저에서 재사용할 수 없습니다. 과목의 loadPlan()을 사용하세요.",
    )
    @Suppress("DEPRECATION")
    fun getPreRegistrationPlanUrl(
        subjectCode: String,
        section: String = "",
        completion: (LmsPlanResult) -> Unit,
    ) {
        apiScope.launch {
            val result = try {
                LmsPlanResult(
                    success = true,
                    plan = getPreRegistrationPlanUrl(subjectCode, section),
                )
            } catch (throwable: Throwable) {
                LmsPlanResult(success = false, errorMessage = throwable.toResultMessage())
            }
            completion(result)
        }
    }

    @JvmSynthetic
    internal fun parsePreRegistrationTable(html: String): PreRegistrationTable {
        return preRegistrationService.parsePreRegistrationTable(html)
    }

    /**
     * 수강편람 검색 화면에서 현재 선택 가능한 콤보박스 옵션을 가져옵니다.
     *
     * 상위 필터 선택에 따라 하위 옵션이 달라질 수 있으므로, 선택한 키는 [query]의
     * `filterKeys`에 순서대로 넣어 다시 호출할 수 있습니다. 이 화면은 익명 접근이
     * 가능하므로 LMS 로그인 없이도 사용할 수 있습니다.
     */
    @Throws(Exception::class)
    suspend fun getCourseCatalogSearchOptions(
        query: CourseCatalogQuery,
    ): CourseCatalogSearchOptions {
        return courseCatalogService.getSearchOptions(query)
    }

    fun getCourseCatalogSearchOptions(
        query: CourseCatalogQuery,
        completion: (LmsCourseCatalogOptionsResult) -> Unit,
    ) {
        apiScope.launch {
            val result = try {
                LmsCourseCatalogOptionsResult(
                    success = true,
                    options = getCourseCatalogSearchOptions(query),
                )
            } catch (throwable: Throwable) {
                LmsCourseCatalogOptionsResult(
                    success = false,
                    errorMessage = throwable.toResultMessage(),
                )
            }
            completion(result)
        }
    }

    /**
     * 수강편람을 검색하여 모든 페이지의 강좌를 반환합니다.
     *
     * 서버의 페이지당 줄수는 최대값인 500으로 설정하고, 결과가 여러 페이지거나
     * 가상 스크롤로 나뉘면 모두 순회해 하나의 [CourseCatalogTable]로 합칩니다.
     * 계획서 PDF는 목록 조회 중 만들지 않으며, 사용자가 강좌를 선택한 시점에 해당
     * [CourseCatalogCourse.loadPlan]을 호출합니다.
     */
    @Throws(Exception::class)
    suspend fun getCourseCatalogTable(query: CourseCatalogQuery): CourseCatalogTable {
        return courseCatalogService.search(query)
    }

    fun getCourseCatalogTable(
        query: CourseCatalogQuery,
        completion: (LmsCourseCatalogResult) -> Unit,
    ) {
        apiScope.launch {
            val result = try {
                LmsCourseCatalogResult(
                    success = true,
                    courseCatalogTable = getCourseCatalogTable(query),
                )
            } catch (throwable: Throwable) {
                LmsCourseCatalogResult(
                    success = false,
                    errorMessage = throwable.toResultMessage(),
                )
            }
            completion(result)
        }
    }

    /**
     * 수강편람 강좌 한 건에 대해 아직 접근하지 않은 계획서 URL을 발급합니다.
     *
     * 목록을 조회할 때 사용한 [query]와 강좌의 과목번호·분반을 전달해야 합니다.
     * [section]이 비어 있으면 처음 일치하는 강좌를 사용합니다. 반환된 URL에는 라이브러리가
     * 접속하지 않으며, 서버 정책상 일회성이므로 발급 직후 열어야 합니다.
     */
    @Deprecated(
        message = "OZ Viewer URL은 다른 브라우저에서 재사용할 수 없습니다. 과목의 loadPlan()을 사용하세요.",
    )
    @Throws(Exception::class)
    suspend fun getCourseCatalogPlanUrl(
        query: CourseCatalogQuery,
        subjectCode: String,
        section: String = "",
    ): String {
        return courseCatalogService.getPlanUrl(query, subjectCode, section)
    }

    @Deprecated(
        message = "OZ Viewer URL은 다른 브라우저에서 재사용할 수 없습니다. 과목의 loadPlan()을 사용하세요.",
    )
    @Suppress("DEPRECATION")
    fun getCourseCatalogPlanUrl(
        query: CourseCatalogQuery,
        subjectCode: String,
        section: String = "",
        completion: (LmsPlanResult) -> Unit,
    ) {
        apiScope.launch {
            val result = try {
                LmsPlanResult(
                    success = true,
                    plan = getCourseCatalogPlanUrl(query, subjectCode, section),
                )
            } catch (throwable: Throwable) {
                LmsPlanResult(success = false, errorMessage = throwable.toResultMessage())
            }
            completion(result)
        }
    }

    @JvmSynthetic
    internal fun parseCourseCatalogTable(
        html: String,
        query: CourseCatalogQuery,
    ): CourseCatalogTable {
        return courseCatalogService.parseTable(html, query)
    }

}

@JvmSynthetic
internal fun normalizePem(raw: String): String {
    return raw
        .replace("\\n", "\n")
        .replace("\\r", "")
        .replace("-----BEGIN RSA PRIVATE KEY-----", "-----BEGIN RSA PRIVATE KEY-----\n")
        .replace("-----END RSA PRIVATE KEY-----", "\n-----END RSA PRIVATE KEY-----")
        .trim()
}

@JvmSynthetic
internal fun String.decodeHtmlEntities(): String {
    val regex = Regex("&(?:#(x?[0-9a-fA-F]+)|([a-zA-Z0-9]+));")
    return regex.replace(this) { matchResult ->
        val hexOrDec = matchResult.groups[1]?.value
        val name = matchResult.groups[2]?.value
        if (hexOrDec != null) {
            try {
                val codePoint = if (hexOrDec.startsWith("x", ignoreCase = true)) {
                    hexOrDec.substring(1).toInt(16)
                } else {
                    hexOrDec.toInt()
                }
                codePoint.toChar().toString()
            } catch (_: Exception) {
                matchResult.value
            }
        } else if (name != null) {
            when (name) {
                "nbsp" -> " "
                "lt" -> "<"
                "gt" -> ">"
                "amp" -> "&"
                "quot" -> "\""
                "apos" -> "'"
                else -> matchResult.value
            }
        } else {
            matchResult.value
        }
    }
}

@JvmSynthetic
internal fun String.stripHtmlTags(): String {
    val withNewlines = this.replace(Regex("""<br\b[^>]*>""", RegexOption.IGNORE_CASE), "\n")
    val stripped = withNewlines.replace(Regex("""<[^>]+>"""), "")
    return stripped.decodeHtmlEntities().trim()
}

internal expect fun pemToString(rawPem: String, rawPw: String): String
