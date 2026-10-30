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
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

private enum class InboxSlot { HEADER, BOTTOM, LIST }

/** The list keeps its full viewport throughout the drag; only its position changes.
 * It is drawn over the fading header. Bottom controls keep their position and only fade. */
@Composable fun InboxViewport(fraction: ()->Float,onHeaderMeasured: (Float)->Unit,modifier: Modifier=Modifier,header: @Composable ()->Unit,bottom: @Composable ()->Unit,
    list: @Composable (bottomPadding: Dp)->Unit) {
    SubcomposeLayout(modifier.clipToBounds()){constraints->
        val width=constraints.maxWidth;val height=constraints.maxHeight
        val panelConstraints=constraints.copy(minWidth=width,minHeight=0)
        val top=subcompose(InboxSlot.HEADER){Box(Modifier.graphicsLayer{alpha=fraction().coerceIn(0f,1f)}){header()}}.single().measure(panelConstraints)
        // This only updates the input controller's range; rendering uses this measurement directly.
        onHeaderMeasured(top.height.toFloat())
        val controls=subcompose(InboxSlot.BOTTOM){Box(Modifier.graphicsLayer{alpha=fraction().coerceIn(0f,1f)}){bottom()}}.single().measure(panelConstraints)
        val progress=fraction().coerceIn(0f,1f)
        val geometry=InboxInteractionRules.viewport(height,top.height,controls.height,progress)
        val mail=subcompose(InboxSlot.LIST){list((geometry.listBottomPadding+12.dp.roundToPx()).toDp())}.single().measure(Constraints.fixed(width,geometry.listHeight))
        layout(width,height) {
            if(progress>0.001f)top.placeRelative(0,0)
            mail.placeRelative(0,geometry.listY)
            if(progress>0.001f)controls.placeRelative(0,geometry.bottomY)
        }
    }
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
