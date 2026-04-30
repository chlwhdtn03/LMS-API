# LMS-API

숭실대학교 LMS(Canvas, LearningX)에서 학기, 강의, 할 일, 출석, 공지, 점수 정보를 가져오기 위한 Kotlin Multiplatform 라이브러리입니다.

현재 코드 기준 지원 타깃은 다음과 같습니다.

- Android
- iOS (`iosX64`, `iosArm64`, `iosSimulatorArm64`)

iOS는 Kotlin/Native 프레임워크로도 빌드할 수 있습니다. 즉 Xcode에서 직접 붙여서 사용할 수는 있지만, 이 라이브러리가 순수 Swift 소스로 자동 변환되는 것은 아닙니다.

## 1. 설치 방법

### Maven Central로 추가

`library/build.gradle.kts` 기준 publish 좌표는 아래와 같습니다.

- Group: `io.github.chlwhdtn03`
- Artifact: `lms`
- Version: `1.1.5`

프로젝트에 `mavenCentral()`이 포함되어 있어야 합니다.

```kotlin
repositories {
    mavenCentral()
    google()
}
```

KMP 프로젝트라면 보통 `commonMain`에 추가하면 됩니다.

```kotlin
kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation("io.github.chlwhdtn03:lms:1.1.5")
        }
    }
}
```

Android 전용 모듈에서만 사용할 경우에는 일반 `dependencies` 블록에서도 사용할 수 있습니다.

```kotlin
dependencies {
    implementation("io.github.chlwhdtn03:lms:1.1.5")
}
```

### 같은 멀티모듈 프로젝트에서 로컬 모듈로 붙이기

이 저장소처럼 `:library` 모듈을 직접 포함해서 사용할 수도 있습니다.

```kotlin
dependencies {
    implementation(project(":library"))
}
```

### Xcode에서 직접 쓰기 (`XCFramework`)

이 프로젝트는 iOS용 `XCFramework` 산출도 지원합니다.

```bash
./gradlew :library:assembleLmsApiXCFramework
```

빌드가 끝나면 아래 경로에 산출물이 생성됩니다.

```text
library/build/XCFrameworks/release/LmsApi.xcframework
```

이 파일을 Xcode 프로젝트에 추가하면 Swift에서 프레임워크 형태로 사용할 수 있습니다.

- 이 방식은 "Swift 네이티브 라이브러리"가 아니라 Kotlin/Native가 만든 Apple 프레임워크입니다.
- 즉 Xcode에서 import 해서 쓰는 것은 가능하지만, 내부 구현은 여전히 Kotlin입니다.
- 순수 Swift Package나 Swift 소스 라이브러리가 필요하다면 별도 Swift 구현이나 Swift 래퍼 타깃을 만들어야 합니다.

Xcode에 붙일 때는 보통 아래 순서로 진행하면 됩니다.

1. `LmsApi.xcframework`를 Xcode 프로젝트로 드래그해서 추가
2. 타깃의 `Frameworks, Libraries, and Embedded Content`에 `LmsApi.xcframework` 연결 확인
3. 현재 빌드 설정은 `isStatic = true` 이므로 Embed 옵션은 `Do Not Embed` 사용

아래는 iOS 앱에서 `XCFramework`를 직접 사용할 때의 Swift 예제입니다. Kotlin의 `suspend` 함수는 Apple 쪽에서 completion handler 형태로 노출되므로, 앱에서 한 번 `async/await` 래퍼를 만들어 두면 쓰기 편합니다.

```swift
import Foundation
import LmsApi

enum LMSXCFrameworkError: Error {
    case loginFailed
    case noTerms
}

enum LMSXCFrameworkClient {
    static func login(id: String, password: String) async throws -> Bool {
        try await withCheckedThrowingContinuation { continuation in
            LmsApiKt.loginLMS(id: id, password: password) { success, error in
                if let error {
                    continuation.resume(throwing: error)
                    return
                }

                continuation.resume(returning: success?.boolValue == true)
            }
        }
    }

    static func getTerms() async throws -> [Term] {
        try await withCheckedThrowingContinuation { continuation in
            LmsApiKt.getTerms { terms, error in
                if let error {
                    continuation.resume(throwing: error)
                    return
                }

                continuation.resume(returning: terms ?? [])
            }
        }
    }

    static func getSubjects(
        term: Term,
        onProgress: @escaping (Float) -> Void = { _ in }
    ) async throws -> [Subject] {
        try await withCheckedThrowingContinuation { continuation in
            LmsApiKt.getSubjects(term: term, loadingState: { progress in
                onProgress(progress.floatValue)
            }) { subjects, error in
                if let error {
                    continuation.resume(throwing: error)
                    return
                }

                continuation.resume(returning: subjects ?? [])
            }
        }
    }
}

func loadLmsFromXcframework() {
    Task {
        do {
            let loggedIn = try await LMSXCFrameworkClient.login(
                id: "20222908",
                password: "비밀번호"
            )

            guard loggedIn else {
                throw LMSXCFrameworkError.loginFailed
            }

            let terms = try await LMSXCFrameworkClient.getTerms()
            guard let selectedTerm = terms.last else {
                throw LMSXCFrameworkError.noTerms
            }

            print("선택한 학기: \(selectedTerm.name ?? "이름 없음")")

            let subjects = try await LMSXCFrameworkClient.getSubjects(
                term: selectedTerm
            ) { progress in
                print("불러오는 중: \(Int(progress * 100))%")
            }

            for subject in subjects {
                print("과목명: \(subject.name)")
                print("교수명: \(subject.professor)")
                print("할 일 개수: \(subject.todoList.count)")

                if let firstTodo = subject.todoList.first {
                    print("첫 과제: \(firstTodo.title)")
                }
            }
        } catch {
            print("LMS 조회 실패: \(error)")
        }
    }
}
```

