# LMS-API (SSU LMS & U-Saint Kotlin Multiplatform API)

숭실대학교 LMS(Canvas, LearningX) 및 유세인트(U-Saint)의 학기, 강의, 할 일, 출석, 공지, 시간표, 성적, 채플, 등록금, 장학금, 졸업사정표 등의 정보를 조회하기 위한 Kotlin Multiplatform 라이브러리입니다.

이 README는 **Swift Package Manager(SPM)**를 이용한 iOS 연동과 **Gradle**을 이용한 Android/Kotlin 연동을 기준으로 작성되었습니다.

---

## 지원 플랫폼

- Android
- JVM
- iOS device: `iosArm64`
- macOS Apple Silicon: `macosArm64`

현재 소스 빌드에는 JavaScript target과 iOS simulator target(`iosX64`, `iosSimulatorArm64`)이 포함되어 있지 않습니다. iOS 앱 연동은 GitHub Release에 배포된 XCFramework를 사용하는 SPM 방식을 권장합니다.

---

## 리팩토링 이후 알아둘 점

- 기존 앱에서 사용하는 `LmsApi` 싱글톤과 로그인·조회 콜백 API는 그대로 유지됩니다.
- JavaScript 지원과 관련 의존성은 제거되었습니다.
- `parse*`, `merge*`, `find*`, `fetchWebDynproHtml` 같은 원본 응답 처리 함수는 내부 구현으로 변경되었습니다. 앱에서는 이 함수들을 직접 호출하지 말고 `get*` 조회 API를 사용해야 합니다.
- `LmsApi`는 프로세스 내에서 로그인 세션, 토큰, 쿠키와 일부 최신 조회 조건을 공유합니다. 새 로그인을 시작하면 이전 사용자의 세션과 캐시를 먼저 비우며, `logout`도 로그인 상태와 사용자별 캐시를 제거합니다.
- U-Saint Web Dynpro의 화면용 secure ID와 form action은 API 호출마다 새로 만들고 해당 호출 안에서만 사용합니다. 따라서 등록금과 장학금 등을 순서를 바꾸거나 반복 호출해도 이전 화면의 부분 응답이 다음 조회를 오염시키지 않습니다. 초기 화면이 일시적인 SAP 오류를 반환할 때는 로그인 쿠키를 유지한 채 최대 3회까지 다시 초기화하므로 별도 로그인이 필요하지 않습니다.
- 로그인 및 LMS 조회(`getTerms`, `getTodoList`, `getSubjects` 등)는 외부에 콜백 API로 제공됩니다. U-Saint 조회 API는 Kotlin의 `suspend` 함수와 결과 콜백을 모두 제공합니다.
- 기능별 구현은 `internal` 서비스로 분리되었지만 외부 사용법은 변경되지 않았습니다. 내부 구조와 기능 추가 규칙은 [내부 구현 가이드](library/src/commonMain/kotlin/io/github/chlwhdtn03/internal/README.md)를 참고하세요.

---

## 라이브러리 추가하기 (Installation)

### 1. iOS에서 Swift Package Manager(SPM)로 추가하기

iOS 앱 프로젝트에서는 Xcode의 Swift Package Manager를 통해 의존성을 추가할 수 있습니다.

1. Xcode에서 앱 프로젝트를 엽니다.
2. 상단 메뉴에서 `File > Add Package Dependencies...`를 선택합니다.
3. 검색창에 이 저장소 URL을 입력합니다.
   ```text
   https://github.com/chlwhdtn03/LMS-API
   ```
4. Dependency Rule에서 사용할 버전을 선택합니다.
5. Package Product 목록에서 `LmsApi`를 선택하고 앱 target에 추가합니다.

Swift 파일에서는 다음과 같이 import 합니다.
```swift
import LmsApi
```

> [!NOTE]
> SPM은 내부적으로 GitHub Release에 업로드된 `LmsApi.xcframework.zip`을 내려받아 사용하도록 구성되어 있습니다.
> SPM에서 설치되는 바이너리 버전은 루트 `Package.swift`의 Release URL을 따르며, Maven 배포 버전과 별도로 갱신될 수 있습니다.

### 2. Android / Kotlin에서 Gradle로 추가하기

Android 또는 Kotlin Multiplatform(KMP) 프로젝트에서는 Gradle 의존성으로 추가하여 사용할 수 있습니다.

