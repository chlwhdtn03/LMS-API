package io.github.chlwhdtn03

import io.github.chlwhdtn03.data.Cyber.CyberEvaluation
import io.github.chlwhdtn03.data.Cyber.CyberEvaluationType
import io.github.chlwhdtn03.data.Cyber.CyberSubject
import io.github.chlwhdtn03.data.Cyber.CyberWeek
import io.github.chlwhdtn03.internal.CyberAuthService
import io.github.chlwhdtn03.internal.CyberCourseService
import io.github.chlwhdtn03.internal.CyberTodoService
import io.github.chlwhdtn03.internal.TodoSnapshotTracker
import io.ktor.client.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.plugins.cookies.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json

private val cyberJson = Json {
    ignoreUnknownKeys = true
    isLenient = true
    coerceInputValues = true
}

internal val cyberClient = HttpClient {
    install(HttpCookies)
    install(ContentNegotiation) {
        json(cyberJson)
    }
    followRedirects = true
}

private val cyberApiScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

/**
 * 숭실사이버대학교(KCU)의 포털(`portal.kcu.ac`)·LMS(`lms.kcu.ac`) 정보를 가져오는
 * 공개 진입점입니다. 완전히 다른 도메인의 시스템이므로 [io.github.chlwhdtn03.LmsApi]와
 * 세션/데이터 모델을 공유하지 않습니다.
 */
object CyberApi {
    var isLoggined = false
        private set
    private val sessionMutex = Mutex()

    private val authService = CyberAuthService(cyberClient)
    private val courseService = CyberCourseService(cyberClient)
    private val trackedTodoSnapshotDatesByDistinctId = mutableMapOf<String, String>()
    private val todoService = CyberTodoService(
        courseService,
        TodoSnapshotTracker(cyberClient, cyberApiScope, trackedTodoSnapshotDatesByDistinctId, eventSuffix = "_cyber"),
    )

    private fun checkLoggedIn() {
        if (!isLoggined) {
            throw IllegalStateException("사이버대학교 LMS 로그인이 되어있지 않습니다.")
        }
    }

    private fun Throwable.toResultMessage(): String {
        return message ?: "알 수 없는 오류가 발생했습니다."
    }

    /**
     * 사이버대학교 포털에 로그인합니다.
     *
     * @param id 사이버대학교 포털 아이디
     * @param password 사이버대학교 포털 비밀번호
     * @return 로그인에 성공하면 true, 실패하면 false를 반환합니다.
     */
    @Throws(Exception::class)
    suspend fun login(id: String, password: String): Boolean {
        sessionMutex.lock()
        try {
            isLoggined = false
            authService.login(id, password)
            isLoggined = true
            return true
        } catch (throwable: Throwable) {
            isLoggined = false
            throw throwable
        } finally {
            sessionMutex.unlock()
        }
    }

    /**
     * 사이버대학교 포털 로그인을 비동기 방식으로 수행하고 그 결과를 completion 콜백으로 전달합니다.
     *
     * @param id 사이버대학교 포털 아이디
     * @param password 사이버대학교 포털 비밀번호
     * @param completion 결과 수신 콜백
     */
    fun login(id: String, password: String, completion: (CyberLoginResult) -> Unit) {
        cyberApiScope.launch {
            val result = try {
                CyberLoginResult(success = login(id, password))
            } catch (throwable: Throwable) {
                CyberLoginResult(success = false, errorMessage = throwable.toResultMessage())
            }
            completion(result)
        }
    }

    /**
     * 로그인된 사용자의 이번 학기 수강과목 목록을 가져옵니다.
     *
     * @return 수강과목 목록
     */
    @Throws(Exception::class)
    suspend fun getSubjects(): List<CyberSubject> {
        return getSubjects(postHogDistinctId = null)
    }

    /**
     * 수강과목 목록을 조회하고, 식별자가 있으면 전체 과목의 강의·퀴즈·과제 상태를 수집합니다.
     * 사용자별 하루 한 번 20% 확률로 todo_snapshot_cyber와 $identify를 전송합니다.
     * null 또는 빈 식별자이면 추가 조회와 분석 전송을 하지 않습니다.
     */
    @Throws(Exception::class)
    suspend fun getSubjects(postHogDistinctId: String?): List<CyberSubject> {
        checkLoggedIn()
        return todoService.getSubjects(postHogDistinctId)
    }

    /**
     * 로그인된 사용자의 이번 학기 수강과목 목록을 비동기 방식으로 조회하고 그 결과를 completion 콜백으로 전달합니다.
     *
     * @param completion 결과 수신 콜백
     */
    fun getSubjects(completion: (CyberSubjectsResult) -> Unit) {
        getSubjects(postHogDistinctId = null, completion = completion)
    }

