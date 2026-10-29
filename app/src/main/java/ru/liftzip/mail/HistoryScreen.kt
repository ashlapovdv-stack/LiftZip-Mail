@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package ru.liftzip.mail

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable fun HistoryScreen(s: UiState,onBack: ()->Unit,onStart: (List<String>)->Unit,onPause: ()->Unit,onResume: ()->Unit) {
    val job=s.historyDownload
    val now by produceState(System.currentTimeMillis()){while(true){value=System.currentTimeMillis();delay(5000)}}
    val issues=HistoryRules.remainingIssues(job,s.messages)
    val restart=HistoryRules.mayRestart(job,s.historyWork,now)
    var selected by rememberSaveable{mutableStateOf<List<String>>(job.accountIds.ifEmpty{s.accounts.filter{it.enabled}.map{it.id}})}
    val available=s.accounts.filter{it.enabled}.map{it.id}.toSet()
    val chosen=selected.filter{it in available}
    val counts=remember(s.messages){s.messages.filter{it.localFolder=="inbox"}.groupingBy{it.accountId}.eachCount()}
    val contents=remember(s.messages){s.messages.filter{it.localFolder=="inbox" && it.contentCached}.groupingBy{it.accountId}.eachCount()}
    Scaffold(topBar={TopAppBar(title={Text("Загрузка истории")},navigationIcon={IconButton(onClick=onBack){Icon(Icons.AutoMirrored.Outlined.ArrowBack,"Назад")}})}){padding->
        Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),verticalArrangement=Arrangement.spacedBy(14.dp)) {
            Text("Загрузить все входящие",style=MaterialTheme.typography.titleLarge,fontWeight=FontWeight.SemiBold)
            Text("Сначала сохраняется список всех входящих, затем тексты писем. Прогресс сохраняется после каждого текста. Вложения скачиваются при открытии или сохранении.")
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                TextButton(onClick={selected=ArrayList(available)},enabled=!job.active && !s.busy){Text("Выбрать все")}
                TextButton(onClick={selected=arrayListOf()},enabled=!job.active && !s.busy){Text("Снять выбор")}
            }
            s.accounts.forEach{a->
                val loaded=counts[a.id]?:0
                val saved=contents[a.id]?:0
                val history=s.history[a.id]
                val total=history?.total
                OutlinedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Row(Modifier.fillMaxWidth().clickable(enabled=a.enabled && !job.active && !s.busy){selected=ArrayList(if(a.id in chosen)chosen-a.id else chosen+a.id)},verticalAlignment=Alignment.CenterVertically) {
                            Checkbox(checked=a.id in if(job.active)job.accountIds else chosen,onCheckedChange=null,enabled=a.enabled)
                            Column(Modifier.weight(1f).padding(start=8.dp)){Text(a.name,fontWeight=FontWeight.SemiBold);Text(a.email,style=MaterialTheme.typography.bodySmall);if(!a.enabled)Text("Синхронизация выключена",style=MaterialTheme.typography.labelSmall)}
                        }
                        Text(if(total==null)"В списке $loaded писем · всего пока неизвестно" else "В списке $loaded из $total",style=MaterialTheme.typography.bodySmall,modifier=Modifier.padding(vertical=8.dp))
                        Text("Текст сохранён у $saved писем",style=MaterialTheme.typography.bodySmall,modifier=Modifier.padding(bottom=8.dp))
                        if(job.active && job.currentAccountId==a.id)Text(when(s.historyWork){
                            HistoryWorkState.RUNNING->if(job.phase=="headers")"Получаем список писем" else "Читаем текст"+(if(job.currentUid>0)" · UID ${job.currentUid}" else "")
                            HistoryWorkState.WAITING->"Ожидание запуска или повторной попытки"
                            HistoryWorkState.STOPPED->"Фоновое задание остановлено"
                            HistoryWorkState.UNKNOWN->"Проверяем состояние задания"
                        },style=MaterialTheme.typography.bodySmall,modifier=Modifier.padding(bottom=8.dp))
                        val failures=issues.count{it.accountId==a.id}
                        if(failures>0)Text("Не получен текст: $failures писем. Подробности ниже.",color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.bodySmall,modifier=Modifier.padding(bottom=8.dp))
                        if(total!=null && total>0)LinearProgressIndicator(progress={saved.toFloat().div(total).coerceIn(0f,1f)},modifier=Modifier.fillMaxWidth())
                        if(!SyncRules.needsHistory(a.id,s.messages,history))Text("Все входящие загружены",style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.primary,modifier=Modifier.padding(top=6.dp))
                    }
                }
            }
            if(job.status!="idle")Text(when {
                job.active && s.historyWork==HistoryWorkState.STOPPED->"Фоновое задание остановлено. Продолжите загрузку: сохранённые письма останутся на месте."
                job.active && s.historyWork==HistoryWorkState.WAITING->"Задание ожидает запуска или повторной попытки. Android может отложить его при недоступной сети или энергосбережении."
                job.active && s.historyWork==HistoryWorkState.UNKNOWN->"Проверяем состояние фонового задания…"
                job.active && job.phase=="headers"->"Получаем список оставшихся писем."
                job.active && job.phase=="bodies"->"Сохраняем тексты. Ящик: ${s.accounts.find{it.id==job.currentAccountId}?.name.orEmpty()}"+(if(job.currentUid>0)" · письмо UID ${job.currentUid}" else "")
                job.status=="paused"->"Загрузка приостановлена. Полученные письма сохранены."
                job.status=="partial"->"Остальная почта сохранена. Не удалось получить тексты ${issues.size} писем после двух попыток. Их можно запросить ещё раз."
                job.status=="done"->"Загрузка выбранных ящиков завершена."
                else->"Ожидание следующего запроса."
            })
            if(job.savedAt>0)Text("Последнее сохранение: "+SimpleDateFormat("HH:mm:ss",Locale("ru")).format(Date(job.savedAt)),style=MaterialTheme.typography.bodySmall)
            if(restart)Text("Сохранение давно не обновлялось или задание остановлено. Можно безопасно перезапустить продолжение.",style=MaterialTheme.typography.bodySmall)
            if(job.error.isNotEmpty())Text(job.error,color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.bodySmall)
            issues.take(3).forEach{issue->
                val message=s.messages.find{it.id==issue.messageId}
                Text("Не сохранён текст: ${message?.subject?:"письмо"}"+(message?.let{" (UID ${it.uid})"}?:"")+". Попыток: ${issue.attempts}. ${issue.detail}",color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.bodySmall)
            }
            if(issues.size>3)Text("Ещё ошибок: ${issues.size-3}",style=MaterialTheme.typography.bodySmall)
            if(job.active) {
                OutlinedButton(onClick=onPause,enabled=!s.busy,modifier=Modifier.fillMaxWidth()){Text("Приостановить загрузку")}
                if(restart)Button(onClick=onResume,enabled=!s.busy && job.accountIds.any{it in available},modifier=Modifier.fillMaxWidth()){Text("Перезапустить продолжение")}
            }else {
                Button(onClick={onStart(chosen)},enabled=chosen.isNotEmpty() && !s.busy,modifier=Modifier.fillMaxWidth()){Icon(Icons.Outlined.Download,null);Spacer(Modifier.width(8.dp));Text("Загрузить всю историю")}
                if(job.status in listOf("paused","partial") && job.accountIds.any{it in available})OutlinedButton(onClick=onResume,enabled=!s.busy,modifier=Modifier.fillMaxWidth()){Text(if(job.status=="partial")"Повторить неполученные тексты" else "Продолжить прежнюю загрузку")}
            }
            Text("Сохранённые письма повторно не скачиваются. Если отдельное письмо не читается, загрузка остальных продолжается; проблемное письмо запрашивается ещё раз в конце. В фоне Android может отложить выполнение.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(24.dp))
        }
    }
}