**Android 단일 프로젝트 (`build.gradle.kts`):**
```kotlin
dependencies {
    implementation("io.github.chlwhdtn03:lms:1.6.3.1")
}
```

**Kotlin Multiplatform 프로젝트 (`commonMain` 의존성):**
```kotlin
kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation("io.github.chlwhdtn03:lms:1.6.3.1")
        }
    }
}
```

---

## 기본 흐름 및 로그인 (Authentication)

LMS 조회 기능과 유세인트(U-Saint) 조회 기능은 모두 **LMS 로그인 완료 후** 생성된 세션을 공유하여 호출할 수 있습니다. 로그인에 성공하면 학번 정보와 토큰 정보가 내부적으로 캐싱되어 이후 호출되는 API에 자동으로 적용됩니다.

### iOS (Swift) 로그인 예시
```swift
import LmsApi

func performLogin() {
    LmsApi.shared.loginLMS(id: "학번", password: "비밀번호") { result in
        if result.success {
            print("로그인 성공")
        } else {
            print("로그인 실패: \(result.errorMessage ?? "알 수 없는 오류")")
        }
    }
}
```

### Android (Kotlin) 로그인 예시
```kotlin
import io.github.chlwhdtn03.LmsApi

fun performLogin() {
    LmsApi.loginLMS(id = "학번", password = "비밀번호") { result ->
        if (result.success) {
            println("로그인 성공")
        } else {
            println("로그인 실패: ${result.errorMessage}")
        }
    }
}
```

---

## 1. 유세인트(U-Saint) 조회 기능 사용법

유세인트 조회 기능은 시간표, 성적, 채플, 등록금, 장학금, 졸업사정표 데이터를 비동기식으로 파싱하여 반환합니다. Kotlin에서는 `suspend` 함수 또는 결과 콜백을 사용할 수 있으며, iOS에서는 Kotlin/Native가 변환한 비동기 API를 Swift `async/await` 또는 결과 콜백 형태로 사용할 수 있습니다.

### iOS (Swift - Async/Await) 사용 예시
```swift
import LmsApi

func loadUSaintInformation() {
    Task {
        do {
            // 1. 시간표 조회
            let timetable = try await LmsApi.shared.getTimetable()
            print("시간표 학기: \(timetable.year) \(timetable.semester)")
            for item in timetable.items {
                print("- [\(item.subject)] \(item.classroom) / \(item.professor)")
            }
            
            // 2. 성적 상세 조회 (year, semester에 nil을 전달하면 캐싱된 최근 학기 성적을 조회합니다.)
            let gradeTable = try await LmsApi.shared.getGradeTable(year: nil, semester: nil)
            for grade in gradeTable.items {
                print("- \(grade.subjectName): \(grade.grade) (\(grade.credits)학점)")
            }
            
            // 3. 성적 요약 조회 (학기별 신청학점, 평점평균, 석차 등)
            let gradeSummary = try await LmsApi.shared.getSemesterGradeSummaryTable()
            for summary in gradeSummary.items {
                print("- \(summary.year)년 \(summary.semester?.name ?? "")학기 평점: \(summary.gpa)")
            }
            
            // 4. 채플 출결 및 좌석 조회 (year, semester에 nil을 전달하면 캐싱된 최근 채플 내역을 조회합니다.)
            let chapel = try await LmsApi.shared.getChapelTable(year: nil, semester: nil)
            print("배정 좌석 번호: \(chapel.seatStatusTable.items.first?.seatNo ?? "없음")")
            
            // 5. 등록금 납부 내역 조회
            let tuition = try await LmsApi.shared.getTuitionTable()
            for record in tuition.items {
                print("- \(record.year) \(record.semester) 납부 금액: \(record.paymentAmount)")
            }
            
            // 6. 장학 수혜 내역 조회
            let scholarship = try await LmsApi.shared.getScholarshipHistoryTable()
            for item in scholarship.items {
                print("- \(item.year)학년도 \(item.semester)학기 [\(item.scholarshipName)] 수혜 금액: \(item.actualAmount)")
            }
            
            // 7. 졸업사정표 조회
            let graduate = try await LmsApi.shared.getGraduateTable()
            for row in graduate.items {
                print("- \(row.classification) (졸업요건: \(row.standardValue)학점 / 취득: \(row.calculatedValue)학점)")
            }
            
        } catch {
            print("유세인트 정보 조회 실패: \(error.localizedDescription)")
        }
    }
}
```

