package ru.liftzip.mail

import org.junit.Assert.*
import org.junit.Test

class InboxPagingTest {
    @Test fun firstPageIsTheNewestHundredRatherThanTheOldest() { assertEquals(401..500,InboxPaging.latest(500)) }
    @Test fun smallAndEmptyMailboxesHaveNoInvalidSequenceNumbers() {
        assertEquals(1..25,InboxPaging.latest(25))
        assertNull(InboxPaging.latest(0))
    }
    @Test fun nextPageExcludesTheAlreadyLoadedBoundary() { assertEquals(301..400,InboxPaging.older(401)) }
    @Test fun lastPageIsShortAndStopsAtOne() {
        assertEquals(1..48,InboxPaging.older(49))
        assertNull(InboxPaging.older(1))
    }
    @Test fun repeatedlyLoadingAnUnchangingMailboxHasNoGapsOrDuplicates() {
        var page=InboxPaging.latest(253)!!
        val loaded=page.toMutableList()
        while(true) { page=InboxPaging.older(page.first)?:break;loaded+=page.toList() }
        assertEquals(253,loaded.size)
        assertEquals((1..253).toSet(),loaded.toSet())
    }
}
