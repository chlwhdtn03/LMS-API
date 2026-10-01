# 과제 Todo 판정

일반 마감은 지각 여부를 구분하는 시각이고, 제출 종료 시각과 다르다.
순차·병렬 Todo 조회 모두 `Submission.toAssignmentTodo`를 사용한다.
일반 마감으로 상세 조회를 생략하지 않는다. 상세 진입이 삭제를 발생시킨다는
인과관계는 확인되지 않았으며, 이번 수정은 조회 시 마감 필터에 의한 누락을 해결한다.

## 확인 근거와 한계

- 저장소 `data/Lms/AssignmentGroup.kt`의 LMS 응답 기록에는
  `due_at=2025-12-06T08:10:00Z`, `lock_at=2025-12-06T09:55:00Z`와
  `published=true`, `locked_for_user=true`가 있다. 일반 마감과 잠금 시각이 다르다.
- `data/Lms/Submission.kt`의 응답 기록에는 해당 과제의
  `cached_due_date=2025-12-06T08:10:00Z`, `workflow_state=graded`,
  `submitted_at=2025-12-06T07:58:06Z`, `late=false`, `excused=false`가 있다.
- [Canvas Assignments 문서](https://developerdocs.instructure.com/services/canvas/resources/assignments)는
  `due_at`, `lock_at`, `unlock_at`이 조회 사용자에게 적용되는 override 날짜라고 설명한다.
  `lock_at` 이후 과제는 잠기며 `locked_for_user`는 사용자별 잠금 여부다.
- [Canvas Submissions 문서](https://developerdocs.instructure.com/services/canvas/resources/submissions)의
  상태 값은 `unsubmitted`, `submitted`, `pending_review`, `graded`다.
  `late`는 제출 지각 여부이며 제출 허용 플래그가 아니다.
- 2026-10-01 QA 요청자가 **SSU LMS의 `lock_at`이 지각 제출 가능 기한**임을 확인했다.
  따라서 `lock_at`을 최우선 종료 기준으로 사용한다. `late_at`은 Canvas 표준 필드가 아니며
  저장소 테스트 예시에서는 `late_at=lock_at`이다. `late_at`은 기존 라이브러리의
  기한 취급을 유지하는 대체값이며, `lock_at`과 충돌할 때 우선하지 않는다.
- 2026-10-01 `commonTest/LMSTest.inspectAssignmentSubmissionDeadlines`에서 로컬 계정으로
  2026년 2학기 실서버 과제 10개를 읽었다. `due_at=2026-09-28T14:59:59Z`,
  `lock_at=2026-10-30T14:59:59Z`, `locked_for_user=false`인 과제가 확인되었다.
  해당 과제는 `workflow_state=submitted`, `submitted_at=2026-09-21T10:20:21Z`였다.
  조회한 10개 상세 응답에는 `late_at`이 없었다. 실측에서도 `lock_at`과 일반 마감은 다르다.
- 실서버 결과의 과제 Todo는 2개였으며 순차·병렬 결과가 동일했다. 현재 계정에는
  일반 마감이 지난 미제출 과제 중 종료 시각이 남은 사례가 없었다. 문제 과제 자체의
  응답은 확보하지 못했으므로 QA 증상을 직접 재현했다고 주장하지 않는다.
- `AssignmentTodoPolicyTest.observedLmsResponseUsesLockAtWithoutLateAt`는 위 응답의 날짜와
  상태만 남긴 익명 예시를 사용한다. 실제 제출 상태에서 제외되는 것을 검증한 뒤,
  로컬 복사본의 상태만 미제출로 바꾸어 일반 마감 이후에도 포함되는지 검증한다.
  이 상태 변경은 합성 테스트이며 LMS 제출을 변경하지 않는다.

## 우선순위

1. 유효한 과제 ID와 `workflow_state=unsubmitted`가 있어야 한다.
   `submitted_at`이 있거나 `submitted`/`pending_review`/`graded`이거나
   `excused=true`이면 제외한다. 재제출 가능 여부와 별개로 Todo는 미제출 목록이다.
2. `published=false`, 과제 상태 `unpublished`/`deleted`, `locked_for_user=true`이면 제외한다.
   응답에 없는 boolean과 명시적인 false를 구분하려고 `AssignmentDetail.published`와
   `locked_for_user`의 기본값은 null로 둔다. 누락만으로 비공개라고 단정하지 않는다.
3. `unlock_at`이 미래이면 제외한다. 공개 시작 시각과 현재 시각이 같으면 포함할 수 있다.
4. 유효한 상세 `due_at`을 일반 마감으로 사용한다. 없거나 잘못되었으면 유효한
   `cached_due_date`를 대체값으로 사용한다. 캐시가 상세 날짜를 덮어쓰지 않는다.
5. 종료 기준은 `lock_at` → `late_at` → 일반 마감 순서다.
   `lock_at`이 있으면 과거 또는 잘못된 `late_at`이 그 기간을 줄이지 않는다.
   `now < 종료 시각`인 과제만 포함하며 종료 시각과 같으면 제외한다.
   종료값이 일반 마감보다 이르더라도 종료값을 우선한다.
6. 제출 가능 종료값이 확인되면 일반 마감이 지나도 포함한다.
   종료값이 없으면 일반 마감까지만 포함한다. 종료값이 없다는 사실을 무제한 제출
   허용으로 해석하지 않는다.

## 누락·잘못된 날짜

- null, 빈 문자열, 공백만 있는 문자열은 누락이다. ISO 8601 날짜와 시간대 오프셋을
  Instant로 비교하고, 계산한 응답 날짜는 UTC로 정규화한다.
- 값이 있는데 `unlock_at` 또는 선택된 종료 필드를 파싱하지 못하면 제외한다.
  잘못된 `lock_at`을 무시하고 `late_at`이나 일반 마감으로 대체하지 않는다.
- `lock_at`이 있으면 사용하지 않는 `late_at`의 파싱 실패는 무시한다.
- 일반 마감이 전부 없거나 잘못되어도 유효한 종료값이 있으면 포함할 수 있다.
  이때 일반 마감 필드는 빈 문자열이다. 일반 마감과 종료값이 모두 불명확하면 제외한다.
- `unlock_at >= 종료 시각`이면 유효한 제출 구간이 없어 제외한다.
- 알 수 없거나 누락된 제출 상태는 미제출이라고 단정하지 않고 제외한다.

## 반환 계약

- `due_date`, 새 `due_at`: 위 정책으로 선택한 일반 마감. 지각 제출 종료값으로 바꾸지 않는다.
- `lock_at`: LMS의 원본 지각 제출 종료 시각.
- `late_at`: 기존 호환 동작을 유지한다(원본 값, 없으면 `lock_at`).
- `submission_deadline`: 판정에 사용한 최종 종료 시각. 앱에서는 이 값으로 기간 종료를 판단한다.
  일반 마감으로 대체된 경우 이 값은 지각 제출 허용의 증거가 아니다.
- 새 필드는 선택적 기본값을 가진다. 기존 JSON은 계속 읽을 수 있다.
  이 정책과 새 필드의 채움은 assignment/quiz에 적용한다. commons 출석 판정은 기존 정책을 유지한다.

마감이 지난 미제출 과제에도 상세 요청이 발생하므로 기존보다 요청 수가 늘어난다.
유효 ID 중복 제거와 완료 과제의 상세 요청 생략은 유지한다.

## 검증

`AssignmentTodoPolicyTest`는 제출 상태, 날짜 우선순위, 공개/잠금, 정확한 종료 경계,
누락/파싱 실패, 시간대와 응답 직렬화를 검증한다.
`AssignmentTodoServiceTest`는 MockEngine 응답으로 일반 마감이 지난 과제가 상세 조회에
도달하고 순차·병렬 경로가 같은 목록을 반환하는지 검증한다.

```sh
./gradlew :library:jvmTest --tests 'io.github.chlwhdtn03.AssignmentTodo*Test' --tests 'io.github.chlwhdtn03.TodoResponseModelTest' --tests 'io.github.chlwhdtn03.TodoSnapshotSamplingTest'
```
