package ru.liftzip.mail

import jakarta.mail.internet.MimeBodyPart
import java.io.IOException
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class HistoryRecoveryTest {
    private fun mail(uid: Long,cached: Boolean=false,account: String="a")=MailItem("$account:1:$uid",account,uid,1,sender="Sender",senderEmail="sender@example.com",recipients="to@example.com",subject="Subject $uid",body=if(cached)"saved $uid" else "",date=uid,contentCached=cached)
    private fun issue(m: MailItem,attempts: Int=1)=HistoryIssue(m.id,m.accountId,"Read failed",attempts)

    @Test fun oneBadMessageCannotKeep459SavedTextsFromAdvancingToTheOther599()=runBlocking {
        var messages=(1L..600L).map{mail(it,cached=it>141)}
        var job=HistoryDownload("job",listOf("a"),"running")
        var batches=0
        while(true) {
            val pending=HistoryRules.pendingBodies("a",messages,job.issues).take(10)
            if(pending.isEmpty())break
            assertTrue(++batches<100)
            HistoryBodies.transfer(pending,read={m->if(m.uid==100L)throw IOException("bad MIME");m.copy(body="full ${m.uid}",contentCached=true)},save={result->
                job=HistoryRules.afterBody(job,messages,result,batches.toLong())
                messages=SyncRules.contentBatch(messages,result);true
            },describe={it.message.orEmpty()})
        }
        assertEquals(600,messages.size)
        assertEquals(599,messages.count{it.contentCached})
        assertEquals(2,job.issues.single().attempts)
        assertEquals("a:1:100",job.issues.single().messageId)
        assertTrue(messages.filter{it.uid>141}.all{it.body=="saved ${it.uid}"})
    }
    @Test fun aBodyIsSavedBeforeTheNextReadHasFinished()=runBlocking {
        withTimeout(5000) {
            val release=CompletableDeferred<Unit>();val secondStarted=CompletableDeferred<Unit>()
            val saved=mutableListOf<String>()
            val worker=async(start=CoroutineStart.UNDISPATCHED){HistoryBodies.transfer(listOf(mail(1),mail(2)),read={m->if(m.uid==2L){secondStarted.complete(Unit);release.await()};m.copy(contentCached=true)},save={saved+=it.messages.map{m->m.id};true},describe={"failed"})}
            secondStarted.await()
            assertEquals(listOf("a:1:1"),saved)
            release.complete(Unit);worker.await()
            assertEquals(listOf("a:1:1","a:1:2"),saved)
        }
    }
    @Test fun anErrorPreservesEarlierBodiesAndEndsTheConnectionBeforeTheNextMessage()=runBlocking {
        val read=mutableListOf<Long>();val saved=mutableListOf<ContentBatch>()
        HistoryBodies.transfer(listOf(mail(1),mail(2),mail(3)),read={m->read+=m.uid;if(m.uid==2L)throw IOException("broken");m.copy(contentCached=true)},save={saved+=it;true},describe={it.message.orEmpty()})
        assertEquals(listOf(1L,2L),read)
        assertEquals("a:1:1",saved[0].messages.single().id)
        assertEquals(mapOf("a:1:2" to "broken"),saved[1].failures)
    }
    @Test fun aStoppedGenerationDoesNotReadOrSaveTheRestOfTheBatch()=runBlocking {
        var reads=0;var saves=0
        HistoryBodies.transfer(listOf(mail(1),mail(2)),read={reads++;it},save={saves++;false},describe={"failed"})
        assertEquals(1,reads);assertEquals(1,saves)
    }
    @Test fun cancellationDoesNotTurnIntoAMessageErrorOrLoseAnEarlierSave()=runBlocking {
        val saved=mutableListOf<ContentBatch>()
        try {
            HistoryBodies.transfer(listOf(mail(1),mail(2)),read={if(it.uid==2L)throw CancellationException("stopped");it},save={saved+=it;true},describe={"should not be called"})
            fail("Cancellation must propagate")
        }catch(_: CancellationException){assertEquals(1,saved.size);assertTrue(saved.single().failures.isEmpty())}
    }
    @Test fun stoppingBeforeAReadAvoidsThatNetworkRequest()=runBlocking {
        var contacted=false
        HistoryBodies.transfer(listOf(mail(1)),read={contacted=true;it},save={true},describe={"failed"},before={false})
        assertFalse(contacted)
    }
    @Test fun aMissingUidIsCommittedAndTheOtherMessageStillLoads()=runBlocking {
        var cached=listOf(mail(1),mail(2))
        HistoryBodies.transfer(cached,read={if(it.uid==1L)null else it.copy(contentCached=true)},save={cached=SyncRules.contentBatch(cached,it);true},describe={"failed"})
        assertEquals(listOf("a:1:2"),cached.map{it.id});assertTrue(cached.single().contentCached)
    }
    @Test fun storageErrorsPropagateInsteadOfBeingMisreportedAsBadMail()=runBlocking {
        try {
            HistoryBodies.transfer(listOf(mail(1)),read={it},save={throw IOException("disk")},describe={"bad mail"})
            fail("The save must fail")
        }catch(e: IOException){assertEquals("disk",e.message)}
    }
    @Test fun freshBodiesHavePriorityOverAnEarlierFailedRead() {
        val bad=mail(10);val fresh=mail(1)
        assertEquals(listOf(fresh,bad),HistoryRules.pendingBodies("a",listOf(bad,fresh),listOf(issue(bad))))
    }
    @Test fun theSecondFailedAttemptStopsAutomaticRetriesOfThatUid() {
        val m=mail(1)
        assertTrue(HistoryRules.pendingBodies("a",listOf(m),listOf(issue(m,2))).isEmpty())
        assertEquals(listOf(m),HistoryRules.pendingBodies("a",listOf(m),emptyList()))
    }
    @Test fun retryCountsSurviveAWorkerRestartBecauseTheyBelongToTheJob() {
        val m=mail(1);val response=ContentBatch(emptyList(),emptySet(),mapOf(m.id to "error"))
        val once=HistoryRules.afterBody(HistoryDownload("job",listOf("a"),"running"),listOf(m),response,10)
        val restored=once.copy(status="waiting")
        val twice=HistoryRules.afterBody(restored,listOf(m),response,20)
        assertEquals(2,twice.issues.single().attempts)
        assertTrue(HistoryRules.pendingBodies("a",listOf(m),twice.issues).isEmpty())
    }
    @Test fun aSuccessfulRetryClearsTheIssueAndUpdatesSavedTime() {
        val m=mail(1);val job=HistoryDownload("job",listOf("a"),"running",issues=listOf(issue(m)),savedAt=5)
        val next=HistoryRules.afterBody(job,listOf(m),ContentBatch(listOf(m.copy(contentCached=true)),emptySet()),20)
        assertTrue(next.issues.isEmpty());assertEquals(20L,next.savedAt)
    }
    @Test fun aFailureUpdatesActivityButDoesNotPretendAnotherTextWasSaved() {
        val m=mail(1);val job=HistoryDownload("job",listOf("a"),"running",savedAt=5)
        val next=HistoryRules.afterBody(job,listOf(m),ContentBatch(emptyList(),emptySet(),mapOf(m.id to "failed")),20)
        assertEquals(5L,next.savedAt);assertEquals(20L,next.activityAt)
    }
    @Test fun resolvedDeletedAndUnselectedIssuesDoNotKeepTheJobIncomplete() {
        val pending=mail(1);val full=mail(2,true);val deleted=mail(3);val other=mail(4,account="b")
        val job=HistoryDownload("job",listOf("a"),"running",issues=listOf(pending,full,deleted,other).map{issue(it)})
        assertEquals(listOf(issue(pending)),HistoryRules.remainingIssues(job,listOf(pending,full,other)))
    }
    @Test fun bodySelectionExcludesSavedTextsSparseUnreadAndOtherAccounts() {
        val pending=mail(1)
        val candidates=listOf(pending,mail(2,true),mail(3).copy(localFolder="unread-cache"),mail(4,account="b"))
        assertEquals(listOf(pending),HistoryRules.pendingBodies("a",candidates,emptyList()))
    }
    @Test fun workStateFollowsTheSchedulerRatherThanAPersistedRunningLabel() {
        assertEquals(HistoryWorkState.STOPPED,HistoryRules.workState(listOf("SUCCEEDED","CANCELLED")))
        assertEquals(HistoryWorkState.WAITING,HistoryRules.workState(listOf("SUCCEEDED","ENQUEUED")))
        assertEquals(HistoryWorkState.WAITING,HistoryRules.workState(listOf("BLOCKED")))
        assertEquals(HistoryWorkState.RUNNING,HistoryRules.workState(listOf("RUNNING","ENQUEUED")))
    }
    @Test fun anotherGenerationsWorkerCannotMakeThisJobLookRunning() {
        assertFalse(HistoryRules.acceptsGeneration(setOf("history-generation:old"),"new"))
        assertTrue(HistoryRules.acceptsGeneration(setOf("history-generation:new"),"new"))
        assertTrue(HistoryRules.acceptsGeneration(setOf("legacy worker"),"new"))
    }
    @Test fun aStoppedOrStaleActiveJobOffersContinuationButAFreshOrPausedJobDoesNot() {
        val job=HistoryDownload("job",listOf("a"),"running",activityAt=10_000)
        assertTrue(HistoryRules.mayRestart(job,HistoryWorkState.STOPPED,10_001))
        assertFalse(HistoryRules.mayRestart(job,HistoryWorkState.RUNNING,99_999))
        assertTrue(HistoryRules.mayRestart(job,HistoryWorkState.RUNNING,100_000))
        assertFalse(HistoryRules.mayRestart(job.copy(status="paused"),HistoryWorkState.STOPPED,200_000))
    }
    @Test fun aContinuouslyDribblingBodyStillHasAnOverallReadBudget() {
        var clock=100L
        val budget=MailReadBudget(limitMs=30,clock={clock})
        clock=129;budget.check()
        clock=130
        try{budget.check();fail("The total budget must expire even if every socket read succeeds")}catch(_: HistoryReadTimeoutException){/* expected */}
    }
    @Test fun readBudgetPreservesCancellationInsteadOfReplacingItWithATimeout() {
        val budget=MailReadBudget(limitMs=0,clock={0},checkCancelled={throw CancellationException("stopped")})
        try{budget.check();fail("Cancellation must propagate")}catch(e: CancellationException){assertEquals("stopped",e.message)}
    }
    @Test fun mimeReaderChecksCancellationInsideTheTextReadLoop() {
        val part=MimeBodyPart().apply{setText("a".repeat(100_000),"UTF-8")}
        var checks=0
        val reader=MimeReader(checkCancelled={if(++checks>4)throw CancellationException("stopped")}){it}
        try{reader.read(part);fail("The body loop must cooperate with cancellation")}catch(_: CancellationException){assertTrue(checks>4)}
    }
}
