@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package ru.liftzip.mail

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.runtime.State
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowDownward
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.getValue
import kotlinx.coroutines.flow.distinctUntilChanged
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.MutableFloatState
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalConfiguration
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
    private val onHide: ()->Unit,private val onPull: ()->Unit,private val listState: LazyListState?=null): NestedScrollConnection {
    override fun onPreScroll(available: Offset,source: NestedScrollSource): Offset {
        if(source==NestedScrollSource.UserInput)controller.endFling()
        val before=controller.fraction
        val force=forced()
        val alignment=if(!force && listState!=null)InboxInteractionRules.revealAlignment(available.y,before,listState.firstVisibleItemScrollOffset)else 0f
        // dispatchRawDelta handles only this row fragment; the remaining motion is still
        // distributed through nested scroll, so neither the row nor the header moves twice.
        val aligned=if(alignment>0f && listState!=null)-listState.dispatchRawDelta(-alignment)else 0f
        val consumed=controller.scroll(available.y-aligned,forced=force,userInput=source==NestedScrollSource.UserInput)
        progress.floatValue=controller.fraction
        if(before==1f && controller.fraction<1f)onHide()
        return Offset(0f,aligned+consumed)
    }
    override fun onPostScroll(consumed: Offset,available: Offset,source: NestedScrollSource): Offset {
        if(source==NestedScrollSource.UserInput && available.y>0 && canPull() && !refreshing() && controller.fraction>=1f)onPull()
        return Offset.Zero
    }
    override suspend fun onPreFling(available: Velocity): Velocity {controller.beginFling(available.y);return Velocity.Zero}
    override suspend fun onPostFling(consumed: Velocity,available: Velocity): Velocity {controller.endFling();return Velocity.Zero}
}

/** Stable insets are read by LazyColumn in measurement, never by the screen's composition. */
@Composable fun InboxViewport(fraction: ()->Float,onHeaderMeasured: (Float)->Unit,modifier: Modifier=Modifier,header: @Composable ()->Unit,bottom: @Composable (contentAlpha: ()->Float)->Unit,
    list: @Composable (bottomPadding: PaddingValues)->Unit,onPanelsMeasured: (InboxPanelMetrics)->Unit={},endInset: Dp=0.dp) {
    val density=LocalDensity.current
    val headerHeight=remember{mutableIntStateOf(0)}
    val bottomHeight=remember{mutableIntStateOf(0)}
    val insets=remember(fraction,density,endInset){object: PaddingValues {
        override fun calculateTopPadding()=0.dp
        override fun calculateLeftPadding(layoutDirection: LayoutDirection)=0.dp
        override fun calculateRightPadding(layoutDirection: LayoutDirection)=0.dp
        override fun calculateBottomPadding(): Dp {
            val progress=fraction().coerceIn(0f,1f)
            return with(density){((headerHeight.intValue+bottomHeight.intValue)*progress).toDp()}+endInset*(1f-progress)+12.dp
        }
    }}
    Layout(modifier=modifier.clipToBounds(),content={
        Box(Modifier.graphicsLayer{alpha=fraction().coerceIn(0f,1f)}){header()}
        Box(Modifier.background(MaterialTheme.colorScheme.surface).clipToBounds()){list(insets)}
        Box{bottom(fraction)}
    }){children,constraints->
        val width=constraints.maxWidth;val height=constraints.maxHeight
        val panelConstraints=constraints.copy(minWidth=width,minHeight=0)
        val top=children[0].measure(panelConstraints)
        val controls=children[2].measure(panelConstraints)
        headerHeight.intValue=top.height;bottomHeight.intValue=controls.height
        onHeaderMeasured(top.height.toFloat())
        onPanelsMeasured(InboxPanelMetrics(height,top.height,controls.height,with(density){12.dp.roundToPx()}))
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

/** Each control keeps a solid neutral backplate while its contents fade. This prevents
 * list glyphs mixing with navigation labels or the floating compose button. */
@Composable fun InboxFadingControl(fraction: ()->Float,modifier: Modifier=Modifier,shape: Shape=RectangleShape,content: @Composable ()->Unit) {
    Box(modifier.background(MaterialTheme.colorScheme.surface,shape)) {
        Box(Modifier.graphicsLayer{alpha=fraction().coerceIn(0f,1f)}){content()}
    }
}

private data class InboxContentSample(val total: Int,val available: Int,val rows: List<Pair<Any,Int>>)

/** Observes row sizes and fixed panel geometry, never scroll pixels or the fade fraction. */
@Composable fun ObserveInboxScrollability(list: LazyListState,expectedKeys: Set<Any>,contentIdentity: Any,metrics: State<InboxPanelMetrics?>,onScrollable: (Boolean)->Unit) {
    val width=LocalConfiguration.current.screenWidthDp
    val measure=remember(contentIdentity,expectedKeys,width){InboxContentMeasure(expectedKeys)}
    val changed by rememberUpdatedState(onScrollable)
    LaunchedEffect(list,measure,metrics) {
        snapshotFlow {
            val layout=list.layoutInfo
            InboxContentSample(layout.totalItemsCount,metrics.value?.contentHeight?:0,layout.visibleItemsInfo.map{it.key to it.size})
        }.distinctUntilChanged().collect{sample->measure.observe(sample.total,sample.available,sample.rows)?.let{changed(it)}}
    }
}

/** Watch only the resting boundary; animation pixels must not invalidate the entire inbox. */
@Composable fun ObservePullRefresh(state: PullToRefreshState,refreshing: Boolean,onRefreshing: ()->Unit,onRest: ()->Unit) {
    val started by rememberUpdatedState(onRefreshing)
    val rested by rememberUpdatedState(onRest)
    LaunchedEffect(state,refreshing) {
        if(refreshing)started()
        snapshotFlow{state.distanceFraction<0.02f}.distinctUntilChanged().collect{atRest->if(!refreshing && atRest)rested()}
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
