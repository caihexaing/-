package com.example.ticketassistant.automation

/** Finite policy for a result list whose accessibility rows arrive late. */
internal enum class TargetTrainWaitAction {
    WAIT,
    SCROLL,
    TAKEOVER
}

internal fun targetTrainWaitAction(
    waitEvents: Int,
    scrollAttempts: Int,
    elapsedMs: Long,
    scrollAvailable: Boolean,
    alreadyScrolledCurrentTree: Boolean,
    maxWaitEvents: Int = 30,
    maxScrollAttempts: Int = 6,
    timeoutMs: Long = 15_000L
): TargetTrainWaitAction {
    if (waitEvents >= maxWaitEvents || elapsedMs >= timeoutMs) return TargetTrainWaitAction.TAKEOVER
    if (scrollAvailable && scrollAttempts < maxScrollAttempts && !alreadyScrolledCurrentTree) {
        return TargetTrainWaitAction.SCROLL
    }
    return TargetTrainWaitAction.WAIT
}
