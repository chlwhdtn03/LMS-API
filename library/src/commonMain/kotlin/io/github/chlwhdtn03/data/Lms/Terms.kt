package io.github.chlwhdtn03.data.Lms

import kotlinx.serialization.Serializable
import kotlin.time.ExperimentalTime

@Serializable
@ExperimentalTime
data class Terms(
    val enrollment_terms: List<Term>
)
