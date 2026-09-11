package com.example.ticketassistant.automation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OfficialSearchInteractorTest {
    @Test
    fun `unique editable resource id identifies the intended field`() {
        val nodes = listOf(
            NodeDescriptor(resourceId = "departureStation", editable = true),
            NodeDescriptor(resourceId = "arrivalStation", editable = true)
        )
        assertEquals("departureStation", findUniqueField(nodes, SearchField.DEPARTURE)?.resourceId)
        assertEquals("arrivalStation", findUniqueField(nodes, SearchField.ARRIVAL)?.resourceId)
    }

    @Test
    fun `ambiguous resource ids do not authorize a field action`() {
        val nodes = listOf(
            NodeDescriptor(resourceId = "departureStation", editable = true),
            NodeDescriptor(resourceId = "departure_city", editable = true)
        )
        assertNull(findUniqueField(nodes, SearchField.DEPARTURE))
    }
}
