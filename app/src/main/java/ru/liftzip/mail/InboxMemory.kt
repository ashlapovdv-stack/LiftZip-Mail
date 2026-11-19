package ru.liftzip.mail

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf

internal class InboxRows(val items: List<MailItem>) {
    val ids=items.map{it.id}
    val byId=items.associateBy{it.id}
    val unread=items.count{it.unread}
}

class InboxMemory(val list: LazyListState = LazyListState(),var restore: InboxPosition? = null,initialControls: Float=1f,initialBottomVisible: Boolean=initialControls>0f) {
    internal var rows: InboxRows? = null
    val visibleRows: List<MailItem>? get()=rows?.items
    var pinned = restore?.atTop ?: true
    var firstId: String? = null
    val bottomVisible=mutableStateOf(initialBottomVisible)
    val controlsProgress=mutableFloatStateOf(initialControls.coerceIn(0f,1f))
    fun position()=InboxPosition(list.layoutInfo.visibleItemsInfo.firstOrNull()?.key as? String,
        list.firstVisibleItemIndex,list.firstVisibleItemScrollOffset)
    fun rememberPosition(){restore=position()}
}

// Kept above navigation: each mailbox/folder owns a live list state even while its reader is open.
val InboxMemorySaver=listSaver<MutableMap<String,InboxMemory>,Any>(
    save={map->listOf("__inbox_v3__")+map.flatMap{(key,memory)->
        val p=memory.restore?:memory.position()
        listOf(key,p.id.orEmpty(),p.index,p.offset,memory.controlsProgress.floatValue,memory.bottomVisible.value)
    }},
    restore={values->
        val rowSize=when(values.firstOrNull()){"__inbox_v3__"->6;"__inbox_v2__"->5;else->4}
        val rows=(if(rowSize==4)values else values.drop(1)).chunked(rowSize)
        rows.filter{it.size==rowSize}.associate{row->
            val p=InboxPosition((row[1] as String).ifEmpty{null},row[2] as Int,row[3] as Int)
            val fraction=if(rowSize>=5)(row[4] as Number).toFloat()else 1f
            val bottomVisible=if(rowSize==6)row[5] as Boolean else fraction>0f
            row[0] as String to InboxMemory(LazyListState(p.index,p.offset),p,fraction,bottomVisible)
        }.toMutableMap()
    })
