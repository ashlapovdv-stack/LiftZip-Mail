package ru.liftzip.mail

import org.junit.Assert.*
import org.junit.Test

class InboxInteractionTest {
    @Test fun collapsedAndExpandedPanelsHaveExactEndpoints() {
        assertEquals(0,InboxInteractionRules.panelHeight(218,0f))
        assertEquals(218,InboxInteractionRules.panelHeight(218,1f))
        assertEquals(0,InboxInteractionRules.panelHeight(218,-1f))
        assertEquals(218,InboxInteractionRules.panelHeight(218,2f))
    }
    @Test fun bothPanelsShrinkMonotonicallyWithTheSameProgress() {
        var top=218;var bottom=80
        for(step in 100 downTo 0) {
            val fraction=step/100f
            val nextTop=InboxInteractionRules.panelHeight(218,fraction)
            val nextBottom=InboxInteractionRules.panelHeight(80,fraction)
            assertTrue(nextTop<=top);assertTrue(nextBottom<=bottom)
            top=nextTop;bottom=nextBottom
        }
    }
    @Test fun reversingAnAccidentalPullRetractsTheHintAndReturnsToRest() {
        assertEquals(0.9f,InboxInteractionRules.pullProgress(0.9f,false,false),0f)
        assertEquals(0.4f,InboxInteractionRules.pullProgress(0.4f,false,false),0f)
        assertEquals(0f,InboxInteractionRules.pullProgress(0f,false,false),0f)
    }
    @Test fun releasedRefreshHasNoPersistentOverlayEvenBeforeUiRefreshStateArrives() {
        assertEquals(0f,InboxInteractionRules.pullProgress(1.2f,false,true),0f)
        assertEquals(0f,InboxInteractionRules.pullProgress(1f,true,false),0f)
        assertEquals(1f,InboxInteractionRules.pullProgress(1.2f,false,false),0f)
    }
    @Test fun snackbarBottomSitsAboveFabWithAGap() {
        val bottom=InboxInteractionRules.noticeBottom(1000,"inbox",NoticeAnchor("inbox",750f),24,12)
        assertEquals(262,bottom)
        assertEquals(738,1000-bottom)
    }
    @Test fun keyboardAndComposeActionsAreNotCountedTwice() {
        assertEquals(362,InboxInteractionRules.noticeBottom(1000,"compose",NoticeAnchor("compose",650f),300,12))
        assertEquals(412,InboxInteractionRules.noticeBottom(1000,"compose",NoticeAnchor("compose",650f),400,12))
    }
    @Test fun leavingInboxDropsItsOldFabAnchor() {
        assertEquals(36,InboxInteractionRules.noticeBottom(1000,"settings",NoticeAnchor("inbox",750f),24,12))
        assertEquals(36,InboxInteractionRules.noticeBottom(1000,"inbox",null,24,12))
    }
    @Test fun invalidOrOffscreenAnchorsCannotPushTheCardOutsideTheScreen() {
        assertEquals(36,InboxInteractionRules.noticeBottom(1000,"inbox",NoticeAnchor("inbox",-10f),24,12))
        assertEquals(36,InboxInteractionRules.noticeBottom(1000,"inbox",NoticeAnchor("inbox",1100f),24,12))
    }
}
