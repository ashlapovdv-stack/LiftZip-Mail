package ru.liftzip.mail

import android.content.Context
import androidx.core.content.FileProvider
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.io.InterruptedIOException
import java.nio.file.Files
import java.security.MessageDigest
import java.util.UUID

data class ForwardSource(val name: String,val mime: String,val size: Long,val open: ()->InputStream)

/** Forwarded draft attachments must survive cache eviction and process restarts. */
class ForwardAttachments(private val root: File, private val uriFor: (File)->String) {
    constructor(context: Context):this(File(context.filesDir,"forwarded"),{ file->
        FileProvider.getUriForFile(context,"${context.packageName}.attachments",file).toString()
    })
    private fun directory(id: String): File {
        val key=MessageDigest.getInstance("SHA-256").digest(id.toByteArray()).joinToString(""){"%02x".format(it)}
        return File(root,key)
    }
    private fun destination(draftId: String,key: String,name: String): File {
        val safeKey=MessageDigest.getInstance("SHA-256").digest(key.toByteArray()).joinToString(""){"%02x".format(it)}
        return File(File(directory(draftId),safeKey),AttachmentRules.safeName(name))
    }
    fun existing(draftId: String,key: String,name: String): File? = destination(draftId,key,name).takeIf{it.isFile}

    fun prepareFile(draftId: String,key: String,name: String,mime: String,size: Long,
        cached: File? = null, progress: (Long)->Unit = {}, write: (OutputStream)->Unit): OutgoingFile {
        require(size<=20L*1024*1024){"Вложение больше 20 МБ."}
        val target=destination(draftId,key,name)
        target.parentFile!!.mkdirs()
        target.parentFile!!.listFiles()?.filter{it!=target && it.name.endsWith(".part")}?.forEach{it.delete()}
        fun checked(bytes: Long) {
            if(Thread.currentThread().isInterrupted)throw InterruptedIOException()
            require(bytes<=20L*1024*1024){"Вложение больше 20 МБ."}
            progress(bytes)
        }
        if(target.isFile) {
            checked(target.length())
            return OutgoingFile(uriFor(target),name,AttachmentRules.mime(name,mime),target.length())
        }
        // Unique partials also keep a cancelled worker from overwriting a later attempt.
        val partial=File(target.parentFile,target.name+".${UUID.randomUUID()}.part")
        try {
            val linked=cached?.takeIf{it.isFile}?.let { file->
                runCatching { Files.createLink(partial.toPath(),file.toPath());true }.getOrDefault(false)
            }?:false
            if(linked)checked(partial.length())
            else partial.outputStream().use { output->
                var length=0L
                val counted=object: OutputStream() {
                    override fun write(value: Int) { checked(length+1);output.write(value);length++ }
                    override fun write(bytes: ByteArray,offset: Int,count: Int) {
                        checked(length+count);output.write(bytes,offset,count);length+=count
                    }
                    override fun flush()=output.flush()
                }
                if(cached?.isFile==true)cached.inputStream().use{it.copyTo(counted,64*1024)}else write(counted)
            }
            check(partial.renameTo(target)){"Не удалось сохранить вложение черновика."}
            return OutgoingFile(uriFor(target),name,AttachmentRules.mime(name,mime),target.length())
        } catch(e: Exception) { partial.delete();throw e }
    }

    fun prepare(draftId: String,sources: List<ForwardSource>): List<OutgoingFile> {
        require(sources.none{it.size>20L*1024*1024}){"Вложение больше 20 МБ. Автоматическая пересылка недоступна."}
        require(sources.sumOf{it.size.coerceAtLeast(0)}<=25L*1024*1024){"Суммарный размер вложений больше 25 МБ."}
        if(sources.isEmpty())return emptyList()
        val folder=directory(draftId)
        val budget=ForwardBudget()
        try {
            return sources.mapIndexed { index,source->
                prepareFile(draftId,index.toString(),source.name,source.mime,source.size,
                    progress={budget.progress(index.toString(),it)}) { output->source.open().use{it.copyTo(output,64*1024)} }
            }
        } catch(e: Exception) { folder.deleteRecursively();throw e }
    }

    fun remove(draftId: String) { directory(draftId).deleteRecursively() }
}