추가로 알아둘 점:

- Swift에서는 top-level Kotlin 함수가 전역 함수가 아니라 `LmsApiKt.loginLMS(...)`, `LmsApiKt.getTerms(...)` 같은 형태로 보입니다.
- 반환 모델도 Swift에서 그대로 접근할 수 있어서 `subject.name`, `subject.todoList`, `firstTodo.title`처럼 사용하면 됩니다.
- 현재 API는 Kotlin 예외를 iOS 친화적인 형태로 모두 감싸서 내려주지는 않으므로, 실서비스에서는 iOS 전용 래퍼나 Kotlin 쪽 Result 래퍼 API를 하나 더 두는 것을 권장합니다.

## 2. 기본 사용 순서

이 라이브러리의 사용 순서는 고정되어 있습니다.

1. `loginLMS(id, password)`로 로그인
2. `getTerms()`로 학기 목록 조회
3. `getSubjects(term)`로 특정 학기의 강의 정보 조회

`getTerms()`와 `getSubjects()`는 로그인 전에 호출하면 `IllegalStateException`이 발생합니다.

또한 공개 API는 모두 네트워크 호출을 포함하므로 `suspend` 함수이며, 코루틴 안에서 호출해야 합니다.

## 3. 공개 함수 설명

일반적인 앱 코드에서는 사실상 `loginLMS`, `getTerms`, `getSubjects` 세 함수만 사용하면 됩니다.

### `loginLMS`

```kotlin
suspend fun loginLMS(id: String, password: String): Boolean
```

LMS 아이디와 비밀번호로 로그인합니다.

- 성공 시 `true`를 반환합니다.
- 아이디 또는 비밀번호가 틀리면 `IllegalArgumentException`이 발생할 수 있습니다.
- 내부적으로 쿠키와 토큰을 보관하므로, 이후 `getTerms()`와 `getSubjects()`가 같은 세션을 사용합니다.

### `getTerms`

```kotlin
@OptIn(ExperimentalTime::class)
suspend fun getTerms(): List<Term>
```

로그인한 사용자의 학기 목록을 가져옵니다.

`Term`의 핵심 필드는 다음과 같습니다.

- `id`: 학기 ID
- `name`: 학기명
- `start_at`: 시작 시각
- `end_at`: 종료 시각

### `getSubjects`

```kotlin
@OptIn(ExperimentalTime::class)
suspend fun getSubjects(
    term: Term,
    loadingState: (Float) -> Unit = {}
): List<Subject>
```

선택한 학기의 강의 목록을 가져옵니다. 한 강의 안에 아래 정보가 함께 들어 있습니다.

- 과목 기본 정보
- 할 일 목록
- 주차별 출석 상태
- 공지사항
- 점수 정보

`loadingState` 콜백에는 `0.0f ~ 1.0f` 범위의 진행률이 전달됩니다.

### `normalizePem`

```kotlin
fun normalizePem(raw: String): String
```

로그인 복호화 과정에서 PEM 문자열 형식을 정리하는 보조 함수입니다.

- 일반 사용자가 직접 호출할 일은 거의 없습니다.
- 디버깅이나 플랫폼별 RSA 처리 코드를 확장할 때만 참고하면 됩니다.

### `pemToString`

```kotlin
expect fun pemToString(rawPem: String, rawPw: String): String
```

플랫폼별 RSA 복호화를 수행하는 내부용 함수입니다.

- Android와 iOS에서 각각 `actual` 구현이 제공됩니다.
- 보통은 `loginLMS()` 내부에서 자동으로 사용되므로 직접 호출하지 않아도 됩니다.

## 4. 주요 반환 모델

### `Subject`

`getSubjects()`가 반환하는 핵심 모델입니다.

- `id`: 과목 ID
- `termId`: 학기 ID
- `termName`: 학기명
- `name`: 과목명
- `professor`: 교수명
- `totalStudents`: 수강 인원
- `todoList`: 할 일 목록
- `attendances`: 주차별 출석 정보
- `discussions`: 공지 목록
- `scoredAssignments`: 과제/시험 점수 목록

