package ru.liftzip.mail

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.net.InetAddress
import java.net.URI
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicLong
import javax.net.ssl.HttpsURLConnection
import kotlinx.coroutines.runBlocking

data class MailImageData(val mime: String,val bytes: ByteArray)

/** Runs only on WebView's resource threads. No email cookies, referer, scripts, or WebView networking. */
class MailImageLoader(private val context: Context,accountId: String,private val inline: suspend (Attachment)->OpenedAttachment): AutoCloseable {
    private val root=File(context.cacheDir,"attachments/${MailImageLimits.hash(accountId)}/html-images")
    private val memory=ConcurrentHashMap<MailImageSource,MailImageData>()
    private val connections=ConcurrentHashMap.newKeySet<HttpsURLConnection>()
    private val permits=Semaphore(3)
    private val used=AtomicLong()
    @Volatile private var closed=false
    fun load(source: MailImageSource): MailImageData? {
        if(closed)return null
        memory[source]?.let{return it}
        var acquired=false
        return try {
            permits.acquire();acquired=true
            if(closed)return null
            memory[source]?.let{return it}
            val data=when(source) {
                is MailImageSource.Inline->{
                    val opened=runBlocking{inline(source.attachment)}
                    val bytes=context.contentResolver.openInputStream(Uri.parse(opened.uri))?.use{readBounded(it)}?:return null
                    validated(bytes)
                }
                is MailImageSource.Data->validated(Base64.getMimeDecoder().decode(source.base64))
                is MailImageSource.Remote->remote(source.url)
            }
            if(closed || used.addAndGet(data.bytes.size.toLong())>MailImageLimits.PAGE_BYTES)return null
            memory[source]=data
            data
        }catch(_: InterruptedException){Thread.currentThread().interrupt();null}catch(_: Exception){null}finally{if(acquired)permits.release()}
    }
    private fun readBounded(input: java.io.InputStream): ByteArray {
        val result=ByteArrayOutputStream()
        val bounded=BoundedMailOutput(result,MailImageLimits.MAX_BYTES)
        input.copyTo(bounded,32*1024)
        return result.toByteArray()
    }
    private fun validated(bytes: ByteArray): MailImageData {
        require(bytes.size.toLong()<=MailImageLimits.MAX_BYTES)
        val options=BitmapFactory.Options().apply{inJustDecodeBounds=true}
        BitmapFactory.decodeByteArray(bytes,0,bytes.size,options)
        require(options.outWidth>0 && options.outHeight>0 && options.outWidth.toLong()*options.outHeight<=24_000_000)
        val mime=requireNotNull(options.outMimeType)
        require(mime in setOf("image/png","image/jpeg","image/gif","image/webp","image/avif","image/heif","image/heic"))
        return MailImageData(mime,bytes)
    }
    private fun remote(raw: String): MailImageData {
        root.mkdirs()
        val cached=File(root,MailImageLimits.hash(raw)+".image")
        if(cached.isFile) {
            val old=runCatching{DataInputStream(cached.inputStream()).use{input->
                val mime=input.readUTF();val count=input.readInt();require(count in 1..MailImageLimits.MAX_BYTES.toInt())
                val bytes=ByteArray(count);input.readFully(bytes);require(input.read()==-1)
                MailImageData(mime,bytes)
            }}.getOrNull()
            if(old!=null){cached.setLastModified(System.currentTimeMillis());return old}
            cached.delete()
        }
        var url=HtmlMail.imageUrl(raw)?:error("Недопустимый адрес изображения.")
        repeat(5) {
            check(!closed)
            val uri=URI(url)
            require(uri.scheme.equals("https",true) && uri.host!=null)
            val addresses=InetAddress.getAllByName(requireNotNull(uri.host))
            require(addresses.isNotEmpty() && addresses.all(MailImageLimits::publicAddress))
            val connection=uri.toURL().openConnection() as HttpsURLConnection
            connections.add(connection)
            try {
                connection.instanceFollowRedirects=false
                connection.connectTimeout=15_000;connection.readTimeout=15_000
                connection.setRequestProperty("Cookie","")
                connection.setRequestProperty("User-Agent","Mail image viewer")
                connection.setRequestProperty("Accept","image/png,image/jpeg,image/gif,image/webp,image/avif")
                val code=connection.responseCode
                if(code in setOf(301,302,303,307,308)) {
                    val location=connection.getHeaderField("Location")?:error("Изображение недоступно.")
                    url=HtmlMail.imageUrl(uri.resolve(location).toString())?:error("Недопустимый адрес изображения.")
                }else {
                    require(code in 200..299 && connection.contentLengthLong<=MailImageLimits.MAX_BYTES)
                    val data=validated(connection.inputStream.use{readBounded(it)})
                    val partial=File.createTempFile("mail-image-",".part",root)
                    try {
                        DataOutputStream(partial.outputStream()).use{output->output.writeUTF(data.mime);output.writeInt(data.bytes.size);output.write(data.bytes)}
                        check(partial.renameTo(cached))
                    }finally{partial.delete()}
                    StorageWorker.enqueue(context)
                    return data
                }
            }finally{connections.remove(connection);connection.disconnect()}
        }
        error("Слишком много перенаправлений изображения.")
    }
    override fun close(){closed=true;connections.forEach{it.disconnect()};memory.clear()}
}
