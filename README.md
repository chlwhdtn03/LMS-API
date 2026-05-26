# LMS-API

숭실대학교 LMS(Canvas, LearningX)에서 학기, 강의, 할 일, 출석, 공지, 제출, 점수 정보를 가져오기 위한 Kotlin Multiplatform 라이브러리입니다.

> SSU-Time에서 iOS로 연동하려면 여기부터 보면 됩니다: [SSU-Time iOS Quick Start](#ssu-time-ios-quick-start)

이 README의 iOS 문서는 **Swift Package Manager(SPM)로 연결하거나 `LmsApi.xcframework` 파일을 Xcode 프로젝트에 직접 추가해서 사용하는 방식**을 기준으로 작성되어 있습니다. SPM도 내부적으로는 GitHub Release에 올라간 `LmsApi.xcframework.zip`을 받는 구조입니다.

## 지원 플랫폼

- Android
- JVM
- iOS device: `iosArm64`
- iOS simulator: `iosX64`, `iosSimulatorArm64`
- macOS Apple Silicon: `macosArm64`

## iOS에서 SPM으로 연결하기

iOS 앱 프로젝트에서는 Xcode의 Swift Package Manager로 이 라이브러리를 추가할 수 있습니다.

1. Xcode에서 앱 프로젝트를 엽니다.
2. 상단 메뉴에서 `File > Add Package Dependencies...`를 선택합니다.
3. 검색창에 이 저장소 URL을 입력합니다.

```text
https://github.com/chlwhdtn03/LMS-API
```

4. Dependency Rule에서 사용할 버전을 선택합니다.
5. Package Product 목록에서 `LmsApi`를 선택합니다.
6. 앱 target에 `LmsApi`가 추가되는지 확인합니다.

Swift 파일에서는 다음처럼 import 합니다.

```swift
import LmsApi
```

### SPM 배포 조건

이 저장소의 루트에는 SPM이 읽는 `Package.swift`가 있어야 합니다. 현재 구조는 Kotlin/Native로 만든 XCFramework를 SPM binary target으로 감싸는 방식입니다.

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
            url: "https://github.com/chlwhdtn03/LMS-API/releases/download/1.2.4/LmsApi.xcframework.zip",
            checksum: "<checksum calculated for the ZIP file>"
        )
    ]
)
```

SPM 배포가 정상 동작하려면 아래 값들이 서로 맞아야 합니다.

- GitHub Release 태그: `1.2.4`
- Release asset 파일명: `LmsApi.xcframework.zip`
- `Package.swift`의 URL: `https://github.com/chlwhdtn03/LMS-API/releases/download/1.2.4/LmsApi.xcframework.zip`
- `Package.swift`의 checksum: 실제 `LmsApi.xcframework.zip`으로 계산한 값

checksum은 zip 파일을 만든 뒤 아래 명령으로 계산합니다.

```bash
swift package compute-checksum LmsApi.xcframework.zip
```

### SPM 배포 순서

라이브러리 제공자는 릴리스할 때 아래 순서로 진행합니다.

1. `./gradlew :library:assembleLmsApiReleaseXCFramework` 실행
2. 산출된 `LmsApi.xcframework`를 `LmsApi.xcframework.zip`으로 압축
3. `swift package compute-checksum LmsApi.xcframework.zip` 실행
4. `Package.swift`의 checksum 갱신
5. `Package.swift`를 커밋
6. Git tag 생성
7. GitHub Release 생성
8. Release asset으로 `LmsApi.xcframework.zip` 업로드

한 번 배포한 `LmsApi.xcframework.zip`은 같은 태그에서 교체하지 않는 것을 권장합니다. 파일 내용이 바뀌면 checksum도 바뀌어서 기존 SPM 설치가 실패할 수 있습니다.

## iOS에서 XCFramework 직접 추가하기

라이브러리 제공자는 아래 Gradle task로 iOS용 XCFramework를 만들 수 있습니다.

```bash
./gradlew :library:assembleLmsApiReleaseXCFramework
```

빌드 결과는 아래 경로에 생성됩니다.

```text
library/build/XCFrameworks/release/LmsApi.xcframework
```

Xcode 프로젝트에 추가할 때는 다음 순서로 진행합니다.

1. `LmsApi.xcframework`를 Xcode 프로젝트 Navigator로 드래그합니다.
2. 필요한 앱 target이 선택되어 있는지 확인합니다.
3. `Copy items if needed`를 체크합니다.
4. 앱 target의 `General > Frameworks, Libraries, and Embedded Content`에 `LmsApi.xcframework`가 들어갔는지 확인합니다.
5. 현재 프레임워크는 static framework로 빌드되므로 Embed 설정은 보통 `Do Not Embed`를 사용합니다.

