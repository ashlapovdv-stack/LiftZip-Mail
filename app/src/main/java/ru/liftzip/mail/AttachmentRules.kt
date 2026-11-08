package ru.liftzip.mail

import java.util.Locale

object AttachmentRules {
    const val THUMBNAIL_MAX_BYTES=12L*1024*1024
    fun safeName(name: String): String = name.substringAfterLast('/').substringAfterLast('\\')
        .replace(Regex("[\\p{Cntrl}<>:\"|?*]"), "_").trim().take(160)
        .takeUnless { it.isBlank() || it == "." || it == ".." } ?: "Вложение"

    fun mime(name: String, reported: String): String {
        val type = reported.substringBefore(';').trim().lowercase(Locale.ROOT)
        val ext=name.substringAfterLast('.', "").lowercase(Locale.ROOT)
        val imageExtension=ext in setOf("jpg","jpeg","png","gif","webp","bmp","heic","heif")
        val generic=type in setOf("application/octet-stream","binary/octet-stream","application/download","application/x-download","application/unknown","application/x-unknown") || type=="text/plain" && imageExtension
        if (!generic && Regex("^[a-z0-9.+-]+/[a-z0-9.+-]+$").matches(type)) return type
        return when (ext) {
            "pdf" -> "application/pdf"
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "gif" -> "image/gif"
            "webp" -> "image/webp"
            "bmp" -> "image/bmp"
            "heic", "heif" -> "image/heif"
            "txt", "log" -> "text/plain"
            "csv" -> "text/csv"
            "doc" -> "application/msword"
            "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
            "xls" -> "application/vnd.ms-excel"
            "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
            "ppt" -> "application/vnd.ms-powerpoint"
            "pptx" -> "application/vnd.openxmlformats-officedocument.presentationml.presentation"
            "zip" -> "application/zip"
            else -> "application/octet-stream"
        }
    }

    fun canPreview(mime: String) = mime == "application/pdf" || (mime.startsWith("image/") && mime != "image/svg+xml")
    fun isImage(name: String,reported: String)=mime(name,reported).let{it.startsWith("image/") && it!="image/svg+xml"}
    fun format(name: String,reported: String): String {
        val extension=name.substringAfterLast('.',"").uppercase(Locale.ROOT).takeIf{it.matches(Regex("[A-Z0-9]{1,6}"))}
        return extension?:when(mime(name,reported)){"application/pdf"->"PDF";"text/plain"->"TXT";else->"ФАЙЛ"}
    }
}
