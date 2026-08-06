package io.github.chlwhdtn03.data.Lms

import io.github.chlwhdtn03.LmsApi
import kotlinx.serialization.Serializable

/** 유세인트 수강편람에서 제공하는 조회 유형입니다. */
@Serializable
enum class CourseCatalogCategory(val displayName: String) {
    DEPARTMENT("학부전공별"),
    REQUIRED_GENERAL("교양필수"),
    ELECTIVE_GENERAL("교양선택"),
    CHAPEL("채플"),
    TEACHING("교직"),
    GRADUATE("대학원"),
    LINKED_MAJOR("연계전공"),
    CONVERGENCE_MAJOR("융합전공"),
    PROFESSOR("교수명검색"),
    SUBJECT("과목검색"),
    CROSS_MAJOR("타전공인정과목"),
    CYBER_UNIVERSITY("숭실사이버대"),
}

/** 콤보박스에서 선택할 수 있는 실제 키와 표시명입니다. */
@Serializable
data class CourseCatalogFilterOption(
    val key: String,
    val label: String,
)

/** 수강편람 조회 화면에 현재 노출된 콤보박스 한 개입니다. */
@Serializable
data class CourseCatalogFilter(
    val index: Int,
    val name: String,
    val selectedKey: String = "",
    val selectedLabel: String = "",
    val options: List<CourseCatalogFilterOption> = emptyList(),
)

/** 특정 학기와 조회 유형에서 사용할 수 있는 검색 조건입니다. */
@Serializable
data class CourseCatalogSearchOptions(
    val year: String,
    val semester: Semester,
    val category: CourseCatalogCategory,
    val filters: List<CourseCatalogFilter>,
    val acceptsKeyword: Boolean,
)

/**
 * 수강편람 검색 조건입니다.
 *
 * [filterKeys]는 [CourseCatalogSearchOptions.filters] 순서대로 선택한 옵션 키입니다.
 * 상위 필터 선택에 따라 다음 필터가 채워지는 경우, 채워진 키까지만 전달해 옵션을 다시
 * 조회할 수 있습니다.
 */
@Serializable
data class CourseCatalogQuery(
    val year: String,
    val semester: Semester,
    val category: CourseCatalogCategory = CourseCatalogCategory.DEPARTMENT,
    val filterKeys: List<String> = emptyList(),
    val keyword: String = "",
)

/** 수강편람 검색 결과의 강좌 한 건입니다. */
@Serializable
data class CourseCatalogCourse(
    /** 화면 HTML에 실제 링크가 포함된 경우의 값이며, 버튼형 계획서는 빈 문자열입니다. */
    val plan: String,
    val primaryClassification: String,
    val multiMajorClassification: String,
    val engineeringCertification: String,
    /** 교양 조회 유형에서만 내려오는 교과영역입니다. */
    val curriculumArea: String = "",
    val subjectCode: String,
    val subjectName: String,
    val registrationNotice: String,
    val courseType: String,
    val section: String,
    val professor: String,
    val department: String,
    val hoursCredits: String,
    val enrollmentCapacity: String,
    val remainingSeats: String,
    val schedule: String,
    val targetStudents: String,
    /** [loadPlan]이 과목을 다시 찾을 때 사용하는 조회 학년도입니다. */
    val year: String = "",
    /** [loadPlan]이 과목을 다시 찾을 때 사용하는 조회 학기입니다. */
    val semester: Semester? = null,
) {
    /**
     * 이 과목의 OZ Viewer를 호출해 PDF가 준비된 뒤에만 바이트를 반환합니다.
     * Android와 iOS에서 지원합니다. 목록 조회만으로는 실행되지 않으며 호출할 때마다
     * 새 보고서를 로드하므로 완료까지 시간이 걸릴 수 있습니다.
     */
    @Throws(Exception::class)
    suspend fun loadPlan(): ByteArray = LmsApi.loadCourseCatalogPlan(this)
}

/** 유세인트 수강편람 검색 결과 전체입니다. */
@Serializable
data class CourseCatalogTable(
    val year: String,
    val semester: Semester,
    val category: CourseCatalogCategory,
    val totalCourseCount: Int,
    val items: List<CourseCatalogCourse>,
)