### Android (Kotlin) 사용 예시
```kotlin
import io.github.chlwhdtn03.LmsApi
import io.github.chlwhdtn03.data.Lms.Semester

fun loadUSaintForAndroid() {
    // 1. 시간표 조회
    LmsApi.getTimetable { result ->
        if (result.success && result.timetable != null) {
            println("시간표 학기: ${result.timetable.year} ${result.timetable.semester}")
        }
    }
    
    // 2. 성적 상세 조회
    LmsApi.getGradeTable(year = "2026", semester = Semester.FIRST) { result ->
        if (result.success && result.gradeTable != null) {
            result.gradeTable.items.forEach { cell ->
                println("${cell.subjectName}: ${cell.grade}")
            }
        }
    }
    
    // 3. 성적 요약 조회
    LmsApi.getSemesterGradeSummaryTable { result ->
        if (result.success && result.summaryTable != null) {
            result.summaryTable.items.forEach { summary ->
                println("${summary.year}학년도 평점평균: ${summary.gpa}")
            }
        }
    }
    
    // 4. 채플 조회
    LmsApi.getChapelTable(year = null, semester = null) { result ->
        if (result.success && result.chapelInformation != null) {
            println("결석 횟수: ${result.chapelInformation.seatStatusTable.items.firstOrNull()?.absenceCount}")
        }
    }
    
    // 5. 등록금 납부 내역 조회
    LmsApi.getTuitionTable { result ->
        if (result.success && result.tuitionTable != null) {
            println("마지막 납부액: ${result.tuitionTable.items.firstOrNull()?.paymentAmount}")
        }
    }
    
    // 6. 장학 수혜 내역 조회
    LmsApi.getScholarshipHistoryTable { result ->
        if (result.success && result.scholarshipHistoryTable != null) {
            println("수혜 장학금명: ${result.scholarshipHistoryTable.items.firstOrNull()?.scholarshipName}")
        }
    }
    
    // 7. 졸업사정표 조회
    LmsApi.getGraduateTable { result ->
        if (result.success && result.graduateTable != null) {
            println("이수결과: ${result.graduateTable.items.firstOrNull()?.result}")
        }
    }
}
```

---

## 2. LMS 조회 기능 사용법

LMS 조회 기능은 학기 목록, 강의 목록, 할 일(과제 및 동영상 시청 기한), 출석, 공지, 제출 및 점수 정보를 조회할 수 있는 기능을 제공합니다.

### iOS (Swift - Callback) 사용 예시
```swift
import LmsApi

func loadLMSTodos() {
    LmsApi.shared.getTerms { termsResult in
        guard termsResult.success else {
            print("학기 조회 실패: \(termsResult.errorMessage ?? "알 수 없는 오류")")
            return
        }

        guard let latestTerm = termsResult.terms.last else {
            print("조회 가능한 학기가 없습니다.")
            return
        }

        LmsApi.shared.getTodoList(
            term: latestTerm,
            loadingState: { progress in
                print("진행률: \(Int(progress.floatValue * 100))%")
            },
            postHogDistinctId: nil
        ) { subjectsResult in
            guard subjectsResult.success else {
                print("할 일 조회 실패: \(subjectsResult.errorMessage ?? "알 수 없는 오류")")
                return
            }

            for subject in subjectsResult.subjects {
                print("과목: \(subject.name)")
                for todo in subject.todoList {
                    print("- \(todo.title) (마감일: \(todo.due_date))")
                }
            }
        }
    }
}
```

### Android (Kotlin) 사용 예시
```kotlin
import io.github.chlwhdtn03.LmsApi

fun loadLMSForAndroid() {
    LmsApi.getTerms { termsResult ->
        if (termsResult.success) {
            val latestTerm = termsResult.terms.lastOrNull() ?: return@getTerms
            
            LmsApi.getTodoList(
                term = latestTerm,
                loadingState = { progress ->
                    println("loading: ${(progress * 100).toInt()}%")
                },
                completion = { subjectsResult ->
                    if (subjectsResult.success) {
                        subjectsResult.subjects.forEach { subject ->
                            subject.todoList.forEach { todo ->
                                println("[${subject.name}] ${todo.title} / ${todo.due_date}")
                            }
                        }
                    }
                }
            )
        }
    }
}
```

