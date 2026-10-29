package ru.liftzip.mail

import org.junit.Assert.*
import org.junit.Test

class InboxChromeTest {
    @Test fun scrollTowardOlderMailHidesAllControlsAfterARealDrag() {
        val c=InboxChromeController();assertTrue(c.scroll(-10f));assertFalse(c.scroll(-14f))
    }
    @Test fun reversingDirectionRestoresControlsSooner() {
        val c=InboxChromeController();c.scroll(-30f);assertFalse(c.scroll(4f));assertTrue(c.scroll(4f))
    }
    @Test fun smallOppositeMovementDoesNotReuseOldDistanceAndFlicker() {
        val c=InboxChromeController();c.scroll(-20f);c.scroll(1f);assertTrue(c.scroll(-20f));assertFalse(c.scroll(-4f))
    }
    @Test fun zeroConsumedScrollDoesNotHidePanelsOnAStationaryList() {
        val c=InboxChromeController();repeat(100){assertTrue(c.scroll(0f))}
    }
    @Test fun selectionForcesControlsVisibleWhileTheListStillScrolls() {
        val c=InboxChromeController();c.scroll(-30f);assertTrue(c.scroll(-100f,forced=true));assertTrue(c.visible)
    }
    @Test fun explicitShowResetsTheOldDragAccumulator() {
        val c=InboxChromeController();c.scroll(-20f);c.show();assertTrue(c.scroll(-10f))
    }
}
