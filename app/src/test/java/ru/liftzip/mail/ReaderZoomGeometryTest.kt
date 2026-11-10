package ru.liftzip.mail

import org.junit.Assert.assertEquals
import org.junit.Test

class ReaderZoomGeometryTest {
    @Test fun zoomUsesOneStableViewportInsteadOfResizingOnEveryProgressStep() {
        val normal=600;val expanded=2200
        assertEquals(normal,readerBodyHeight(normal,expanded,zoomed=false))
        assertEquals(expanded,readerBodyHeight(normal,expanded,zoomed=true))
        val heights=List(101){step->readerBodyHeight(normal,expanded,zoomed=step>0)}
        assertEquals(2,heights.toSet().size)
        assertEquals(expanded,heights.last())
    }

    @Test fun normalDocumentHeightIsRestoredAtInitialScale() {
        assertEquals(600,readerBodyHeight(600,2200,zoomed=false))
        assertEquals(600,readerBodyHeight(600,0,zoomed=true))
    }
}
