package ru.liftzip.mail

import org.junit.Assert.*
import org.junit.Test
import androidx.compose.ui.unit.Constraints
import org.jsoup.Jsoup

class ReaderDocumentsTest {
    private fun key(): ReaderDocumentCache.Key {
        val html="<p>Cached content</p><img src='https://images.example/p.png'>"
        return ReaderDocumentCache.Key("a:m:1",html,"Cached content",emptyList(),HtmlMail.noHistory(html),false,"#20241f","#f8f9f6")
    }
    @Test fun reopeningAnUnchangedMessageReusesThePreparedDocument() {
        val cache=ReaderDocumentCache();val first=cache.prepare(key())
        assertSame(first,cache.prepare(key().copy()))
    }
    @Test fun imagePermissionAndMessageIdentityCannotReuseTheWrongDocument() {
        val cache=ReaderDocumentCache();val blocked=cache.prepare(key())
        val allowed=cache.prepare(key().copy(external=true))
        val other=cache.prepare(key().copy(identity="b:m:1",external=true))
        assertTrue(blocked.images.isEmpty());assertEquals(1,allowed.images.size)
        assertNotEquals(allowed.images.keys,other.images.keys)
        assertSame(blocked,cache.prepare(key()))
    }
    @Test fun changedBodyAttachmentsHistoryOrColorsInvalidateThePreparedContent() {
        val cache=ReaderDocumentCache();val original=cache.prepare(key())
        for(changed in listOf(key().copy(html="<p>Changed</p>"),key().copy(plain="Changed"),key().copy(history=HtmlMail.HistoryAnalysis("<p>Changed current HTML</p>",true,HtmlMail.HistoryReason.SEPARATED)),
            key().copy(attachments=listOf(Attachment("1","x.png","image/png",1,"x"))),key().copy(background="#000000"))) {
            assertNotSame(original,cache.prepare(changed))
        }
    }
    @Test fun leastRecentlyUsedDocumentsAreEvictedAndLargeLettersAreNotRetained() {
        val cache=ReaderDocumentCache(limit=2);val first=cache.prepare(key())
        val second=cache.prepare(key().copy(identity="second"))
        assertSame(first,cache.prepare(key()))
        cache.prepare(key().copy(identity="third"))
        assertSame(first,cache.prepare(key()));assertNotSame(second,cache.prepare(key().copy(identity="second")))
        val tiny=ReaderDocumentCache(budget=8)
        assertNotSame(tiny.prepare(key()),tiny.prepare(key()))
    }
    @Test fun fallbackAndConfirmedHistorySplitsCannotReuseEachOthersDocument() {
        val html="<p>Ответ</p><div class='gmail_quote'>Старое письмо</div>"
        val matching=QuotedConversation("Ответ",listOf(QuotedMessage(null,null,"Старое письмо")),"Ответ\n\nСтарое письмо")
        val mismatch=matching.copy(messages=listOf(QuotedMessage(null,null,"Другой plain-текст")))
        val split=HtmlMail.analyzeHistory(html,matching);val fallback=HtmlMail.analyzeHistory(html,mismatch)
        assertTrue(split.separateHistory);assertFalse(fallback.separateHistory)
        val cache=ReaderDocumentCache()
        fun prepare(history: HtmlMail.HistoryAnalysis)=cache.prepare(ReaderDocumentCache.Key("a:m:1",html,"Ответ",emptyList(),history,false,"#20241f","#f8f9f6"))
        val splitDocument=prepare(split);val fallbackDocument=prepare(fallback)
        assertFalse(Jsoup.parse(splitDocument.html).text().contains("Старое письмо"))
        assertTrue(Jsoup.parse(fallbackDocument.html).text().contains("Старое письмо"))
        assertSame(fallbackDocument,prepare(fallback));assertSame(splitDocument,prepare(split))
    }
    @Test fun historyHtmlBudgetSkipsSharedSourceAndChargesAnIndependentSplitString() {
        val shared=key()
        val document=ReaderDocumentCache().prepare(shared)
        val baseSize=2L*(shared.identity.length+shared.html.length+shared.plain.length+document.html.length)
        val sharedCache=ReaderDocumentCache(budget=baseSize)
        assertSame(sharedCache.prepare(shared),sharedCache.prepare(shared.copy()))

        val independentHtml=String(shared.html.toCharArray())
        val independent=shared.copy(history=HtmlMail.HistoryAnalysis(independentHtml,false,HtmlMail.HistoryReason.NO_PLAIN_HISTORY))
        assertNotSame(independent.html,independent.history.currentHtml)
        val exactOldBudget=ReaderDocumentCache(budget=baseSize)
        assertNotSame(exactOldBudget.prepare(independent),exactOldBudget.prepare(independent.copy()))
        val adjustedBudget=ReaderDocumentCache(budget=baseSize+2L*independentHtml.length)
        assertSame(adjustedBudget.prepare(independent),adjustedBudget.prepare(independent.copy()))
    }

    @Test fun clearingTheCacheReleasesPreparedDocuments() {
        val cache=ReaderDocumentCache();val first=cache.prepare(key());cache.clear()
        assertNotSame(first,cache.prepare(key()))
    }
    @Test fun theReported266942PixelMailNeverBecomesAnUnrepresentableLayout() {
        assertThrows(IllegalArgumentException::class.java){Constraints(minHeight=266942,maxHeight=266942)}
        assertEquals(1200,HtmlMail.height(266942,1f,1200))
        for(width in listOf(0,690,1080,1440,4096)) {
            val height=HtmlMail.height(Int.MAX_VALUE,3.5f,Int.MAX_VALUE)
            val bounds=Constraints(maxWidth=width,minHeight=height,maxHeight=height)
            assertEquals(height,bounds.maxHeight);assertEquals(width,bounds.maxWidth)
        }
    }
    @Test fun invalidScalesAndViewportLimitsStayBounded() {
        for(scale in listOf(Float.NaN,Float.POSITIVE_INFINITY,-1f,0f,3.5f)) {
            assertTrue(HtmlMail.height(Int.MAX_VALUE,scale,1080) in 1..1080)
            assertEquals(1,HtmlMail.height(100,scale,0))
        }
    }
    @Test fun listPreviewOmitsImageAltMarkersButKeepsTheFollowingMessageText() {
        assertEquals("Создан пароль приложения",MailContent.snippet("[image: Google]\n\nСоздан пароль приложения"))
        assertEquals("Прогноз на Q4",MailContent.snippet("[IMAGE: logo] [изображение: баннер]\nПрогноз на Q4"))
    }
    @Test fun previewCleanupKeepsOrdinaryBracketedTextAndDoesNotAlterTheStoredBody() {
        val body="[image quality] Смета [123]";assertEquals(body,MailContent.snippet(body))
        val original="[image: Google] Important message";MailContent.snippet(original)
        assertEquals("[image: Google] Important message",original)
    }
}