## SSU-Time iOS Quick Start

SSU-Time에서는 전체 과목 상세 정보가 아니라 시간표와 할 일 중심 데이터가 필요합니다. iOS에서는 아래 순서로 호출하면 됩니다.

```text
loginLMS(id, password)
getTerms()
getTodoList(term)
getCookies() // 외부 서비스에 현재 LMS 세션 쿠키를 전달해야 할 때만 사용
```

중요한 점은 **SSU-Time에서는 `getSubjects()`가 아니라 `getTodoList()`를 사용한다는 것**입니다. `getSubjects()`는 출석, 공지, 점수까지 가져와서 더 무겁습니다.

### Swift Result API

Swift에 노출되는 API는 `throws`를 사용하지 않습니다. 모든 함수는 completion으로 result class를 받고, 성공 여부는 `result.success`로 확인합니다.

```swift
import LmsApi

LmsApi.shared.loginLMS(id: "학번", password: "비밀번호") { loginResult in
    guard loginResult.success else {
        print(loginResult.errorMessage ?? "로그인 실패")
        return
    }

    LmsApi.shared.getTerms { termsResult in
        guard termsResult.success, let term = termsResult.terms.last else {
            print(termsResult.errorMessage ?? "학기 조회 실패")
            return
        }

        LmsApi.shared.getTodoList(
            term: term,
            loadingState: { progress in
                print("loading: \(Int(progress.floatValue * 100))%")
            },
            completion: { subjectsResult in
                guard subjectsResult.success else {
                    print(subjectsResult.errorMessage ?? "todo 조회 실패")
                    return
                }

                for subject in subjectsResult.subjects {
                    print("과목: \(subject.name)")

                    for todo in subject.todoList {
                        print("- \(todo.title)")
                        print("  type: \(todo.component_type)")
                        print("  due: \(todo.due_date)")
                    }
                }
            }
        )
    }
}
```

completion과 `loadingState`는 메인 스레드 호출을 보장하지 않습니다. SwiftUI `@Published`나 UIKit UI를 갱신할 때는 `DispatchQueue.main.async`로 넘겨서 처리하세요.

### Swift에서 LMS 세션 쿠키 가져오기

로그인 이후 현재 `LmsApi` 클라이언트가 들고 있는 LMS 세션 쿠키가 필요하면 `getCookies`를 호출합니다. 응답은 `lmsSession.cookies` 구조입니다.

```swift
import LmsApi

LmsApi.shared.loginLMS(id: "학번", password: "비밀번호") { loginResult in
    guard loginResult.success else {
        print(loginResult.errorMessage ?? "로그인 실패")
        return
    }

    LmsApi.shared.getCookies { cookiesResult in
        guard cookiesResult.success else {
            print(cookiesResult.errorMessage ?? "쿠키 조회 실패")
            return
        }

        let lmsSession = cookiesResult.lmsSession

        for cookie in lmsSession.cookies {
            print(cookie.name)
            print(cookie.value)
            print(cookie.domain)
            print(cookie.path)
        }
    }
}
```

외부 API가 아래 형태를 요구한다면 `cookiesResult.lmsSession.cookies`를 그대로 매핑하면 됩니다.

```json
{
  "lmsSession": {
    "cookies": [
      {
        "name": "string",
        "value": "string",
        "domain": "string",
        "path": "string"
      }
    ]
  }
}
```

### throws 없는 async/await 래퍼

