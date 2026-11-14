package ru.liftzip.mail

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderZoomGeometryTest {
    @Test fun zoomResetAppearsOnBothSidesOfTheCurrentReadingScale() {
        assertTrue(readerZoomResetVisible(0.65f))
        assertTrue(readerZoomResetVisible(1.5f))
        assertEquals(false,readerZoomResetVisible(1f))
        assertEquals(false,readerZoomResetVisible(1.02f))
        assertEquals(false,readerZoomResetVisible(0.98f))
        assertEquals(false,readerZoomResetVisible(Float.NaN))
    }

    @Test fun collapsingHistoryRestoresScrollHiddenChromeWithoutOverridingZoom() {
        var zoom=0f
        val toolbar=ReaderToolbarState{zoom}
        toolbar.height=100
        toolbar.scroll(100f)
        assertEquals(100f,toolbar.hiddenPixels,0.001f)
        toolbar.onHistoryCollapsed()
        assertEquals(0f,toolbar.hiddenPixels,0.001f)
        toolbar.scroll(100f)
        zoom=1f
        toolbar.onHistoryCollapsed()
        assertEquals(100f,toolbar.hiddenPixels,0.001f)
        zoom=0f
        assertEquals(0f,toolbar.hiddenPixels,0.001f)
    }

    @Test fun headerInsetUsesFittedCssScaleInsteadOfDeclaredPageWidth() {
        val document=MailHtmlDocument("<html><head></head><body>Letter</body></html>",emptyMap(),false)
        val prepared=document.withReaderInset(600,1080,0.5f).withReaderCanvas(2200,1080,0.5f)
        assertTrue(prepared.html.contains("height:1200.0px!important"))
        assertTrue(prepared.html.contains("min-height:4400.0px!important"))
    }

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
