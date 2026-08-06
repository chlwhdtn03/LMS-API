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

- 로그인 사용자, 토큰, HTTP 쿠키와 기능별 최신 조회 조건 캐시는 `LmsApi.kt`가 소유한다.
- Web Dynpro의 secure ID와 form action은 화면 응답 하나에 종속된 값이다. 전역으로
  캐시하지 않고 `WebDynproContext`에 담아 한 번의 조회 흐름에서만 사용한다.
- `internal` 구현 클래스는 전달받은 상태를 사용하지만 별도의 전역 상태를 만들지 않는다.
- 로그인 또는 로그아웃 시 캐시 초기화는 `LmsApi.resetSession()` 한 곳에서 수행한다.

기능별 구현:

- `LmsAuthService`: 로그인, 학기, 사용자 정보, 세션 쿠키
- `LmsCourseClient`: 수강 과목과 Todo가 공유하는 Canvas/LearningX HTTP 요청
- `LmsCourseService`: 수강 과목, 제출 상태, 공지, 출석, 점수
- `TodoService`: Todo 목록, 완료 상태, 미제출 통계, Todo 분석 전송
- `WebDynproService`: 호출별 유세인트 Web Dynpro 화면 세션, 응답 검증과 공통 이벤트 요청
- `TimetableService`: 시간표
- `GradeService`: 성적 상세와 학기별 성적 요약
- `ChapelService`: 채플 좌석, 출결, 결석계
- `GraduateTableService`: 졸업사정표
- `TuitionTableService`: 등록금 납부 이력
- `ScholarshipHistoryService`: 장학 수혜 이력
- `PreRegistrationService`: 예비수강신청 장바구니
- `CourseCatalogService`: 익명 수강편람 필터와 전체 검색 결과
- `OzPlanPdfLoader`: 과목의 `loadPlan()` 호출 시에만 플랫폼 WebView에서 OZ 보고서를 PDF 메모리 스트림으로 변환

새 기능을 추가할 때는 같은 기능의 서비스에 요청·파싱 로직을 추가하고,
`LmsApi`에는 기존 패턴과 같은 얇은 공개 조회 메서드만 추가한다. HTML 파서는 네트워크
요청과 분리하되 `internal`로 유지하여 응답 fixture만으로 단위 테스트할 수 있게 한다.

Web Dynpro 기능을 구현할 때는 공개 API 호출마다 `openSession`으로 새 context를 만들고,
같은 호출 안의 후속 이벤트에만 그 context를 넘긴다. 다른 호출의 context를 재사용하면
SAP의 부분 응답만 받아 데이터가 비어 보일 수 있다. 초기 화면이 일시적인 SAP 서버 오류나
렌더링되지 않은 응답을 반환하면 `WebDynproService`가 최대 3회까지 새 화면 세션을
초기화한다. 이 재시도는 기존 로그인 쿠키를 사용하므로 사용자가 다시 로그인할 필요는 없다.
