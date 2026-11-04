package ru.liftzip.mail

import org.junit.Assert.*
import org.junit.Test

class InboxContentMeasureTest {
    @Test fun oneMessageAndAnEmptyPlaceholderKeepThePanelsVisible() {
        assertEquals(false,InboxContentMeasure(setOf("mail")).observe(1,480,listOf("mail" to 126)))
        assertEquals(false,InboxContentMeasure(setOf("empty","footer")).observe(2,480,listOf("empty" to 180,"footer" to 80)))
    }
    @Test fun aMeasuredLowerBoundCanIdentifyALongListBeforeAllRowsAreKnown() {
        val measure=InboxContentMeasure((1..1000).toSet())
        assertNull(measure.observe(1000,480,listOf(1 to 126,2 to 126)))
        assertEquals(true,measure.observe(1000,480,listOf(2 to 126,3 to 126,4 to 126)))
    }
    @Test fun repeatedSamplesDoNotDoubleCountRowsAndResizingReplacesTheirHeight() {
        val measure=InboxContentMeasure(setOf("a","b"))
        assertNull(measure.observe(2,480,listOf("a" to 200)))
        assertEquals(false,measure.observe(2,480,listOf("a" to 200,"b" to 200)))
        repeat(20){assertEquals(false,measure.observe(2,480,listOf("b" to 200)))}
        assertEquals(true,measure.observe(2,480,listOf("b" to 300)))
        assertEquals(false,measure.observe(2,480,listOf("b" to 200)))
    }
    @Test fun missingGeometryAndStaleItemCountsCannotOverrideTheCurrentDecision() {
        val measure=InboxContentMeasure(setOf("new"))
        assertNull(measure.observe(1,0,listOf("new" to 200)))
        assertNull(measure.observe(30,480,listOf("new" to 200)))
        assertNull(measure.observe(1,480,emptyList()))
        assertEquals(false,measure.observe(1,480,listOf("new" to 200)))
    }
    @Test fun anOldLayoutWithTheSameCountCannotPolluteANewFilteredDataset() {
        val measure=InboxContentMeasure(setOf("new"))
        assertNull(measure.observe(1,480,listOf("old" to 700)))
        assertEquals(false,measure.observe(1,480,listOf("new" to 126)))
    }
    @Test fun availableSpaceAlwaysReservesTheFullPanelsInsteadOfTheirFadedHeight() {
        assertEquals(432,InboxPanelMetrics(800,218,138,12).contentHeight)
        assertEquals(0,InboxPanelMetrics(200,218,138,12).contentHeight)
    }
}
