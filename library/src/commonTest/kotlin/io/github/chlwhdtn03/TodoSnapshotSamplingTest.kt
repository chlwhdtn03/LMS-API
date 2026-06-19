package io.github.chlwhdtn03

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TodoSnapshotSamplingTest {
    @Test
    fun sendsSnapshotWhenSampleIsBelowConfiguredRate() {
        assertTrue(shouldSendTodoSnapshot(0.0))
        assertTrue(shouldSendTodoSnapshot(TODO_SNAPSHOT_SAMPLE_RATE - 0.001))
    }

    @Test
    fun skipsSnapshotWhenSampleMeetsOrExceedsConfiguredRate() {
        assertFalse(shouldSendTodoSnapshot(TODO_SNAPSHOT_SAMPLE_RATE))
        assertFalse(shouldSendTodoSnapshot(0.999))
    }
}
