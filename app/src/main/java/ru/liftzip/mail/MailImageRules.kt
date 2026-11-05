package ru.liftzip.mail

import java.io.OutputStream
import java.io.IOException
import java.net.InetAddress
import java.security.MessageDigest

object MailImageLimits {
    const val MAX_BYTES=8L*1024*1024
    const val PAGE_BYTES=24L*1024*1024
    fun hash(value: String)=MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString(""){"%02x".format(it)}
    fun publicAddress(address: InetAddress): Boolean {
        if(address.isAnyLocalAddress || address.isLoopbackAddress || address.isLinkLocalAddress || address.isSiteLocalAddress || address.isMulticastAddress)return false
        val b=address.address.map{it.toInt() and 255}
        return if(b.size==4) b[0]!=0 && b[0]<224 && !(b[0]==100 && b[1] in 64..127) && !(b[0]==198 && b[1] in 18..19)
        else (b[0] and 254) != 252
    }
}

/** An oversized MIME part must stop while streaming, before a full attachment reaches disk. */
class BoundedMailOutput(private val output: OutputStream,private val limit: Long): OutputStream() {
    private var bytes=0L
    override fun write(value: Int){reserve(1);output.write(value)}
    override fun write(buffer: ByteArray,offset: Int,length: Int){reserve(length);output.write(buffer,offset,length)}
    private fun reserve(length: Int){if(length.toLong()>limit-bytes)throw IOException("Изображение слишком большое для просмотра в письме.");bytes+=length}
    override fun flush()=output.flush()
}
