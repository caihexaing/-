package com.example.ticketassistant.automation

import org.junit.Assert.assertEquals
import org.junit.Test

class TargetTrainWaitPolicyTest {
    @Test
    fun `train overview is distinct from detail and seat nodes`() {
        assertEquals(
            true,
            isTrainSummaryCandidateText(
                "D 6 3 1次列车6点44分从汉口出发,7点35分到达潜江,历时51分",
                "D631",
                "汉口",
                "潜江"
            )
        )
        assertEquals(
            false,
            isTrainSummaryCandidateText("D 6 3 1次列车经停详情", "D631", "汉口", "潜江")
        )
        assertEquals(
            false,
            isTrainSummaryCandidateText("D 6 3 1次列车二等席位有票", "D631", "汉口", "潜江")
        )
    }

    @Test
    fun `missing row waits instead of taking over immediately`() {
        assertEquals(
            TargetTrainWaitAction.WAIT,
            targetTrainWaitAction(
                waitEvents = 1,
                scrollAttempts = 1,
                elapsedMs = 300,
                scrollAvailable = false,
                alreadyScrolledCurrentTree = false
            )
        )
    }

    @Test
    fun `missing row can scroll once per unchanged tree`() {
        assertEquals(
            TargetTrainWaitAction.SCROLL,
            targetTrainWaitAction(
                waitEvents = 2,
                scrollAttempts = 1,
                elapsedMs = 600,
                scrollAvailable = true,
                alreadyScrolledCurrentTree = false
            )
        )
        assertEquals(
            TargetTrainWaitAction.WAIT,
            targetTrainWaitAction(
                waitEvents = 3,
                scrollAttempts = 1,
                elapsedMs = 900,
                scrollAvailable = true,
                alreadyScrolledCurrentTree = true
            )
        )
    }

    @Test
    fun `wait policy takes over only at the configured limit`() {
        assertEquals(
            TargetTrainWaitAction.TAKEOVER,
            targetTrainWaitAction(
                waitEvents = 30,
                scrollAttempts = 6,
                elapsedMs = 9_000,
                scrollAvailable = true,
                alreadyScrolledCurrentTree = false
            )
        )
        assertEquals(
            TargetTrainWaitAction.TAKEOVER,
            targetTrainWaitAction(
                waitEvents = 2,
                scrollAttempts = 0,
                elapsedMs = 15_000,
                scrollAvailable = true,
                alreadyScrolledCurrentTree = false
            )
        )
    }
}
