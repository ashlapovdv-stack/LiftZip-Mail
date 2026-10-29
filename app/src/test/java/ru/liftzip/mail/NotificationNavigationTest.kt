package ru.liftzip.mail

import org.junit.Assert.*
import org.junit.Test

class NotificationNavigationTest {
    @Test fun aChildRetainsItsExactAccountValidityAndUid() {
        val id="account-a:721:459"
        assertEquals(id,NotificationNavigation.destination(NotificationNavigation.uri(id))?.messageId)
    }
    @Test fun equalUidsInDifferentAccountsHaveDifferentPendingIntentIdentities() {
        assertNotEquals(NotificationNavigation.uri("one:8:40"),NotificationNavigation.uri("two:8:40"))
    }
    @Test fun specialCharactersAndUnicodeRoundTripWithoutTruncatingTheId() {
        val id="ящик+one/space here:22:33?x=#%"
        assertEquals(id,NotificationNavigation.destination(NotificationNavigation.uri(id))?.messageId)
    }
    @Test fun identityInTheUriWinsOverStaleExtras() {
        assertEquals("a:4:8",NotificationNavigation.destination(NotificationNavigation.uri("a:4:8"),"b:4:8",true)?.messageId)
    }
    @Test fun groupAndIndividualDestinationsCannotCollide() {
        assertEquals(NotificationDestination(),NotificationNavigation.destination(NotificationNavigation.uri()))
        assertEquals("unread",NotificationNavigation.destination(NotificationNavigation.uri("unread"))?.messageId)
        assertNotEquals(NotificationNavigation.uri(),NotificationNavigation.uri("unread"))
    }
    @Test fun alreadyPostedLegacyNotificationsStillOpenTheirMessage() {
        assertEquals("one:90:459",NotificationNavigation.destination("liftzip-mail://open/one%3A90%3A459")?.messageId)
        assertEquals(NotificationDestination(),NotificationNavigation.destination("liftzip-mail://open/inbox"))
    }
    @Test fun legacyExtrasWithoutDataStillWork() {
        assertEquals("id",NotificationNavigation.destination(null,"id",true)?.messageId)
        assertEquals(NotificationDestination(),NotificationNavigation.destination(null,null,true))
        assertNull(NotificationNavigation.destination(null))
    }
    @Test fun malformedAndForeignLinksDoNotOpenAnotherMessageViaExtras() {
        listOf("https://example.com/id","liftzip-mail://other/id","liftzip-mail://open/message/","liftzip-mail://open/message/%ZZ","liftzip-mail://open/message/id?other=1").forEach {
            assertNull(it,NotificationNavigation.destination(it,"unrelated",true))
        }
    }
    @Test fun completingAnOldTapCannotConsumeANewerTapWhileTheDatabaseLoads() {
        val a=NotificationOpenRequest(NotificationDestination("A"))
        val b=NotificationOpenRequest(NotificationDestination("B"))
        assertSame(b,NotificationNavigation.consume(b,a.token))
        assertNull(NotificationNavigation.consume(b,b.token))
    }
    @Test fun tappingTheSameMessageAgainIsANewEvent() {
        val a=NotificationOpenRequest(NotificationDestination("same"))
        val b=NotificationOpenRequest(NotificationDestination("same"))
        assertNotEquals(a.token,b.token)
        assertSame(b,NotificationNavigation.consume(b,a.token))
    }
}
