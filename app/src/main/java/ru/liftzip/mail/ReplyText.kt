package ru.liftzip.mail

data class ReplyParts(val current: String, val quoted: String? = null) {
    val canCollapse: Boolean get() = !quoted.isNullOrBlank() && current.isNotBlank()
}

/** Conservative text-only quote folding. The complete body is always retained. */
object ReplyText {
    private val weekdays = Regex("^(понедельник|вторник|среда|четверг|пятница|суббота|воскресенье),?\\s+\\d.*", RegexOption.IGNORE_CASE)
    private val original = Regex("^-{2,}\\s*(Original Message|Исходное сообщение)\\s*-{2,}$", RegexOption.IGNORE_CASE)
    private val date = Regex("\\b\\d{1,4}\\b")

    fun split(body: String): ReplyParts {
        val text = body.replace("\r\n", "\n").replace('\r', '\n')
        val lines = text.split('\n')
        var boundary = -1
        for (index in lines.indices) {
            val line = lines[index].trim()
            val following = lines.subList(index + 1,minOf(index + 5,lines.size)).firstOrNull { it.isNotBlank() }?.trim().orEmpty()
            val headerLines = lines.subList(index,minOf(index + 4,lines.size))
            val end = headerLines.indexOfFirst { Regex("(?:\\bwrote|\\bписал(?:а|\\(а\\))?):\\s*$",RegexOption.IGNORE_CASE).containsMatchIn(it) }
            val header = if(end<0)"" else headerLines.take(end + 1).joinToString(" ") { it.trim() }
            val english = line.startsWith("On ") && date.containsMatchIn(header) && Regex("\\bwrote:\\s*$").containsMatchIn(header)
            val russian = date.containsMatchIn(header) && Regex("\\bписал(?:а|\\(а\\))?:\\s*$", RegexOption.IGNORE_CASE).containsMatchIn(header)
            val mailRu = weekdays.matches(line) && following.startsWith("от ", true) && following.contains('@')
            val ours = line == "──────────" && following.endsWith("пишет:")
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
