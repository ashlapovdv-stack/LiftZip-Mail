package ru.liftzip.mail

import android.content.Context
import androidx.core.content.FileProvider
import java.io.File
import java.io.InputStream
import java.security.MessageDigest

data class ForwardSource(val name: String,val mime: String,val size: Long,val open: ()->InputStream)

/** Forwarded draft attachments must survive cache eviction and process restarts. */
class ForwardAttachments(private val context: Context) {
    private fun directory(id: String): File {
        val key=MessageDigest.getInstance("SHA-256").digest(id.toByteArray()).joinToString(""){"%02x".format(it)}
        return File(File(context.filesDir,"forwarded"),key)
    }

    fun prepare(draftId: String,sources: List<ForwardSource>): List<OutgoingFile> {
        require(sources.none{it.size>20L*1024*1024}){"Вложение больше 20 МБ. Автоматическая пересылка недоступна."}
        require(sources.sumOf{it.size.coerceAtLeast(0)}<=25L*1024*1024){"Суммарный размер вложений больше 25 МБ."}
        if(sources.isEmpty())return emptyList()
        val folder=directory(draftId)
        var total=0L
        try {
            return sources.mapIndexed { index,source->
                val target=File(File(folder,index.toString()),AttachmentRules.safeName(source.name))
                target.parentFile!!.mkdirs()
                var length=0L
                source.open().use { input->target.outputStream().use { output->
                    val buffer=ByteArray(8192)
                    while(true) {
                        val count=input.read(buffer)
                        if(count<0)break
                        length+=count;total+=count
                        require(length<=20L*1024*1024){"Вложение больше 20 МБ. Автоматическая пересылка недоступна."}
                        require(total<=25L*1024*1024){"Суммарный размер вложений больше 25 МБ."}
                        output.write(buffer,0,count)
                    }
                } }
                val uri=FileProvider.getUriForFile(context,"${context.packageName}.attachments",target)
                OutgoingFile(uri.toString(),source.name,AttachmentRules.mime(source.name,source.mime),length)
            }
        } catch(e: Exception) { folder.deleteRecursively();throw e }
    }

    fun remove(draftId: String) { directory(draftId).deleteRecursively() }
}
