package ru.liftzip.mail

import androidx.compose.runtime.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

internal const val ReaderProgressDelayMillis=250L

/** Only feedback waits. Content and errors are published as soon as they are ready. */
@Composable internal fun rememberReaderProgress(request: Any,loading: Boolean): Boolean {
    val elapsed=remember(request,loading){mutableStateOf(false)}
    LaunchedEffect(request,loading) {
        if(loading){delay(ReaderProgressDelayMillis);elapsed.value=true}
    }
    return loading && elapsed.value
}

internal data class PreparedReaderContent(val message: MailItem,val conversation: QuotedConversation,val history: HtmlMail.HistoryAnalysis)

/** Keep the last complete source/analysis pair while this letter is updated.
 * A preview is never substituted for a full body, and another letter starts a new session. */
@Composable internal fun rememberReaderContent(message: MailItem,
    prepare: suspend (ReaderAnalysisCache.Key)->Pair<QuotedConversation,HtmlMail.HistoryAnalysis> = {ReaderDocuments.analysis.prepare(it)}
): State<PreparedReaderContent?> {
    val identity="${message.accountId}:${message.id}:${message.validity}"
    val analysisKey=remember(identity,message.body,message.htmlBody,message.contentVersion){
        ReaderAnalysisCache.Key(identity,message.body,message.htmlBody,message.contentVersion)
    }
    val available=MailContent.canDisplay(message)
    val latestPrepare by rememberUpdatedState(prepare)
    return key(identity) {
        val cached=if(available)ReaderDocuments.analysis.peek(analysisKey)else null
        produceState(cached?.let{PreparedReaderContent(message,it.first,it.second)},analysisKey,available,message.attachments) {
            if(!available)return@produceState
            // Publish on Main even when a test frame interceptor resumes the producer
            // inline on the worker that finished parsing.
            withContext(Dispatchers.Main.immediate) {
                val prepareNow=latestPrepare
                val analysis=withContext(Dispatchers.Default){prepareNow(analysisKey)}
                value=PreparedReaderContent(message,analysis.first,analysis.second)
            }
        }
    }
}
