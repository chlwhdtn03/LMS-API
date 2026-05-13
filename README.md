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

### 1. Xcode에서 Package 추가

1. Xcode에서 앱 프로젝트를 엽니다.
2. 상단 메뉴에서 `File > Add Package Dependencies...`를 선택합니다.
3. 검색창에 이 저장소 URL을 입력합니다.

```text
https://github.com/chlwhdtn03/LMS-API
```

4. Dependency Rule에서 사용할 버전을 선택합니다.
   - 태그를 `1.2.4`로 배포했다면 `Exact Version` 또는 `Up to Next Major Version`에 `1.2.4`를 지정합니다.
5. Package Product 목록에서 `LmsApi`를 선택합니다.
6. 앱 target에 `LmsApi`가 추가되는지 확인합니다.

Swift 파일에서는 다음처럼 import 합니다.

```swift
import LmsApi
```

이후 사용 코드는 [SSU-Time iOS Quick Start](#ssu-time-ios-quick-start)의 Swift 예제를 그대로 사용하면 됩니다.

### 2. SPM 연결이 되기 위한 배포 조건

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

### 3. SPM 배포 순서

라이브러리 제공자는 릴리스할 때 아래 순서로 진행합니다.

1. `./gradlew :library:assembleLmsApiReleaseXCFramework` 실행
2. 산출된 `LmsApi.xcframework`를 `LmsApi.xcframework.zip`으로 압축
3. `swift package compute-checksum LmsApi.xcframework.zip` 실행
4. `Package.swift`의 checksum 갱신
5. `Package.swift`를 커밋
6. `1.2.4` Git tag 생성
7. GitHub Release `1.2.4` 생성
8. Release asset으로 `LmsApi.xcframework.zip` 업로드

한 번 배포한 `LmsApi.xcframework.zip`은 같은 태그에서 교체하지 않는 것을 권장합니다. 파일 내용이 바뀌면 checksum도 바뀌어서 기존 SPM 설치가 실패할 수 있습니다.

## iOS에서 XCFramework 직접 추가하기

### 1. XCFramework 준비

라이브러리 제공자는 아래 Gradle task로 iOS용 XCFramework를 만들 수 있습니다.

```bash
./gradlew :library:assembleLmsApiReleaseXCFramework
```

빌드 결과는 아래 경로에 생성됩니다.

```text
library/build/XCFrameworks/release/LmsApi.xcframework
```

파일로 전달할 때는 보통 `LmsApi.xcframework`를 압축해서 전달하고, iOS 프로젝트에서는 압축을 푼 뒤 Xcode에 추가합니다.

### 2. Xcode 프로젝트에 추가

1. `LmsApi.xcframework`를 Xcode 프로젝트 Navigator로 드래그합니다.
2. 필요한 앱 target이 선택되어 있는지 확인합니다.
3. `Copy items if needed`를 체크합니다.
4. 앱 target의 `General > Frameworks, Libraries, and Embedded Content`에 `LmsApi.xcframework`가 들어갔는지 확인합니다.
5. 현재 프레임워크는 static framework로 빌드되므로 Embed 설정은 보통 `Do Not Embed`를 사용합니다.

Swift 파일에서는 다음처럼 import 합니다.

```swift
import LmsApi
```

## SSU-Time iOS Quick Start

SSU-Time에서는 전체 과목 상세 정보가 아니라 시간표와 할 일 중심 데이터가 필요합니다. 그래서 iOS에서는 아래 순서로 호출하면 됩니다.

```text
loginLMS(id, password)
getTerms()
getTodoList(term)
```

중요한 점은 **SSU-Time에서는 `getSubjects()`가 아니라 `getTodoList()`를 사용한다는 것**입니다.

### 바로 붙여 쓸 수 있는 Swift 래퍼

Kotlin의 `suspend` 함수는 Swift에서 completion handler 형태로 노출됩니다. iOS 앱에서는 아래처럼 `async/await` 래퍼를 하나 만들어 두면 편합니다.

```swift
import Foundation
import LmsApi

enum SSUTimeLMSClientError: Error {
    case loginFailed
    case noTerms
}

enum SSUTimeLMSClient {
    static func login(id: String, password: String) async throws {
        let success: Bool = try await withCheckedThrowingContinuation { continuation in
            LmsApi.shared.loginLMS(id: id, password: password) { result, error in
                if let error {
                    continuation.resume(throwing: error)
                    return
                }

                continuation.resume(returning: result?.boolValue == true)
            }
        }

        if !success {
            throw SSUTimeLMSClientError.loginFailed
        }
    }

    static func getTerms() async throws -> [Term] {
        try await withCheckedThrowingContinuation { continuation in
            LmsApi.shared.getTerms { terms, error in
                if let error {
                    continuation.resume(throwing: error)
                    return
                }

                continuation.resume(returning: terms ?? [])
            }
        }
    }

    static func getTodoList(
        term: Term,
        onProgress: @escaping (Float) -> Void = { _ in }
    ) async throws -> [Subject] {
        try await withCheckedThrowingContinuation { continuation in
            LmsApi.shared.getTodoList(term: term, loadingState: { progress in
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

    static func loadTodoSubjects(
        id: String,
        password: String,
        selectTerm: ([Term]) -> Term? = { $0.last },
        onProgress: @escaping (Float) -> Void = { _ in }
    ) async throws -> [Subject] {
        try await login(id: id, password: password)

        let terms = try await getTerms()
        guard let term = selectTerm(terms) else {
            throw SSUTimeLMSClientError.noTerms
        }

        return try await getTodoList(term: term, onProgress: onProgress)
    }
}
```

### 가장 작은 사용 예제

```swift
func loadSSUTimeTodos() {
    Task {
        do {
            let subjects = try await SSUTimeLMSClient.loadTodoSubjects(
                id: "학번",
                password: "비밀번호"
            ) { progress in
                print("loading: \(Int(progress * 100))%")
            }

            for subject in subjects {
                print("과목: \(subject.name)")

                for todo in subject.todoList {
                    print("- \(todo.title)")
                    print("  type: \(todo.component_type)")
                    print("  due: \(todo.due_date)")
                }
            }
        } catch {
            print("SSU-Time LMS load failed: \(error)")
        }
    }
}
```

### Swift에서 쓰기 편한 Todo 모델로 변환하기

`getTodoList()`의 반환 타입은 `[Subject]`입니다. SSU-Time 화면에서는 과목별 `todoList`를 평평한 배열로 바꿔 쓰는 편이 편할 수 있습니다.

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

사용 예시는 다음과 같습니다.

```swift
func loadFlatTodos() {
    Task {
        do {
            let subjects = try await SSUTimeLMSClient.loadTodoSubjects(
                id: "학번",
                password: "비밀번호"
            )

            let todos = subjects.toSSUTimeTodoItems()

            for todo in todos {
                print("[\(todo.courseName)] \(todo.title) / \(todo.dueDate)")
            }
        } catch {
            print(error)
        }
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
            do {
                let loadedSubjects = try await SSUTimeLMSClient.loadTodoSubjects(
                    id: id,
                    password: password
                ) { [weak self] progress in
                    Task { @MainActor in
                        self?.progress = progress
                    }
                }

                subjects = loadedSubjects
                todos = loadedSubjects.toSSUTimeTodoItems()
                isLoading = false
            } catch {
                errorMessage = String(describing: error)
                isLoading = false
            }
        }
    }
}
```

## SSU-Time에서 받는 데이터

`getTodoList(term)`는 `List<Subject>`를 Swift에서는 `[Subject]`로 반환합니다.

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

출석, 공지, 점수까지 모두 필요한 화면에서는 `getSubjects(term)`를 사용해야 합니다. SSU-Time의 할 일 중심 연동에는 `getTodoList(term)`를 권장합니다.

## 일반 Kotlin 사용법

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

기본 흐름은 iOS와 같습니다.

```kotlin
import io.github.chlwhdtn03.LmsApi
import kotlin.time.ExperimentalTime

@OptIn(ExperimentalTime::class)
suspend fun loadTodosForKotlin() {
    LmsApi.loginLMS(
        id = "학번",
        password = "비밀번호"
    )

    val term = LmsApi.getTerms().lastOrNull()
        ?: error("조회 가능한 학기가 없습니다.")

    val subjects = LmsApi.getTodoList(term) { progress ->
        println("loading: ${(progress * 100).toInt()}%")
    }

    subjects.forEach { subject ->
        subject.todoList.forEach { todo ->
            println("[${subject.name}] ${todo.title} / ${todo.due_date}")
        }
    }
}
```

## 공개 API

### `LmsApi.loginLMS`

```kotlin
suspend fun loginLMS(id: String, password: String): Boolean
```

LMS 아이디와 비밀번호로 로그인합니다. 로그인에 성공하면 이후 호출에서 같은 세션과 API 토큰을 사용합니다.

### `LmsApi.getTerms`

```kotlin
@OptIn(ExperimentalTime::class)
suspend fun getTerms(): List<Term>
```

로그인한 사용자의 학기 목록을 가져옵니다.

### `LmsApi.getTodoList`

```kotlin
@ExperimentalTime
suspend fun getTodoList(
    term: Term,
    loadingState: (Float) -> Unit = {}
): List<Subject>
```

과목 기본 정보, 할 일 목록, 제출 정보를 빠르게 가져옵니다. SSU-Time 연동에서 권장하는 API입니다.

### `LmsApi.getSubjects`

```kotlin
@ExperimentalTime
suspend fun getSubjects(
    term: Term,
    loadingState: (Float) -> Unit = {}
): List<Subject>
```

과목 기본 정보, 할 일, 출석, 공지, 제출, 점수 정보를 모두 가져옵니다. 더 많은 API를 호출하므로 `getTodoList`보다 무겁습니다.

### `LmsApi.getLoginInfo`

```kotlin
suspend fun getLoginInfo(): Info
```

로그인한 사용자의 이름, 학과, 로그인 ID, 이메일 정보를 가져옵니다.

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

### `Submission`

- `assignment_id`: 과제 ID
- `attachments`: 제출 파일 목록
- `attempt`: 제출 횟수
- `cached_due_date`: LMS 캐시 기준 마감 시각
- `late`: 지각 제출 여부
- `preview_url`: 제출 파일 미리보기 주소
- `submitted_at`: 제출 시각
- `submission_type`: 제출 방식
- `score`: 받은 점수

## 주의사항

- `getTerms`, `getTodoList`, `getSubjects`, `getLoginInfo`는 반드시 `loginLMS` 이후에 호출해야 합니다.
- iOS에서는 Kotlin `object LmsApi`가 Swift의 `LmsApi.shared`로 보입니다.
- iOS에서는 Kotlin `suspend` 함수가 completion handler 형태로 노출됩니다.
- 이 라이브러리는 Swift로 작성된 라이브러리가 아니라 Kotlin/Native가 만든 XCFramework입니다.
- 숭실대학교 LMS 로그인 페이지나 LearningX API 구조가 바뀌면 동작이 깨질 수 있습니다.
- 이미 로그인한 세션과 토큰은 `LmsApi` 내부 상태로 유지됩니다.
