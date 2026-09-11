package com.example.ticketassistant.automation

import org.junit.Assert.assertEquals
import org.junit.Test

class SearchStageTest {
    @Test
    fun `successful form actions advance in order`() {
        assertEquals(SearchStage.DEPARTURE, nextSearchStage(SearchStage.OPEN_SEARCH, OfficialPageState.HOME_PAGE, InteractionResult.DONE))
        assertEquals(SearchStage.ARRIVAL, nextSearchStage(SearchStage.DEPARTURE, OfficialPageState.HOME_PAGE, InteractionResult.DONE))
        assertEquals(SearchStage.DATE, nextSearchStage(SearchStage.ARRIVAL, OfficialPageState.HOME_PAGE, InteractionResult.DONE))
        assertEquals(SearchStage.SUBMIT_SEARCH, nextSearchStage(SearchStage.DATE, OfficialPageState.HOME_PAGE, InteractionResult.DONE))
        assertEquals(SearchStage.RESULT, nextSearchStage(SearchStage.SUBMIT_SEARCH, OfficialPageState.SEARCH_RESULT, InteractionResult.DONE))
    }

    @Test
    fun `waiting or failed actions never advance`() {
        assertEquals(SearchStage.DATE, nextSearchStage(SearchStage.DATE, OfficialPageState.HOME_PAGE, InteractionResult.WAITING))
        assertEquals(SearchStage.DATE, nextSearchStage(SearchStage.DATE, OfficialPageState.HOME_PAGE, InteractionResult.FAILED))
    }
}