```swift
import Foundation
import LmsApi

enum SSUTimeLMSClient {
    static func login(id: String, password: String) async -> LmsLoginResult {
        await withCheckedContinuation { continuation in
            LmsApi.shared.loginLMS(id: id, password: password) { result in
                continuation.resume(returning: result)
            }
        }
    }

    static func getTerms() async -> LmsTermsResult {
        await withCheckedContinuation { continuation in
            LmsApi.shared.getTerms { result in
                continuation.resume(returning: result)
            }
        }
    }

    static func getTodoList(
        term: Term,
        onProgress: @escaping (Float) -> Void = { _ in }
    ) async -> LmsSubjectsResult {
        await withCheckedContinuation { continuation in
            LmsApi.shared.getTodoList(term: term, loadingState: { progress in
                onProgress(progress.floatValue)
            }) { result in
                continuation.resume(returning: result)
            }
        }
    }

    static func getCookies() async -> LmsCookiesResult {
        await withCheckedContinuation { continuation in
            LmsApi.shared.getCookies { result in
                continuation.resume(returning: result)
            }
        }
    }

    static func loadTodoSubjects(
        id: String,
        password: String,
        selectTerm: ([Term]) -> Term? = { $0.last },
        onProgress: @escaping (Float) -> Void = { _ in }
    ) async -> LmsSubjectsResult {
        let loginResult = await login(id: id, password: password)
        guard loginResult.success else {
            return LmsSubjectsResult(
                success: false,
                subjects: [],
                errorMessage: loginResult.errorMessage
            )
        }

        let termsResult = await getTerms()
        guard termsResult.success else {
            return LmsSubjectsResult(
                success: false,
                subjects: [],
                errorMessage: termsResult.errorMessage
            )
        }

        guard let term = selectTerm(termsResult.terms) else {
            return LmsSubjectsResult(
                success: false,
                subjects: [],
                errorMessage: "조회 가능한 학기가 없습니다."
            )
        }

        return await getTodoList(term: term, onProgress: onProgress)
    }
}
```

### 가장 작은 Swift 사용 예제

```swift
func loadSSUTimeTodos() {
    Task {
        let result = await SSUTimeLMSClient.loadTodoSubjects(
            id: "학번",
            password: "비밀번호"
        ) { progress in
            print("loading: \(Int(progress * 100))%")
        }

        guard result.success else {
            print(result.errorMessage ?? "SSU-Time LMS load failed")
            return
        }

        for subject in result.subjects {
            print("과목: \(subject.name)")

            for todo in subject.todoList {
                print("- \(todo.title)")
                print("  type: \(todo.component_type)")
                print("  due: \(todo.due_date)")
            }
        }
    }
}
```

### Swift에서 쓰기 편한 Todo 모델로 변환하기

`getTodoList()`의 실제 데이터는 `LmsSubjectsResult.subjects`입니다. SSU-Time 화면에서는 과목별 `todoList`를 평평한 배열로 바꿔 쓰는 편이 편할 수 있습니다.

```swift
import Foundation
import LmsApi

struct SSUTimeTodoItem: Identifiable {
    let id: String
    let courseId: Int32
    let courseName: String
    let professor: String
    let title: String
    let componentType: String
    let assignmentId: Int?
    let dueDate: String
}

extension Subject {
    func toSSUTimeTodoItems() -> [SSUTimeTodoItem] {
        todoList.map { todo in
            let assignmentId = todo.assignment_id.map { Int($0.intValue) }
            let itemId = [
                String(id),
                todo.component_type,
                String(assignmentId ?? -1),
                todo.title,
                todo.due_date
            ].joined(separator: "-")

            return SSUTimeTodoItem(
                id: itemId,
                courseId: id,
                courseName: name,
                professor: professor,
                title: todo.title,
                componentType: todo.component_type,
                assignmentId: assignmentId,
                dueDate: todo.due_date
            )
        }
    }
}

extension Array where Element == Subject {
    func toSSUTimeTodoItems() -> [SSUTimeTodoItem] {
        flatMap { $0.toSSUTimeTodoItems() }
    }
}
```

### SwiftUI ViewModel 예제

```swift
import Foundation
import LmsApi

@MainActor
final class SSUTimeTodoViewModel: ObservableObject {
    @Published private(set) var subjects: [Subject] = []
    @Published private(set) var todos: [SSUTimeTodoItem] = []
    @Published private(set) var progress: Float = 0
    @Published private(set) var isLoading = false
    @Published var errorMessage: String?

    func load(id: String, password: String) {
        isLoading = true
        progress = 0
        errorMessage = nil

        Task {
            let result = await SSUTimeLMSClient.loadTodoSubjects(
                id: id,
                password: password
            ) { [weak self] progress in
                Task { @MainActor in
                    self?.progress = progress
                }
            }

            guard result.success else {
                errorMessage = result.errorMessage ?? "LMS 정보를 불러오지 못했습니다."
                isLoading = false
                return
            }

            subjects = result.subjects
            todos = result.subjects.toSSUTimeTodoItems()
            isLoading = false
        }
    }
}
```

## SSU-Time에서 받는 데이터

`getTodoList(term:loadingState:completion:)`는 `LmsSubjectsResult`를 반환합니다. 실제 과목 목록은 `result.subjects`에 들어 있습니다.

SSU-Time에서 주로 쓰는 필드는 다음과 같습니다.

### `Subject`

