package ru.liftzip.mail

import org.junit.Assert.*
import org.junit.Test

class FastMailRulesTest {
    @Test fun serviceStartsOnlyAfterOptInAndWhileVisibleWithAnActiveAccount() {
        assertTrue(FastMailRules.mayStart(true,true,true,false))
        assertFalse(FastMailRules.mayStart(false,true,true,false))
        assertFalse(FastMailRules.mayStart(true,false,true,false))
        assertFalse(FastMailRules.mayStart(true,true,false,false))
        assertFalse(FastMailRules.mayStart(true,true,true,true))
    }
    @Test fun failedConnectionsBackOffWithoutAnUnboundedSpin() {
        assertEquals(1000L,FastMailRules.backoff(0))
        assertEquals(2000L,FastMailRules.backoff(1))
        assertEquals(60_000L,FastMailRules.backoff(100))
    }
    @Test fun changedCredentialsOrServerReplaceTheWatcher() {
        val a=MailAccount(id="a",name="A",email="a@example.com",provider=Provider.CUSTOM,imapHost="imap.example.com",smtpHost="smtp.example.com",secret="one")
        assertNotEquals(FastMailRules.fingerprint(a),FastMailRules.fingerprint(a.copy(secret="two")))
        assertNotEquals(FastMailRules.fingerprint(a),FastMailRules.fingerprint(a.copy(imapHost="other.example.com")))
        assertEquals(FastMailRules.fingerprint(a),FastMailRules.fingerprint(a.copy(name="renamed")))
    }
}
