@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package ru.liftzip.mail

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable fun FolderHistoryScreen(s: UiState,onBack: ()->Unit,onDiscover: ()->Unit,onStart: (List<ServerFolder>)->Unit,onPause: (String,String)->Unit) {
    val folders=s.folders.filter{it.role!=FolderRole.INBOX}
    val active=s.accounts.filter{it.enabled}.map{it.id}.toSet()
    var selected by rememberSaveable{mutableStateOf<List<String>>(emptyList())}
    fun key(f: ServerFolder)=f.accountId+":"+f.path
    val chosen=folders.filter{it.accountId in active && key(it) in selected}
    LaunchedEffect(Unit){onDiscover()}
    Scaffold(topBar={TopAppBar(title={Text("История серверных папок")},navigationIcon={IconButton(onClick=onBack){Icon(Icons.AutoMirrored.Outlined.ArrowBack,"Назад")}})}){padding->
        LazyColumn(Modifier.fillMaxSize().padding(padding),contentPadding=PaddingValues(20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            item{Text("Выберите папки всех или отдельных ящиков. Сохраняется список сообщений; тексты и вложения серверных папок загружаются при открытии. Входящие настраиваются на предыдущем экране.")}
            item{Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                TextButton(onClick={selected=folders.filter{it.accountId in active}.map(::key)},enabled=!s.busy){Text("Выбрать все")}
                TextButton(onClick={selected=emptyList()},enabled=!s.busy){Text("Снять выбор")}
            }}
            items(folders,key={key(it)}){f->
                OutlinedCard(onClick={selected=if(key(f) in selected)selected-key(f)else selected+key(f)},enabled=f.accountId in active && !s.busy,modifier=Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Row(verticalAlignment=androidx.compose.ui.Alignment.CenterVertically) {
                            Checkbox(checked=key(f) in selected,onCheckedChange=null)
                            Column(Modifier.weight(1f)){Text(f.title,fontWeight=FontWeight.SemiBold);Text(s.accounts.find{it.id==f.accountId}?.name.orEmpty(),style=MaterialTheme.typography.bodySmall)}
                        }
                        val loaded=s.messages.count{it.accountId==f.accountId && it.remoteFolder==f.path && FolderRules.remote(it) && !it.pendingDelete}
                        Text("Загружено $loaded"+(f.total?.let{" из $it"}?:""),style=MaterialTheme.typography.bodySmall)
                        if(f.error.isNotBlank())Text(f.error,color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.bodySmall)
                        if(f.syncAll)TextButton(onClick={onPause(f.accountId,f.path)},enabled=!s.busy){Text("Загружается · приостановить")}
                        else if(!f.hasOlder)Text("Весь список загружен",style=MaterialTheme.typography.labelSmall)
                    }
                }
            }
            item{Button(onClick={onStart(chosen)},enabled=!s.busy && chosen.isNotEmpty(),modifier=Modifier.fillMaxWidth()){Text("Загрузить выбранные папки")}}
            if(folders.isEmpty())item{Text("Список папок пока пуст. Проверьте подключение ящиков.")}
        }
    }
}
