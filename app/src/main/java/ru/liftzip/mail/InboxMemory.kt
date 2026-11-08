package ru.liftzip.mail

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.mutableFloatStateOf

class InboxMemory(val list: LazyListState = LazyListState(),var restore: InboxPosition? = null,initialControls: Float=1f) {
    var pinned = restore?.atTop ?: true
    var firstId: String? = null
    val controlsProgress=mutableFloatStateOf(initialControls.coerceIn(0f,1f))
    fun position()=InboxPosition(list.layoutInfo.visibleItemsInfo.firstOrNull()?.key as? String,
        list.firstVisibleItemIndex,list.firstVisibleItemScrollOffset)
    fun rememberPosition(){restore=position()}
}

// Kept above navigation: each mailbox/folder owns a live list state even while its reader is open.
val InboxMemorySaver=listSaver<MutableMap<String,InboxMemory>,Any>(
    save={map->listOf("__inbox_v2__")+map.flatMap{(key,memory)->
        val p=memory.restore?:memory.position()
        listOf(key,p.id.orEmpty(),p.index,p.offset,memory.controlsProgress.floatValue)
    }},
    restore={values->val versioned=values.firstOrNull()=="__inbox_v2__";val rows=if(versioned)values.drop(1).chunked(5)else values.chunked(4)
        rows.filter{it.size==(if(versioned)5 else 4)}.associate{row->
        val p=InboxPosition((row[1] as String).ifEmpty{null},row[2] as Int,row[3] as Int)
        row[0] as String to InboxMemory(LazyListState(p.index,p.offset),p,if(versioned)(row[4] as Number).toFloat()else 1f)
    }.toMutableMap()}
)
