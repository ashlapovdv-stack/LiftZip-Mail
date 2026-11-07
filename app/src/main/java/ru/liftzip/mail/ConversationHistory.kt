package ru.liftzip.mail

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable fun ConversationHistory(id: String,conversation: QuotedConversation,ownAddresses: List<String>) {
    var expanded by rememberSaveable(id){mutableStateOf(false)}
    var original by rememberSaveable(id){mutableStateOf(false)}
    var collapsed by remember(id){mutableStateOf(emptySet<Int>())}
    OutlinedButton(onClick={if(!expanded)collapsed=emptySet();expanded=!expanded},modifier=Modifier.padding(bottom=8.dp)) {
        Icon(Icons.Outlined.MoreHoriz,null);Spacer(Modifier.width(8.dp))
        Text(if(expanded)"Скрыть историю · ${conversation.messages.size}" else "Предыдущие сообщения · ${conversation.messages.size}")
    }
    if(expanded) {
        Text("От ближайшего ответа к более ранним",style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant,modifier=Modifier.padding(bottom=10.dp))
        conversation.messages.forEachIndexed { index,message->
            key(id,index) { QuotedMessageCard(message,index,ownAddresses,index !in collapsed){collapsed=if(index in collapsed)collapsed-index else collapsed+index} }
        }
        TextButton(onClick={original=!original}){Text(if(original)"Скрыть исходный текст" else "Показать полный исходный текст")}
        if(original)SelectionContainer{Text(conversation.original,style=MaterialTheme.typography.bodyMedium,modifier=Modifier.fillMaxWidth().padding(vertical=12.dp))}
    }
}

@Composable private fun QuotedMessageCard(message: QuotedMessage,index: Int,ownAddresses: List<String>,expanded: Boolean,onToggle: ()->Unit) {
    val own=message.isOwn(ownAddresses)
    val colors=MaterialTheme.colorScheme
    Surface(shape=RoundedCornerShape(16.dp),color=if(own)colors.primaryContainer.copy(alpha=0.45f)else colors.surfaceContainer,
        modifier=Modifier.fillMaxWidth().padding(bottom=8.dp)) {
        Column {
            Row(Modifier.fillMaxWidth().clickable(onClick=onToggle).semantics{stateDescription=if(expanded)"Развёрнуто" else "Свёрнуто"}.padding(14.dp),verticalAlignment=Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("${index+1} · "+(if(own)"Ваш ответ" else "Предыдущее сообщение"),style=MaterialTheme.typography.labelSmall,color=colors.primary)
                    Text(message.sender?:"Отправитель не указан",fontWeight=FontWeight.SemiBold,style=MaterialTheme.typography.bodyMedium,maxLines=2,overflow=TextOverflow.Ellipsis)
                    message.date?.let{Text(it,style=MaterialTheme.typography.labelSmall,color=colors.onSurfaceVariant)}
                    if(message.sender==null && message.date==null)message.attribution?.let{Text(it,style=MaterialTheme.typography.labelSmall,color=colors.onSurfaceVariant)}
                    if(!expanded)Text(message.body.replace('\n',' ').trim().ifBlank{"Без текста"},style=MaterialTheme.typography.bodySmall,color=colors.onSurfaceVariant,maxLines=2,overflow=TextOverflow.Ellipsis,modifier=Modifier.padding(top=5.dp))
                }
                Icon(if(expanded)Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,if(expanded)"Свернуть сообщение" else "Развернуть сообщение")
            }
            if(expanded) {
                HorizontalDivider(Modifier.padding(horizontal=14.dp))
                SelectionContainer{Text(message.body.ifBlank{"Нет текстового содержимого."},style=MaterialTheme.typography.bodyLarge,lineHeight=25.sp,modifier=Modifier.fillMaxWidth().padding(14.dp))}
            }
        }
    }
}
