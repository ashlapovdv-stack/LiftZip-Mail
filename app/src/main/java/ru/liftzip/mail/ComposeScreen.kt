@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package ru.liftzip.mail

import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

@Composable internal fun ComposeScreen(d: ComposeDraft,s: UiState,onChange: (ComposeDraft)->Unit,onBack: ()->Unit,onDelete: ()->Unit,onSend: ()->Unit,
    onRetryForward: ()->Unit,onRemoveForward: (String)->Unit,onNoticeAnchor: (Float?)->Unit,onDefaultAccount: (String?)->Unit={}) {
    val context=LocalContext.current
    var senderMenu by remember(d.id){mutableStateOf(false)}
    var more by remember(d.id){mutableStateOf(false)}
    var copies by remember(d.id){mutableStateOf(d.cc.isNotBlank() || d.bcc.isNotBlank())}
    var attachmentError by remember(d.id){mutableStateOf<String?>(null)}
    var confirmResend by remember(d.id){mutableStateOf(false)}
    val colors=MaterialTheme.colorScheme.copy(surface=Color.White,background=Color.White,onBackground=Color(0xFF303030),onSurface=Color(0xFF303030),onSurfaceVariant=Color(0xFF777777),surfaceContainer=Color.White)
    MaterialTheme(colorScheme=colors) {
    SystemBarIcons(forceLightSurface=true)
    if(confirmResend)AlertDialog(onDismissRequest={confirmResend=false},title={Text("Повторить отправку?")},text={Text("Результат предыдущей попытки неизвестен. Если письмо уже доставлено, получатель получит ещё одну копию.")},
        confirmButton={TextButton(onClick={confirmResend=false;onSend()}){Text("Отправить ещё раз")}},dismissButton={TextButton(onClick={confirmResend=false}){Text("Отмена")}})
    DisposableEffect(Unit){onDispose{onNoticeAnchor(null)}}
    val addFiles=rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()){uris->
        val files=uris.mapNotNull{uri->runCatching {
            context.contentResolver.takePersistableUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION)
            var name="Вложение";var size=-1L
            context.contentResolver.query(uri,arrayOf(OpenableColumns.DISPLAY_NAME,OpenableColumns.SIZE),null,null,null)?.use{c->if(c.moveToFirst()){name=c.getString(0)?:name;if(!c.isNull(1))size=c.getLong(1)}}
            OutgoingFile(uri.toString(),name,context.contentResolver.getType(uri)?:"application/octet-stream",size)
        }.getOrNull()}
        if(files.size!=uris.size)attachmentError="Некоторые файлы недоступны. Выберите их из папки на устройстве."
        else if(files.any{it.size>20L*1024*1024}||(d.filesToSend()+files).sumOf{it.size.coerceAtLeast(0)}>25L*1024*1024)attachmentError="До 20 МБ на файл и 25 МБ суммарно."
        else {attachmentError=null;onChange(d.copy(attachments=(d.attachments+files).distinctBy{it.uri}))}
    }
    Scaffold(containerColor=Color.White,modifier=Modifier.testTag("compose-screen"),topBar={
        TopAppBar(title={},colors=TopAppBarDefaults.topAppBarColors(containerColor=Color.White),navigationIcon={
            IconButton(onClick=onBack,enabled=!s.busy){Icon(Icons.AutoMirrored.Outlined.ArrowBack,"Сохранить черновик и закрыть")}
        },actions={
            IconButton(onClick={addFiles.launch(arrayOf("*/*"))},enabled=!s.busy){Icon(Icons.Outlined.AttachFile,"Добавить вложения")}
            IconButton(onClick={if(d.previousDeliveryUncertain)confirmResend=true else onSend()},enabled=!s.busy && d.resourcesReady()){
                Icon(Icons.AutoMirrored.Outlined.Send,"Отправить письмо")
            }
            Box {
                IconButton(onClick={more=true},enabled=!s.busy){Icon(Icons.Outlined.MoreVert,"Действия с черновиком")}
                DropdownMenu(expanded=more,onDismissRequest={more=false}) {
                    DropdownMenuItem(text={Text("Сохранить черновик")},onClick={more=false;onBack()})
                    DropdownMenuItem(text={Text("Сделать ящиком по умолчанию")},onClick={more=false;onDefaultAccount(d.accountId)})
                    DropdownMenuItem(text={Text("Удалить черновик")},onClick={more=false;onDelete()})
                }
            }
        })
    },bottomBar={Box(Modifier.fillMaxWidth().navigationBarsPadding().imePadding()){
        Spacer(Modifier.fillMaxWidth().height(1.dp).onGloballyPositioned{onNoticeAnchor(it.boundsInRoot().top)})
    }}) { padding->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())) {
            if(d.previousDeliveryUncertain)Text("Результат прошлой отправки неизвестен. Проверьте доставку перед повтором.",Modifier.padding(16.dp),color=colors.error)
            Box {
                Row(Modifier.fillMaxWidth().heightIn(min=56.dp).clickable(enabled=!s.busy){senderMenu=true}.padding(horizontal=16.dp),verticalAlignment=Alignment.CenterVertically) {
                    Text("От",Modifier.width(84.dp),color=colors.onSurfaceVariant,style=MaterialTheme.typography.bodyMedium)
                    Text(s.accounts.find{it.id==d.accountId}?.email.orEmpty(),Modifier.weight(1f),color=colors.onSurface)
                    Icon(Icons.Outlined.ExpandMore,"Выбрать отправителя",tint=colors.onSurfaceVariant)
                }
                DropdownMenu(expanded=senderMenu,onDismissRequest={senderMenu=false}){s.accounts.forEach{a->
                    DropdownMenuItem(text={Text(a.email+if(a.enabled)""else " · выключен")},enabled=a.enabled,onClick={onChange(d.copy(accountId=a.id));senderMenu=false})
                }}
            }
            ComposeDivider()
            ComposeField("Кому",d.to,{onChange(d.copy(to=it))},!s.busy,email=true,trailing={
                IconButton(onClick={copies=!copies},enabled=!s.busy,modifier=Modifier.semantics{stateDescription=if(copies)"Развернуто"else "Свернуто"}) {
                    Icon(if(copies)Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,"Копия и скрытая копия")
                }
            })
            if(copies) {
                ComposeField("Копия",d.cc,{onChange(d.copy(cc=it))},!s.busy,email=true)
                ComposeField("Скр. копия",d.bcc,{onChange(d.copy(bcc=it))},!s.busy,email=true)
            }
            ComposeField("Тема",d.subject,{onChange(d.copy(subject=it))},!s.busy)
            BasicTextField(d.body,{onChange(d.copy(body=it))},enabled=!s.busy,
                textStyle=MaterialTheme.typography.bodyLarge.copy(color=colors.onSurface),cursorBrush=SolidColor(colors.primary),
                keyboardOptions=KeyboardOptions(capitalization=KeyboardCapitalization.Sentences),
                modifier=Modifier.fillMaxWidth().heightIn(min=280.dp).padding(16.dp).testTag("compose-body"),
                decorationBox={inner->Box{if(d.body.isEmpty())Text("Текст письма",color=colors.onSurfaceVariant);inner()}})
            d.quote?.let{quote->
                Column(Modifier.fillMaxWidth().padding(horizontal=16.dp)) {
                    Row(verticalAlignment=Alignment.CenterVertically) {
                        Text(if(quote.kind=="forward")"Пересылаемое письмо"else "Цитируемое письмо",Modifier.weight(1f),style=MaterialTheme.typography.labelLarge)
                        TextButton(onClick={onChange(d.copy(quoteIncluded=!d.quoteIncluded))},enabled=!s.busy){Text(if(d.quoteIncluded)"Исключить цитату"else "Включить цитату")}
                    }
                    if(d.quoteIncluded)Row(Modifier.fillMaxWidth().padding(bottom=12.dp).height(IntrinsicSize.Min)) {
                        Box(Modifier.width(2.dp).fillMaxHeight().background(Color(0xFFA58AC7)))
                        Column(Modifier.padding(start=10.dp)) {
                            Text(quote.attribution(),style=MaterialTheme.typography.bodySmall,color=colors.onSurfaceVariant,maxLines=3,overflow=TextOverflow.Ellipsis)
                            Text(quote.plain,style=MaterialTheme.typography.bodyMedium,maxLines=6,overflow=TextOverflow.Ellipsis)
                            if(quote.html.isNotBlank())Text("Исходное HTML-оформление сохранено",style=MaterialTheme.typography.labelSmall,color=colors.onSurfaceVariant)
                        }
                    }
                }
            }
            if(d.selectedForwardFiles().isNotEmpty())Column(Modifier.fillMaxWidth().padding(16.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
                val files=d.selectedForwardFiles()
                Text(if(d.resourcesReady())"Вложения готовы · ${files.size}"else "Подготовка вложений · ${files.count{it.status==ForwardStatus.READY}} из ${files.size}",style=MaterialTheme.typography.labelLarge)
                files.forEach{file->ForwardFileCard(file,!s.busy,onRemove={onRemoveForward(file.key)},allowRemove=file.contentId.isBlank())}
                if(files.any{it.status==ForwardStatus.ERROR}) {
                    TextButton(onClick=onRetryForward,enabled=!s.busy){Text("Повторить подготовку")}
                    if(files.any{it.contentId.isNotBlank()})Text("Для отправки без встроенных изображений исключите цитату.",style=MaterialTheme.typography.bodySmall)
                }
            }
            Column(Modifier.padding(horizontal=16.dp)) {
                d.attachments.filter{it.contentId.isBlank()}.forEach{file->InputChip(selected=false,onClick={runCatching{
                    val uri=Uri.parse(file.uri)
                    context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri,AttachmentRules.mime(file.name,file.mime)).apply{clipData=ClipData.newRawUri(file.name,uri)}.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
                }.onFailure{attachmentError="Не удалось открыть файл для просмотра."}},label={Text(file.name,maxLines=1,overflow=TextOverflow.Ellipsis)},trailingIcon={
                    IconButton(onClick={onChange(d.copy(attachments=d.attachments.filter{it.uri!=file.uri}))},enabled=!s.busy,modifier=Modifier.size(24.dp)){Icon(Icons.Outlined.Close,"Удалить вложение",Modifier.size(16.dp))}
                })}
                attachmentError?.let{Text(it,color=colors.error)}
            }
            Spacer(Modifier.height(16.dp))
        }
    }
    }
}

