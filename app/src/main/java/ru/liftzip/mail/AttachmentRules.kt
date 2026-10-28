package ru.liftzip.mail

import java.util.Locale

object AttachmentRules {
    fun safeName(name: String): String = name.substringAfterLast('/').substringAfterLast('\\')
        .replace(Regex("[\\p{Cntrl}<>:\"|?*]"), "_").trim().take(160)
        .takeUnless { it.isBlank() || it == "." || it == ".." } ?: "Вложение"

    fun mime(name: String, reported: String): String {
        val type = reported.substringBefore(';').trim().lowercase(Locale.ROOT)
        if (type != "application/octet-stream" && type != "binary/octet-stream" && Regex("^[a-z0-9.+-]+/[a-z0-9.+-]+$").matches(type)) return type
        return when (name.substringAfterLast('.', "").lowercase(Locale.ROOT)) {
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
}
