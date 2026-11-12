package ru.liftzip.mail

import android.content.Context
import android.util.AtomicFile
import androidx.compose.runtime.saveable.Saver
import java.io.File
import java.io.ByteArrayOutputStream
import java.io.ObjectInputStream
import java.io.ObjectOutputStream

/** Lifecycle state only, never the transport queue. Large quote snapshots must not enter Binder.
 * Files are app-private, are not exposed by FileProvider, and are read only from hashed local tokens.
 * SQLite remains the durable mailbox/draft format; an incompatible UI cache is safely ignored.
 */
internal class DraftSessionCache(private val root: File) {
    constructor(context: Context):this(File(context.filesDir,"editor-sessions"))
    private var currentToken: String?=null
    private fun file(token: String)=AtomicFile(File(root,"$token.state"))
    @Synchronized fun save(draft: ComposeDraft): String? = runCatching {
        val bytes=ByteArrayOutputStream().use{buffer->
            ObjectOutputStream(buffer).use{output->output.writeInt(1);output.writeObject(draft)}
            buffer.toByteArray()
        }
        require(bytes.size<=32*1024*1024)
        check(root.isDirectory || root.mkdirs())
        val token=MailImageLimits.hash(draft.id)
        val target=file(token)
        val stream=target.startWrite()
        try {
            stream.write(bytes)
            target.finishWrite(stream)
        }catch(e: Exception){target.failWrite(stream);throw e}
        currentToken=token
        token
    }.onFailure{android.util.Log.e("MailDraftState","Cannot preserve editor session",it)}.getOrNull()
    @Synchronized fun restore(token: String): ComposeDraft? {
        if(!token.matches(Regex("[a-f0-9]{64}")))return null
        currentToken=token
        return runCatching {
            val target=file(token)
            require(target.baseFile.length()<=32L*1024*1024)
            ObjectInputStream(target.openRead()).use{input->
                require(input.readInt()==1)
                (input.readObject() as? ComposeDraft)?.takeIf{MailImageLimits.hash(it.id)==token}
            }
        }.getOrNull()
    }
    @Synchronized fun clear() {
        currentToken?.let{file(it).delete()}
        currentToken=null
    }
}

internal fun draftSessionSaver(cache: DraftSessionCache,editor: ComposeEditorSession?=null)=Saver<ComposeDraft?,String>(save={draft->draft?.let{cache.save(editor?.snapshot(it)?:it)}},restore=cache::restore)