---

## 공개 API 레퍼런스

### 1. LMS 관련 API

#### `LmsApi.loginLMS`
```kotlin
fun loginLMS(id: String, password: String, completion: (LmsLoginResult) -> Unit)
```
LMS 아이디와 비밀번호로 로그인합니다. 새 로그인 시도 전에 기존 세션을 제거하며, 로그인 후 사용자 정보가 실제로 조회된 경우에만 성공 처리합니다. 성공 시 유세인트 세션도 자동으로 공유됩니다.

로그인 여부는 외부에서 읽기만 가능한 `LmsApi.isLoggined`로 확인할 수 있습니다. 기존 호환성을 위해 현재 철자를 유지합니다.

#### `LmsApi.logout`
```kotlin
fun logout(completion: () -> Unit)
```
현재 로그인 상태와 쿠키, 사용자별 성적·채플 최신 조회 조건 캐시를 제거합니다.

#### `LmsApi.getTerms`
```kotlin
fun getTerms(completion: (LmsTermsResult) -> Unit)
```
로그인된 사용자의 학기 목록을 가져옵니다.

#### `LmsApi.getTodoList`
```kotlin
fun getTodoList(term: Term, loadingState: (Float) -> Unit = {}, completion: (LmsSubjectsResult) -> Unit)

fun getTodoList(
    term: Term,
    loadingState: (Float) -> Unit = {},
    postHogDistinctId: String?,
    completion: (LmsSubjectsResult) -> Unit,
)
```
과목 기본 정보와 할 일 목록(과제, 동영상 등), 제출 정보를 빠르게 파싱하여 가져옵니다.

`postHogDistinctId`가 없는 오버로드를 사용하거나 `null`을 전달하면 분석 데이터를 전송하지 않습니다. 식별자를 전달하면 식별자별 하루 한 번 전송 여부를 20% 비율로 샘플링하며, 선택된 경우 Todo 동기화 통계와 항목 상태 스냅샷을 PostHog로 전송합니다. 이를 의도한 앱에서만 사용하세요.

#### `LmsApi.getSubjects`
```kotlin
fun getSubjects(term: Term, loadingState: (Float) -> Unit = {}, completion: (LmsSubjectsResult) -> Unit)
```
과목 기본 정보, 할 일, 출석, 공지, 제출 및 점수 데이터를 모두 가져옵니다. 여러 API를 내부적으로 호출하므로 `getTodoList`에 비해 무겁습니다.

#### `LmsApi.getLoginInfo`
```kotlin
fun getLoginInfo(completion: (LmsLoginInfoResult) -> Unit)
```
로그인한 학생의 이름, 학과, 로그인 ID, 이메일 등의 기본 신원 정보를 가져옵니다.

#### `LmsApi.getCookies`
```kotlin
fun getCookies(completion: (LmsCookiesResult) -> Unit)
```
현재 로그인된 세션의 쿠키 목록을 반환합니다. 외부 서비스와의 연동 시 사용합니다.

#### Notice API
```kotlin
// 창업지원단 공지사항 조회
fun loadStartUpNotices(pageNum: Int = 1, completion: (StartUpNoticesResult) -> Unit)

// 장학 공지사항 조회
fun loadScholarships(pageNum: Int = 1, completion: (ScholarshipNoticesResult) -> Unit)
```

---

### 2. U-Saint 관련 API

#### `LmsApi.getTimetable`
```kotlin
suspend fun getTimetable(): Timetable
suspend fun getTimetable(year: String?, semester: Semester?): Timetable
fun getTimetable(completion: (LmsTimetableResult) -> Unit)
fun getTimetable(year: String?, semester: Semester?, completion: (LmsTimetableResult) -> Unit)
```
개인의 유세인트 시간표를 조회합니다. 학년도와 학기를 생략하면 유세인트가 제공하는 기본 조회 기간을 사용합니다.

#### `LmsApi.getGradeTable`
```kotlin
suspend fun getGradeTable(year: String? = null, semester: Semester? = null): GradeTable
fun getGradeTable(completion: (LmsGradeResult) -> Unit)
fun getGradeTable(year: String?, semester: Semester?, completion: (LmsGradeResult) -> Unit)
```
지정된 학년도 및 학기의 성적 상세 내역을 조회합니다. 파라미터가 모두 `null`일 경우 캐싱된 최신 학기 성적을 반환합니다.

