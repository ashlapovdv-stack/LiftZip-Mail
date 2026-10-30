package ru.liftzip.mail

import org.junit.Assert.*
import org.junit.Test

class InboxChromeTest {
    private fun controller(fraction: Float=1f,height: Float=200f)=InboxChromeController(fraction).apply{measureHeader(height)}
    @Test fun hideDistanceAndOpacityFollowTheDragImmediately() {
        val c=controller();assertEquals(-30f,c.scroll(-30f),0f);assertEquals(0.85f,c.fraction,0.00001f)
    }
    @Test fun tenSlowStepsHaveTheSameResultAsOneFastStep() {
        val slow=controller();val fast=controller()
        repeat(10){slow.scroll(-5f)};fast.scroll(-50f)
        assertEquals(fast.fraction,slow.fraction,0.00001f)
    }
    @Test fun pausingHalfwayPreservesTheExactPositionAndOpacity() {
        val c=controller();c.scroll(-85f);val paused=c.fraction
        repeat(100){assertEquals(0f,c.scroll(0f),0f);assertEquals(paused,c.fraction,0f)}
    }
    @Test fun reversingTheFingerRestoresTheSameDistanceWithoutAThreshold() {
        val c=controller();c.scroll(-80f);assertEquals(1f,c.scroll(1f),0.00001f);assertEquals(0.605f,c.fraction,0.00001f)
    }
    @Test fun reverseMovementCanStopAtAnotherPartialOpacity() {
        val c=controller();c.scroll(-120f);c.scroll(40f);assertEquals(0.6f,c.fraction,0.00001f)
        c.scroll(0f);assertEquals(0.6f,c.fraction,0.00001f)
    }
    @Test fun onlyTheRemainingHeaderDistanceIsConsumedAtTheHiddenBoundary() {
        val c=controller();assertEquals(-200f,c.scroll(-500f),0f);assertEquals(0f,c.fraction,0f)
        assertEquals(0f,c.scroll(-50f),0f)
    }
    @Test fun onlyTheRemainingHeaderDistanceIsConsumedAtTheVisibleBoundary() {
        val c=controller(0f);assertEquals(200f,c.scroll(500f),0f);assertEquals(1f,c.fraction,0f)
        assertEquals(0f,c.scroll(50f),0f)
    }
    @Test fun viewportMovementAndRemainingListScrollNeverDoubleTheFingerDistance() {
        val c=controller();var listScroll=0f
        for(delta in listOf(-55f,-120f,-100f,30f,190f,65f)) {
            val before=InboxInteractionRules.viewport(800,200,152,c.fraction).listY-listScroll
            val consumed=c.scroll(delta);listScroll-=delta-consumed
            val after=InboxInteractionRules.viewport(800,200,152,c.fraction).listY-listScroll
            assertEquals(delta,after-before,0.001f)
        }
    }
    @Test fun programmaticScrollDoesNotFinishAPartialFade() {
        val c=controller();c.scroll(-75f);val paused=c.fraction
        assertEquals(0f,c.scroll(-800f,userInput=false),0f)
        assertEquals(0f,c.scroll(300f,userInput=false),0f);assertEquals(paused,c.fraction,0f)
    }
    @Test fun disabledAutoHideAndSelectionShowActionsWithoutStealingListScroll() {
        val c=controller(0.3f)
        assertEquals(0f,c.scroll(-100f,forced=true),0f);assertEquals(1f,c.fraction,0f)
    }
    @Test fun openingTheMenuCanExplicitlyRevealAllControls() {
        val c=controller();c.scroll(-90f);c.show();assertEquals(1f,c.fraction,0f)
        c.scroll(-10f);assertEquals(0.95f,c.fraction,0.00001f)
    }
    @Test fun aNewHeaderHeightPreservesOpacityAndTheNextDragUsesItsActualPixels() {
        val c=controller(0.5f);c.measureHeader(400f);assertEquals(0.5f,c.fraction,0f)
        assertEquals(-40f,c.scroll(-40f),0f);assertEquals(0.4f,c.fraction,0.00001f)
    }
    @Test fun restoringTheScreenRetainsItsPartialState() {
        val old=controller();old.scroll(-70f);val restored=controller(old.fraction)
        assertEquals(old.fraction,restored.fraction,0f)
        restored.scroll(10f);assertEquals(0.7f,restored.fraction,0.00001f)
    }
    @Test fun subpixelDragsAccumulateInsteadOfBeingDropped() {
        val c=controller();repeat(20){assertEquals(-0.25f,c.scroll(-0.25f),0f)}
        assertEquals(0.975f,c.fraction,0.00001f)
    }
    @Test fun inputBeforeMeasurementDoesNotCauseADivideByZeroOrAPositionJump() {
        val c=InboxChromeController(0.5f);assertEquals(0f,c.scroll(-20f),0f);assertEquals(0.5f,c.fraction,0f)
        c.measureHeader(200f);c.scroll(-20f);assertEquals(0.4f,c.fraction,0.00001f)
    }
    @Test fun invalidInputCannotEscapeTheVisibleRange() {
        val c=controller(0.5f)
        listOf(Float.NaN,Float.POSITIVE_INFINITY,Float.NEGATIVE_INFINITY).forEach{assertEquals(0f,c.scroll(it),0f)}
        assertEquals(0.5f,c.fraction,0f)
        c.measureHeader(0f);assertEquals(0f,c.scroll(5f),0f)
        assertEquals(1f,controller(2f).fraction,0f);assertEquals(0f,controller(-1f).fraction,0f)
        assertEquals(1f,controller(Float.NaN).fraction,0f)
    }
    @Test fun nativeFlingContinuesTheSamePixelMotionUntilTheListStops() {
        val c=controller();c.scroll(-50f);c.beginFling(-800f)
        assertEquals(-30f,c.scroll(-30f,userInput=false),0f);assertEquals(0.6f,c.fraction,0.00001f)
        c.endFling();val stopped=c.fraction
        assertEquals(0f,c.scroll(-30f,userInput=false),0f);assertEquals(stopped,c.fraction,0f)
    }
    @Test fun anOppositeFlingRestoresPanelsAtItsOwnSpeed() {
        val c=controller(0.3f);c.beginFling(600f);c.scroll(20f,userInput=false)
        assertEquals(0.4f,c.fraction,0.00001f)
    }

}
