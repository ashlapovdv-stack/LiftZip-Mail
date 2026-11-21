package ru.liftzip.mail

import jakarta.mail.Session
import jakarta.mail.internet.MimeBodyPart
import jakarta.mail.internet.MimeMessage
import jakarta.mail.internet.MimeMultipart
import java.io.ByteArrayInputStream
import java.util.Properties
import org.junit.Assert.*
import org.junit.Test

class NotificationPreviewTest {
    private val session=Session.getInstance(Properties())
    private fun mail(uid: Long,unread: Boolean=true)=MailItem("a:1:$uid","a",uid,1,
        sender="Sender",senderEmail="sender@example.com",recipients="me@example.com",
        subject="Subject",body="",date=uid,unread=unread,contentCached=false)

    @Test fun theFirstNotificationCanShowAnExcerptWhileTheFullReaderStillNeedsTheBody() {
        val text="Reply is ready\n\n> Older reply"
        val mime=MimeMessage(session).apply{setText(text,"UTF-8");saveChanges()}
        val incoming=mail(11).copy(notificationPreview=NotificationPreview.read(mime))
        val visible=NotificationRules.plan(emptySet(),setOf(incoming.id),listOf(incoming),emptySet()).messages.single()
        assertEquals("Reply is ready",NotificationRules.body(visible))
        assertFalse(MailContent.canDisplay(visible))
        assertEquals("Full reply arrived",NotificationRules.body(visible.copy(body="Full reply arrived",contentCached=true)))
    }

    @Test fun readsThePlainAlternativeWithoutOpeningAnAttachmentOrHtmlWhenPlainIsPresent() {
        val neverRead=object: MimeBodyPart(){
            override fun getInputStream(): java.io.InputStream=throw AssertionError("Unneeded part was read")
        }.apply{setContent("<p>HTML alternative</p>","text/html; charset=utf-8")}
        val attachment=object: MimeBodyPart(){
            override fun getInputStream(): java.io.InputStream=throw AssertionError("Attachment was read")
        }.apply{setText("Private attachment");fileName="report.txt"}
        val related=MimeMultipart("alternative").apply{
            addBodyPart(neverRead)
            addBodyPart(MimeBodyPart().apply{setText("Текст из письма","UTF-8")})
        }
        val mime=MimeMessage(session).apply {
            setContent(MimeMultipart("mixed").apply{
                addBodyPart(attachment)
                addBodyPart(MimeBodyPart().apply{setContent(related)})
            })
            saveChanges()
        }
        assertEquals("Текст из письма",NotificationPreview.read(mime))
    }

    @Test fun htmlPreviewShowsVisibleTextAndDoesNotExposeMarkup() {
        val mime=MimeMessage(session).apply {
            setContent("<style>p{color:red}</style><p>Заказ готов</p><img src='cid:photo@example.com'>","text/html; charset=UTF-8")
            saveChanges()
        }
        assertEquals("Заказ готов",NotificationPreview.read(mime))
    }

    @Test fun aLargeMimePartReadsOnlyTheBoundedPrefix() {
        val data=object: ByteArrayInputStream(ByteArray(20_000){'x'.code.toByte()}) {
            fun consumed()=pos
        }
        val part=object: MimeBodyPart(){override fun getInputStream(): java.io.InputStream=data}.apply{setText("body")}
        val message=MimeMessage(session).apply{
            setContent(MimeMultipart("mixed").apply{addBodyPart(part)})
            saveChanges()
        }
        assertEquals(MailContent.PREVIEW_LENGTH,NotificationPreview.read(message).length)
        assertEquals(NotificationPreview.READ_BYTES,data.consumed())
    }

    @Test fun prioritizesOnlyNewUnreadMissingTextsAndKeepsTheWorkBounded() {
        val existing=mail(11).copy(contentCached=true)
        val arrivals=(13L..17L).map{mail(it)}
        val page=InboxPage(listOf(mail(10),existing,mail(12,false))+arrivals,InboxHistory(),1)
        val cursor=ArrivalCursor(1,10,true)
        assertEquals(listOf(17L,16L,15L),NotificationPreview.candidates(page,cursor).map{it.uid})
        assertTrue(NotificationPreview.candidates(page,ArrivalCursor()).isEmpty())
        assertTrue(NotificationPreview.candidates(page,cursor.copy(validity=2)).isEmpty())
    }

    @Test fun aSubsequentHeaderOnlyRefreshPreservesTheExcerpt() {
        val incoming=mail(11)
        val previous=incoming.copy(notificationPreview="Fetched with the headers")
        val page=InboxPage(listOf(incoming),InboxHistory(),1)
        assertEquals(previous,SyncRules.checkedPage(listOf(previous),listOf(previous),page,emptySet()).single())
    }
}
