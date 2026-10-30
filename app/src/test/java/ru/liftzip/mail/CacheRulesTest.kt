package ru.liftzip.mail

import org.junit.Assert.*
import org.junit.Test

class CacheRulesTest {
    private fun mail(id: String,bytes: Long=100,unread: Boolean=false)=MailItem(id,"a",1,1,sender="S",senderEmail="s@example.com",recipients="r@example.com",subject="S",body="body",date=1,unread=unread,cacheBytes=bytes,cachedAt=1)
    @Test fun quotaNeverEvictsDraftsOrQueuedActions() {
        val source=listOf(mail("read"),mail("protected"),mail("draft").copy(uid=0,localFolder="drafts"),mail("outbox").copy(uid=0,localFolder="outbox"))
        assertEquals(setOf("read"),CacheRules.evict(source,setOf("protected"),0,clear=true))
    }
    @Test fun quotaRemovesOldReadBodiesBeforeUnreadBodies() {
        val read=mail("read").copy(cachedAt=9)
        val unread=mail("unread",unread=true)
        assertEquals(setOf("read"),CacheRules.evict(listOf(unread,read),emptySet(),100))
    }
    @Test fun warmupRespectsKnownLargeBodiesAndDoesNotRefillThemForever() {
        val large=mail("large",bytes=200).copy(contentCached=false)
        val small=mail("small",bytes=50).copy(contentCached=false)
        assertEquals(listOf(small),CacheRules.warmSources(listOf(large,small),emptySet(),100))
    }
    @Test fun notificationPriorityWinsTheLimitedWarmupWindow() {
        val older=mail("notification").copy(date=1)
        val recent=mail("recent").copy(date=2)
        assertEquals(listOf(older),CacheRules.warmSources(listOf(recent,older),setOf(older.id),100))
    }
    @Test fun unlimitedBudgetDoesNotEvictOrFilter() {
        val source=listOf(mail("a"),mail("b"))
        assertEquals(source,CacheRules.warmSources(source,emptySet(),CacheRules.bodyBudget(0)))
        assertTrue(CacheRules.evict(source,emptySet(),CacheRules.bodyBudget(0)).isEmpty())
        assertEquals(250,CacheRules.normalized(-1))
    }
}
