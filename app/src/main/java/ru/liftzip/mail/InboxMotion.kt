@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package ru.liftzip.mail

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowDownward
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.MutableFloatState
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/** Distinguishes native fling deltas from unrelated programmatic positioning. */
class InboxScrollConnection(private val controller: InboxChromeController,private val progress: MutableFloatState,
    private val forced: ()->Boolean,private val canPull: ()->Boolean,private val refreshing: ()->Boolean,
    private val onHide: ()->Unit,private val onPull: ()->Unit): NestedScrollConnection {
    override fun onPreScroll(available: Offset,source: NestedScrollSource): Offset {
        if(source==NestedScrollSource.UserInput)controller.endFling()
        val before=controller.fraction
        val consumed=controller.scroll(available.y,forced=forced(),userInput=source==NestedScrollSource.UserInput)
        progress.floatValue=controller.fraction
        if(before==1f && controller.fraction<1f)onHide()
        return Offset(0f,consumed)
    }
    override fun onPostScroll(consumed: Offset,available: Offset,source: NestedScrollSource): Offset {
        if(source==NestedScrollSource.UserInput && available.y>0 && canPull() && !refreshing() && controller.fraction>=1f)onPull()
        return Offset.Zero
    }
    override suspend fun onPreFling(available: Velocity): Velocity {controller.beginFling(available.y);return Velocity.Zero}
    override suspend fun onPostFling(consumed: Velocity,available: Velocity): Velocity {controller.endFling();return Velocity.Zero}
}

/** Stable insets are read by LazyColumn in measurement, never by the screen's composition. */
@Composable fun InboxViewport(fraction: ()->Float,onHeaderMeasured: (Float)->Unit,modifier: Modifier=Modifier,header: @Composable ()->Unit,bottom: @Composable ()->Unit,
    list: @Composable (bottomPadding: PaddingValues)->Unit) {
    val density=LocalDensity.current
    val headerHeight=remember{mutableIntStateOf(0)}
    val bottomHeight=remember{mutableIntStateOf(0)}
    val insets=remember(fraction,density){object: PaddingValues {
        override fun calculateTopPadding()=0.dp
        override fun calculateLeftPadding(layoutDirection: LayoutDirection)=0.dp
        override fun calculateRightPadding(layoutDirection: LayoutDirection)=0.dp
        override fun calculateBottomPadding()=with(density){(headerHeight.intValue*fraction().coerceIn(0f,1f)+bottomHeight.intValue).toDp()+12.dp}
    }}
    Layout(modifier=modifier.clipToBounds(),content={
        Box(Modifier.graphicsLayer{alpha=fraction().coerceIn(0f,1f)}){header()}
        Box{list(insets)}
        Box(Modifier.graphicsLayer{alpha=fraction().coerceIn(0f,1f)}){bottom()}
    }){children,constraints->
        val width=constraints.maxWidth;val height=constraints.maxHeight
        val panelConstraints=constraints.copy(minWidth=width,minHeight=0)
        val top=children[0].measure(panelConstraints)
        val controls=children[2].measure(panelConstraints)
        headerHeight.intValue=top.height;bottomHeight.intValue=controls.height
        onHeaderMeasured(top.height.toFloat())
        val mail=children[1].measure(Constraints.fixed(width,height))
        layout(width,height) {
            val progress=fraction().coerceIn(0f,1f)
            val geometry=InboxInteractionRules.viewport(height,top.height,controls.height,progress)
            if(progress>0.001f)top.placeRelative(0,0,zIndex=0f)
            mail.placeRelative(0,geometry.listY,zIndex=1f)
            if(progress>0.001f)controls.placeRelative(0,geometry.bottomY,zIndex=2f)
        }
    }
}

/** Appears only before release. Refresh itself uses the thin, reserved toolbar progress strip. */
@Composable fun PullRefreshHint(distance: Float,refreshing: Boolean,accepted: Boolean,gestureArmed: Boolean,modifier: Modifier=Modifier) {
    val progress=InboxInteractionRules.pullProgress(distance,refreshing,accepted,gestureArmed)
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