#### `LmsApi.getSemesterGradeSummaryTable`
```kotlin
suspend fun getSemesterGradeSummaryTable(): SemesterGradeSummaryTable
fun getSemesterGradeSummaryTable(completion: (LmsSemesterGradeSummaryResult) -> Unit)
```
전체 학기별 신청학점, 취득학점, 평점평균 및 석차 정보가 담긴 성적 요약 데이터를 조회합니다.

#### `LmsApi.getChapelTable`
```kotlin
suspend fun getChapelTable(year: String? = null, semester: Semester? = null): ChapelInformation
fun getChapelTable(completion: (LmsChapelResult) -> Unit)
fun getChapelTable(year: String?, semester: Semester?, completion: (LmsChapelResult) -> Unit)
```
지정된 학년도 및 학기의 채플 정보(좌석 번호, 주차별 출결, 결석계 신청 현황)를 조회합니다. (계절학기 조회 불가)

#### `LmsApi.getTuitionTable`
```kotlin
suspend fun getTuitionTable(): TuitionTable
fun getTuitionTable(completion: (LmsTuitionResult) -> Unit)
```
학년도/학기별 등록금 고지액, 장학 감면액, 실납부일자 및 납부 금액 등 등록금 납부 이력을 조회합니다.

#### `LmsApi.getScholarshipHistoryTable`
```kotlin
suspend fun getScholarshipHistoryTable(): ScholarshipHistoryTable
fun getScholarshipHistoryTable(completion: (LmsScholarshipHistoryResult) -> Unit)
```
학기별 수혜한 장학금 명칭, 지급 방법, 선발 금액 및 실수혜금액 등의 내역을 조회합니다.

#### `LmsApi.getGraduateTable`
```kotlin
suspend fun getGraduateTable(): GraduateTable
fun getGraduateTable(completion: (LmsGraduateTableResult) -> Unit)
```
졸업사정표 상의 이수구분별 졸업 기준 요건 학점, 본인 취득학점, 차이값 및 판정 결과를 조회합니다.

---

## 주요 데이터 모델 (Models)

### `Semester` (학기 구분 Enum)

- `FIRST`: 1학기 (코드: `"090"`)
- `SUMMER`: 여름학기 (코드: `"091"`)
- `SECOND`: 2학기 (코드: `"092"`)
- `WINTER`: 겨울학기 (코드: `"093"`)

### `Timetable` & `TimetableCell` (시간표)
- `year`: 학년도 (예: "2026학년도")
- `semester`: 학기 (예: "1학기")
- `items`: `TimetableCell` 리스트
  - `dayOfWeek`: 요일 (`DayOfWeek` Enum)
  - `period`: 교시 (예: "1 교시")
  - `periodTime`: 교시 시간 범위 (예: "(08:00-08:50)")
  - `subject`: 과목명
  - `professor`: 교수명
  - `time`: 강의 시간 문자열
  - `classroom`: 강의실

### `GradeTable` & `GradeCell` (성적 상세)
- `year`: 학년도
- `semester`: 학기
- `items`: `GradeCell` 리스트
  - `subjectCode`: 과목코드
  - `subjectName`: 과목명
  - `classification`: 이수구분 (예: "전공기초")
  - `credits`: 학점 (예: "3.0")
  - `grade`: 등급 (예: "A+")
  - `gradePoint`: 평점 (예: "4.5")
  - `professor`: 교수명

### `SemesterGradeSummaryTable` & `SemesterGradeSummaryCell` (성적 요약)
- `items`: `SemesterGradeSummaryCell` 리스트
  - `year`: 학년도
  - `semester`: 학기
  - `attemptedCredits`: 신청학점
  - `earnedCredits`: 취득학점
  - `pfCredits`: P/F학점
  - `gpa`: 평점평균
  - `gpaSum`: 평점계
  - `arithmeticMean`: 산술평균
  - `semesterRank`: 학기석차
  - `totalRank`: 전체석차
  - `academicWarning`: 학사경고여부
  - `consultationStatus`: 상담여부
  - `failedYearStatus`: 유급여부