```swift
subject.id              // 과목 ID
subject.termId          // 학기 ID
subject.termName        // 학기명
subject.name            // 과목명
subject.professor       // 교수명
subject.totalStudents   // 수강 인원
subject.todoList        // 할 일 목록
subject.submissions     // 제출 정보
```

### `TodoList`

```swift
todo.title              // 할 일 제목
todo.component_type     // assignment, commons 등
todo.assignment_id      // 과제 ID, 없을 수 있음
todo.due_date           // 마감 시각 문자열
```

`getTodoList(term)`는 빠른 조회를 위해 아래 필드는 빈 목록으로 반환합니다.

```swift
subject.attendances
subject.discussions
subject.scoredAssignments
```

출석, 공지, 점수까지 모두 필요한 화면에서는 `getSubjects(term:loadingState:completion:)`를 사용해야 합니다. SSU-Time의 할 일 중심 연동에는 `getTodoList(term:loadingState:completion:)`를 권장합니다.

## Android/Kotlin 사용법

KMP 또는 Android에서는 Gradle 의존성으로 사용할 수 있습니다.

```kotlin
dependencies {
    implementation("io.github.chlwhdtn03:lms:1.2.4")
}
```

KMP 프로젝트에서는 보통 `commonMain`에 추가합니다.

```kotlin
kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation("io.github.chlwhdtn03:lms:1.2.4")
        }
    }
}
```

기본 흐름은 iOS와 같습니다. Android/Kotlin에서도 public API는 callback result 방식입니다.

```kotlin
import io.github.chlwhdtn03.LmsApi
import kotlin.time.ExperimentalTime

@OptIn(ExperimentalTime::class)
fun loadTodosForAndroid() {
    LmsApi.loginLMS(
        id = "학번",
        password = "비밀번호",
    ) { loginResult ->
        if (!loginResult.success) {
            println(loginResult.errorMessage ?: "로그인 실패")
        } else {
            LmsApi.getTerms { termsResult ->
                if (!termsResult.success) {
                    println(termsResult.errorMessage ?: "학기 조회 실패")
                } else {
                    val term = termsResult.terms.lastOrNull()
                    if (term == null) {
                        println("조회 가능한 학기가 없습니다.")
                    } else {
                        LmsApi.getTodoList(
                            term = term,
                            loadingState = { progress ->
                                println("loading: ${(progress * 100).toInt()}%")
                            },
                            completion = { subjectsResult ->
                                if (!subjectsResult.success) {
                                    println(subjectsResult.errorMessage ?: "todo 조회 실패")
                                } else {
                                    subjectsResult.subjects.forEach { subject ->
                                        subject.todoList.forEach { todo ->
                                            println("[${subject.name}] ${todo.title} / ${todo.due_date}")
                                        }
                                    }
                                }
                            },
                        )
                    }
                }
            }
        }
    }
}
```

Android에서도 completion은 메인 스레드 호출을 보장하지 않습니다. Activity/Fragment UI를 갱신할 때는 `runOnUiThread { ... }`, Compose/ViewModel에서는 `viewModelScope.launch(Dispatchers.Main) { ... }` 같은 방식으로 메인 스레드로 넘겨서 처리하세요.

## 공개 API

### `LmsApi.loginLMS`

```kotlin
fun loginLMS(
    id: String,
    password: String,
    completion: (LmsLoginResult) -> Unit,
)
```

LMS 아이디와 비밀번호로 로그인합니다. 성공하면 `LmsLoginResult.success == true`이고, 이후 호출에서 같은 세션과 API 토큰을 사용합니다.

### `LmsApi.getTerms`

```kotlin
fun getTerms(
    completion: (LmsTermsResult) -> Unit,
)
```

로그인한 사용자의 학기 목록을 가져옵니다. 실제 목록은 `result.terms`입니다.

### `LmsApi.getTodoList`

```kotlin
@ExperimentalTime
fun getTodoList(
    term: Term,
    loadingState: (Float) -> Unit = {},
    completion: (LmsSubjectsResult) -> Unit,
)
```

과목 기본 정보, 할 일 목록, 제출 정보를 빠르게 가져옵니다. SSU-Time 연동에서 권장하는 API입니다. 실제 과목 목록은 `result.subjects`입니다.

### `LmsApi.getSubjects`

```kotlin
@ExperimentalTime
fun getSubjects(
    term: Term,
    loadingState: (Float) -> Unit = {},
    completion: (LmsSubjectsResult) -> Unit,
)
```

