package ru.liftzip.mail

import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Network requests never own the data lock. History and a new-mail check may run independently. */
class MailboxCoordinator {
    private val data=Mutex()
    private val polls=ConcurrentHashMap<String,Mutex>()
    private val bodies=ConcurrentHashMap<String,Mutex>()
    private val versions=mutableMapOf<String,Long>()
    suspend fun <T> data(block: ()->T): T=data.withLock{block()}
    suspend fun <T> poll(accountId: String,block: suspend ()->T): T=polls.computeIfAbsent(accountId){Mutex()}.withLock{block()}
    suspend fun <T> content(messageId: String,block: suspend ()->T): T=bodies.computeIfAbsent(messageId){Mutex()}.withLock{block()}
    // These two functions are used only in data() / request() prepare and commit sections.
    fun version(accountId: String)=versions[accountId]?:0L
    fun invalidate(accountId: String){versions[accountId]=version(accountId)+1}
    suspend fun <S: Any,R,T> request(prepare: ()->S?,fetch: suspend (S)->R,commit: (S,R)->T): T? {
        val captured=data(prepare)?:return null
        val response=fetch(captured)
        return data{commit(captured,response)}
    }
}
