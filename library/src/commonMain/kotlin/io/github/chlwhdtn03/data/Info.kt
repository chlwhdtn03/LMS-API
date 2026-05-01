package io.github.chlwhdtn03.data

import kotlinx.serialization.Serializable

@Serializable
data class Info(
    val user_name: String,
    val dept_name: String,
    val user_login: String,
    var user_email: String,
)
