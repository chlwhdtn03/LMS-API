package com.yourssu.lms.data

import kotlinx.serialization.Serializable
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

@Serializable
@ExperimentalTime
data class Term(
    val id: Int? = -1,
    val name: String? = "",
    val start_at: Instant?,
    val end_at: Instant?,
)

@Serializable
@ExperimentalTime
data class Terms(
    val enrollment_terms: List<Term>
)
