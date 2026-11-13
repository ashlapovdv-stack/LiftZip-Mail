package ru.liftzip.mail

import jakarta.mail.*
import jakarta.mail.internet.MimeMessage
import org.jsoup.Jsoup
import org.junit.Assert.*
import org.junit.Test
import java.util.Properties

class OutgoingMimeTest {
    private val account=MailAccount("a","Sender","sender@example.com",provider=Provider.REG,imapHost="imap.example.com",smtpHost="smtp.example.com")
    private val session=Session.getInstance(Properties())
    private val builder=OutgoingMime{file->("bytes:"+file.name).byteInputStream()}
    private fun source(html: String="<p>Старая история</p>")=MailItem("source","a",uid=42,validity=7,sender="Клиент",senderEmail="from@example.com",recipients=account.email,subject="Тема",body="Старая история",date=1_800_000_000_000L,htmlBody=html,
        headers=MessageHeaders("<parent@example.com>","reply@example.com",references=listOf("<first@example.com>")))
    private fun draft(m: MailItem=source())=ComposeDraft(id="draft",accountId="a",to="reply@example.com",subject="Re: Тема",body="Новый ответ",quote=ComposeContent.quote(m,"reply"),responseKind="reply")
    private fun wire(message: MimeMessage)=java.io.ByteArrayOutputStream().let{out->message.writeTo(out);MimeMessage(session,out.toByteArray().inputStream())}
    @Test fun newMailRoundTripsUnicodeLiteralMarkupAndLineBreaks() {
        val text="Кириллица 🙂 <>&\"\nВторая строка"
        val d=ComposeDraft(accountId="a",to="client@example.com",subject="Тема 🙂",body=text)
        val m=wire(builder.build(session,account,d).message)
        assertTrue(m.isMimeType("multipart/alternative"))
        val decoded=MimeReader().read(m)
        assertEquals(text,decoded.text)
        assertTrue(decoded.html.contains("&lt;&gt;&amp;"));assertTrue(decoded.html.contains("<br>"))
        assertNull(m.getHeader("In-Reply-To"));assertNull(m.getHeader("References"))
    }
    @Test fun replyPreservesRichSourceExactlyOnceAndAddsRealThreadHeaders() {
        val html="<table><tr><td>Таблица</td></tr></table><a href='https://example.com'>Ссылка</a><blockquote>Старая история</blockquote>"
        val source=source(html)
        val d=draft(source)
        val result=wire(builder.build(session,account,d).message)
        assertEquals("<parent@example.com>",result.getHeader("In-Reply-To",null))
        assertEquals("<first@example.com> <parent@example.com>",result.getHeader("References",null))
        val decoded=MimeReader().read(result)
        assertEquals(1,Jsoup.parse(decoded.html).select("table").size)
        assertEquals(2,Jsoup.parse(decoded.html).select("blockquote").size)
        assertTrue(decoded.text.contains("> Старая история"))
        assertEquals(html,source.htmlBody)
        assertEquals(ComposeContent.render(d),ComposeContent.render(d.copy(body="Новый ответ")))
    }
    @Test fun draftMetadataRestoresEditorAndQuoteWithoutStackingQuotation() {
        val d=draft().copy(cc="copy@example.com",bcc="private@example.com")
        var current=d
        repeat(3) {
            val decoded=MimeReader().read(wire(builder.build(session,account,current,MimePurpose.DRAFT,4).message))
            val editor=decoded.composer!!
            current=current.copy(body=editor.text,quote=editor.quote,quoteIncluded=editor.quoteIncluded,cc=editor.cc,bcc=editor.bcc)
            assertEquals(d.body,current.body);assertEquals(d.quote,current.quote)
            assertEquals(1,Jsoup.parse(ComposeContent.render(current).html).select("blockquote").size)
        }
        assertEquals("private@example.com",current.bcc)
    }
    @Test fun cidResourcesUseRelatedAndOrdinaryAttachmentsStayOutsideAlternatives() {
        val d=draft(source("<table><tr><td><img src='cid:picture'></td></tr></table>")).copy(attachments=listOf(
            OutgoingFile("private://one","picture.png","image/png",10,"picture"),OutgoingFile("private://two","document.pdf","application/pdf",10)))
        val message=wire(builder.build(session,account,d).message)
        assertTrue(message.isMimeType("multipart/mixed"))
        val mixed=message.content as Multipart
        val alternative=mixed.getBodyPart(0).content as Multipart
        assertTrue(alternative.getBodyPart(1).isMimeType("multipart/related"))
        val related=alternative.getBodyPart(1).content as Multipart
        assertEquals(2,related.count)
        val cid=HtmlMail.cid(related.getBodyPart(1).getHeader("Content-ID")[0])
        val decoded=MimeReader().read(message)
        assertTrue(decoded.html.contains("cid:$cid"));assertEquals(2,decoded.attachments.size)
        assertFalse(decoded.html.contains("private://"));assertFalse(decoded.html.contains("mail.liftzip.invalid"))
    }
    @Test fun serverDraftCidAliasesRestoreOriginalQuoteAndResolvableResources() {
        val d=draft(source("<p><img src='cid:picture'></p>")).copy(attachments=listOf(OutgoingFile("owned","picture.png","image/png",10,"picture")))
        val decoded=MimeReader().read(wire(builder.build(session,account,d,MimePurpose.DRAFT,1).message))
        val editor=decoded.composer!!
        assertEquals(d.quote,editor.quote)
        val restoredFiles=decoded.attachments.map{a->OutgoingFile("owned",a.name,a.mime,a.size.toLong(),editor.resourceAliases[a.contentId]?:a.contentId)}
        val restored=d.copy(body=editor.text,quote=editor.quote,attachments=restoredFiles)
        assertEquals(1,MimeReader().read(wire(builder.build(session,account,restored).message)).attachments.size)
    }
    @Test fun excludingQuoteRemovesBothAlternativesAndDoesNotRequireItsMissingCid() {
        val d=draft(source("<img src='cid:missing'>")).copy(quoteIncluded=false)
        val m=builder.build(session,account,d).message
        val decoded=MimeReader().read(wire(m))
        assertEquals("Новый ответ",decoded.text);assertFalse(decoded.html.contains("blockquote"))
        assertEquals("<parent@example.com>",m.getHeader("In-Reply-To",null))
    }
    @Test(expected=IllegalArgumentException::class) fun includedQuoteCannotSilentlyLoseAnInlineImage() {
        builder.build(session,account,draft(source("<img src='cid:missing'>")))
    }
    @Test fun bccIsAnEnvelopeRecipientAndNeverASmtpHeader() {
        val d=draft().copy(cc="copy@example.com",bcc="hidden@example.com")
        val built=builder.build(session,account,d)
        assertEquals(3,built.envelope.size);assertNull(built.message.getHeader("Bcc"))
        assertTrue(built.envelope.any{it.toString()=="hidden@example.com"})
        assertEquals("hidden@example.com",builder.build(session,account,d,MimePurpose.SENT).message.getHeader("Bcc",null))
    }
    @Test fun forwardingAndLegacyDraftsDoNotInheritReplyHeadersOrDuplicateHistory() {
        val forward=draft().copy(quote=ComposeContent.quote(source(),"forward"))
        assertNull(builder.build(session,account,forward).message.getHeader("In-Reply-To"))
        val legacy=forward.copy(body="Новый текст\n> Старая цитата",quote=null,responseKind="reply")
        assertEquals(legacy.body,MimeReader().read(wire(builder.build(session,account,legacy).message)).text)
    }
    @Test fun retriesAndSentCopyKeepTheSameMessageIdAndContent() {
        val d=draft().copy(timestamp=1_800_000_000_000L)
        val first=wire(builder.build(session,account,d).message)
        val retry=wire(builder.build(session,account,d).message)
        val sent=wire(builder.build(session,account,d,MimePurpose.SENT).message)
        assertEquals(first.messageID,retry.messageID);assertEquals(first.messageID,sent.messageID)
        assertEquals(MimeReader().read(first),MimeReader().read(sent))
    }
    @Test fun threeRepliesIncreaseTrueNestingOneLevelAtATime() {
        var m=source()
        repeat(3){index->
            val d=draft(m).copy(id="reply-$index",body="Ответ $index")
            val outgoing=wire(builder.build(session,account,d).message)
            val decoded=MimeReader().read(outgoing)
            assertEquals(index+1,Jsoup.parse(decoded.html).select("blockquote").size)
            assertTrue(HtmlMail.analyzeHistory(decoded.html,ConversationText.parse(decoded.text)).separateHistory)
            assertEquals(m.headers!!.messageId,outgoing.getHeader("In-Reply-To",null))
            m=m.copy(id="received-$index",body=decoded.text,htmlBody=decoded.html,headers=ReplyHeaders.read(outgoing))
        }
    }
    @Test fun replyToAndThreadIdsAreValidatedAndKnownAbsenceIsDistinctFromUnknown() {
        val m=MimeMessage(session).apply{setHeader("Message-ID","<real@example.com>");setHeader("Reply-To","Клиент <reply@example.com>")}
        val h=ReplyHeaders.read(m)
        assertEquals("reply@example.com",h.replyTo)
        assertEquals("reply@example.com",ReplyHeaders.recipient(source().copy(headers=h)))
        assertEquals("from@example.com",ReplyHeaders.recipient(source().copy(headers=MessageHeaders())))
        assertNull(MessageThread.id("<x@example.com>\r\nBcc: attacker@example.com"))
        assertEquals(listOf("<a@example.com>","<b@example.com>"),MessageThread.chain(MessageHeaders("<b@example.com>",references=listOf("<a@example.com>","<a@example.com>"))))
    }
    @Test fun outgoingQuoteKeepsTablesAndExternalImagesWithoutReaderUrlsOrActiveContent() {
        val html="<style>.x{background:url(content://private)}</style><table class='x'><tr><td>Ячейка</td></tr></table>"+
            "<img src='https://example.com/image.png'><img src='file:///private/photo'><script>alert(1)</script><a href='javascript:alert(1)'>Ссылка</a>"
        val d=draft(source(html))
        var requests=0
        val message=OutgoingMime{requests++;error("No resources should be fetched")}.build(session,account,d).message
        val result=MimeReader().read(wire(message)).html
        assertEquals(0,requests);assertTrue(result.contains("<table"));assertTrue(result.contains("https://example.com/image.png"))
        assertFalse(result.contains("file:"));assertFalse(result.contains("content:"));assertFalse(result.contains("javascript:"));assertFalse(result.contains("<script"))
    }
    @Test fun nestedAlternativeStructureRequiresMatchingTextAndDepth() {
        val header="Клиент пишет:"
        val source=source("<p>Предыдущий ответ</p><div>──────────</div><div>$header</div><blockquote>Самое старое письмо</blockquote>").copy(
            body="Предыдущий ответ\n\n──────────\n$header\n> Самое старое письмо")
        val decoded=MimeReader().read(wire(builder.build(session,account,draft(source)).message))
        val conversation=ConversationText.parse(decoded.text)
        assertTrue(HtmlMail.analyzeHistory(decoded.html,conversation).separateHistory)
        // Identical words at a different quotation depth are not corroboration.
        val wrongDepth=decoded.html.replace("<blockquote>Самое старое письмо</blockquote>","<div>Самое старое письмо</div>")
        assertFalse(HtmlMail.analyzeHistory(wrongDepth,conversation).separateHistory)
        assertFalse(HtmlMail.analyzeHistory(decoded.html.replace("Самое старое письмо","Другой текст"),conversation).separateHistory)
    }
    @Test fun anOrdinaryAttachmentWithThePrivateMetadataMimeTypeIsNeverConsumedAsEditorState() {
        val d=draft()
        val mixed=jakarta.mail.internet.MimeMultipart("mixed")
        mixed.addBodyPart(jakarta.mail.internet.MimeBodyPart().apply{setText("Текст")})
        mixed.addBodyPart(jakarta.mail.internet.MimeBodyPart().apply{
            setContent(ComposeCodec.draftMetadata(d),ComposeCodec.MIME+"; charset=UTF-8")
            fileName="saved.json";disposition=Part.ATTACHMENT
        })
        val message=MimeMessage(session).apply{setHeader("X-LiftZip-Draft-ID",d.id);setContent(mixed);saveChanges()}
        val decoded=MimeReader().read(wire(message))
        assertEquals("saved.json",decoded.attachments.single().name)
        assertNull(decoded.composer)
    }
    @Test fun foreignHtmlDraftKeepsRichBodyInlineResourcesAndExistingThreadWithoutANewQuote() {
        val original=source("<table><tr><td>Старая история<img src='cid:photo'></td></tr></table>").copy(headers=MessageHeaders(
            "<foreign-draft@example.com>",inReplyTo="<parent@example.com>",references=listOf("<first@example.com>","<parent@example.com>")))
        val d=draft(original).copy(body="",quote=ComposeContent.quote(original,"draft"),internetMessageId="<foreign-draft@example.com>",
            attachments=listOf(OutgoingFile("owned","photo.png","image/png",10,"photo")))
        val message=wire(builder.build(session,account,d).message)
        val decoded=MimeReader().read(message)
        assertEquals(original.body,decoded.text)
        assertEquals(1,Jsoup.parse(decoded.html).select("table").size)
        assertEquals(0,Jsoup.parse(decoded.html).select("blockquote").size)
        assertEquals(1,decoded.attachments.size)
        assertEquals("<foreign-draft@example.com>",message.messageID)
        assertEquals("<parent@example.com>",message.getHeader("In-Reply-To",null))
        assertEquals("<first@example.com> <parent@example.com>",message.getHeader("References",null))
        val editor=MimeReader().read(wire(builder.build(session,account,d,MimePurpose.DRAFT,1).message)).composer!!
        assertEquals("draft",editor.quote!!.kind);assertEquals("",editor.text)
    }
    @Test fun incompleteAddressesAreRestoredInDraftsAndRejectedForDelivery() {
        val d=draft().copy(to="unfinished@",cc="copy@example.com, still-typing",bcc="hidden@")
        val m=wire(builder.build(session,account,d,MimePurpose.DRAFT,1).message)
        val editor=MimeReader().read(m).composer!!
        assertEquals(d.to,editor.to);assertEquals(d.cc,editor.cc);assertEquals(d.bcc,editor.bcc)
        assertThrows(IllegalArgumentException::class.java){builder.build(session,account,d)}
        m.setRecipients(Message.RecipientType.TO,"another@example.com");m.saveChanges()
        assertNull(MimeReader().read(wire(m)).composer)
    }
    @Test fun anotherClientsDraftEditWinsOverStalePrivateMetadata() {
        val m=builder.build(session,account,draft(),MimePurpose.DRAFT,1).message
        val mixed=m.content as Multipart
        val alternatives=mixed.getBodyPart(0).content as Multipart
        alternatives.getBodyPart(0).setText("Изменено другим клиентом")
        alternatives.getBodyPart(1).setContent("<p>Изменено другим клиентом</p>","text/html; charset=UTF-8")
        m.saveChanges()
        val decoded=MimeReader().read(wire(m))
        assertNull(decoded.composer)
        assertEquals("Изменено другим клиентом",decoded.text)
    }
    @Test fun locallySentInlineResourcesUseDisplayIdsWithoutDuplicatingTheirFiles() {
        val mail=source().copy(outgoing=listOf(OutgoingFile("owned://photo","photo.png","image/png",10,"original")),
            attachments=listOf(Attachment("owned://photo","photo.png","image/png",10,"display")),
            composer=ComposerSnapshot("Текст",resourceAliases=mapOf("display" to "original")))
        val plan=ForwardRules.plan(mail,includeOrdinary=false)!!
        assertEquals(1,plan.files.size)
        assertEquals("display",plan.files.single().contentId)
        assertEquals("owned://photo",plan.files.single().sourceUri)
    }
    @Test fun draftMetadataCannotBeInjectedWithoutMatchingDraftHeader() {
        val d=draft()
        assertNull(ComposeCodec.readMetadata(ComposeCodec.draftMetadata(d),null))
        assertNull(ComposeCodec.readMetadata(ComposeCodec.draftMetadata(d),"other"))
        assertEquals(ComposeCodec.snapshot(d),ComposeCodec.readMetadata(ComposeCodec.draftMetadata(d),d.id))
    }
    @Test fun anotherClientsFormattingOnlyEditInvalidatesStaleEditorMetadata() {
        val message=builder.build(session,account,draft(),MimePurpose.DRAFT,1).message
        val mixed=message.content as Multipart
        val alternatives=mixed.getBodyPart(0).content as Multipart
        val html=alternatives.getBodyPart(1).content as String
        alternatives.getBodyPart(1).setContent(html.replace("Новый ответ","<b>Новый ответ</b>"),"text/html; charset=UTF-8")
        message.saveChanges()
        assertNull(MimeReader().read(wire(message)).composer)
    }

}
