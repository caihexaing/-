package com.example.ticketassistant.automation

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticActionSemanticsTest {
    @Test
    fun `waiting text mentioning passenger is not a passenger action`() {
        assertFalse(isPassengerActionDispatched("席别动作后仍未进入乘车人页面"))
        assertTrue(isPassengerActionDispatched("目标乘车人点击已派发（姓名已脱敏）"))
        assertTrue(isPassengerActionDispatched("乘车人页面继续控件点击已派发"))
    }

    @Test
    fun `only booking action advances seat timestamps`() {
        assertFalse(isTargetControlDispatched("目标席别动作后仍未进入乘车人页面"))
        assertFalse(isSeatActionDispatched("目标席别节点点击已派发：二等座"))
        assertTrue(isSeatActionDispatched("目标席别行“预订”控件点击已派发"))
        assertFalse(isSeatActionDispatched("目标席别触控回退已完成"))
    }

    @Test
    fun `train card expansion is tracked separately from seat booking`() {
        assertTrue(isTrainActionDispatched("目标车次卡片点击已派发（外层展开）：D631"))
        assertTrue(isTrainActionDispatched("目标车次卡片点击已派发（摘要回退展开）：D631"))
        assertTrue(isTargetControlDispatched("目标车次卡片点击已派发（外层展开）：D631"))
        assertFalse(isSeatActionDispatched("目标车次卡片点击已派发（外层展开）：D631"))
    }
}
