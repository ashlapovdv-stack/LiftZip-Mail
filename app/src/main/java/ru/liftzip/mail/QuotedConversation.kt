package ru.liftzip.mail

data class QuotedMessage(val sender: String?,val date: String?,val body: String,val attribution: String?=null) {
    fun isOwn(addresses: Collection<String>): Boolean {
        val emails=Regex("[A-Za-z0-9.!#$%&'*+/=?^_`{|}~-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}").findAll(sender.orEmpty()).map{it.value.lowercase()}.toSet()
        return addresses.any{it.lowercase() in emails}
    }
}
data class QuotedConversation(val current: String,val messages: List<QuotedMessage>,val original: String)

/** Recognizes nested text quotations, not server threads. Ambiguous inline replies stay intact. */
object ConversationText {
    private data class Header(val lines: Int,val sender: String?=null,val date: String?=null,val attribution: String?=null)
    private val weekdays=Regex("^(понедельник|вторник|среда|четверг|пятница|суббота|воскресенье),?\\s+\\d.*",RegexOption.IGNORE_CASE)
    private val original=Regex("^-{2,}\\s*(Original Message|Исходное сообщение)\\s*-{2,}$",RegexOption.IGNORE_CASE)
    private val russianDate=Regex("^(\\d{1,2}\\s+\\p{L}+\\s+\\d{4}(?:\\s*г\\.?)?(?:[, ]+\\d{1,2}:\\d{2}(?::\\d{2})?)?(?:\\s*[+-]\\d{2}:?\\d{2})?)\\s+(.+)$")

    fun parse(body: String): QuotedConversation {
        val parts=ReplyText.split(body)
        if(!parts.canCollapse)return QuotedConversation(body,emptyList(),body)
        val messages=mutableListOf<QuotedMessage>()
        var remaining=parts.quoted.orEmpty()
        // Bound work for deeply nested or malformed messages; the full source is always accessible.
        repeat(32) {
            val lines=peel(remaining.replace("\r\n","\n").replace('\r','\n').trim().split('\n'))
            val header=header(lines)
            val text=lines.drop(header?.lines?:0).joinToString("\n").trim()
            val nested=ReplyText.split(text)
            messages+=QuotedMessage(header?.sender,header?.date,if(nested.canCollapse)nested.current else text,header?.attribution)
            if(!nested.canCollapse)return QuotedConversation(parts.current,messages,body)
            remaining=nested.quoted.orEmpty()
        }
        messages+=QuotedMessage(null,null,remaining)
        return QuotedConversation(parts.current,messages,body)
    }

    private fun peel(source: List<String>): List<String> {
        var lines=source
        repeat(32) {
            if(lines.none{it.isNotBlank()} || lines.any{it.isNotBlank() && !it.trimStart().startsWith('>')})return lines
            lines=lines.map{line->val trimmed=line.trimStart();if(trimmed.startsWith('>'))trimmed.drop(1).removePrefix(" ")else line}
        }
        return lines
    }
    private fun header(lines: List<String>): Header? {
        val first=lines.firstOrNull()?.trim().orEmpty()
        if(Regex("^[─━]{5,}$").matches(first)) {
            val index=(1 until minOf(lines.size,5)).firstOrNull{lines[it].isNotBlank()}?:return null
            val author=lines[index].trim()
            if(author.endsWith("пишет:",true))return Header(index+1,sender=author.dropLast("пишет:".length).trim(),attribution=author)
        }
        if(original.matches(first)) {
            var sender: String?=null;var date: String?=null;var end=1
            for(i in 1 until minOf(lines.size,18)) {
                val line=lines[i].trim()
                if(line.isEmpty()){end=i+1;break}
                val label=line.substringBefore(':').lowercase()
                val value=line.substringAfter(':',"").trim()
                when(label){"from","от"->sender=value;"sent","date","отправлено","дата"->date=value;"to","кому","subject","тема","cc","копия"->Unit;else->break}
                end=i+1
            }
            return Header(end,sender,date,first)
        }
        if(weekdays.matches(first)) {
            for(end in 1..minOf(lines.size,4)) {
                val text=lines.take(end).joinToString(" "){it.trim()}
                val author=Regex("(?:^|\\s)от\\s+(.+@.+?)\\s*:\\s*$",RegexOption.IGNORE_CASE).find(text)
                if(author!=null)return Header(end,author.groupValues[1].trim(),text.take(author.range.first).trim(),text)
            }
        }
        for(end in 1..minOf(lines.size,4)) {
            val text=lines.take(end).joinToString(" "){it.trim()}
            if(first.startsWith("On ",true) && text.endsWith("wrote:",true)) {
                val attribution=text.drop(3).dropLast("wrote:".length).trim()
                // Only separate an English sender when an address and a preceding comma exist.
                val address=Regex("<[^>]+@[^>]+>|[^\\s,]+@[^\\s,]+").find(attribution)
                if(address!=null) {
                    val comma=attribution.lastIndexOf(',',address.range.first)
                    if(comma>=0)return Header(end,attribution.drop(comma+1).trim(),attribution.take(comma).trim(),text)
                }
                return Header(end,attribution=text)
            }
            val russianEnd=Regex("\\s+писал(?:а|\\(а\\))?:\\s*$",RegexOption.IGNORE_CASE).find(text)
            if(first.firstOrNull()?.isDigit()==true && russianEnd!=null) {
                val values=russianDate.find(text.take(russianEnd.range.first))
                if(values!=null)return Header(end,values.groupValues[2],values.groupValues[1],text)
                return Header(end,attribution=text)
            }
        }
        return null
    }
}
