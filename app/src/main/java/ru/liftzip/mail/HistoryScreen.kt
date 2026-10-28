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

@Composable fun HistoryScreen(s: UiState,onBack: ()->Unit,onStart: (List<String>)->Unit,onPause: ()->Unit,onResume: ()->Unit) {
    val job=s.historyDownload
    var selected by rememberSaveable{mutableStateOf(ArrayList(job.accountIds.ifEmpty{s.accounts.map{it.id}}))}
    val available=s.accounts.map{it.id}.toSet()
    val chosen=selected.filter{it in available}
    val counts=remember(s.messages){s.messages.filter{it.localFolder=="inbox"}.groupingBy{it.accountId}.eachCount()}
    Scaffold(topBar={TopAppBar(title={Text("Загрузка истории")},navigationIcon={IconButton(onClick=onBack){Icon(Icons.AutoMirrored.Outlined.ArrowBack,"Назад")}})}){padding->
        Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),verticalArrangement=Arrangement.spacedBy(14.dp)) {
            Text("Загрузить все входящие",style=MaterialTheme.typography.titleLarge,fontWeight=FontWeight.SemiBold)
            Text("Выберите все ящики или нужные. Письма загружаются автоматически порциями. Вложения скачиваются при открытии или сохранении.")
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                TextButton(onClick={selected=ArrayList(available)},enabled=!job.active && !s.busy){Text("Выбрать все")}
                TextButton(onClick={selected=arrayListOf()},enabled=!job.active && !s.busy){Text("Снять выбор")}
            }
            s.accounts.forEach{a->
                val loaded=counts[a.id]?:0
                val history=s.history[a.id]
                val total=history?.total
                OutlinedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Row(Modifier.fillMaxWidth().clickable(enabled=!job.active && !s.busy){selected=ArrayList(if(a.id in chosen)chosen-a.id else chosen+a.id)},verticalAlignment=Alignment.CenterVertically) {
                            Checkbox(checked=a.id in if(job.active)job.accountIds else chosen,onCheckedChange=null)
                            Column(Modifier.weight(1f).padding(start=8.dp)){Text(a.name,fontWeight=FontWeight.SemiBold);Text(a.email,style=MaterialTheme.typography.bodySmall)}
                        }
                        Text(if(total==null)"Загружено $loaded писем · всего пока неизвестно" else "Загружено $loaded из $total",style=MaterialTheme.typography.bodySmall,modifier=Modifier.padding(vertical=8.dp))
                        if(total!=null && total>0)LinearProgressIndicator(progress={loaded.toFloat().div(total).coerceIn(0f,1f)},modifier=Modifier.fillMaxWidth())
                        if(history?.hasOlder==false)Text("Все входящие загружены",style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.primary,modifier=Modifier.padding(top=6.dp))
                    }
                }
            }
            if(job.status!="idle")Text(when(job.status){"running"->"История загружается. Можно продолжать пользоваться приложением.";"waiting"->"Ожидание следующей попытки. Загрузка продолжится автоматически при доступном соединении.";"paused"->"Загрузка приостановлена. Полученные письма сохранены.";"done"->"Загрузка выбранных ящиков завершена.";else->""})
            if(job.error.isNotEmpty())Text(job.error,color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.bodySmall)
            if(job.active) {
                OutlinedButton(onClick=onPause,enabled=!s.busy,modifier=Modifier.fillMaxWidth()){Text("Приостановить загрузку")}
            }else {
                Button(onClick={onStart(chosen)},enabled=chosen.isNotEmpty() && !s.busy,modifier=Modifier.fillMaxWidth()){Icon(Icons.Outlined.Download,null);Spacer(Modifier.width(8.dp));Text("Загрузить всю историю")}
                if(job.status=="paused" && job.accountIds.any{it in available})OutlinedButton(onClick=onResume,enabled=!s.busy,modifier=Modifier.fillMaxWidth()){Text("Продолжить прежнюю загрузку")}
            }
            Text("Прогресс сохраняется после каждой порции. После потери сети загрузка возобновится без повторного получения сохранённых писем. В фоне Android может отложить выполнение.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(24.dp))
        }
    }
}
