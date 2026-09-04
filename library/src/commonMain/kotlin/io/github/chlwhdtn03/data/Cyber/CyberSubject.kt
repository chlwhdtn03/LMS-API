package io.github.chlwhdtn03.data.Cyber

import kotlinx.serialization.Serializable

/**
 * 숭실사이버대학교(KCU) 수강과목 한 건.
 *
 * [year], [semesterCode], [courseCode], [deptCode], [userNo]는 `/atnlcSubj/atnlcApe/list`
 * 등 후속 요청에 그대로 넘겨야 하는 값으로, `btnEntryLect`/`btnEntryView`
 * 버튼에 있는 `data-shyr`/`data-smst-cd`/`data-cose-cd`/`data-dert-cd`/`data-user`에서 얻는다.
 */
@Serializable
data class CyberSubject(
    val name: String,
    val category: String,
    val professor: String,
    val credit: String,
    val year: String,
    val semesterCode: String,
    val courseCode: String,
    val deptCode: String,
    val userNo: String,
    val progressPercent: Int,
)