### `ChapelInformation` (채플 정보)
- `year`: 학년도
- `semester`: 학기
- `seatStatusTable`: 좌석 정보 테이블 (`classGroup`, `timetable`, `classroom`, `seatNo`, `absenceCount`, `gradeResult`)
- `attendanceTable`: 출결 현황 테이블 (`classGroup`, `date`, `lectureType`, `status`)
- `absenceTable`: 결석계 신청 이력 테이블 (`year`, `semester`, `detail`)

### `TuitionTable` & `TuitionCell` (등록금)
- `items`: `TuitionCell` 리스트
  - `year`, `semester`: 학년도 및 학기
  - `grade`: 학년(기)
  - `registrationType`: 등록구분 (예: "정규등록")
  - `registrationDate`: 납부일자
  - `amount`: 고지금액
  - `reduction`: 장학 감면액
  - `paymentAmount`: 최종 실납부금액

### `ScholarshipHistoryTable` & `ScholarshipHistoryCell` (장학)
- `items`: `ScholarshipHistoryCell` 리스트
  - `year`, `semester`: 장학금 지급 학년도 및 학기
  - `scholarshipName`: 장학금명
  - `paymentMethod`: 지급방법 (예: "고지서 감면")
  - `processStatus`: 처리 상태
  - `selectedAmount`: 선발금액
  - `actualAmount`: 실수혜금액
  - `redeemedAmount`: 환수금액
  - `replacedAmount`: 교체금액
  - `replacedScholarshipName`: 교체장학금명
  - `workDepartment`: 근로부서

### `GraduateTable` & `GraduateTableCell` (졸업사정표)
- `items`: `GraduateTableCell` 리스트
  - `classification`: 이수구분 (예: "전공선택")
  - `requirement`: 졸업요건
  - `standardValue`: 기준학점
  - `calculatedValue`: 취득학점
  - `difference`: 차이값
  - `result`: 이수 여부 판정 (예: "합격", "미필")

### `Term` (LMS 학기 정보)
- `id`: 학기 고유 ID
- `name`: 학기명
- `start_at`: 시작 시각
- `end_at`: 종료 시각

### `Subject` (LMS 수강 과목)
- `id`: 과목 고유 ID
- `termId`: 학기 ID
- `termName`: 학기명
- `name`: 과목명
- `professor`: 담당 교수
- `totalStudents`: 수강 인원
- `todoList`: 과제 및 할 일 목록
- `attendances`: 출석 기록 리스트
- `discussions`: 공지사항 목록
- `submissions`: 과제 제출 정보
- `scoredAssignments`: 평가 및 획득한 점수 리스트

### `TodoList` (LMS 할 일)
- `component_type`: 항목 타입 (예: `assignment`, `commons`)
- `assignment_id`: 과제 고유 ID
- `title`: 제목
- `due_date`: 마감 기한

### `Submission` (LMS 제출 정보)

- `assignment_id`: 과제 고유 ID
- `attachments`: 첨부파일 목록
- `attempt`: 제출 시도 횟수
- `cached_due_date`: 마감 시각
- `late`: 지각 여부
- `preview_url`: 제출 파일 미리보기 URL
- `submitted_at`: 제출 시각
- `submission_type`: 제출 유형
- `workflow_state`: 제출 상태 (예: `submitted`, `graded`, `unsubmitted`)
- `score`: 획득 점수

---

## 개발 및 검증

파서와 공개 범위 등 외부 서버가 필요 없는 테스트는 일반 JVM 테스트로 실행합니다.

```bash
./gradlew :library:jvmTest
```

실제 LMS/U-Saint 네트워크 API와 각 콜백 오버로드를 함께 검증하려면 테스트 계정을 환경 변수로 전달합니다. 계정 정보가 없으면 통합 테스트는 외부 서버를 호출하지 않고 종료됩니다.

```bash
LMS_TEST_ID="학번" \
LMS_TEST_PASSWORD="비밀번호" \
./gradlew :library:jvmTest \
  --tests "io.github.chlwhdtn03.LmsApiFullIntegrationTest"
```

필요하면 `LMS_TEST_TERM_ID`, `LMS_TEST_YEAR`, `LMS_TEST_SEMESTER`도 지정할 수 있습니다. `LMS_TEST_SEMESTER`는 `FIRST`, `SECOND`, `090`, `092`, `1학기`, `2학기` 형식을 지원합니다. 실제 계정 정보는 소스 코드나 커밋에 저장하지 마세요.

