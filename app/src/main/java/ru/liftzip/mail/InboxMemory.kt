package ru.liftzip.mail

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.saveable.listSaver

class InboxMemory(val list: LazyListState = LazyListState(),var restore: InboxPosition? = null) {
    var pinned = restore?.atTop ?: true
    var firstId: String? = null
    fun position()=InboxPosition(list.layoutInfo.visibleItemsInfo.firstOrNull()?.key as? String,
        list.firstVisibleItemIndex,list.firstVisibleItemScrollOffset)
}

// Kept above navigation: each mailbox/folder owns a live list state even while its reader is open.
val InboxMemorySaver=listSaver<MutableMap<String,InboxMemory>,Any>(
    save={map->map.flatMap{(key,memory)->
        val p=memory.restore?:memory.position()
        listOf(key,p.id.orEmpty(),p.index,p.offset)
    }},
    restore={values->values.chunked(4).associate{row->
        val p=InboxPosition((row[1] as String).ifEmpty{null},row[2] as Int,row[3] as Int)
        row[0] as String to InboxMemory(LazyListState(p.index,p.offset),p)
    }.toMutableMap()}
)
