package ru.liftzip.mail

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.*
import java.net.*
import java.security.*
import java.security.cert.CertificateFactory
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64
import java.util.concurrent.*
import javax.net.ssl.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Local TLS peer exercises the actual Android provider, Angus parser, IDLE loop, and callbacks. */
@RunWith(AndroidJUnit4::class)
class ImapIdleDeviceTest {
    @Test fun tlsIdleDeliversTwoIndependentArrivalsAndRearms(): Unit=runBlocking {
        LocalImapPeer().use{peer->
            val connected=CompletableDeferred<Boolean>();val events=ChannelCounter()
            val c=ApplicationProvider.getApplicationContext<Context>()
            val account=MailAccount(id="test",name="Test",email="test@example.com",provider=Provider.CUSTOM,imapHost="localhost",imapPort=peer.port,smtpHost="localhost")
            val job=async(Dispatchers.IO){MailGateway(c,peer.clientFactory).watchInbox(account,"dummy",{connected.complete(it)},{events.next()})}
            try {
                assertTrue(withTimeout(15_000){connected.await()})
                withTimeout(10_000){while(peer.idleCommands.get()<1)delay(10)}
                peer.arrival(2);withTimeout(10_000){events.await(1)}
                withTimeout(10_000){while(peer.idleCommands.get()<2)delay(10)}
                peer.arrival(3);withTimeout(10_000){events.await(2)}
                withTimeout(10_000){while(peer.idleCommands.get()<2)delay(10)}
                assertTrue("Watcher should rearm IDLE",peer.idleCommands.get()>=2)
            }finally{withTimeout(3_000){job.cancelAndJoin()}}
            peer.problem.get()?.let{throw AssertionError("Test server failed",it)}
        }
    }
    @Test fun aServerWithoutIdleUsesThePeriodicFallback(): Unit=runBlocking {
        LocalImapPeer(idle=false).use{peer->
            val connected=CompletableDeferred<Boolean>()
            val c=ApplicationProvider.getApplicationContext<Context>()
            val a=MailAccount(id="test",name="Test",email="test@example.com",provider=Provider.CUSTOM,imapHost="localhost",imapPort=peer.port,smtpHost="localhost")
            val job=async(Dispatchers.IO){MailGateway(c,peer.clientFactory).watchInbox(a,"dummy",{connected.complete(it)},{})}
            try{assertFalse(withTimeout(15_000){connected.await()})}finally{withTimeout(3_000){job.cancelAndJoin()}}
        }
    }
    private class ChannelCounter {
        private val events=kotlinx.coroutines.channels.Channel<Unit>(kotlinx.coroutines.channels.Channel.UNLIMITED)
        private var count=0
        fun next(){events.trySend(Unit)}
        suspend fun await(target: Int){while(count<target){events.receive();count++}}
    }
}

private class LocalImapPeer(private val idle: Boolean=true): Closeable {
    private val executor=Executors.newCachedThreadPool()
    private val writers=CopyOnWriteArrayList<BufferedWriter>()
    private val sockets=CopyOnWriteArrayList<Socket>()
    private val server: SSLServerSocket
    private var exists=1
    @Volatile private var closed=false
    val idleCommands=java.util.concurrent.atomic.AtomicInteger()
    val problem=java.util.concurrent.atomic.AtomicReference<Throwable?>()
    val clientFactory: SSLSocketFactory
    val port get()=server.localPort
    init {
        val assets=InstrumentationRegistry.getInstrumentation().context.assets
        val certificate=assets.open("localhost-cert.pem").use{CertificateFactory.getInstance("X.509").generateCertificate(it)}
        val keyBytes=assets.open("localhost-test-key.b64").bufferedReader().use{Base64.getMimeDecoder().decode(it.readText())}
        val key=KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(keyBytes))
        val keys=KeyStore.getInstance("PKCS12").apply{load(null,null);setKeyEntry("test",key,"test".toCharArray(),arrayOf(certificate))}
        val km=KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply{init(keys,"test".toCharArray())}
        val trust=KeyStore.getInstance(KeyStore.getDefaultType()).apply{load(null,null);setCertificateEntry("localhost",certificate)}
        val tm=TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply{init(trust)}
        val ssl=SSLContext.getInstance("TLS").apply{init(km.keyManagers,tm.trustManagers,null)}
        clientFactory=ssl.socketFactory
        server=(ssl.serverSocketFactory.createServerSocket(0,10,InetAddress.getByName("127.0.0.1")) as SSLServerSocket)
        executor.submit{try{while(!closed){val s=server.accept();sockets+=s;executor.submit{serve(s)}}}catch(e: Exception){if(!closed)problem.set(e)}}
    }
    private fun write(writer: BufferedWriter,line: String)=synchronized(writer){writer.write(line+"\r\n");writer.flush()}
    fun arrival(count: Int){exists=count;writers.forEach{write(it,"* $count EXISTS")}}
    private fun serve(socket: Socket) {
        try {
            socket.use {
                val input=socket.getInputStream().bufferedReader(Charsets.US_ASCII)
                val out=socket.getOutputStream().bufferedWriter(Charsets.US_ASCII)
                val caps="IMAP4rev1 AUTH=PLAIN UIDPLUS"+if(idle)" IDLE" else ""
                write(out,"* OK [CAPABILITY $caps] Local test server")
                var idleTag: String?=null
                while(!closed) {
                    val line=input.readLine()?:break
                    if(line=="DONE") {write(out,"${idleTag.orEmpty()} OK idle completed");idleTag=null;continue}
                    val tag=line.substringBefore(' ');val command=line.substringAfter(' ').substringBefore(' ').uppercase()
                    when(command) {
                        "CAPABILITY"->{write(out,"* CAPABILITY $caps");write(out,"$tag OK capability")}
                        "AUTHENTICATE"->{if(line.trim().split(' ').size<4){write(out,"+ ");input.readLine()};write(out,"$tag OK authenticated")}
                        "LOGIN"->write(out,"$tag OK authenticated")
                        "EXAMINE","SELECT"->{write(out,"* FLAGS (\\Seen \\Answered \\Flagged \\Deleted \\Draft)");write(out,"* $exists EXISTS");write(out,"* 0 RECENT");write(out,"* OK [UIDVALIDITY 7] epoch");write(out,"* OK [UIDNEXT ${exists+1}] next");write(out,"$tag OK [READ-ONLY] open");writers+=out}
                        "IDLE"->{idleTag=tag;idleCommands.incrementAndGet();write(out,"+ idling")}
                        "NOOP"->write(out,"$tag OK noop")
                        "LOGOUT"->{write(out,"* BYE done");write(out,"$tag OK logout");break}
                        else->write(out,"$tag BAD unsupported test command")
                    }
                }
                writers-=out
            }
        }catch(e: Exception){if(!closed && e !is SocketException && e !is EOFException && e !is SSLException)problem.set(e)}
    }
    override fun close(){closed=true;runCatching{server.close()};sockets.forEach{runCatching{it.close()}};executor.shutdownNow()}
}
