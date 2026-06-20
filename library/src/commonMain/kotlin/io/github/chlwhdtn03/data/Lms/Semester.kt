package io.github.chlwhdtn03.data.Lms

import kotlinx.serialization.Serializable

@Serializable
enum class Semester(val code: String, val nameKor: String) {
    FIRST("090", "1학기"),
    SUMMER("091", "여름학기"),
    SECOND("092", "2학기"),
    WINTER("093", "겨울학기");

    companion object {
        fun fromName(name: String): Semester? {
            val cleanName = name.replace(" ", "")
            return entries.find { cleanName.contains(it.nameKor) || cleanName.contains(it.name) }
        }
        fun fromCode(code: String): Semester? {
            return entries.find { it.code == code }
        }
    }
}
