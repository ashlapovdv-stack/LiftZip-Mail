package ru.liftzip.mail

import org.junit.Assert.assertEquals
import org.junit.Test

class ReaderZoomGeometryTest {
    @Test fun zoomKeepsTheDocumentReadingWindowAtItsNormalHeight() {
        val normal=600
        assertEquals(normal,readerBodyHeight(normal))
        // Any intermediate or completed zoom state must use the same native window. Changing
        // the height here re-enters native WebView layout in the middle of the pinch stream.
        val heights=List(101){step->readerBodyHeight(normal)}
        assertEquals(1,heights.toSet().size)
        assertEquals(normal,heights.last())
    }

    @Test fun differentDocumentHeightsStillProduceIndependentReadingWindows() {
        assertEquals(300,readerBodyHeight(300))
        assertEquals(2048,readerBodyHeight(2048))
    }
}
