package ru.liftzip.mail

import java.net.InetAddress
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.SSLSocketFactory
import kotlinx.coroutines.suspendCancellableCoroutine

/** Android TLS sockets don't expose a SocketChannel. Use regular IMAP IDLE on a dedicated
 * reader, and close the underlying plain socket to interrupt TLS reads without protocol locks. */
class IdleConnection(delegate: SSLSocketFactory) {
    private val raw=ConcurrentHashMap.newKeySet<Socket>()
    private val tls=ConcurrentHashMap.newKeySet<Socket>()
    private val closed=AtomicBoolean(false)
    private val reader=Executors.newSingleThreadExecutor()
    private fun track(socket: Socket): Socket {
        raw+=socket
        if(closed.get()){runCatching{socket.close()};throw java.io.InterruptedIOException("Соединение отменено.")}
        return socket
    }
    val factory=object: SSLSocketFactory() {
        override fun getDefaultCipherSuites()=delegate.defaultCipherSuites
        override fun getSupportedCipherSuites()=delegate.supportedCipherSuites
        override fun createSocket(socket: Socket,host: String,port: Int,autoClose: Boolean): Socket {
            track(socket)
            return delegate.createSocket(socket,host,port,autoClose).also{tls+=it}
        }
        override fun createSocket()=track(delegate.createSocket())
        override fun createSocket(host: String,port: Int)=track(delegate.createSocket(host,port))
        override fun createSocket(host: String,port: Int,local: InetAddress,localPort: Int)=track(delegate.createSocket(host,port,local,localPort))
        override fun createSocket(host: InetAddress,port: Int)=track(delegate.createSocket(host,port))
        override fun createSocket(host: InetAddress,port: Int,local: InetAddress,localPort: Int)=track(delegate.createSocket(host,port,local,localPort))
    }
    fun timeout(milliseconds: Int){(raw+tls).forEach{runCatching{it.soTimeout=milliseconds}}}
    suspend fun <T> call(block: ()->T): T=suspendCancellableCoroutine{continuation->
        val task=reader.submit(Runnable {
            val result=runCatching(block)
            if(continuation.isActive)continuation.resumeWith(result)
        })
        continuation.invokeOnCancellation{closeSockets();task.cancel(true)}
    }
    private fun closeSockets(){closed.set(true);raw.forEach{runCatching{it.close()}}}
    fun close(){closeSockets();reader.shutdownNow()}
}
