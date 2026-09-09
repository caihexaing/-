package com.example.ticketassistant.update

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateCheckerTest {
    private val checker = UpdateChecker()

    @Test
    fun detectsHigherVersion() {
        assertTrue(checker.isNewer("0.2.2", "0.2.1"))
        assertTrue(checker.isNewer("v1.0.0", "0.9.9"))
    }

    @Test
    fun rejectsSameOrOlderVersion() {
        assertFalse(checker.isNewer("0.2.1", "0.2.1"))
        assertFalse(checker.isNewer("0.2.0", "0.2.1"))
    }
}
