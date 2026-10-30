package ru.liftzip.mail

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class TrashTransferTest {
    @Test fun largeSelectionsCommitOnlyTheirAcknowledgedBatches()=runBlocking {
        val moved=mutableListOf<List<Long>>();val saved=mutableListOf<Set<Long>>()
        TrashTransfer.run((1L..201L).toList(),read={it},uid={it},move={moved+=it},before={true},onConfirmed={saved+=it;true})
        assertEquals(listOf(100,100,1),moved.map{it.size})
        assertEquals((1L..201L).toSet(),saved.flatten().toSet())
    }
    @Test fun failureOnALaterBatchKeepsPreviousSuccessAndDoesNotTouchTheRest()=runBlocking {
        val saved=mutableSetOf<Long>();var calls=0
        try{TrashTransfer.run(listOf(1L,2L,3L),read={it},uid={it},move={if(++calls==2)error("offline")},before={true},onConfirmed={saved+=it;true},batchSize=1);fail("expected failure")}
        catch(e: IllegalStateException){assertEquals("offline",e.message)}
        assertEquals(setOf(1L),saved);assertEquals(2,calls)
    }
    @Test fun alreadyMissingUidsAreRemovedFromTheLocalListWithoutMovingAnotherMessage()=runBlocking {
        var moves=0;val saved=mutableSetOf<Long>()
        TrashTransfer.run(listOf(1L,2L),read={listOf(2L)},uid={it},move={assertEquals(listOf(2L),it);moves++},before={true},onConfirmed={saved+=it;true})
        assertEquals(setOf(1L,2L),saved);assertEquals(1,moves)
    }
    @Test fun changedMailboxStopsBeforeAnyServerMutation()=runBlocking {
        TrashTransfer.run(listOf(1L),read={fail("read");it},uid={it},move={fail("move")},before={false},onConfirmed={fail("commit");true})
    }
    @Test fun missingMoveAcknowledgementDoesNotDeleteTheLocalCopy()=runBlocking {
        var saved=false
        try{TrashTransfer.run(listOf(1L),read={it},uid={it},move={error("connection lost")},before={true},onConfirmed={saved=true;true});fail("expected failure")}
        catch(_: IllegalStateException){}
        assertFalse(saved)
    }
    @Test fun anUnrequestedUidIsRejectedBeforeMovingAnything()=runBlocking {
        var moved=false
        try{TrashTransfer.run(listOf(1L),read={listOf(99L)},uid={it},move={moved=true},before={true},onConfirmed={fail("commit");true});fail("expected failure")}
        catch(_: IllegalArgumentException){}
        assertFalse(moved)
    }
    @Test fun aRejectedCommitPreventsAnotherBatch()=runBlocking {
        var moved=0
        TrashTransfer.run(listOf(1L,2L),read={it},uid={it},move={moved++},before={true},onConfirmed={false},batchSize=1)
        assertEquals(1,moved)
    }
    @Test fun cancellationDoesNotReportThePendingBatchAsDeleted()=runBlocking {
        var saved=false
        try{TrashTransfer.run(listOf(1L),read={it},uid={it},move={throw CancellationException("stop")},before={true},onConfirmed={saved=true;true});fail("expected cancellation")}
        catch(_: CancellationException){}
        assertFalse(saved)
    }
}
