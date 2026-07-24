# LmsApi 내부 구조

`LmsApi`는 Android, iOS, JVM 호출부가 사용하는 공개 파사드다.
로그인과 데이터 조회 메서드의 이름, 인자, 반환형과 completion 콜백은 `LmsApi.kt`에
유지한다.

외부 API 경계:

- 외부에는 로그인, 로그아웃과 LMS 데이터 조회 메서드만 공개한다.
- `parse*`, `merge*`, `find*`, `fetchWebDynproHtml`은 서버 응답 처리와 테스트를 위한
  내부 구현이므로 `internal`로 유지한다.
- `normalizePem`, `decodeHtmlEntities`, `stripHtmlTags` 같은 응답 처리 유틸리티도
  외부에 공개하지 않는다.
- `commonTest`와 플랫폼 테스트는 friend source set이므로 `internal` 함수에 접근할 수
  있다. 테스트를 위해 내부 구현을 공개 API로 변경하지 않는다.

상태 관리 원칙:

- 로그인 사용자, 토큰, WebDynpro 세션과 기능별 캐시는 `LmsApi.kt`가 소유한다.
- `internal` 구현 클래스는 전달받은 상태를 사용하지만 별도의 전역 상태를 만들지 않는다.
- 로그인 또는 로그아웃 시 캐시 초기화는 `LmsApi.resetSession()` 한 곳에서 수행한다.

기능별 구현:

- `LmsAuthService`: 로그인, 학기, 사용자 정보, 세션 쿠키
- `LmsCourseClient`: 수강 과목과 Todo가 공유하는 Canvas/LearningX HTTP 요청
- `LmsCourseService`: 수강 과목, 제출 상태, 공지, 출석, 점수
- `TodoService`: Todo 목록, 완료 상태, 미제출 통계, Todo 분석 전송
- `WebDynproService`: 유세인트 WebDynpro 세션과 공통 이벤트 요청
- `TimetableService`: 시간표
- `GradeService`: 성적 상세와 학기별 성적 요약
- `ChapelService`: 채플 좌석, 출결, 결석계
- `GraduateTableService`: 졸업사정표
- `TuitionTableService`: 등록금 납부 이력
- `ScholarshipHistoryService`: 장학 수혜 이력

새 기능을 추가할 때는 같은 기능의 서비스에 요청·파싱 로직을 추가하고,
`LmsApi`에는 기존 패턴과 같은 얇은 공개 조회 메서드만 추가한다. HTML 파서는 네트워크
요청과 분리하되 `internal`로 유지하여 응답 fixture만으로 단위 테스트할 수 있게 한다.