---

## SPM 배포 및 XCFramework 수동 추가 (라이브러리 제공자용)

### SPM 배포 조건
이 저장소의 루트에 있는 `Package.swift`는 Kotlin/Native 컴파일 결과물인 XCFramework를 바이너리 타겟으로 래핑하여 배포하도록 설정되어 있습니다.

```swift
// swift-tools-version:5.9
import PackageDescription

let package = Package(
    name: "LmsApi",
    platforms: [
        .iOS(.v14),
    ],
    products: [
        .library(name: "LmsApi", targets: ["LmsApi"])
    ],
    targets: [
        .binaryTarget(
            name: "LmsApi",
            url: "https://github.com/chlwhdtn03/LMS-API/releases/download/<release-tag>/LmsApi.xcframework.zip",
            checksum: "<checksum calculated for the ZIP file>"
        )
    ]
)
```

**체크리스트:**

- GitHub Release 태그와 `Package.swift` URL의 `<release-tag>`가 일치해야 합니다.
- `checksum`은 빌드 완료된 `LmsApi.xcframework.zip` 파일 기준으로 계산된 체크섬이어야 합니다.
- 체크섬은 아래 명령어로 추출할 수 있습니다:
  ```bash
  swift package compute-checksum LmsApi.xcframework.zip
  ```

### 배포 파이프라인 진행 순서
1. `./gradlew :library:assembleLmsApiReleaseXCFramework` 실행
2. 빌드 결과인 `LmsApi.xcframework`를 `LmsApi.xcframework.zip`으로 압축
3. `swift package compute-checksum LmsApi.xcframework.zip` 실행하여 체크섬 확보
4. `Package.swift`의 checksum 값 및 URL 경로 업데이트 후 커밋
5. 배포할 버전의 Git 태그를 생성한 후 원격에 푸시
6. GitHub에서 동일한 태그명으로 릴리스를 생성하고 `LmsApi.xcframework.zip`을 릴리스 에셋으로 업로드

### iOS에서 XCFramework 직접 추가하기
바이너리 프레임워크를 수동으로 내려받거나 직접 로컬 빌드하여 추가할 수도 있습니다.

1. 로컬에서 아래 명령어로 빌드합니다:
   ```bash
   ./gradlew :library:assembleLmsApiReleaseXCFramework
   ```
2. 생성된 `library/build/XCFrameworks/release/LmsApi.xcframework` 폴더를 Xcode의 Project Navigator로 드래그하여 드롭합니다.
3. Target 설정의 `General > Frameworks, Libraries, and Embedded Content` 항목에서 `LmsApi.xcframework`를 등록합니다.
4. Kotlin Multiplatform static framework 이므로 Embed 속성은 `Do Not Embed`를 선택합니다.

현재 소스 설정으로 직접 빌드한 XCFramework에는 `iosArm64` slice만 포함되므로 실제 iOS 기기용입니다.

---

## 주의사항

- `getTerms`, `getTodoList`, `getSubjects` 및 모든 유세인트(U-Saint) API는 반드시 `loginLMS` 인증이 완료된 후에 정상 호출 가능합니다.
- iOS/Swift에서는 Kotlin `object LmsApi`가 싱글톤 객체로 변환되어 `LmsApi.shared` 형태로 접근합니다.
- `LmsApi`는 단일 사용자 세션을 공유하므로 같은 프로세스에서 여러 계정의 요청을 동시에 처리하는 용도로 사용할 수 없습니다.
- `loadingState` 콜백 및 비동기 결과 수신 스레드는 메인(UI) 스레드를 보장하지 않습니다. SwiftUI/UIKit/Compose 등 화면 렌더링에 반영할 경우 메인 디스패처/스레드로의 컨텍스트 스위칭이 필요합니다.
- 본 프로젝트는 순수 Swift 라이브러리가 아닌, Kotlin Multiplatform으로 개발되어 Kotlin/Native를 통해 iOS용 XCFramework 및 Android AAR 형태로 바인딩되는 구조입니다.
- 숭실대학교 LMS 로그인 페이지 규격이나 유세인트 Web Dynpro 컴포넌트의 HTML 속성 또는 SAP 세션 구조가 변경될 시 정보 로딩이 정상적으로 이루어지지 않을 수 있습니다.
