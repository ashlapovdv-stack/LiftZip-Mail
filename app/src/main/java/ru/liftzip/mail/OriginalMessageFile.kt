package ru.liftzip.mail

internal object OriginalMessageFile {
    fun name(subject: String): String {
        val safe=subject.replace(Regex("[\\p{Cntrl}\\\\/:*?\"<>|]")," ").replace(Regex("\\s+")," ").trim(' ','.').take(100).trimEnd(' ','.')
        return (safe.ifBlank{"Письмо"})+".eml"
    }
}
