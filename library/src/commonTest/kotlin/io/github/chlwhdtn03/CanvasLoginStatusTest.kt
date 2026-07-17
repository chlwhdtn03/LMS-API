package io.github.chlwhdtn03

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CanvasLoginStatusTest {
    @Test
    fun acceptsCanvasLoginRedirect() {
        assertTrue(LmsApi.isAcceptedCanvasLoginStatus(302))
    }

    @Test
    fun rejectsCanvasLoginServerError() {
        assertFalse(LmsApi.isAcceptedCanvasLoginStatus(500))
    }
}
