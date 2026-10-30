package ru.liftzip.mail

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File
import java.util.UUID

/** Snapshot external attachments before queueing SMTP, so later edits/removal cannot change a queued letter. */
class QueueFiles(private val context: Context) {
    private val root=File(context.filesDir,"outbox-files")
    fun freeze(draft: ComposeDraft): ComposeDraft {
        val directory=File(root,UUID.randomUUID().toString());var total=0L
        try {
            val files=draft.attachments.mapIndexed{index,file->
                directory.mkdirs()
                val target=File(directory,"$index-"+AttachmentRules.safeName(file.name));var size=0L
                val input=context.contentResolver.openInputStream(Uri.parse(file.uri))?:error("Не удалось прочитать ${file.name}.")
                input.use{source->target.outputStream().use{out->val bytes=ByteArray(64*1024);while(true){val count=source.read(bytes);if(count<0)break;size+=count;total+=count
                    require(size<=20L*1024*1024 && total<=25L*1024*1024){"До 20 МБ на файл и 25 МБ суммарно."};out.write(bytes,0,count)
                }}}
                OutgoingFile(FileProvider.getUriForFile(context,"${context.packageName}.attachments",target).toString(),file.name,file.mime,size)
            }
            return draft.copy(attachments=files)
        }catch(e: Exception){directory.deleteRecursively();throw e}
    }
}
