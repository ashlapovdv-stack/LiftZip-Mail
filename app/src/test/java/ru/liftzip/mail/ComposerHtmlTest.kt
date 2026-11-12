package ru.liftzip.mail

import jakarta.mail.Session
import jakarta.mail.internet.MimeMessage
import org.json.JSONObject
import org.jsoup.Jsoup
import org.junit.Assert.*
import org.junit.Test
import java.util.Properties

class ComposerHtmlTest {
    private val rich="<h2>Тест HTML · Ёжик 🙂</h2><p>Это <b>жирный</b>, <i>курсивный</i> и <span style='color:#c62828'>цветной</span> текст.</p><table border='1'><tr><th>Позиция</th><th>Цена</th></tr><tr><td>Контактор</td><td>4 500 ₽</td></tr></table><blockquote>Старая история</blockquote><a href='https://example.com'>Ссылка</a>"
    private val account=MailAccount("a","Sender","sender@example.com",provider=Provider.REG,imapHost="imap.example.com",smtpHost="smtp.example.com")
    @Test fun htmlSurvivesLocalCodecServerDraftReopeningAndEveryDeliveryPurpose() {
        var draft=ComposerHtml.replace(ComposeDraft(accountId="a",to="client@example.com",subject="HTML"),rich)
        val expected=draft.bodyHtml
        val session=Session.getInstance(Properties())
        val builder=OutgoingMime{error("No file access")}
        repeat(3) {
            val local=ComposeCodec.decode(JSONObject(ComposeCodec.encode(ComposeCodec.snapshot(draft)).toString()))!!
            assertEquals(expected,local.bodyHtml)
            draft=draft.copy(body=local.text,bodyHtml=local.bodyHtml)
            for(purpose in MimePurpose.entries) {
                val bytes=java.io.ByteArrayOutputStream().also{builder.build(session,account,draft,purpose).message.writeTo(it)}.toByteArray()
                val decoded=MimeReader().read(MimeMessage(session,bytes.inputStream()))
                val doc=Jsoup.parse(decoded.html)
                assertEquals("жирный",doc.select("b").text());assertEquals("курсивный",doc.select("i").text())
                assertEquals(1,doc.select("table").size);assertEquals("color:#c62828",doc.select("span").attr("style"))
                assertTrue(decoded.text.contains("4 500 ₽"));assertTrue(decoded.text.contains("Ёжик 🙂"))
                if(purpose==MimePurpose.DRAFT) {
                    val editor=decoded.composer!!
                    assertEquals(expected,editor.bodyHtml)
                    draft=draft.copy(body=editor.text,bodyHtml=editor.bodyHtml)
                }
            }
        }
    }
    @Test fun ordinaryTextAndLiteralTagsAreNeverInterpretedAsHtml() {
        val d=ComposeDraft(accountId="a",body="<b>literal</b>\nВторая строка")
        assertNull(ComposeCodec.decode(ComposeCodec.encode(ComposeCodec.snapshot(d)))!!.bodyHtml)
        assertTrue(ComposerHtml.initial(d).contains("&lt;b&gt;"))
        assertEquals(d.body,ComposeContent.render(d).plain)
        assertEquals("",ComposeContent.render(d.copy(bodyHtml="")).plain)
    }
    @Test fun stylesheetFormattingIsScopedAndActiveContentIsRemovedBeforeEditing() {
        val clean=ComposerHtml.clean("<style>.red{color:red} body{position:fixed;background:url(https://tracker.example)}</style><p class='red' id='editor' contenteditable='false' onclick='alert(1)'>Hello</p><script>EditorBridge.input('bad')</script><iframe srcdoc='<script>alert(1)</script>'></iframe><img src='file:///private'><img src='data:image/svg+xml;base64,YQ=='><a href='javascript:alert(1)'>Link</a>")
        val doc=Jsoup.parse(clean)
        assertEquals("color:red",doc.select("p").attr("style"))
        assertTrue(doc.select("script,style,iframe,[id],[onclick],[contenteditable]").isEmpty())
        assertFalse(clean.contains("file:"));assertFalse(clean.contains("javascript:"));assertFalse(clean.contains("svg"));assertFalse(clean.contains("url("))
        assertEquals(clean,ComposerHtml.clean(clean))
    }
    @Test fun editingAFragmentDoesNotFlattenUntouchedTableOrDoubleQuote() {
        val d=ComposerHtml.replace(ComposeDraft(accountId="a"),rich)
        val edited=ComposerHtml.replace(d,d.bodyHtml!!.replace("Контактор","Датчик"))
        assertEquals(1,Jsoup.parse(ComposeContent.render(edited).html).select("blockquote").size)
        assertEquals("Датчик",Jsoup.parse(edited.bodyHtml!!).select("td").first()!!.text())
        assertEquals("Старая история",Jsoup.parse(d.bodyHtml!!).select("blockquote").text())
    }
    @Test fun authorCidIsRetainedWithoutASeparateQuote() {
        val d=ComposerHtml.replace(ComposeDraft(accountId="a",to="client@example.com",subject="Image",attachments=listOf(OutgoingFile("owned","pic.png","image/png",1,"photo"))),"<p>Фото<img src='cid:photo'></p>")
        assertEquals(listOf("photo"),ComposeContent.render(d).inlineIds.keys.toList())
        assertEquals(1,d.filesToSend().size)
        ComposeContent.requireResources(d)
    }
}
