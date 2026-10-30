package ru.liftzip.mail

import org.junit.Assert.*
import org.junit.Test

class InboxInteractionTest {
    @Test fun scrollingViewportNeverChangesHeightAsThePanelsFade() {
        for(step in 0..100)assertEquals(800,InboxInteractionRules.viewport(800,218,152,step/100f).listHeight)
    }
    @Test fun bottomControlsStayFixedWhileTheListCoversTheHeader() {
        for(step in 0..100) {
            val g=InboxInteractionRules.viewport(800,218,152,step/100f)
            assertEquals(648,g.bottomY)
            assertEquals(648,g.listHeight+g.listY-g.listBottomPadding)
        }
    }
    @Test fun reversingTheAnimationHasNoViewportResizeOrPositionJumpAtTheEndpoints() {
        val fractions=(0..100).map{it/100f}+(100 downTo 0).map{it/100f}
        val positions=fractions.map{InboxInteractionRules.viewport(800,218,152,it)}
        positions.zipWithNext().forEach{(a,b)->assertTrue(kotlin.math.abs(a.listY-b.listY)<=3);assertEquals(a.listHeight,b.listHeight);assertEquals(a.bottomY,b.bottomY)}
    }
    @Test fun animationProgressIsClampedAtItsVisibleAndHiddenStates() {
        assertEquals(0,InboxInteractionRules.viewport(800,218,152,-1f).listY)
        assertEquals(218,InboxInteractionRules.viewport(800,218,152,2f).listY)
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