과목 기본 정보, 할 일, 출석, 공지, 제출, 점수 정보를 모두 가져옵니다. 더 많은 API를 호출하므로 `getTodoList`보다 무겁습니다.

### `LmsApi.getLoginInfo`

```kotlin
fun getLoginInfo(
    completion: (LmsLoginInfoResult) -> Unit,
)
```

로그인한 사용자의 이름, 학과, 로그인 ID, 이메일 정보를 가져옵니다.

### `LmsApi.getCookies`

```kotlin
fun getCookies(
    completion: (LmsCookiesResult) -> Unit,
)
```

현재 로그인 세션의 LMS 쿠키를 가져옵니다. 실제 쿠키 목록은 `result.lmsSession.cookies`입니다. 로그인 이후에 호출해야 하며, 외부 서비스에 현재 LMS 세션을 전달해야 할 때 사용합니다.

### Notice API

```kotlin
fun loadStartUpNotices(
    pageNum: Int = 1,
    completion: (StartUpNoticesResult) -> Unit,
)

fun loadScholarships(
    pageNum: Int = 1,
    completion: (ScholarshipNoticesResult) -> Unit,
)
```

창업지원단 공지와 장학 공지를 가져옵니다.

### Result 클래스

모든 public API는 예외를 직접 던지지 않고 result class로 성공/실패를 전달합니다.

- `LmsLoginResult`: `success`, `errorMessage`
- `LmsTermsResult`: `success`, `terms`, `errorMessage`
- `LmsLoginInfoResult`: `success`, `info`, `errorMessage`
- `LmsCookiesResult`: `success`, `lmsSession`, `errorMessage`
- `LmsSubjectsResult`: `success`, `subjects`, `errorMessage`
- `StartUpNoticesResult`: `success`, `notices`, `errorMessage`
- `ScholarshipNoticesResult`: `success`, `notices`, `errorMessage`

`success == false`이면 데이터 필드는 빈 목록 또는 `null`이고, 실패 이유는 `errorMessage`를 확인합니다.

## 주요 모델

### `Term`

- `id`: 학기 ID
- `name`: 학기명
- `start_at`: 시작 시각
- `end_at`: 종료 시각

### `Subject`

- `id`: 과목 ID
- `termId`: 학기 ID
- `termName`: 학기명
- `name`: 과목명
- `professor`: 교수명
- `totalStudents`: 수강 인원
- `todoList`: 할 일 목록
- `attendances`: 주차별 출석 정보
- `discussions`: 공지 목록
- `submissions`: 제출 정보 목록
- `scoredAssignments`: 점수 정보 목록

### `TodoList`

- `component_type`: 항목 타입. 예: `assignment`, `commons`
- `assignment_id`: 과제 ID
- `title`: 제목
- `due_date`: 마감 시각

### `LmsSession`

- `cookies`: 현재 로그인 세션에서 사용하는 쿠키 목록

### `LmsSessionCookie`

- `name`: 쿠키 이름
- `value`: 쿠키 값
- `domain`: 쿠키 도메인
- `path`: 쿠키 경로

### `Submission`

- `assignment_id`: 과제 ID
- `attachments`: 제출 파일 목록
- `attempt`: 제출 횟수
- `cached_due_date`: LMS 캐시 기준 마감 시각
- `late`: 지각 제출 여부
- `preview_url`: 제출 파일 미리보기 주소
- `submitted_at`: 제출 시각
- `submission_type`: 제출 방식
- `workflow_state`: 제출 상태. 예: `submitted`, `graded`, `unsubmitted`
- `score`: 받은 점수

## 주의사항

- `getTerms`, `getTodoList`, `getSubjects`, `getLoginInfo`, `getCookies`는 반드시 `loginLMS` 이후에 호출해야 합니다.
- iOS에서는 Kotlin `object LmsApi`가 Swift의 `LmsApi.shared`로 보입니다.
- iOS/Android public API는 callback result 방식입니다. Swift에 `throws` 기반 API를 노출하지 않기 위해 내부 suspend 함수는 public으로 노출하지 않습니다.
- completion과 `loadingState`는 메인 스레드 호출을 보장하지 않습니다.
- 이 라이브러리는 Swift로 작성된 라이브러리가 아니라 Kotlin/Native가 만든 XCFramework입니다.
- 숭실대학교 LMS 로그인 페이지나 LearningX API 구조가 바뀌면 동작이 깨질 수 있습니다.
- 이미 로그인한 세션과 토큰은 `LmsApi` 내부 상태로 유지됩니다.
