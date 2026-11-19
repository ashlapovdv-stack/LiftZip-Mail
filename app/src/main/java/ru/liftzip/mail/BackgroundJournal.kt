package ru.liftzip.mail

import android.content.SharedPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.net.UnknownHostException

internal enum class BackgroundSource(val label: String) {
    APP("Почта"), SCHEDULER("Планировщик"), AUTOMATIC("Автопроверка"), TEST("Разовая проверка")
}

internal enum class BackgroundStage(val label: String) {
    OPENED("открыта"), HIDDEN("свёрнута"), STARTED("запуск"), SUCCESS("успешно"),
    DNS_RETRY("сбой DNS, ожидается повтор"), RETRY("ошибка, ожидается повтор"),
    STOPPED("прервано"), SKIPPED("пропущено"),
    STATE("состояние задания"), REQUESTED("запрос расписания"), APPLIED("запрос выполнен"), SCHEDULE_FAILED("запрос не выполнен")
}

internal data class BackgroundEvent(
    val time: Long,
    val source: BackgroundSource,
    val stage: BackgroundStage,
    val visible: Boolean,
    val attempt: Int,
    val newUnread: Int,
    val detail: String=""
) {
    fun description(): String = buildString {
        append(source.label).append(" · ").append(stage.label)
        if(source==BackgroundSource.AUTOMATIC || source==BackgroundSource.TEST) {
            append(" · попытка ").append(attempt)
            append(if(visible)" · Почта открыта" else " · Почта в фоне")
            if(stage==BackgroundStage.SUCCESS)append(" · новых непрочитанных ").append(newUnread)
        }
        if(detail.isNotBlank())append(" · ").append(detail)
    }
}

internal fun Throwable.isMailDnsFailure()=mailErrorChain(this).any{it is UnknownHostException}

internal class BackgroundJournal(prefs: ()->SharedPreferences,scope: CoroutineScope) {
    private val queue=Channel<BackgroundEvent>(Channel.UNLIMITED)
    private val state=MutableStateFlow<List<BackgroundEvent>>(emptyList())
    val events=state.asStateFlow()
    private var visible=false

    init {
        scope.launch(Dispatchers.IO) {
            val store=prefs()
            state.value=read(store)
            for(event in queue) {
                val updated=state.value.takeLast(LIMIT-1)+event
                val json=JSONArray()
                updated.forEach { item->
                    json.put(JSONObject().put("time",item.time).put("source",item.source.name).put("stage",item.stage.name)
                        .put("visible",item.visible).put("attempt",item.attempt).put("newUnread",item.newUnread).put("detail",item.detail))
                }
                // Publish after the write so the on-screen journal is normally durable.
                if(!store.edit().putString(KEY,json.toString()).commit()){
                    android.util.Log.w("MailJournal","Could not persist background events")
                }
                state.value=updated
            }
        }
    }

    @Synchronized fun visibility(active: Boolean,detail: String="") {
        if(visible==active)return
        visible=active
        record(BackgroundSource.APP,if(active)BackgroundStage.OPENED else BackgroundStage.HIDDEN,detail=detail)
    }

    @Synchronized fun record(source: BackgroundSource,stage: BackgroundStage,attempt: Int=0,newUnread: Int=0,detail: String="") {
        queue.trySend(BackgroundEvent(System.currentTimeMillis(),source,stage,visible,attempt,newUnread,detail))
    }

    private fun read(store: SharedPreferences): List<BackgroundEvent> {
        val json=runCatching{JSONArray(store.getString(KEY,"[]"))}.getOrElse{return emptyList()}
        return (maxOf(0,json.length()-LIMIT) until json.length()).mapNotNull { index->
            runCatching {
                val item=json.getJSONObject(index)
                BackgroundEvent(item.getLong("time"),BackgroundSource.valueOf(item.getString("source")),
                    BackgroundStage.valueOf(item.getString("stage")),item.getBoolean("visible"),item.getInt("attempt"),item.getInt("newUnread"),item.optString("detail"))
            }.getOrNull()
        }
    }

    companion object {
        private const val KEY="events"
        const val LIMIT=256
    }
}
