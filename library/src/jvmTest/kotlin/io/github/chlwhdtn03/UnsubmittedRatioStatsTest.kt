package io.github.chlwhdtn03

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.time.Duration.Companion.minutes
import kotlin.time.ExperimentalTime

class UnsubmittedRatioStatsTest {
    @OptIn(ExperimentalTime::class)
    @Test
    fun printCurrentUnsubmittedRatioStats() = runTest(timeout = 2.minutes) {
        val id = System.getenv("LMS_TEST_ID")
        val password = System.getenv("LMS_TEST_PASSWORD")

        if (id.isNullOrBlank() || password.isNullOrBlank()) {
            println("LMS_TEST_ID or LMS_TEST_PASSWORD is missing. Skipping live LMS stats test.")
            return@runTest
        }

        LmsApi.loginLMS(id = id, password = password)
        val term = LmsApi.getTerms().lastOrNull() ?: error("No LMS term found.")
        val stats = LmsApi.getUnsubmittedRatioStats(term) { progress ->
            println("loading: ${(progress * 100).toInt()}%")
        }

        println("term=${term.name}")
        println("current_total_count=${stats.totalCount}")
        println("current_unsubmitted_count=${stats.unsubmittedCount}")
        println("current_unsubmitted_ratio=${stats.ratio}")
    }
}
