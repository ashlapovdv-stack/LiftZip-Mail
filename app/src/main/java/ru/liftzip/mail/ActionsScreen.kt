@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package ru.liftzip.mail

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable fun ActionsScreen(s: UiState,onBack: ()->Unit,onRetry: (String,Boolean)->Unit,onCancel: (String)->Unit,onAccounts: ()->Unit) {
    var confirm by remember{mutableStateOf<MailAction?>(null)}
    var changesExpanded by remember{mutableStateOf(false)}
    confirm?.let{a->AlertDialog(onDismissRequest={confirm=null},title={Text("Повторить отправку?")},
        text={Text("Сервер мог уже принять это письмо. Проверьте отправленные и доставку получателю. Повтор может создать вторую копию.")},
        confirmButton={TextButton(onClick={confirm=null;onRetry(a.id,true)}){Text("Отправить ещё раз")}},dismissButton={TextButton(onClick={confirm=null}){Text("Отмена")}})}
    val outgoing=s.actions.filter{it.kind==ActionKind.SEND}
    val changes=s.actions.filter{it.kind!=ActionKind.SEND}
    Scaffold(topBar={TopAppBar(title={Text("Исходящие и синхронизация")},navigationIcon={IconButton(onClick=onBack){Icon(Icons.AutoMirrored.Outlined.ArrowBack,"Назад")}})}){padding->
        LazyColumn(Modifier.fillMaxSize().padding(padding),contentPadding=PaddingValues(18.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            if(s.actions.isEmpty())item{Text("Очередь пуста. Все сохранённые действия выполнены.",Modifier.padding(vertical=24.dp))}
            if(outgoing.isNotEmpty())item{Text("Исходящие",style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.SemiBold)}
            items(outgoing,key={it.id}){a->ActionCard(a,s,onRetry={if(a.status=="uncertain")confirm=a else onRetry(a.id,false)},onCancel={onCancel(a.id)},onAccounts=onAccounts)}
            if(changes.isNotEmpty())item {
                OutlinedButton(onClick={changesExpanded=!changesExpanded},modifier=Modifier.fillMaxWidth()) {
                    Text("Синхронизация: ${changes.size}",Modifier.weight(1f));Icon(Icons.Outlined.ExpandMore,null)
                }
                if(changes.any{it.status in setOf("failed","auth")})Text("Есть действия, которым нужна помощь. Разверните список.",color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.bodySmall)
            }
            if(changesExpanded)items(changes,key={it.id}){a->ActionCard(a,s,onRetry={onRetry(a.id,false)},onCancel={onCancel(a.id)},onAccounts=onAccounts)}
            item{Text("Ожидающие действия сохраняются после закрытия приложения. Отмена доступна до начала выполнения. Письмо с неподтверждённой отправкой автоматически повторно не отправляется.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)}
        }
    }
}

@Composable private fun ActionCard(a: MailAction,s: UiState,onRetry: ()->Unit,onCancel: ()->Unit,onAccounts: ()->Unit) {
    val account=s.accounts.find{it.id==a.accountId}
    val running=a.status in setOf("running","sending")
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp),verticalArrangement=Arrangement.spacedBy(7.dp)) {
            Text(a.subject.ifBlank{"Без темы"},fontWeight=FontWeight.SemiBold)
            Text("${account?.name.orEmpty()} · ${a.title}",style=MaterialTheme.typography.bodySmall)
            Text(if(account?.enabled==false)"Ящик приостановлен" else when(a.status){
                "running","sending"->"Выполняется…";"uncertain"->"Доставка не подтверждена";"auth"->"Нужен повторный вход";"failed"->"Не выполнено";else->if(!s.notificationAccess.connected)"Ожидает сети" else "Ожидает выполнения"
            },style=MaterialTheme.typography.bodyMedium,color=if(a.status in setOf("failed","auth","uncertain"))MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
            if(a.error.isNotBlank())Text(a.error,style=MaterialTheme.typography.bodySmall)
            if(running)LinearProgressIndicator(Modifier.fillMaxWidth())
            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                if(a.status=="auth")TextButton(onClick=onAccounts){Text("Настройки ящика")}
                else if(!running)TextButton(onClick=onRetry,enabled=!s.busy){Text(if(a.status=="uncertain")"Проверил, повторить" else "Повторить")}
                if(!running)TextButton(onClick=onCancel,enabled=!s.busy){Text(if(a.kind==ActionKind.SEND)"В черновики" else "Отменить")}
            }
        }
    }
}
