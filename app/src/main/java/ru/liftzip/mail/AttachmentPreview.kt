@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package ru.liftzip.mail

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color as AndroidColor
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.ChevronLeft
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private data class RenderedAttachment(val bitmap: Bitmap?=null,val pages: Int=1,val error: String?=null)

private fun renderAttachment(context: Context, file: OpenedAttachment, page: Int): RenderedAttachment {
    val uri=Uri.parse(file.uri)
    if(file.mime=="application/pdf") {
        val descriptor=context.contentResolver.openFileDescriptor(uri,"r")?:error("Файл недоступен.")
        descriptor.use { fd->PdfRenderer(fd).use { renderer->
            check(renderer.pageCount>0){"В PDF нет страниц."}
            renderer.openPage(page.coerceIn(0,renderer.pageCount-1)).use { pdf->
                check(pdf.width>0 && pdf.height>0){"Не удалось определить размер страницы."}
                val scale=minOf(2048f/pdf.width,2048f/pdf.height,3f)
                val bitmap=Bitmap.createBitmap((pdf.width*scale).toInt().coerceAtLeast(1),(pdf.height*scale).toInt().coerceAtLeast(1),Bitmap.Config.ARGB_8888)
                bitmap.eraseColor(AndroidColor.WHITE)
                pdf.render(bitmap,null,null,PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                return RenderedAttachment(bitmap,renderer.pageCount)
            }
        } }
    }
    val bounds=BitmapFactory.Options().apply{inJustDecodeBounds=true}
    context.contentResolver.openInputStream(uri)?.use{BitmapFactory.decodeStream(it,null,bounds)}
    check(bounds.outWidth>0 && bounds.outHeight>0){"Этот формат изображения не поддерживается встроенным просмотром."}
    var sample=1
    while(bounds.outWidth.toLong()*bounds.outHeight/(sample.toLong()*sample)>4_000_000L || maxOf(bounds.outWidth,bounds.outHeight)/sample>4096)sample*=2
    val options=BitmapFactory.Options().apply{inSampleSize=sample}
    val bitmap=context.contentResolver.openInputStream(uri)?.use{BitmapFactory.decodeStream(it,null,options)}?:error("Не удалось открыть изображение.")
    return RenderedAttachment(bitmap)
}

@Composable fun AttachmentPreviewScreen(file: OpenedAttachment,onBack: ()->Unit,onExternal: ()->Unit) {
    val context=LocalContext.current
    var page by rememberSaveable(file.uri){mutableIntStateOf(0)}
    val rendered by produceState<RenderedAttachment?>(null,file.uri,page) {
        value=null
        value=withContext(Dispatchers.IO) {
            try { renderAttachment(context,file,page) }
            catch(e: CancellationException){throw e}
            catch(e: Exception){RenderedAttachment(error=e.message?:"Не удалось открыть файл.")}
        }
    }
    Scaffold(topBar={TopAppBar(title={Text(file.name,maxLines=1)},navigationIcon={IconButton(onClick=onBack){Icon(Icons.AutoMirrored.Outlined.ArrowBack,"Назад к письму")}},actions={IconButton(onClick=onExternal){Icon(Icons.AutoMirrored.Outlined.OpenInNew,"Открыть в другом приложении")}})},
        bottomBar={if(file.mime=="application/pdf" && (rendered?.pages?:0)>1)Surface{Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(8.dp),horizontalArrangement=Arrangement.Center,verticalAlignment=Alignment.CenterVertically){
            IconButton(onClick={page--},enabled=page>0){Icon(Icons.Outlined.ChevronLeft,"Предыдущая страница")}
            Text("${page+1} / ${rendered!!.pages}",Modifier.padding(horizontal=20.dp))
            IconButton(onClick={page++},enabled=page<(rendered?.pages?:1)-1){Icon(Icons.Outlined.ChevronRight,"Следующая страница")}
        }}}) { padding->
        Box(Modifier.padding(padding).fillMaxSize().background(Color(0xFF252825)).clipToBounds(),contentAlignment=Alignment.Center) {
            val item=rendered
            when {
                item==null->CircularProgressIndicator()
                item.bitmap!=null->{
                    var zoom by remember(file.uri,page){mutableFloatStateOf(1f)}
                    var x by remember(file.uri,page){mutableFloatStateOf(0f)}
                    var y by remember(file.uri,page){mutableFloatStateOf(0f)}
                    Image(item.bitmap.asImageBitmap(),file.name,contentScale=ContentScale.Fit,modifier=Modifier.fillMaxSize()
                        .pointerInput(file.uri,page){detectTransformGestures { _,pan,scale,_->zoom=(zoom*scale).coerceIn(1f,5f);x=if(zoom==1f)0f else (x+pan.x).coerceIn(-size.width*(zoom-1)/2,size.width*(zoom-1)/2);y=if(zoom==1f)0f else (y+pan.y).coerceIn(-size.height*(zoom-1)/2,size.height*(zoom-1)/2)}}
                        .graphicsLayer(scaleX=zoom,scaleY=zoom,translationX=x,translationY=y))
                }
                else->Column(Modifier.padding(24.dp),horizontalAlignment=Alignment.CenterHorizontally){
                    Text(item.error.orEmpty(),color=Color.White)
                    Spacer(Modifier.height(20.dp))
                    Button(onClick=onExternal){Text("Открыть в другом приложении")}
                }
            }
        }
    }
}
