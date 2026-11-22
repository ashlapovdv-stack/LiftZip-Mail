package ru.liftzip.mail

import jakarta.mail.Session
import jakarta.mail.internet.MimeMessage
import java.util.Properties
import org.jsoup.Jsoup
import org.junit.Assert.*
import org.junit.Test

/** Sanitized real MIME structure; transport headers and identities are synthetic. */
class LinkedMimeHistoryTest {
    private fun decoded()=javaClass.getResourceAsStream("/mailru-linked-history.eml")!!.use{
        MimeReader().read(MimeMessage(Session.getInstance(Properties()),it))
    }
    @Test fun realAlternativeStructureCollapsesTheWholeHeaderAndPreservesRichExpansion() {
        val mime=decoded()
        val conversation=ConversationText.parse(mime.text)
        assertEquals("Текущий ответ",conversation.current.trim())
        assertTrue(conversation.messages.isNotEmpty())
        val analysis=HtmlMail.analyzeHistory(mime.html,conversation)
        assertTrue(analysis.reason.toString(),analysis.separateHistory)
        val current=Jsoup.parse(analysis.currentHtml).text()
        assertEquals("Текущий ответ",current.trim())
        val expanded=HtmlMail.document(mime.html,conversation.current,emptyList(),HtmlMail.noHistory(mime.html)).html
        val full=Jsoup.parse(expanded)
        assertTrue(full.text().contains("Первое старое письмо"))
        assertTrue(full.text().contains("Самое старое письмо"))
        assertEquals("mailto:client@example.com",full.selectFirst("a")!!.attr("href"))
        assertTrue(full.selectFirst("blockquote")!!.attr("style").contains("border-left"))
        assertEquals(analysis,HtmlMail.analyzeHistory(mime.html,ConversationText.parse(mime.text)))
    }
    @Test fun mismatchingSenderOrTrailingReplyKeepsTheEntireSource() {
        val mime=decoded();val conversation=ConversationText.parse(mime.text)
        for(html in listOf(mime.html.replace("client@example.com","other@example.com"),mime.html.replace("</blockquote>","</blockquote><p>Ответ после цитаты</p>"))) {
            val analysis=HtmlMail.analyzeHistory(html,conversation)
            assertFalse(analysis.separateHistory);assertEquals(html,analysis.currentHtml)
        }
    }
    private val photoReplyText="Current reply\n\nсуббота, 21 ноября 2026г., 22:37 +09:00 от Дмитрий sender@example.com:\n\n> \n>"
    private val photoReplyHtml="""
        <p>Current reply</p>суббота, 21 ноября 2026г., 22:37 +09:00 от Дмитрий
        <a href="mailto:sender@example.com">sender@example.com</a>:<br><br>
        <blockquote id="mail-app-auto-quote" style="border-left:1px solid blue">
          <br><img src="cid:photo%40example.com" alt="Quoted photo"><br>
        </blockquote>
    """.trimIndent()
    private val quotePhoto=Attachment("1.1","photo.jpg","image/jpeg",128,"photo@example.com")

    @Test fun imageOnlyReplyCollapsesAndUsesTheSamePhotoInTheExpandedDocument() {
        val quote=ConversationText.parse(photoReplyText)
        assertEquals("",quote.messages.single().body)
        val analysis=ReaderAnalysisCache().prepare(
            ReaderAnalysisCache.Key("photo-reply",photoReplyText,photoReplyHtml,1,attachments=listOf(quotePhoto))
        )
        assertEquals(1,analysis.first.messages.size)
        val history=analysis.second
        assertEquals(HtmlMail.HistoryReason.SEPARATED,history.reason)
        assertEquals("Current reply",Jsoup.parse(history.currentHtml).text())
        assertTrue(Jsoup.parse(history.quotedHtml).selectFirst("blockquote")!!.attr("style").contains("border-left"))
        for(expanded in listOf(false,true)) {
            val html=UnifiedReaderDocument.assemble(history.currentHtml,history.quotedHtml,expanded)
            val doc=HtmlMail.document(html,quote.current,listOf(quotePhoto))
            val page=Jsoup.parse(doc.html)
            assertEquals(expanded.toString(),page.getElementById("mail-history-toggle")!!.attr("aria-expanded"))
            assertEquals(!expanded,page.getElementById(UnifiedReaderDocument.HISTORY_ID)!!.attr("style").contains("display:none"))
            assertEquals(listOf(MailImageSource.Inline(quotePhoto)),doc.images.values.toList())
        }
    }

    @Test fun imageOnlyQuoteRequiresAMatchedAttributionAndAnAvailableInlineImage() {
        for(html in listOf(photoReplyHtml.replace("sender@example.com","other@example.com"),
            photoReplyHtml.replace("photo%40example.com","missing@example.com"),photoReplyHtml+"<p>New reply text</p>")) {
            assertFalse(HtmlMail.analyzeHistory(html,ConversationText.parse(photoReplyText),listOf(quotePhoto)).separateHistory)
        }
    }

    @Test fun readerListsOnlyFilesWithoutAnInlineImagePlacementAndKeepsExplicitAttachments() {
        val unused=quotePhoto.copy(path="1.2",contentId="unused@example.com")
        val pdf=Attachment("2","manual.pdf","application/pdf",128)
        val explicit=quotePhoto.copy(path="3",explicitAttachment=true)
        val files=listOf(quotePhoto,unused,pdf,explicit)
        val history=ReaderAnalysisCache().prepare(
            ReaderAnalysisCache.Key("photo-reply",photoReplyText,photoReplyHtml,1,attachments=files)
        ).second
        assertEquals(listOf(unused,pdf,explicit),history.visibleAttachments(files))
        assertEquals(files,HtmlMail.noHistory("").visibleAttachments(files))
    }

    @Test fun htmlDerivedPlainStillRecognizesLinkTargets() {
        val html="<p>Новый ответ</p><div>On 7 Nov 2026, client@example.com wrote:</div>"+
            "<blockquote>Старое письмо <a href='https://example.com/archive'>архив</a></blockquote>"
        val conversation=ConversationText.parse(HtmlMail.text(html))
        assertTrue(HtmlMail.analyzeHistory(html,conversation).separateHistory)
    }
}
