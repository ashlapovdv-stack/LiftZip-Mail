package ru.liftzip.mail

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class MailboxCoordinatorTest {
    @Test fun cachedDataCanBeReadAndChangedWhileHistoryWaitsForTheNetwork()=runBlocking {
        withTimeout(5000) {
            val coordinator=MailboxCoordinator()
            val release=CompletableDeferred<Unit>()
            var cached="old"
            val history=async(start=CoroutineStart.UNDISPATCHED){coordinator.request(prepare={cached},fetch={release.await();"downloaded"},commit={_,value->cached=value})}
            coordinator.data{cached="user can continue working"}
            assertEquals("user can continue working",coordinator.data{cached})
            assertFalse(history.isCompleted)
            release.complete(Unit);history.await()
            assertEquals("downloaded",cached)
        }
    }
    @Test fun aNewMailCheckFinishesWhileAHistoryRequestIsStillRunning()=runBlocking {
        withTimeout(5000) {
            val c=MailboxCoordinator();val release=CompletableDeferred<Unit>()
            val history=async(start=CoroutineStart.UNDISPATCHED){c.request(prepare={1},fetch={release.await();2},commit={_,value->value})}
            val check=c.poll("a"){c.request(prepare={3},fetch={4},commit={_,value->value})}
            assertEquals(4,check);assertFalse(history.isCompleted)
            release.complete(Unit);assertEquals(2,history.await())
        }
    }
    @Test fun differentMailboxesDoNotWaitForEachOther()=runBlocking {
        withTimeout(5000) {
            val c=MailboxCoordinator();val release=CompletableDeferred<Unit>()
            val slow=async(start=CoroutineStart.UNDISPATCHED){c.poll("slow"){release.await()}}
            assertEquals("fresh mail",c.poll("fast"){"fresh mail"})
            assertFalse(slow.isCompleted);release.complete(Unit);slow.await()
        }
    }
    @Test fun duplicateChecksOfTheSameMailboxAreSerialized()=runBlocking {
        withTimeout(5000) {
            val c=MailboxCoordinator();val release=CompletableDeferred<Unit>();val entered=CompletableDeferred<Unit>()
            val first=async(start=CoroutineStart.UNDISPATCHED){c.poll("a"){release.await()}}
            val second=async(start=CoroutineStart.UNDISPATCHED){c.poll("a"){entered.complete(Unit)}}
            assertFalse(entered.isCompleted)
            release.complete(Unit);first.await();second.await();assertTrue(entered.isCompleted)
        }
    }
    @Test fun disablingAndReenablingAnAccountInvalidatesItsEarlierRequest()=runBlocking {
        withTimeout(5000) {
            val c=MailboxCoordinator();val release=CompletableDeferred<Unit>();var committed=false
            val old=async(start=CoroutineStart.UNDISPATCHED){c.request(prepare={c.version("a")},fetch={release.await()},commit={version,_->if(version==c.version("a"))committed=true})}
            c.data{c.invalidate("a");c.invalidate("a")}
            release.complete(Unit);old.await();assertFalse(committed)
        }
    }
    @Test fun anInactiveAccountCanSkipNetworkWorkEntirely()=runBlocking {
        var contacted=false
        val result=MailboxCoordinator().request(prepare={null as String?},fetch={contacted=true;it},commit={_,value->value})
        assertNull(result);assertFalse(contacted)
    }
}
