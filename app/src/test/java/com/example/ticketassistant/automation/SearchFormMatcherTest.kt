package com.example.ticketassistant.automation

import org.junit.Assert.assertFalse
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
