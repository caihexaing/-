package com.example.ticketassistant.automation

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
    fun `generic next step is not a search action`() {
        assertFalse(actionLabelMatches("下一步", SearchAction.SUBMIT_SEARCH))
        assertTrue(actionLabelMatches("查询车票", SearchAction.SUBMIT_SEARCH))
    }
}
