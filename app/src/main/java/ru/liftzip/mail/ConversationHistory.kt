package ru.liftzip.mail

import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** One neutral capsule for every source format; count and expansion stay accessible. */
@Composable internal fun HistoryToggle(count: Int,expanded: Boolean,onClick: ()->Unit,modifier: Modifier=Modifier) {
    val interaction=remember{MutableInteractionSource()}
    val ink=Color(0xFF191919)
    Box(modifier.sizeIn(minWidth=56.dp,minHeight=48.dp)
        .clickable(interactionSource=interaction,indication=null,role=Role.Button,onClick=onClick)
        .semantics{contentDescription="Предыдущие сообщения: $count";stateDescription=if(expanded)"Развернуто" else "Свернуто"}) {
        Box(Modifier.padding(top=12.dp)) {
        Row(Modifier.heightIn(min=28.dp).widthIn(min=52.dp).clip(RoundedCornerShape(percent=50))
            .background(Color(0xFFEEEEEE)).testTag("history-capsule").padding(horizontal=10.dp),
            verticalAlignment=Alignment.CenterVertically) {
            Icon(Icons.Outlined.MoreHoriz,null,Modifier.size(18.dp),tint=ink)
            Spacer(Modifier.width(4.dp))
            Text(count.toString(),color=ink,fontSize=14.sp,lineHeight=20.sp)
        }
        }
    }
}

@Composable fun ConversationHistory(id: String,conversation: QuotedConversation,ownAddresses: List<String>) {
    var expanded by remember(id,conversation.original){mutableStateOf(false)}
    if(conversation.messages.isNotEmpty())HistoryToggle(conversation.messages.size,expanded,{expanded=!expanded})
    if(expanded) {
        QuotedHistoryContent(conversation)

    }
}

@Composable internal fun QuotedHistoryContent(conversation: QuotedConversation) {

        SelectionContainer {
            Column(Modifier.fillMaxWidth()) {
                conversation.messages.forEachIndexed { index,message->
                    val heading=message.attribution?.takeIf{it.isNotBlank()}?:listOfNotNull(message.date,message.sender?.let{"от $it"}).joinToString(" ").let{if(it.isNotBlank())"$it:" else "Предыдущее сообщение:"}
                    val lineColor=androidx.compose.ui.graphics.Color(0xFFA58AC7)
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
}
