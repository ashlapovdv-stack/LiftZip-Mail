package ru.liftzip.mail

import jakarta.mail.AuthenticationFailedException
import jakarta.mail.MessagingException
import org.junit.Assert.*
import org.junit.Test
import java.net.UnknownHostException
import javax.net.ssl.SSLHandshakeException

class MailErrorsTest {
    @Test fun wrappedDnsFailureIdentifiesTheStageAndSuggestsCheckingVpn() {
        val error=MailConnectionException("Входящая почта IMAP","imap.example.com",993,MessagingException("connect",UnknownHostException()))
        val text=friendlyError(error)
        assertTrue(text.contains("IMAP"));assertTrue(text.contains("imap.example.com:993"));assertTrue(text.contains("VPN"))
    }
    @Test fun nestedTlsFailureDoesNotSuggestChangingThePassword() {
        val text=friendlyError(MessagingException("outer",MessagingException("inner",SSLHandshakeException("certificate"))))
        assertTrue(text.contains("защищённое"));assertFalse(text.contains("пароль"))
    }
    @Test fun authenticationDoesNotExposeTheRawServerResponse() {
        val text=friendlyError(AuthenticationFailedException("private diagnostic payload"))
        assertTrue(text.contains("пароль"));assertFalse(text.contains("private diagnostic payload"))
    }
    @Test fun uncertainDeliveryPreservesTheDoNotRetryBlindlyAdvice() { assertTrue(friendlyError(DeliveryUncertainException()).contains("перед повторной отправкой")) }
}
