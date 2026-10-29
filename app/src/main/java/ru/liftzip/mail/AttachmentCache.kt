package ru.liftzip.mail

import android.content.Context
import androidx.core.content.FileProvider
import java.io.File
import java.io.OutputStream
import java.nio.file.Files
import java.security.MessageDigest

class AttachmentCache(private val root: File,private val uriFor: (File)->String) {
    constructor(context: Context):this(File(context.cacheDir,"attachments"),{file->FileProvider.getUriForFile(context,"${context.packageName}.attachments",file).toString()})
    private fun hash(text: String) = MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
    fun existing(message: MailItem,attachment: Attachment): File? =
        File(File(File(root,hash(message.accountId)),hash("${message.id}|${attachment.path}")),AttachmentRules.safeName(attachment.name)).takeIf{it.isFile}
    /** Share a durable forward with the preview cache without an extra copy. Optional on filesystems without hard links. */
    fun remember(message: MailItem,attachment: Attachment,source: File) {
        val target=File(File(File(root,hash(message.accountId)),hash("${message.id}|${attachment.path}")),AttachmentRules.safeName(attachment.name))
        if(target.isFile)return
        target.parentFile!!.mkdirs()
        runCatching{Files.createLink(target.toPath(),source.toPath())}
    }

    fun prepare(message: MailItem, attachment: Attachment, fetch: (OutputStream)->Unit): File {
        val parent=File(File(root,hash(message.accountId)),hash("${message.id}|${attachment.path}"))
        val result=File(parent,AttachmentRules.safeName(attachment.name))
        if(result.isFile) { result.setLastModified(System.currentTimeMillis());return result }
        parent.mkdirs()
        val partial=File(parent,result.name+".part")
        try {
            partial.outputStream().use(fetch)
            check(partial.renameTo(result)){"Не удалось подготовить вложение для просмотра."}
        } catch(e: Exception) { partial.delete();throw e }
        // Only this dedicated private cache is shared; old previews expire after two days.
        val cutoff=System.currentTimeMillis()-2*24*60*60*1000L
        root.walkBottomUp().filter{it.isFile && it!=result && it.lastModified()<cutoff}.forEach{it.delete()}
        return result
    }

    fun opened(file: File, attachment: Attachment) = OpenedAttachment(
        uriFor(file),
        attachment.name,AttachmentRules.mime(attachment.name,attachment.mime)
    )

    fun removeAccount(id: String) { File(root,hash(id)).deleteRecursively() }
}
