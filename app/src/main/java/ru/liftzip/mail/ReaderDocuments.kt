package ru.liftzip.mail

/** Process-only LRU: no WebViews, Activities, credentials or downloaded images are retained. */
class ReaderDocumentCache(private val budget: Long=4L*1024*1024,private val limit: Int=8) {
    data class Key(val identity: String,val html: String,val plain: String,val attachments: List<Attachment>,
        val history: HtmlMail.HistoryAnalysis,val external: Boolean,val foreground: String,val background: String,val originalScale: Boolean=false)
    private data class Entry(val document: MailHtmlDocument,val bytes: Long)
    private val entries=LinkedHashMap<Key,Entry>(8,0.75f,true)
    private var bytes=0L
    @Synchronized fun peek(key: Key)=entries[key]?.document
    fun prepare(key: Key): MailHtmlDocument {
        synchronized(this){entries[key]?.let{return it.document}}
        val doc=HtmlMail.document(key.html,key.plain,key.attachments,key.history,key.external,key.foreground,key.background,key.identity,key.originalScale)
        val historyHtml=key.history.currentHtml
        val historyBytes=(if(historyHtml===key.html)0L else 2L*historyHtml.length)+2L*key.history.quotedHtml.length
        val size=2L*(key.identity.length+key.html.length+key.plain.length+doc.html.length)+historyBytes+
            key.attachments.sumOf{2L*(it.name.length+it.path.length+it.contentId.length)}+doc.images.size*256L
        synchronized(this) {
            entries[key]?.let{return it.document}
            if(size<=budget && limit>0) {
                entries[key]=Entry(doc,size);bytes+=size
                val iterator=entries.entries.iterator()
                while((bytes>budget || entries.size>limit) && iterator.hasNext()){bytes-=iterator.next().value.bytes;iterator.remove()}
            }
        }
        return doc
    }
    @Synchronized fun clear(){entries.clear();bytes=0}
}

object ReaderDocuments {
    val html=ReaderDocumentCache()
    val analysis=ReaderAnalysisCache()
    val geometry=ReaderGeometryCache()
    fun clear(){html.clear();analysis.clear();geometry.clear()}
}

/** Source/version keyed analysis; cached hits never need to parse HTML on the UI thread. */
class ReaderAnalysisCache(private val budget: Long=4L*1024*1024,private val limit: Int=8) {
    data class Key(val identity: String,val body: String,val html: String,val contentVersion: Int,val parserVersion: Int=2)
    private data class Entry(val result: Pair<QuotedConversation,HtmlMail.HistoryAnalysis>,val bytes: Long)
    private val entries=LinkedHashMap<Key,Entry>(8,0.75f,true)
    private var bytes=0L
    @Synchronized fun peek(key: Key)=entries[key]?.result
    fun prepare(key: Key): Pair<QuotedConversation,HtmlMail.HistoryAnalysis> {
        peek(key)?.let{return it}
        val conversation=ConversationText.parse(key.body)
        val history=HtmlMail.analyzeHistory(key.html,conversation)
        val result=(if(history.separateHistory)conversation else conversation.copy(messages=emptyList())) to history
        val size=6L*key.body.length+4L*key.html.length+2L*(history.currentHtml.length+history.quotedHtml.length)
        synchronized(this) {
            entries[key]?.let{return it.result}
            if(limit>0 && size<=budget) {
                entries[key]=Entry(result,size);bytes+=size
                val iterator=entries.entries.iterator()
                while((bytes>budget || entries.size>limit) && iterator.hasNext()){bytes-=iterator.next().value.bytes;iterator.remove()}
            }
        }
        return result
    }
    @Synchronized fun clear(){entries.clear();bytes=0}
}

/** Only intrinsic, unzoomed geometry is reusable. No native view or scroll position is cached. */
class ReaderGeometryCache(private val limit: Int=8,private val budget: Long=4L*1024*1024) {
    data class Key(val document: MailHtmlDocument,val width: Int,val density: Float,val fontScale: Float,val viewport: Int=0)
    data class Geometry(val height: Int,val scale: Float)
    private val entries=LinkedHashMap<Key,Geometry>(8,0.75f,true)
    private var bytes=0L
    @Synchronized fun peek(key: Key)=entries[key]
    @Synchronized fun put(key: Key,value: Geometry) {
        if(value.height<=0 || !value.scale.isFinite() || value.scale<=0f || key.width<=0)return
        val size=2L*key.document.html.length
        if(limit<=0 || size>budget)return
        if(entries.put(key,value)==null)bytes+=size
        val iterator=entries.entries.iterator()
        while((entries.size>limit || bytes>budget) && iterator.hasNext()){bytes-=2L*iterator.next().key.document.html.length;iterator.remove()}
    }
    @Synchronized fun clear(){entries.clear();bytes=0}
}