    /** 분석 식별자를 전달하면 전체 과목의 강의·퀴즈·과제 스냅샷도 수집합니다. */
    fun getSubjects(postHogDistinctId: String?, completion: (CyberSubjectsResult) -> Unit) {
        cyberApiScope.launch {
            val result = try {
                CyberSubjectsResult(success = true, subjects = getSubjects(postHogDistinctId))
            } catch (throwable: Throwable) {
                CyberSubjectsResult(success = false, errorMessage = throwable.toResultMessage())
            }
            completion(result)
        }
    }

    /**
     * 특정 수강과목의 주차별 수강일람(출석, 진도율, 학습시간, 학습 기한 등)을 가져옵니다.
     *
     * @param subject [getSubjects]로 가져온 수강과목
     * @return 주차별 수강일람 목록
     */
    @Throws(Exception::class)
    suspend fun getWeeklyLectures(subject: CyberSubject): List<CyberWeek> {
        checkLoggedIn()
        return courseService.getWeeklyLectures(subject)
    }

    /**
     * 특정 수강과목의 주차별 수강일람을 비동기 방식으로 조회하고 그 결과를 completion 콜백으로 전달합니다.
     *
     * @param subject [getSubjects]로 가져온 수강과목
     * @param completion 결과 수신 콜백
     */
    fun getWeeklyLectures(subject: CyberSubject, completion: (CyberWeeklyLecturesResult) -> Unit) {
        cyberApiScope.launch {
            val result = try {
                CyberWeeklyLecturesResult(success = true, weeks = getWeeklyLectures(subject))
            } catch (throwable: Throwable) {
                CyberWeeklyLecturesResult(success = false, errorMessage = throwable.toResultMessage())
            }
            completion(result)
        }
    }

    /**
     * 특정 수강과목의 학습평가응시 목록(시험, 퀴즈, 과제, 토론, 기타)을 가져옵니다.
     * 출석 항목과, 제목이나 기간이 비어 있는(아직 등록되지 않은) 퀴즈/과제는 제외됩니다.
     *
     * LMS 서버가 세션에 저장된 "현재 강의실" 기준으로 목록을 응답하므로, 내부적으로 수강일람을
     * 먼저 요청해 강의실을 [subject]로 전환한 뒤 목록을 조회합니다.
     *
     * @param subject [getSubjects]로 가져온 수강과목
     * @return 학습평가 목록
     */
    @Throws(Exception::class)
    suspend fun getEvaluations(subject: CyberSubject): List<CyberEvaluation> {
        checkLoggedIn()
        return courseService.getEvaluations(subject)
    }

    /**
     * 특정 수강과목의 학습평가응시 목록을 비동기 방식으로 조회하고 그 결과를 completion 콜백으로 전달합니다.
     *
     * @param subject [getSubjects]로 가져온 수강과목
     * @param completion 결과 수신 콜백
     */
    fun getEvaluations(subject: CyberSubject, completion: (CyberEvaluationsResult) -> Unit) {
        cyberApiScope.launch {
            val result = try {
                CyberEvaluationsResult(success = true, evaluations = getEvaluations(subject))
            } catch (throwable: Throwable) {
                CyberEvaluationsResult(success = false, errorMessage = throwable.toResultMessage())
            }
            completion(result)
        }
    }

    /**
     * 특정 수강과목의 학습평가 중 퀴즈와 과제만 가져옵니다.
     *
     * @param subject [getSubjects]로 가져온 수강과목
     * @return 퀴즈/과제 목록
     */
    @Throws(Exception::class)
    suspend fun getQuizzesAndAssignments(subject: CyberSubject): List<CyberEvaluation> {
        return getEvaluations(subject).filter {
            it.type == CyberEvaluationType.QUIZ || it.type == CyberEvaluationType.ASSIGNMENT
        }
    }

    /**
     * 특정 수강과목의 퀴즈/과제 목록을 비동기 방식으로 조회하고 그 결과를 completion 콜백으로 전달합니다.
     *
     * @param subject [getSubjects]로 가져온 수강과목
     * @param completion 결과 수신 콜백
     */
    fun getQuizzesAndAssignments(subject: CyberSubject, completion: (CyberEvaluationsResult) -> Unit) {
        cyberApiScope.launch {
            val result = try {
                CyberEvaluationsResult(success = true, evaluations = getQuizzesAndAssignments(subject))
            } catch (throwable: Throwable) {
                CyberEvaluationsResult(success = false, errorMessage = throwable.toResultMessage())
            }
            completion(result)
        }
    }

    suspend fun logout() {
        sessionMutex.withLock {
            isLoggined = false
        }
    }

    fun logout(completion: () -> Unit) {
        cyberApiScope.launch {
            logout()
            completion()
        }
    }
}

/** RSA 공개키(16진수 modulus/exponent)로 [plainText]를 PKCS#1 v1.5 암호화해 짝수 길이 16진 문자열로 반환합니다. */
internal expect fun cyberRsaEncrypt(modulusHex: String, exponentHex: String, plainText: String): String
