package ru.liftzip.mail

import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable internal fun AttachmentThumbnails(files: List<Attachment>,enabled: Boolean,load: suspend (Attachment)->OpenedAttachment,onOpen: (Attachment)->Unit,onDownload: (Attachment)->Unit) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(bottom=8.dp),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
        files.forEachIndexed { index,file->
            val context=LocalContext.current
            val bitmap by produceState<android.graphics.Bitmap?>(null,file.path,file.size,enabled) {
                if(!enabled || index>=6 || file.size<0 || file.size>2*1024*1024)return@produceState
                value=try {
                    val opened=load(file)
                    withContext(Dispatchers.IO) {
                        val uri=Uri.parse(opened.uri)
                        val bounds=BitmapFactory.Options().apply{inJustDecodeBounds=true}
                        context.contentResolver.openInputStream(uri)?.use{BitmapFactory.decodeStream(it,null,bounds)}
                        if(bounds.outWidth<=0 || bounds.outHeight<=0 || bounds.outWidth.toLong()*bounds.outHeight>100_000_000L)null else {
                            var sample=1
                            while(maxOf(bounds.outWidth,bounds.outHeight)/sample>320)sample*=2
                            val options=BitmapFactory.Options().apply{inSampleSize=sample}
                            context.contentResolver.openInputStream(uri)?.use{BitmapFactory.decodeStream(it,null,options)}
                        }
                    }
                }catch(e: CancellationException){throw e}catch(_: Exception){null}
            }
            Column(Modifier.width(112.dp)) {
                Box(Modifier.size(width=112.dp,height=84.dp).clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                    .semantics{contentDescription="Открыть изображение ${file.name}";stateDescription=if(bitmap==null)"Миниатюра недоступна" else "Миниатюра загружена"}.clickable(enabled=enabled){onOpen(file)},contentAlignment=Alignment.Center) {
                    val image=bitmap
                    if(image!=null)Image(image.asImageBitmap(),null,Modifier.fillMaxSize(),contentScale=ContentScale.Crop)
                    else Icon(Icons.Outlined.Image,null,tint=MaterialTheme.colorScheme.onSurfaceVariant)
                    if(file.size>0)Text("${(file.size.toLong()+1023)/1024} КБ",Modifier.align(Alignment.BottomStart).background(Color.Black.copy(alpha=0.55f)).padding(horizontal=5.dp,vertical=2.dp),color=Color.White,style=MaterialTheme.typography.labelSmall)
                }
                Row(verticalAlignment=Alignment.CenterVertically) {
                    Text(file.name,style=MaterialTheme.typography.labelSmall,maxLines=1,overflow=TextOverflow.Ellipsis,modifier=Modifier.weight(1f))
                    IconButton(onClick={onDownload(file)},enabled=enabled,modifier=Modifier.size(32.dp)) {Icon(Icons.Outlined.Download,"Сохранить ${file.name}",Modifier.size(18.dp))}
                }
            }
        }
    }
}