### `TodoList`

할 일 한 건을 의미합니다.

- `component_type`: 항목 타입, 예: `assignment`, `commons`
- `assignment_id`: 과제 ID
- `title`: 제목
- `due_date`: 마감 시각

### `AttendanceType`

출석 상태는 enum 으로 제공됩니다.

- `AttendanceType.ATTENDANCE`
- `AttendanceType.ABSENT`
- `AttendanceType.LATE`
- `AttendanceType.NONE`

한글 값은 `kor` 프로퍼티로 확인할 수 있습니다.

```kotlin
val text = AttendanceType.ATTENDANCE.kor // "출석"
```

### `ScoredAssignment`

점수 정보는 아래 형태로 들어옵니다.

- `groupName`: 평가 항목 그룹명, 예: 과제 / 퀴즈 / 기말고사
- `name`: 과제 또는 시험 이름
- `score`: 내가 받은 점수
- `maxScore`: 만점

## 5. 전체 예제 코드

아래 예제는 로그인부터 학기 선택, 과목 조회, 과제/공지/점수 출력까지 한 번에 보여줍니다.

```kotlin
import io.github.chlwhdtn03.getSubjects
import io.github.chlwhdtn03.getTerms
import io.github.chlwhdtn03.loginLMS
import io.github.chlwhdtn03.data.AttendanceType
import kotlin.time.ExperimentalTime

@OptIn(ExperimentalTime::class)
suspend fun loadLmsExample() {
    val loginSuccess = loginLMS(
        id = "20222908",
        password = "비밀번호"
    )

    if (!loginSuccess) return

    val terms = getTerms()
    val selectedTerm = terms.lastOrNull()
        ?: error("조회 가능한 학기가 없습니다.")

    println("선택한 학기: ${selectedTerm.name}")

    val subjects = getSubjects(selectedTerm) { progress ->
        println("불러오는 중: ${(progress * 100).toInt()}%")
    }

    subjects.forEach { subject ->
        println("과목명: ${subject.name}")
        println("교수명: ${subject.professor}")
        println("수강인원: ${subject.totalStudents}")

        println("[할 일]")
        subject.todoList.forEach { todo ->
            println("- ${todo.title} / ${todo.component_type} / 마감: ${todo.due_date}")
        }

        println("[출석]")
        subject.attendances.forEachIndexed { weekIndex, weekAttendances ->
            val attendanceText = weekAttendances.joinToString { it.kor }
            println("- ${weekIndex + 1}주차: $attendanceText")
        }

        println("[공지]")
        subject.discussions.forEach { discussion ->
            println("- ${discussion.title} / 작성자: ${discussion.user_name}")
        }

        println("[점수]")
        subject.scoredAssignments.forEach { score ->
            println("- [${score.groupName}] ${score.name}: ${score.score}/${score.maxScore}")
        }

        println()
    }
}
```

## 6. Android ViewModel 예제

Android 앱에서 사용할 때는 보통 `viewModelScope.launch` 안에서 호출하면 됩니다.

```kotlin
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.chlwhdtn03.getSubjects
import io.github.chlwhdtn03.getTerms
import io.github.chlwhdtn03.loginLMS
import kotlinx.coroutines.launch
import kotlin.time.ExperimentalTime

class LmsViewModel : ViewModel() {

    @OptIn(ExperimentalTime::class)
    fun load(id: String, password: String) {
        viewModelScope.launch {
            loginLMS(id, password)

            val term = getTerms().lastOrNull() ?: return@launch
            val subjects = getSubjects(term) { progress ->
                println("progress = $progress")
            }

            subjects.forEach {
                println(it.name)
            }
        }
    }
}
```

## 7. 사용 시 주의사항

- `getTerms()`와 `getSubjects()`는 반드시 로그인 이후에 호출해야 합니다.
- 공개 함수는 모두 네트워크 요청을 수행하므로 메인 스레드를 직접 막지 않도록 코루틴에서 사용해야 합니다.
- `Term`과 관련 함수는 `kotlin.time.ExperimentalTime` opt-in 이 필요합니다.
- 내부 상태로 로그인 세션을 유지하므로, 앱 시작 후 한 번 로그인하고 같은 프로세스에서 이어서 사용하는 방식이 자연스럽습니다.
- 로그인 과정에서 사이트 구조가 바뀌면 동작이 깨질 수 있으므로, LMS 로그인 페이지 변경에 민감합니다.

## 8. 가장 자주 쓰는 패턴

실제 사용에서는 아래 흐름만 기억하면 됩니다.

```kotlin
@OptIn(ExperimentalTime::class)
suspend fun simpleFlow() {
    loginLMS("학번", "비밀번호")
    val term = getTerms().last()
    val subjects = getSubjects(term)
    println(subjects.map { it.name })
}
```
