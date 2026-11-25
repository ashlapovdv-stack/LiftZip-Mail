package ru.liftzip.mail

import android.content.ContentResolver
import android.content.ContentValues
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.annotation.RequiresApi
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File

@RequiresApi(Build.VERSION_CODES.Q)
internal suspend fun saveInDownloads(resolver: ContentResolver, attachment: Attachment, source: File): Uri {
    currentCoroutineContext().ensureActive()
    val columns = ContentValues().apply {
        put(MediaStore.MediaColumns.DISPLAY_NAME, AttachmentRules.safeName(attachment.name))
        put(MediaStore.MediaColumns.MIME_TYPE, AttachmentRules.mime(attachment.name, attachment.mime))
        put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
        put(MediaStore.MediaColumns.IS_PENDING, 1)
    }
    val downloads = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
    val uri = resolver.insert(downloads, columns) ?: error("Не удалось создать файл в «Загрузках».")
    try {
        val output = resolver.openOutputStream(uri) ?: error("Не удалось открыть файл для записи.")
        output.use { destination -> source.inputStream().use { it.copyTo(destination, 64 * 1024) } }
        currentCoroutineContext().ensureActive()
        val published = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
        check(resolver.update(uri, published, null, null) == 1) { "Не удалось сохранить файл в «Загрузках»." }
        return uri
    } catch (e: Exception) {
        runCatching { resolver.delete(uri, null, null) }
        throw e
    }
}
