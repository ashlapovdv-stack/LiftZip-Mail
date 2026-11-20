package ru.liftzip.mail

import org.junit.Assert.*
import org.junit.Test

class InboxInteractionTest {
    @Test fun bottomControlsWaitForAboutTwoCardsInEachDirection() {
        val chrome=InboxChromeController()
        assertTrue(chrome.bottomVisibility(true,-120f,120f))
        assertTrue(chrome.bottomVisibility(true,-118f,120f))
        assertFalse(chrome.bottomVisibility(true,-3f,120f))
        assertFalse(chrome.bottomVisibility(false,80f,160f))
        assertFalse(chrome.bottomVisibility(false,120f,120f))
        assertTrue(chrome.bottomVisibility(false,60f,120f))
    }
    @Test fun smallBackAndForthMovementsCancelBeforeSwitching() {
        val chrome=InboxChromeController()
        repeat(500){
            assertTrue(chrome.bottomVisibility(true,-10f,100f))
            assertTrue(chrome.bottomVisibility(true,10f,100f))
        }
        assertTrue(chrome.bottomVisibility(true,-195f,100f))
        assertTrue(chrome.bottomVisibility(true,10f,100f))
        assertTrue(chrome.bottomVisibility(true,-10f,100f))
        assertFalse(chrome.bottomVisibility(true,-6f,100f))
    }
    @Test fun returningToStartOrForcingPanelsResetsTheTravel() {
        val chrome=InboxChromeController()
        assertTrue(chrome.bottomVisibility(true,-190f,100f))
        assertTrue(chrome.bottomVisibility(true,0f,100f,forceVisible=true))
        assertTrue(chrome.bottomVisibility(true,-20f,100f))
        assertFalse(chrome.bottomVisibility(false,Float.NaN,100f))
        assertTrue(chrome.bottomVisibility(false,0f,100f,forceVisible=true))
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
    @Test fun hidingWaitsForThePartlyVisibleRowToMovePastTheHeader() {
        assertEquals(-30f,InboxInteractionRules.hideAlignment(-30f,1f,80,200),0f)
        assertEquals(-120f,InboxInteractionRules.hideAlignment(-200f,1f,80,200),0f)
        assertEquals(0f,InboxInteractionRules.hideAlignment(-50f,1f,0,200),0f)
        assertEquals(0f,InboxInteractionRules.hideAlignment(-50f,0.5f,80,200),0f)
        assertEquals(0f,InboxInteractionRules.hideAlignment(50f,1f,80,200),0f)
    }
    @Test fun headerOffsetIsSmoothAndClamped() {
        val fractions=(0..100).map{it/100f}+(100 downTo 0).map{it/100f}
        val offsets=fractions.map{InboxInteractionRules.headerOffset(218,it)}
        offsets.zipWithNext().forEach{(a,b)->assertTrue(kotlin.math.abs(a-b)<=3)}
        assertEquals(0,InboxInteractionRules.headerOffset(218,-1f))
        assertEquals(218,InboxInteractionRules.headerOffset(218,2f))
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
