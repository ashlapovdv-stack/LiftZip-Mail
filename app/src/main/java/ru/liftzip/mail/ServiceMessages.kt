@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package ru.liftzip.mail

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import java.util.UUID

data class ServiceNotice(val text: String,val error: Boolean=false,val id: String=UUID.randomUUID().toString())

class NoticeVisuals(val notice: ServiceNotice): SnackbarVisuals {
    override val message=notice.text
    override val actionLabel: String?=null
    override val withDismissAction=false
    override val duration=if(notice.error)SnackbarDuration.Long else SnackbarDuration.Short
}

/** Dismissing a card only completes SnackbarHostState.showSnackbar; it never cancels a mail job. */
@Composable fun ServiceSnackbar(data: SnackbarData) {
    key(data.visuals) {
        val swipe=rememberSwipeToDismissBoxState()
        LaunchedEffect(swipe.currentValue){if(swipe.currentValue!=SwipeToDismissBoxValue.Settled)data.dismiss()}
        val error=(data.visuals as? NoticeVisuals)?.notice?.error==true
        val colors=MaterialTheme.colorScheme
        Box(Modifier.fillMaxWidth(),contentAlignment=Alignment.Center) {
        SwipeToDismissBox(state=swipe,backgroundContent={},modifier=Modifier.widthIn(max=300.dp)) {
            Surface(shape=RoundedCornerShape(18.dp),modifier=Modifier.semantics{
                customActions=listOf(CustomAccessibilityAction("Скрыть сообщение"){data.dismiss();true})
            },
                color=if(error)colors.errorContainer else colors.secondaryContainer,
                contentColor=if(error)colors.onErrorContainer else colors.onSecondaryContainer) {
                Text(data.visuals.message,Modifier.padding(horizontal=16.dp,vertical=11.dp),style=MaterialTheme.typography.bodyMedium)
            }
        }
        }
    }
}
