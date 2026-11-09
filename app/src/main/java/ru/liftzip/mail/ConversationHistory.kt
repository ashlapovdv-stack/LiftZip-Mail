package ru.liftzip.mail

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Show the quoted chain in its original order: nearest message first, then older ones. */
@Composable fun ConversationHistory(id: String,conversation: QuotedConversation,ownAddresses: List<String>) {
    var expanded by rememberSaveable(id){mutableStateOf(false)}
    var original by rememberSaveable(id){mutableStateOf(false)}
    if(conversation.messages.isNotEmpty())Box(Modifier.sizeIn(minWidth=64.dp,minHeight=48.dp).clip(RoundedCornerShape(24.dp)).clickable{expanded=!expanded}.semantics{
        contentDescription="Предыдущие сообщения: ${conversation.messages.size}"
        stateDescription=if(expanded)"Развернуто" else "Свернуто"
    },contentAlignment=Alignment.CenterStart) {
        Surface(shape=RoundedCornerShape(19.dp),color=MaterialTheme.colorScheme.surfaceContainerHigh) {
            Row(Modifier.height(38.dp).padding(horizontal=12.dp),verticalAlignment=Alignment.CenterVertically) {
                Icon(Icons.Outlined.MoreHoriz,null,Modifier.size(22.dp));Spacer(Modifier.width(5.dp));Text(conversation.messages.size.toString())
            }
        }
    }
    if(expanded) {
        SelectionContainer {
            Column(Modifier.fillMaxWidth()) {
                conversation.messages.forEachIndexed { index,message->
                    val heading=message.attribution?.takeIf{it.isNotBlank()}?:listOfNotNull(message.date,message.sender?.let{"от $it"}).joinToString(" ").let{if(it.isNotBlank())"$it:" else "Предыдущее сообщение:"}
                    val lineColor=MaterialTheme.colorScheme.primary.copy(alpha=0.7f)
                    Row(Modifier.fillMaxWidth().padding(start=(index.coerceAtMost(4)*8).dp,bottom=18.dp).drawBehind{drawLine(lineColor,androidx.compose.ui.geometry.Offset(1.dp.toPx(),0f),androidx.compose.ui.geometry.Offset(1.dp.toPx(),size.height),2.dp.toPx())}) {
                        Column(Modifier.weight(1f).padding(start=10.dp)) {
                            Text(heading,style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
                            if(message.recipients!=null || message.subject!=null) {
                                message.sender?.let{Text("От: $it",style=MaterialTheme.typography.bodyMedium)}
                                message.recipients?.let{Text("Кому: $it",style=MaterialTheme.typography.bodyMedium)}
                                message.date?.let{Text("Дата: $it",style=MaterialTheme.typography.bodyMedium)}
                                message.subject?.let{Text("Тема: $it",style=MaterialTheme.typography.bodyMedium)}
                            }
                            Spacer(Modifier.height(8.dp))
                            Text(message.body.ifBlank{"Нет текстового содержимого."},style=MaterialTheme.typography.bodyLarge,lineHeight=25.sp)
                        }
                    }
                }
            }
        }
        TextButton(onClick={original=!original}){Text(if(original)"Скрыть исходный текст" else "Показать полный исходный текст")}
        if(original)SelectionContainer{Text(conversation.original,style=MaterialTheme.typography.bodyMedium,modifier=Modifier.fillMaxWidth().padding(vertical=12.dp))}
    }
}
