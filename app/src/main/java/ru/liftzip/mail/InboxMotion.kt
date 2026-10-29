@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package ru.liftzip.mail

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowDownward
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/** Both panels use the same tween, including their space in Scaffold. No separate springs. */
@Composable fun SlidingInboxPanel(fraction: ()->Float,top: Boolean,modifier: Modifier=Modifier,content: @Composable ()->Unit) {
    Box(modifier.clipToBounds().layout{measurable,constraints->
        val child=measurable.measure(constraints.copy(minHeight=0))
        val height=InboxInteractionRules.panelHeight(child.height,fraction())
        layout(child.width,height){child.placeRelative(0,if(top)height-child.height else 0)}
    }.graphicsLayer{alpha=fraction().coerceIn(0f,1f)}){content()}
}

/** Appears only before release. Refresh itself uses the thin, reserved toolbar progress strip. */
@Composable fun PullRefreshHint(distance: Float,refreshing: Boolean,accepted: Boolean,modifier: Modifier=Modifier) {
    val progress=InboxInteractionRules.pullProgress(distance,refreshing,accepted)
    if(progress<=0.02f)return
    val ready=distance>=1f
    Surface(modifier.padding(top=8.dp).offset(y=(16*progress).dp).size(34.dp).graphicsLayer{alpha=progress},
        shape=CircleShape,color=MaterialTheme.colorScheme.secondaryContainer,shadowElevation=2.dp) {
        Box(Modifier.semantics{contentDescription=if(ready)"Отпустите для обновления. Верните палец вверх, чтобы отменить." else "Потяните для обновления"},contentAlignment=Alignment.Center) {
            CircularProgressIndicator(progress={progress},modifier=Modifier.size(29.dp),strokeWidth=2.dp)
            Icon(if(ready)Icons.Outlined.Refresh else Icons.Outlined.ArrowDownward,null,Modifier.size(16.dp),tint=MaterialTheme.colorScheme.onSecondaryContainer)
        }
    }
}
