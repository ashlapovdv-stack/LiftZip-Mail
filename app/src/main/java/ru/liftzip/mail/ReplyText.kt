package ru.liftzip.mail

data class ReplyParts(val current: String, val quoted: String? = null) {
    val canCollapse: Boolean get() = !quoted.isNullOrBlank() && current.isNotBlank()
}

/** Conservative text-only quote folding. The complete body is always retained. */
object ReplyText {
    private val weekdays = Regex("^(понедельник|вторник|среда|четверг|пятница|суббота|воскресенье),?\\s+\\d.*", RegexOption.IGNORE_CASE)
    private val original = Regex("^-{2,}\\s*(Original Message|Forwarded Message|Исходное сообщение|Пересланное письмо)\\s*-{2,}$", RegexOption.IGNORE_CASE)
    private val date = Regex("\\b\\d{1,4}\\b")
    private val headerEnd = Regex("(?:\\bwrote|(?<!\\p{L})писал(?:а|\\(а\\))?):\\s*$", RegexOption.IGNORE_CASE)
    private val englishEnd = Regex("\\bwrote:\\s*$", RegexOption.IGNORE_CASE)
    private val russianEnd = Regex("(?<!\\p{L})писал(?:а|\\(а\\))?:\\s*$", RegexOption.IGNORE_CASE)

    fun split(body: String): ReplyParts {
        val text = body.replace("\r\n", "\n").replace('\r', '\n')
        val lines = text.split('\n')
        var boundary = -1
        for (index in lines.indices) {
            val line = lines[index].trim()
            val following = lines.subList(index + 1,minOf(index + 5,lines.size)).firstOrNull { it.isNotBlank() }?.trim().orEmpty()
            val headerLines = lines.subList(index,minOf(index + 4,lines.size))
            val end = headerLines.indexOfFirst { headerEnd.containsMatchIn(it) }
            val header = if(end<0)"" else headerLines.take(end + 1).joinToString(" ") { it.trim() }
            val english = line.startsWith("On ") && date.containsMatchIn(header) && englishEnd.containsMatchIn(header)
            val russian = line.firstOrNull()?.isDigit()==true && date.containsMatchIn(line) && russianEnd.containsMatchIn(header)
            val mailRu = weekdays.matches(line) && (1..headerLines.size).any{count->
                Regex("(?:^|\\s)от\\s+.+@.+:\\s*$",RegexOption.IGNORE_CASE).containsMatchIn(headerLines.take(count).joinToString(" "){it.trim()})
            }
            val ours = Regex("^[─━]{5,}$").matches(line) && following.endsWith("пишет:",true)
            if (english || russian || mailRu || ours || original.matches(line)) {
                boundary = index
                break
            }
        }
        if (boundary < 0) {
            // Collapse a trailing quote block only. Inline replies after > remain visible.
            var index = lines.indexOfLast { it.isNotBlank() }
            var hasQuote = false
            while (index >= 0 && (lines[index].isBlank() || lines[index].trimStart().startsWith('>'))) {
                if (lines[index].trimStart().startsWith('>')) hasQuote = true
                index--
            }
            if (hasQuote) boundary = index + 1
        }
        if (boundary <= 0) return ReplyParts(body)
        val tail = lines.subList(boundary,lines.size)
        val firstQuote = tail.indexOfFirst { it.trimStart().startsWith('>') }
        if(firstQuote>=0 && tail.drop(firstQuote).any{it.isNotBlank() && !it.trimStart().startsWith('>')})return ReplyParts(body)
        val current = lines.take(boundary).joinToString("\n").trimEnd()
        val quote = lines.drop(boundary).joinToString("\n").trim()
        return if (current.isBlank() || quote.isBlank()) ReplyParts(body) else ReplyParts(current, quote)
    }
}
