package ru.liftzip.mail

/** Process-only LRU: no WebViews, Activities, credentials or downloaded images are retained. */
class ReaderDocumentCache(private val budget: Long=4L*1024*1024,private val limit: Int=8) {
    data class Key(val identity: String,val html: String,val plain: String,val attachments: List<Attachment>,
        val history: HtmlMail.HistoryAnalysis,val external: Boolean,val foreground: String,val background: String)
    private data class Entry(val document: MailHtmlDocument,val bytes: Long)
    private val entries=LinkedHashMap<Key,Entry>(8,0.75f,true)
    private var bytes=0L
    fun prepare(key: Key): MailHtmlDocument {
        synchronized(this){entries[key]?.let{return it.document}}
        val doc=HtmlMail.document(key.html,key.plain,key.attachments,key.history,key.external,key.foreground,key.background,key.identity)
        val size=2L*(key.identity.length+key.html.length+key.plain.length+doc.html.length)+
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
    private val conversations=LinkedHashMap<String,QuotedConversation>(4,0.75f,true)
    private var bytes=0L
    fun clear(){html.clear();synchronized(conversations){conversations.clear();bytes=0}}
    fun conversation(body: String): QuotedConversation {
        synchronized(conversations){conversations[body]?.let{return it}}
        val parsed=ConversationText.parse(body)
        val size=4L*body.length
        synchronized(conversations) {
            conversations[body]?.let{return it}
            if(size<=1024*1024) {
                conversations[body]=parsed;bytes+=size
                val iterator=conversations.entries.iterator()
                while((bytes>1024*1024 || conversations.size>4) && iterator.hasNext()){bytes-=4L*iterator.next().key.length;iterator.remove()}
            }
        }
        return parsed
    }
}
