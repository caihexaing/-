package com.example.ticketassistant.update

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
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

    @Test
    fun resumesOnlyWhenServerReturnsPartialContent() {
        assertEquals(
            DownloadResumeDecision.RESUME,
            downloadResumeDecision(existingBytes = 128L, responseCode = 206, expectedSize = 256L)
        )
        assertEquals(
            DownloadResumeDecision.RESTART,
            downloadResumeDecision(existingBytes = 128L, responseCode = 200, expectedSize = 256L)
        )
        assertEquals(
            DownloadResumeDecision.START,
            downloadResumeDecision(existingBytes = 0L, responseCode = 200, expectedSize = 256L)
        )
    }

    @Test
    fun completeRangeIsAcceptedWhenPartialFileMatchesExpectedSize() {
        assertEquals(
            DownloadResumeDecision.COMPLETE,
            downloadResumeDecision(
                existingBytes = 256L,
                responseCode = 416,
                expectedSize = 256L
            )
        )
        assertEquals(
            DownloadResumeDecision.RESTART,
            downloadResumeDecision(
                existingBytes = 255L,
                responseCode = 416,
                expectedSize = 256L
            )
        )
    }

    @Test
    fun parsesAndValidatesContentRange() {
        assertEquals(
            DownloadContentRange(start = 128L, end = 255L, total = 256L),
            parseContentRange("bytes 128-255/256")
        )
        assertEquals(
            DownloadContentRange(start = 128L, end = 255L, total = null),
            parseContentRange("bytes 128-255/*")
        )
        assertEquals(null, parseContentRange("bytes 256-255/256"))
        assertEquals(null, parseContentRange("items 128-255/256"))
        assertEquals(null, parseContentRange("bytes 128-255/255"))
    }

    @Test
    fun progressDefaultsToConnectingStage() {
        assertEquals(UpdateDownloadStage.CONNECTING, UpdateProgress(0L, -1L, 1).stage)
    }
}
