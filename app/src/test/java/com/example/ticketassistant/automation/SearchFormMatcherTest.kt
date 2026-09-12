package com.example.ticketassistant.automation

import com.example.ticketassistant.data.Station
import com.example.ticketassistant.data.TicketTask
import com.example.ticketassistant.data.Train
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchFormMatcherTest {
    @Test
    fun `date matcher accepts official display variants`() {
        assertTrue(matchesTravelDate("2026年9月19日", "2026-09-19"))
        assertTrue(matchesTravelDate("出发日期 09/19", "2026-09-19"))
        assertTrue(matchesTravelDate("2026-09-19", "2026-09-19"))
        assertFalse(matchesTravelDate("2026年9月18日", "2026-09-19"))
        assertFalse(matchesTravelDate("2025年9月19日", "2026-09-19"))
    }

    @Test
    fun `date picker confirmation labels stay narrow`() {
        assertTrue(datePickerConfirmationLabelMatches("确定"))
        assertTrue(datePickerConfirmationLabelMatches("完成选择"))
        assertTrue(datePickerConfirmationLabelMatches("确认日期"))
        assertFalse(datePickerConfirmationLabelMatches("选择日期"))
        assertFalse(datePickerConfirmationLabelMatches("查询"))
        assertFalse(datePickerConfirmationLabelMatches("下一步"))
    }

    @Test
    fun `date picker confirmation only clicks one button after a date is selected`() {
        assertEquals(
            DatePickerConfirmationDecision.CLICK,
            datePickerConfirmationDecision(pickerVisible = true, dateSelected = true, confirmationCount = 1)
        )
        assertEquals(
            DatePickerConfirmationDecision.WAITING,
            datePickerConfirmationDecision(pickerVisible = true, dateSelected = false, confirmationCount = 1)
        )
        assertEquals(
            DatePickerConfirmationDecision.WAITING,
            datePickerConfirmationDecision(pickerVisible = true, dateSelected = true, confirmationCount = 0)
        )
        assertEquals(
            DatePickerConfirmationDecision.AMBIGUOUS,
            datePickerConfirmationDecision(pickerVisible = true, dateSelected = true, confirmationCount = 2)
        )
        assertEquals(
            DatePickerConfirmationDecision.WAITING,
            datePickerConfirmationDecision(pickerVisible = false, dateSelected = true, confirmationCount = 1)
        )
    }

    @Test
    fun `order confirmation requires an adult ticket selection`() {
        assertTrue(hasAdultTicketSelection("确认订单 蔡某 成人票 二等座 提交订单"))
        assertTrue(hasAdultTicketSelection("确认订单 蔡某 成人（默认） 二等座 提交订单"))
        assertTrue(hasAdultTicketSelection("确认订单 蔡某 1张成人 二等座 提交订单"))
        assertTrue(hasAdultTicketSelection("确认订单 蔡某 成人 1张 二等座 提交订单"))
        assertTrue(hasAdultTicketSelection("确认订单 蔡某 成人 ¥51 提交订单"))
        assertFalse(hasAdultTicketSelection("确认订单 蔡某 学生票 二等座 提交订单"))
        assertFalse(hasAdultTicketSelection("确认订单 蔡某 儿童票 二等座 提交订单"))
        assertFalse(hasAdultTicketSelection("确认订单 请选择成人票"))
        assertFalse(hasAdultTicketSelection("确认订单 成人票未选择 二等座 提交订单"))
        assertFalse(hasAdultTicketSelection("确认订单 蔡某 成人身份证已填写 二等座 提交订单"))
        assertFalse(hasAdultTicketSelection("确认订单 非成人票 二等座 提交订单"))
    }

    @Test
    fun `station candidate requires exact normalized name`() {
        assertTrue(exactStationCandidate("汉口", "汉口"))
        assertTrue(exactStationCandidate(" 汉 口 ", "汉口"))
        assertFalse(exactStationCandidate("汉口站", "汉口"))
        assertFalse(exactStationCandidate("汉阳", "汉口"))
    }

    @Test
    fun `field selection rejects duplicate editable fields`() {
        val fields = listOf(
            NodeDescriptor(resourceId = "fromStation", editable = true),
            NodeDescriptor(text = "出发地", editable = true)
        )
        assertNull(findUniqueField(fields, SearchField.DEPARTURE))
        assertTrue(findUniqueField(listOf(NodeDescriptor(resourceId = "fromStation", editable = true)), SearchField.DEPARTURE) != null)
    }

    @Test
    fun `field selection keeps departure and arrival inputs separate`() {
        val fields = listOf(
            NodeDescriptor(resourceId = "stationInput", editable = true, contextText = "出发地"),
            NodeDescriptor(resourceId = "stationInput", editable = true, contextText = "到达地")
        )
        assertEquals("stationInput", findUniqueField(fields.take(1), SearchField.DEPARTURE)?.resourceId)
        assertEquals("出发地", findUniqueField(fields, SearchField.DEPARTURE)?.contextText)
        assertEquals("stationInput", findUniqueField(fields.takeLast(1), SearchField.ARRIVAL)?.resourceId)
    }

    @Test
    fun `field context never treats arrival as departure`() {
        assertTrue(fieldContextMatches("出发地", SearchField.DEPARTURE))
        assertTrue(fieldContextMatches("到达地", SearchField.ARRIVAL))
        assertFalse(fieldContextMatches("出发日期", SearchField.DEPARTURE))
        assertFalse(fieldContextMatches("到达日期", SearchField.ARRIVAL))
        assertFalse(fieldContextMatches("到达地 潜江", SearchField.DEPARTURE))
        assertFalse(fieldContextMatches("出发地 汉口 到达地 潜江", SearchField.DEPARTURE))
    }

    @Test
    fun `station candidates stay bound to their field context`() {
        assertNull(findExactCandidate(
            listOf(NodeDescriptor(text = "汉口", clickable = true, contextText = "到达地")),
            "汉口",
            SearchField.DEPARTURE
        ))
        val candidates = listOf(NodeDescriptor(text = "汉口", clickable = true, contextText = "出发地"))
        assertEquals("出发地", findExactCandidate(candidates, "汉口", SearchField.DEPARTURE)?.contextText)
        assertNull(findExactCandidate(candidates, "潜江", SearchField.DEPARTURE))
    }

    @Test
    fun `candidate selection rejects duplicate exact suggestions`() {
        val candidates = listOf(
            NodeDescriptor(text = "汉口", clickable = true),
            NodeDescriptor(contentDescription = "汉口", clickable = true)
        )
        assertNull(findExactCandidate(candidates, "汉口"))
        assertTrue(findExactCandidate(listOf(NodeDescriptor(text = "汉口", clickable = true)), "汉口") != null)
    }

    @Test
    fun `station picker suffix is accepted only as a supported display variant`() {
        assertTrue(stationCandidateMatches("汉口站", "汉口"))
        assertTrue(stationCandidateMatches("汉口", "汉口"))
        assertFalse(stationCandidateMatches("汉口南", "汉口"))
    }

    @Test
    fun `typed station text cannot confirm before candidate click`() {
        assertFalse(
            stationSelectionConfirmed(
                phase = StationSelectionPhase.WAITING_CANDIDATE,
                fieldValue = "汉口",
                stationName = "汉口",
                pickerVisible = false,
                candidateCount = 0,
                pickerEvidenceSeen = true,
                snapshotChanged = false
            )
        )
    }

    @Test
    fun `candidate click requires refreshed form confirmation`() {
        assertFalse(
            stationSelectionConfirmed(
                phase = StationSelectionPhase.WAITING_CONFIRMATION,
                fieldValue = "汉口",
                stationName = "汉口",
                pickerVisible = true,
                candidateCount = 0,
                pickerEvidenceSeen = true,
                snapshotChanged = true
            )
        )
        assertTrue(
            stationSelectionConfirmed(
                phase = StationSelectionPhase.WAITING_CONFIRMATION,
                fieldValue = "汉口",
                stationName = "汉口",
                pickerVisible = false,
                candidateCount = 0,
                pickerEvidenceSeen = true,
                snapshotChanged = true
            )
        )
    }

    @Test
    fun `matching text without picker evidence cannot confirm`() {
        assertFalse(
            stationSelectionConfirmed(
                phase = StationSelectionPhase.WAITING_CONFIRMATION,
                fieldValue = "汉口",
                stationName = "汉口",
                pickerVisible = false,
                candidateCount = 0,
                pickerEvidenceSeen = false,
                snapshotChanged = true
            )
        )
    }

    @Test
    fun `date text alone cannot confirm an action`() {
        assertFalse(
            dateSelectionConfirmed(
                phase = DateSelectionPhase.WAITING_CONFIRMATION,
                fieldValue = "2026-09-19",
                fieldContextText = "活动 2026-09-19",
                date = "2026-09-19",
                pickerVisible = false,
                snapshotChanged = false
            )
        )
        assertTrue(
            dateSelectionConfirmed(
                phase = DateSelectionPhase.WAITING_CONFIRMATION,
                fieldValue = "",
                fieldContextText = "出发日期 2026-09-19",
                date = "2026-09-19",
                pickerVisible = false,
                snapshotChanged = true
            )
        )
    }

    @Test
    fun `date elsewhere on the page cannot confirm a selection`() {
        assertFalse(
            dateSelectionConfirmed(
                phase = DateSelectionPhase.WAITING_CONFIRMATION,
                fieldValue = "",
                fieldContextText = "搜索历史 2026-09-19",
                date = "2026-09-19",
                pickerVisible = false,
                snapshotChanged = true
            )
        )
    }

    @Test
    fun `seat unavailable evidence excludes no seat`() {
        assertTrue(seatUnavailableEvidence("二等座 无票 一等座 有票", "二等座"))
        assertTrue(seatUnavailableEvidence("二等座 -- 一等座 有票", "二等座"))
        assertTrue(seatUnavailableEvidence("二等座 * 一等座 有票", "二等座"))
        assertFalse(seatUnavailableEvidence("二等座 有票 无座 5张", "二等座"))
    }

    @Test
    fun `reversed route is a conflict`() {
        val task = TicketTask(
            date = "2026-09-19",
            from = Station("汉口", "HKN"),
            to = Station("潜江", "QJN"),
            train = Train("D353", "汉口", "潜江", "07:25", "08:16", "00:51", emptyMap()),
            seat = "二等座",
            passengerName = "乘客",
            saleDateTime = null
        )
        assertEquals(
            SearchContextStatus.CONFLICT,
            classifySearchContext("查询车票 2026-09-19 潜江 汉口 D353 二等座 余票", task).status
        )
    }

    @Test
    fun `generic next step is not a search action`() {
        assertFalse(actionLabelMatches("下一步", SearchAction.SUBMIT_SEARCH))
        assertTrue(actionLabelMatches("查询车票", SearchAction.SUBMIT_SEARCH))
    }
}