@Composable private fun ComposeDivider()=HorizontalDivider(color=Color(0xFFE9E9E9),thickness=1.dp)
@Composable private fun ComposeField(label: String,value: String,onChange: (String)->Unit,enabled: Boolean,email: Boolean=false,trailing: @Composable ()->Unit={}) {
    Row(Modifier.fillMaxWidth().heightIn(min=56.dp).padding(start=16.dp,end=8.dp),verticalAlignment=Alignment.CenterVertically) {
        Text(label,Modifier.width(84.dp),color=MaterialTheme.colorScheme.onSurfaceVariant,style=MaterialTheme.typography.bodyMedium)
        BasicTextField(value,onChange,enabled=enabled,singleLine=true,textStyle=MaterialTheme.typography.bodyLarge.copy(color=MaterialTheme.colorScheme.onSurface),
            keyboardOptions=KeyboardOptions(keyboardType=if(email)KeyboardType.Email else KeyboardType.Text),cursorBrush=SolidColor(MaterialTheme.colorScheme.primary),
            modifier=Modifier.weight(1f).padding(vertical=14.dp).semantics{contentDescription=label}.testTag("compose-$label"))
        trailing()
    }
    ComposeDivider()
}
@Composable private fun ForwardFileCard(file: ForwardFile,enabled: Boolean,onRemove: ()->Unit,allowRemove: Boolean=true) {
    val context=LocalContext.current
    var openError by remember(file.key){mutableStateOf(false)}
    Surface(shape=RoundedCornerShape(14.dp),color=MaterialTheme.colorScheme.surfaceContainer,modifier=Modifier.fillMaxWidth()) {
        Column(Modifier.padding(start=12.dp,top=8.dp,end=4.dp,bottom=8.dp)) {
            Row(verticalAlignment=Alignment.CenterVertically) {
                Icon(if(file.status==ForwardStatus.READY)Icons.Outlined.CheckCircleOutline else Icons.Outlined.AttachFile,null,Modifier.size(20.dp))
                Column(Modifier.weight(1f).padding(horizontal=8.dp)) {
                    Text(file.name,style=MaterialTheme.typography.bodyMedium,maxLines=2,overflow=TextOverflow.Ellipsis)
                    Text(when(file.status){
                        ForwardStatus.WAITING->"В очереди"
                        ForwardStatus.DOWNLOADING->"Подготовлено ${file.bytes/1024} КБ"+(if(file.size>0)" из ${file.size/1024} КБ" else "")
                        ForwardStatus.READY->"Готово · ${(file.prepared!!.size/1024).coerceAtLeast(1)} КБ"
                        ForwardStatus.ERROR->file.error
                    },style=MaterialTheme.typography.labelSmall,color=if(file.status==ForwardStatus.ERROR)MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if(file.prepared!=null)IconButton(onClick={runCatching{
                    val uri=Uri.parse(file.prepared.uri)
                    context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri,file.prepared.mime).apply{clipData=ClipData.newRawUri(file.name,uri)}.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
                }.onFailure{openError=true}},enabled=enabled){Icon(Icons.AutoMirrored.Outlined.OpenInNew,"Открыть ${file.name}")}
                if(allowRemove)IconButton(onClick=onRemove,enabled=enabled){Icon(Icons.Outlined.Close,"Удалить ${file.name} из пересылки")}
            }
            if(file.status==ForwardStatus.DOWNLOADING) {
                if(file.size>0)LinearProgressIndicator(progress={(file.bytes.toFloat()/file.size).coerceIn(0f,1f)},modifier=Modifier.fillMaxWidth().padding(end=8.dp))
                else LinearProgressIndicator(Modifier.fillMaxWidth().padding(end=8.dp))
            }
            if(openError)Text("Не удалось открыть файл. Установите приложение для этого формата.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.error)
        }
    }
}
