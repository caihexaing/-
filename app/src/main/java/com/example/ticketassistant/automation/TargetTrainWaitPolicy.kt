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

/**
 * Identifies the train overview row rather than detail or seat sub-nodes.
 * A single train is represented by several accessibility nodes in the
 * official WebView, so matching the train number alone is insufficient.
 */
internal fun isTrainSummaryCandidateText(
    text: String,
    trainNo: String,
    from: String,
    to: String
): Boolean {
    val normalized = normalizeText(text)
    val fromToken = normalizeText(from)
    val toToken = normalizeText(to)
    if (!matchesToken(text, trainNo)) return false
    if (listOf("经停详情", "停站详情", "车站详情", "席位", "席别", "余票").any(normalized::contains)) {
        return false
    }
    val routeTiming = normalized.contains("从${fromToken}出发") &&
        normalized.contains("到达${toToken}")
    val routeLabels = normalized.contains(fromToken) && normalized.contains(toToken) &&
        normalized.contains("出发") && normalized.contains("到达")
    return (routeTiming || routeLabels) &&
        (normalized.contains("次列车") || normalized.contains("列车"))
}

internal fun isTargetSeatCandidateText(text: String, trainNo: String, seat: String): Boolean =
    matchesToken(text, trainNo) && seatLabelMatches(text, seat)
