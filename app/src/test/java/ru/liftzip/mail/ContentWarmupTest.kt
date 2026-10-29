package ru.liftzip.mail

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

class ContentWarmupTest {
    private fun account(id: String="a",enabled: Boolean=true)=MailAccount(id,"Box $id","$id@example.com",provider=Provider.CUSTOM,imapHost="imap.example.com",smtpHost="smtp.example.com",enabled=enabled)
    private fun mail(uid: Long=1,account: String="a",unread: Boolean=true,cached: Boolean=false,folder: String="inbox")=
        MailItem("$account:1:$uid",account,uid,1,sender="Sender",senderEmail="sender@example.com",recipients="",subject="Subject",body=if(cached)"full body" else "",date=uid,unread=unread,contentCached=cached,localFolder=folder)
    @Test fun missingUnreadBodyIsQueuedWithoutAnyNotificationOrPermission() {
        assertEquals(listOf(mail()),ContentCacheRules.pending(listOf(mail()),listOf(account())))
    }
    @Test fun dismissingAnAlertDoesNotRemoveUnreadBodyFromTheQueue() {
        val m=mail()
        assertEquals(listOf(m),ContentCacheRules.pending(listOf(m),listOf(account()),emptySet()))
    }
    @Test fun oldUnreadBeyondRecentHundredIsStillQueued() {
        val messages=(1L..250).map{mail(it,unread=it==1L)}
        val pending=ContentCacheRules.pending(messages,listOf(account()))
        assertEquals(101,pending.size)
        assertEquals(1L,pending.first().uid)
    }
    @Test fun recentReadBodiesAreWarmedForFastOpeningButOldReadHistoryIsNotDownloadedAgain() {
        val pending=ContentCacheRules.pending((1L..250).map{mail(it,unread=false)},listOf(account()))
        assertEquals(100,pending.size)
        assertEquals((151L..250).toSet(),pending.map{it.uid}.toSet())
    }
    @Test fun notificationBodiesPrecedeOtherUnreadAndReadMail() {
        val priority=mail(1,unread=false);val unread=mail(2);val read=mail(3,unread=false)
        assertEquals(listOf(priority,unread,read),ContentCacheRules.pending(listOf(read,unread,priority),listOf(account()),setOf(priority.id)))
    }
    @Test fun pausedAccountsAndLocalFoldersNeverEnterTheNetworkQueue() {
        val messages=listOf(mail(),mail(2,folder="unread-cache"),mail(3,folder="sent"),mail(4,folder="drafts"),mail(5,"b"))
        val pending=ContentCacheRules.pending(messages,listOf(account(),account("b",false)))
        assertEquals(setOf(1L,2L),pending.map{it.uid}.toSet())
    }
    @Test fun cachedMailIsNeverReadAgainAndEachAccountHasItsOwnRecentWindow() {
        val messages=(1L..120).flatMap{listOf(mail(it,unread=false),mail(it,"b",unread=false))}+mail(200,cached=true)
        val pending=ContentCacheRules.pending(messages,listOf(account(),account("b")))
        assertTrue(pending.none{it.contentCached})
        assertEquals(100,pending.count{it.accountId=="b"})
        assertEquals(99,pending.count{it.accountId=="a"}) // cached latest mail occupies a recent slot
    }
    @Test fun atMostTwoAccountsAndThreeBodiesPerConnectionAreSelected() {
        val messages=(1L..10).flatMap{uid->listOf(mail(uid),mail(uid,"b"),mail(uid,"c"))}
        val pending=ContentCacheRules.pending(messages,listOf(account(),account("b"),account("c")))
        val batches=ContentCacheRules.batches(pending,emptySet(),emptyMap())
        assertEquals(2,batches.size)
        assertTrue(batches.all{it.size==3 && it.map{m->m.accountId}.distinct().size==1})
    }
    @Test fun unreadAccountsRotateAndFreshNotificationsRetainPriority() {
        val a=mail(10);val b=mail(9,"b");val c=mail(8,"c")
        val served=mapOf("a" to 2,"b" to 1)
        assertEquals(listOf("c","b"),ContentCacheRules.batches(listOf(a,b,c),emptySet(),served).map{it.first().accountId})
        assertEquals("a",ContentCacheRules.batches(listOf(a,b,c),setOf(a.id),served).first().first().accountId)
    }
    @Test fun oneFailedBodyDoesNotBlockOtherMessagesOrMailboxes() {
        val messages=listOf(mail(),mail(2),mail(3,"b"))
        val failure=ContentCacheRules.failed(null,1000,"timeout")
        val ready=ContentCacheRules.eligible(messages,mapOf(messages.first().id to failure),1001)
        assertEquals(listOf(messages[1],messages[2]),ready)
        assertEquals(messages,ContentCacheRules.eligible(messages,mapOf(messages.first().id to failure),failure.retryAt))
    }
    @Test fun failuresHaveShortBoundedBackoffRatherThanHalfAnHour() {
        var f=ContentCacheRules.failed(null,1000,"error")
        assertEquals(16_000L,f.retryAt)
        repeat(40){f=ContentCacheRules.failed(f,1000,"error")}
        assertEquals(301_000L,f.retryAt)
        assertEquals(20,f.attempts)
    }
    @Test fun aLargeCachedHistoryKeepsOnlyTheNewestHundredReadTextsQueued() {
        val messages=(1L..10_000L).map{mail(it,unread=false,cached=it<=9_900)}
        val pending=ContentCacheRules.pending(messages.shuffled(kotlin.random.Random(1)),listOf(account()))
        assertEquals((9_901L..10_000L).toList().reversed(),pending.map{it.uid})
    }
    @Test fun aRequestArrivingAsTheCurrentScanFinishesIsNotLost()=runBlocking {
        val signals=BodyWarmupSignals();val next=CompletableDeferred<Unit>();var calls=0
        val job=launch{signals.collect({true},{
            calls++;if(calls==1)signals.request()else next.complete(Unit)
            BodyWarmupResult(0,0,false)
        })}
        signals.request();withTimeout(3000){next.await()};assertEquals(2,calls);job.cancelAndJoin()
    }
    @Test fun aBurstDuringLoadingAddsOnlyOneFollowUpScan()=runBlocking {
        val signals=BodyWarmupSignals();val first=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>();val second=CompletableDeferred<Unit>();var calls=0
        val job=launch{signals.collect({true},{
            calls++;if(calls==1){first.complete(Unit);release.await()}else second.complete(Unit)
            BodyWarmupResult(0,0,false)
        })}
        signals.request();first.await();repeat(100){signals.request()};release.complete(Unit)
        withTimeout(3000){second.await()};yield();assertEquals(2,calls);job.cancelAndJoin()
    }
    @Test fun aFailedScanDoesNotKillFutureWarmupRequests()=runBlocking {
        val signals=BodyWarmupSignals();val failed=CompletableDeferred<Unit>();val recovered=CompletableDeferred<Unit>();var calls=0
        val job=launch{signals.collect({true},{
            calls++;if(calls==1)throw IOException("network")
            recovered.complete(Unit);BodyWarmupResult(1,0,false)
        },{failed.complete(Unit)})}
        signals.request();failed.await();signals.request();withTimeout(3000){recovered.await()};assertEquals(2,calls);job.cancelAndJoin()
    }
    @Test fun bodyGateIncludesSavingSoReaderAndWarmupCannotDownloadTheSameMessageTwice()=runBlocking {
        val gate=MailboxCoordinator();var full: MailItem?=null;val reads=AtomicInteger();val entered=CompletableDeferred<Unit>();val allowSave=CompletableDeferred<Unit>()
        val work=async(Dispatchers.Default){HistoryBodies.transfer(listOf(mail()),read={reads.incrementAndGet();entered.complete(Unit);mail(cached=true)},save={
            allowSave.await();full=it.messages.single();true
        },describe={"error"},around={m,action->gate.content(m.id){action()}})}
        entered.await()
        val reader=async(Dispatchers.Default){gate.content(mail().id){if(full==null){reads.incrementAndGet();mail(cached=true)}else full!!}}
        yield();allowSave.complete(Unit)
        work.await();assertTrue(reader.await().contentCached);assertEquals(1,reads.get())
    }
    @Test fun cancellingAQueuedReaderDoesNotCancelTheBackgroundBody()=runBlocking {
        val gate=MailboxCoordinator();val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>();var completed=false
        val background=launch{gate.content("m"){entered.complete(Unit);release.await();completed=true}}
        entered.await();val reader=launch{gate.content("m"){fail("Cancelled reader must not run")}}
        yield();reader.cancelAndJoin();release.complete(Unit);background.join();assertTrue(completed)
    }
    @Test fun finishedBodiesSurviveFailureAndTheNextBatchCanContinue()=runBlocking {
        val messages=(1L..4).map{mail(it)};val saved=mutableListOf<MailItem>();val failures=mutableSetOf<String>()
        HistoryBodies.transfer(messages,read={if(it.uid==2L)throw IOException();it.copy(contentCached=true,body="saved")},save={saved+=it.messages;failures+=it.failures.keys;true},describe={"timeout"})
        assertEquals(listOf(1L),saved.map{it.uid})
        val pending=ContentCacheRules.pending(messages.map{m->saved.find{it.id==m.id}?:m},listOf(account()))
        val eligible=ContentCacheRules.eligible(pending,failures.associateWith{ContentCacheRules.failed(null,0,"timeout")},1)
        assertEquals(setOf(3L,4L),eligible.map{it.uid}.toSet())
    }
}
