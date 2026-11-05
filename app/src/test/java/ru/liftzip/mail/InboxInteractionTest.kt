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
            assertEquals(800-kotlin.math.round(152*step/100f).toInt(),g.listHeight+g.listY-g.listBottomPadding)
        }
    }
    @Test fun aHiddenHeaderWaitsForTheClippedRowAndUsesOnlyTheRemainingDrag() {
        assertEquals(30f,InboxInteractionRules.revealAlignment(30f,0f,80),0f)
        assertEquals(80f,InboxInteractionRules.revealAlignment(120f,0f,80),0f)
        assertEquals(0f,InboxInteractionRules.revealAlignment(120f,0f,0),0f)
        assertEquals(0f,InboxInteractionRules.revealAlignment(-20f,0f,80),0f)
        assertEquals(0f,InboxInteractionRules.revealAlignment(20f,0.5f,80),0f)
        assertEquals(0f,InboxInteractionRules.revealAlignment(Float.NaN,0f,80),0f)
        val controller=InboxChromeController(0f).apply{measureHeader(200f)}
        val alignment=InboxInteractionRules.revealAlignment(120f,controller.fraction,80)
        val consumed=controller.scroll(120f-alignment)
        assertEquals(120f,alignment+consumed,0f)
        assertEquals(0.2f,controller.fraction,0.00001f)
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
        assertEquals(0.9f,InboxInteractionRules.pullProgress(0.9f,false,false,true),0f)
        assertEquals(0.4f,InboxInteractionRules.pullProgress(0.4f,false,false,true),0f)
        assertEquals(0f,InboxInteractionRules.pullProgress(0f,false,false,true),0f)
    }
    @Test fun releasedRefreshHasNoPersistentOverlayEvenBeforeUiRefreshStateArrives() {
        assertEquals(0f,InboxInteractionRules.pullProgress(1.2f,false,true,true),0f)
        assertEquals(0f,InboxInteractionRules.pullProgress(1f,true,false,true),0f)
        assertEquals(1f,InboxInteractionRules.pullProgress(1.2f,false,false,true),0f)
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
    @Test fun automaticRefreshNeverShowsThePullHintDuringItsReturnAnimation() {
        assertEquals(0f,InboxInteractionRules.pullProgress(1f,true,false,false),0f)
        for(step in 100 downTo 0)assertEquals(0f,InboxInteractionRules.pullProgress(step/100f,false,false,false),0f)
    }

}
