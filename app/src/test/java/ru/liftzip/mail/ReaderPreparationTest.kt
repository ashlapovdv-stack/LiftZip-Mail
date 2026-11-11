package ru.liftzip.mail

import org.junit.Assert.*
import org.junit.Test

class ReaderPreparationTest {
    private fun key()=ReaderAnalysisCache.Key("a:m:7","Ответ\n\nOn 7 Nov 2026, sender@example.com wrote:\n> Старое письмо","<p>Ответ</p><div>On 7 Nov 2026, sender@example.com wrote:</div><blockquote>Старое письмо</blockquote>",1)
    @Test fun unchangedSourceReusesTheCompleteAnalysis() {
        val cache=ReaderAnalysisCache();assertNull(cache.peek(key()))
        val first=cache.prepare(key());assertTrue(first.second.separateHistory)
        assertSame(first,cache.peek(key()));assertSame(first,cache.prepare(key().copy()))
    }
    @Test fun changedSourceAccountOrParserCannotReuseAnalysis() {
        val cache=ReaderAnalysisCache();val first=cache.prepare(key())
        for(changed in listOf(key().copy(body="Другой ответ"),key().copy(html="<p>Иное письмо</p>"),key().copy(identity="other:m:7"),key().copy(contentVersion=2),key().copy(parserVersion=2))) {
            assertNull(cache.peek(changed));assertNotSame(first,cache.prepare(changed))
        }
    }
    @Test fun analysisCacheIsBoundedAndClearable() {
        val cache=ReaderAnalysisCache(limit=1);cache.prepare(key());cache.prepare(key().copy(identity="second"));assertNull(cache.peek(key()))
        cache.clear();assertNull(cache.peek(key().copy(identity="second")))
        val tiny=ReaderAnalysisCache(budget=1);tiny.prepare(key());assertNull(tiny.peek(key()))
    }
    @Test fun geometryRequiresTheSameDocumentWidthAndFontConfiguration() {
        val doc=HtmlMail.document("<p>Ответ</p>","Ответ",emptyList())
        val key=ReaderGeometryCache.Key(doc,1080,3f,1f)
        val cache=ReaderGeometryCache();val geometry=ReaderGeometryCache.Geometry(250,3f)
        cache.put(key,geometry);assertEquals(geometry,cache.peek(key))
        for(other in listOf(key.copy(width=720),key.copy(fontScale=1.2f),key.copy(density=2f),key.copy(document=doc.copy(html="changed"))))assertNull(cache.peek(other))
        cache.clear();assertNull(cache.peek(key))
    }
    @Test fun invalidGeometryAndOverBudgetDocumentsAreNeverRetained() {
        val key=ReaderGeometryCache.Key(HtmlMail.document("<p>Ответ</p>","Ответ",emptyList()),1080,3f,1f)
        val cache=ReaderGeometryCache()
        for(g in listOf(ReaderGeometryCache.Geometry(0,1f),ReaderGeometryCache.Geometry(20,Float.NaN),ReaderGeometryCache.Geometry(20,0f)))cache.put(key,g)
        assertNull(cache.peek(key));val tiny=ReaderGeometryCache(budget=1);tiny.put(key,ReaderGeometryCache.Geometry(20,1f));assertNull(tiny.peek(key))
    }
    @Test fun preparedDocumentIsImmediatelyAvailableOnCacheHit() {
        val cache=ReaderDocumentCache();val source=key()
        val key=ReaderDocumentCache.Key(source.identity,source.html,source.body,emptyList(),HtmlMail.noHistory(source.html),false,"#111111","#ffffff")
        assertNull(cache.peek(key));val document=cache.prepare(key);assertSame(document,cache.peek(key));cache.clear();assertNull(cache.peek(key))
    }
}
