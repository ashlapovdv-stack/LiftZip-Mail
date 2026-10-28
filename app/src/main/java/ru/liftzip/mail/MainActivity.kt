@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package ru.liftzip.mail

import android.Manifest
import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity: ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { MailTheme { MailApp() } }
    }
}

private val Olive=Color(0xFF4C5D47)
private val Paper=Color(0xFFF8F9F6)

@Composable private fun MailTheme(content: @Composable ()->Unit) {
    val dark=isSystemInDarkTheme()
    val colors=if(dark)darkColorScheme(primary=Color(0xFFBDD0B2),secondary=Color(0xFFD5D8B8),surface=Color(0xFF171B16),background=Color(0xFF111510))
    else lightColorScheme(primary=Olive,secondary=Color(0xFF69775F),primaryContainer=Color(0xFFE4EBD9),surface=Paper,background=Paper,surfaceContainer=Color(0xFFF0F2EB))
    MaterialTheme(colorScheme=colors,content=content)
}

@Composable private fun MailApp(vm: MailViewModel=viewModel()) {
    val s by vm.state.collectAsStateWithLifecycle()
    var route by rememberSaveable { mutableStateOf("inbox") }
    var currentAccount by rememberSaveable { mutableStateOf<String?>(null) }
    var folder by rememberSaveable { mutableStateOf(MailFolder.INBOX) }
    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    var draft by rememberSaveable { mutableStateOf<ComposeDraft?>(null) }
    var preview by rememberSaveable { mutableStateOf<OpenedAttachment?>(null) }
    val snack=remember { SnackbarHostState() }
    val context=LocalContext.current
    val notificationPermission=rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()){}
    var download by remember { mutableStateOf<Pair<MailItem,Attachment>?>(null) }
    val saveFile=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")){uri->
        val requested=download
        if(uri!=null && requested!=null)vm.download(requested.first,requested.second,uri)
        download=null
    }
    fun goInbox(){route="inbox";selected=null}
    fun openExternal(file: OpenedAttachment) {
        runCatching {
            val uri=Uri.parse(file.uri)
            context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri,file.mime)
                .apply{clipData=ClipData.newRawUri(file.name,uri)}.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
        }.onFailure { vm.notice("Не удалось открыть файл. Установите приложение для просмотра этого формата или сохраните вложение.") }
    }
    fun openFile(file: OpenedAttachment) {
        if(AttachmentRules.canPreview(file.mime)){preview=file;route="preview"}else openExternal(file)
    }
    fun compose(value: ComposeDraft?=null){
        val senderId=SenderRules.forNewMessage(s.accounts,s.defaultAccountId,currentAccount)
        val a=s.accounts.firstOrNull { it.id==senderId }
        if(a==null){route="add";return}
        draft=value?:ComposeDraft(accountId=a.id);route="compose"
    }
    LaunchedEffect(s.notice){s.notice?.let { snack.showSnackbar(it,duration=SnackbarDuration.Long);vm.dismissNotice() }}
    BackHandler(enabled=route!="inbox") {
        val d=draft
        if(route=="preview"){preview=null;route="reader"}
        else if(route=="compose" && d!=null && (d.body.isNotBlank()||d.subject.isNotBlank()||d.to.isNotBlank()||d.attachments.isNotEmpty()))vm.saveDraft(d){draft=null;goInbox()}
        else goInbox()
    }
    Scaffold(snackbarHost={SnackbarHost(snack,Modifier.navigationBarsPadding().imePadding()
        .padding(start=8.dp,end=8.dp,bottom=if(route=="inbox" && s.accounts.isNotEmpty() || route=="compose")80.dp else 12.dp))},contentWindowInsets=WindowInsets(0,0,0,0)) { outer->
        Box(Modifier.fillMaxSize().padding(outer)) {
            when {
                !s.loaded -> Box(Modifier.fillMaxSize(),contentAlignment=Alignment.Center){CircularProgressIndicator()}
                route=="add" -> AddAccount(s.busy,onBack={goInbox()},onAdd={a,p->vm.addAccount(a,p){
                    currentAccount=a.id;goInbox()
                    if(Build.VERSION.SDK_INT>=33)notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                }})
                route=="settings" -> SettingsScreen(s,onBack={goInbox()},onAdd={route="add"},onRemove={id->if(currentAccount==id)currentAccount=null;vm.removeAccount(id)},onDefault=vm::setDefaultAccount)
                route=="preview" && preview!=null -> AttachmentPreviewScreen(preview!!,onBack={preview=null;route="reader"},onExternal={openExternal(preview!!)})
                route=="compose" && draft!=null -> ComposeScreen(draft!!,s,onChange={draft=it},onBack={vm.saveDraft(draft!!){draft=null;goInbox()}},
                    onDelete={vm.deleteDraft(draft!!.id){draft=null;goInbox()}},onSend={vm.send(draft!!){draft=null;goInbox()}})
                route=="reader" -> {
                    val message=s.messages.find{it.id==selected}
                    if(message==null)LaunchedEffect(Unit){goInbox()}
                    else ReaderScreen(message,s.accounts.find{it.id==message.accountId},s.busy,onBack={goInbox()},onStar={vm.star(message)},
                        onDownload={attachment->download=message to attachment;saveFile.launch(attachment.name)},
                        onOpenAttachment={file->vm.openAttachment(message,file){if(route=="reader" && selected==message.id)openFile(it)}},
                        onOpenLocal={file->openFile(OpenedAttachment(file.uri,file.name,AttachmentRules.mime(file.name,file.mime)))},
                        onReply={compose(ComposeDraft(accountId=message.accountId,to=message.senderEmail,
                            subject=if(message.subject.startsWith("Re:",true))message.subject else "Re: ${message.subject}",body="\n\n──────────\n${message.sender} пишет:\n${message.body}"))},
                        onForward={
                            val senderId=SenderRules.forNewMessage(s.accounts,s.defaultAccountId,message.accountId)?:message.accountId
                            vm.forward(message,senderId){ready->if(route=="reader" && selected==message.id)compose(ready)else vm.notice("Пересылка подготовлена в черновиках")}
                        })
                }
                s.accounts.isEmpty() -> Welcome(onAdd={route="add"})
                else -> InboxScreen(s,currentAccount,folder,onAccount={currentAccount=it},onFolder={folder=it},onAdd={route="add"},
                    onSettings={route="settings"},onRefresh=vm::refresh,onLoadOlder={vm.loadOlder(currentAccount)},onCompose={compose()},onStar=vm::star,onOpen={m->
                        if(m.localFolder=="drafts")compose(ComposeDraft(m.id,m.accountId,m.recipients,m.subject,m.body,m.outgoing,previousDeliveryUncertain=m.delivery in listOf("sending","uncertain")))
                        else {selected=m.id;route="reader";vm.read(m)}
                    })
            }
        }
    }
}

@Composable private fun Welcome(onAdd: ()->Unit) {
    Column(Modifier.fillMaxSize().safeDrawingPadding().padding(28.dp),verticalArrangement=Arrangement.Center) {
        Surface(color=Olive,shape=RoundedCornerShape(24.dp)) { Icon(Icons.Outlined.MailOutline,null,Modifier.padding(22.dp).size(44.dp),tint=Color.White) }
        Spacer(Modifier.height(32.dp))
        Text("Ваша почта.\nВ одном месте.",style=MaterialTheme.typography.headlineLarge,fontWeight=FontWeight.Bold)
        Spacer(Modifier.height(14.dp))
        Text("Рабочие и личные ящики — с отдельными папками, поиском и черновиками.",style=MaterialTheme.typography.bodyLarge,color=MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(32.dp))
        Button(onClick=onAdd,modifier=Modifier.fillMaxWidth().height(54.dp)){Icon(Icons.Outlined.Add,null);Spacer(Modifier.width(8.dp));Text("Подключить почту")}
        Spacer(Modifier.height(28.dp))
        Text("REG.RU  ·  Mail.ru  ·  Яндекс  ·  Gmail\nи другие серверы IMAP / SMTP",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant,lineHeight=20.sp)
    }
}

@Composable private fun InboxScreen(s: UiState,accountId: String?,folder: MailFolder,onAccount: (String?)->Unit,onFolder: (MailFolder)->Unit,
    onAdd: ()->Unit,onSettings: ()->Unit,onRefresh: ()->Unit,onLoadOlder: ()->Unit,onCompose: ()->Unit,onStar: (MailItem)->Unit,onOpen: (MailItem)->Unit) {
    val drawer=rememberDrawerState(DrawerValue.Closed)
    val scope=rememberCoroutineScope()
    var query by rememberSaveable { mutableStateOf("") }
    val visible=remember(s.messages,accountId,folder,query){MailRules.filter(s.messages,accountId,folder,query)}
    val current=s.accounts.find{it.id==accountId}
    val selectedAccounts=s.accounts.filter{accountId==null || it.id==accountId}
    val hasOlder=selectedAccounts.any{s.history[it.id]?.hasOlder!=false}
    val total=selectedAccounts.takeIf{accounts->accounts.all{s.history[it.id]?.total!=null}}?.sumOf{s.history[it.id]?.total?:0}
    val loaded=s.messages.count{it.localFolder=="inbox" && (accountId==null || it.accountId==accountId)}
    ModalNavigationDrawer(drawerState=drawer,drawerContent={
        ModalDrawerSheet {
            Column(Modifier.padding(24.dp)) { Text("Почта",style=MaterialTheme.typography.headlineMedium,fontWeight=FontWeight.Bold);Text("Ваши почтовые ящики",style=MaterialTheme.typography.bodySmall) }
            NavigationDrawerItem(label={Text("Все ящики")},selected=accountId==null,icon={Icon(Icons.Outlined.AllInbox,null)},modifier=Modifier.padding(horizontal=12.dp),onClick={onAccount(null);scope.launch{drawer.close()}})
            s.accounts.forEach { a->NavigationDrawerItem(label={Column {Text(a.name);Text(a.email,style=MaterialTheme.typography.labelSmall)}},selected=a.id==accountId,
                icon={AccountMark(a)},modifier=Modifier.padding(horizontal=12.dp),onClick={onAccount(a.id);scope.launch{drawer.close()}}) }
            Spacer(Modifier.height(16.dp));HorizontalDivider(Modifier.padding(horizontal=24.dp))
            TextButton(onClick={scope.launch{drawer.close()};onAdd()},modifier=Modifier.padding(12.dp)){Icon(Icons.Outlined.Add,null);Spacer(Modifier.width(12.dp));Text("Добавить ящик")}
            Spacer(Modifier.weight(1f))
            TextButton(onClick={scope.launch{drawer.close()};onSettings()},modifier=Modifier.padding(12.dp)){Icon(Icons.Outlined.Settings,null);Spacer(Modifier.width(12.dp));Text("Настройки")}
            Text("Lift-Zip Почта · 0.2",Modifier.padding(24.dp),style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }) {
        Scaffold(topBar={TopAppBar(title={Column {Text(folder.label,fontWeight=FontWeight.Bold);Text(current?.name?:"Все ящики",style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)}},
            navigationIcon={IconButton(onClick={scope.launch{drawer.open()}}){Icon(Icons.Outlined.Menu,"Выбрать ящик")}},actions={IconButton(onClick=onRefresh,enabled=!s.busy){Icon(Icons.Outlined.Refresh,"Обновить почту")};IconButton(onClick=onSettings){Icon(Icons.Outlined.Settings,"Настройки")}})},
            floatingActionButton={ExtendedFloatingActionButton(onClick=onCompose,icon={Icon(Icons.Outlined.Edit,null)},text={Text("Написать")})},
            bottomBar={NavigationBar {MailFolder.entries.forEach { f->NavigationBarItem(selected=f==folder,onClick={onFolder(f)},icon={Icon(folderIcon(f),null)},label={Text(f.label,fontSize=10.sp)},alwaysShowLabel=true)} }}) { padding->
            Column(Modifier.padding(padding).fillMaxSize()) {
                if(s.busy)LinearProgressIndicator(Modifier.fillMaxWidth())
                OutlinedTextField(value=query,onValueChange={query=it},placeholder={Text("Поиск писем")},leadingIcon={Icon(Icons.Outlined.Search,null)},trailingIcon={if(query.isNotEmpty())IconButton(onClick={query=""}){Icon(Icons.Outlined.Close,"Очистить поиск")}},
                    singleLine=true,shape=RoundedCornerShape(18.dp),modifier=Modifier.fillMaxWidth().padding(horizontal=18.dp,vertical=10.dp))
                Row(Modifier.fillMaxWidth().padding(horizontal=22.dp,vertical=8.dp),verticalAlignment=Alignment.CenterVertically) {
                    Text(if(query.isNotBlank())"Поиск среди загруженных писем" else if(folder==MailFolder.SENT)"Отправленные с этого устройства" else "${visible.size} писем · ${visible.count{it.unread}} непрочитанных",
                        style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
                }
                LazyColumn(Modifier.weight(1f),contentPadding=PaddingValues(bottom=100.dp)) {
                if(visible.isEmpty())item { Box(Modifier.fillMaxWidth().padding(vertical=40.dp),contentAlignment=Alignment.Center) {
                    Column(horizontalAlignment=Alignment.CenterHorizontally,modifier=Modifier.padding(32.dp)) {
                        Icon(folderIcon(folder),null,Modifier.size(48.dp),tint=MaterialTheme.colorScheme.outline)
                        Spacer(Modifier.height(16.dp));Text(if(query.isBlank())"Здесь пока нет писем" else "Ничего не найдено",style=MaterialTheme.typography.titleMedium)
                        Text(if(query.isNotBlank())"Попробуйте другое слово" else "Нажмите обновление, чтобы проверить почту",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                } }
                items(visible,key={it.id}) { m->MailRow(m,s.accounts.find{it.id==m.accountId},onOpen={onOpen(m)},onStar={onStar(m)},busy=s.busy) }
                if(folder==MailFolder.INBOX)item {
                    Column(Modifier.fillMaxWidth().padding(horizontal=22.dp,vertical=20.dp),horizontalAlignment=Alignment.CenterHorizontally) {
                        Text(if(total==null)"Загружено $loaded писем" else "Загружено $loaded из $total",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                        if(hasOlder)OutlinedButton(onClick=onLoadOlder,enabled=!s.busy,modifier=Modifier.padding(top=10.dp)) {
                            Icon(Icons.Outlined.ExpandMore,null);Spacer(Modifier.width(8.dp));Text(if(s.busy)"Загружаем…" else if(selectedAccounts.size>1)"Загрузить ещё" else "Загрузить ещё 100")
                        } else Text("Все входящие загружены",Modifier.padding(top=8.dp),style=MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
    }
}
}

private fun folderIcon(folder: MailFolder)=when(folder){MailFolder.INBOX->Icons.Outlined.Inbox;MailFolder.STARRED->Icons.Outlined.StarOutline;MailFolder.SENT->Icons.AutoMirrored.Outlined.Send;MailFolder.DRAFTS->Icons.Outlined.Drafts}
@Composable private fun AccountMark(account: MailAccount) {
    Surface(color=MaterialTheme.colorScheme.primaryContainer,shape=CircleShape,modifier=Modifier.size(34.dp)) {
        Box(contentAlignment=Alignment.Center){Text(account.provider.mark,fontWeight=FontWeight.Bold,color=MaterialTheme.colorScheme.primary)}
    }
}
private fun displayDate(time: Long)=SimpleDateFormat(if(System.currentTimeMillis()-time<24*3600000L)"HH:mm" else "d MMM",Locale("ru")).format(Date(time))

@Composable private fun MailRow(m: MailItem,account: MailAccount?,onOpen: ()->Unit,onStar: ()->Unit,busy: Boolean) {
    Row(Modifier.fillMaxWidth().clickable(onClick=onOpen).padding(start=18.dp,top=15.dp,bottom=15.dp,end=8.dp)) {
        val color=remember(m.sender){listOf(Color(0xFFE6EAD8),Color(0xFFE8E2D7),Color(0xFFDCE7E9),Color(0xFFEBDDDD))[(m.sender.hashCode() and Int.MAX_VALUE)%4]}
        Surface(shape=CircleShape,color=color,modifier=Modifier.size(44.dp)){Box(contentAlignment=Alignment.Center){Text(m.sender.take(1).uppercase(),fontWeight=FontWeight.Medium,color=Color(0xFF475042),fontSize=18.sp)}}
        Column(Modifier.weight(1f).padding(start=13.dp)) {
            Row(verticalAlignment=Alignment.CenterVertically) {
                Text(m.sender,Modifier.weight(1f),maxLines=1,overflow=TextOverflow.Ellipsis,fontWeight=if(m.unread)FontWeight.Bold else FontWeight.Medium,fontSize=14.sp)
                Text(displayDate(m.date),Modifier.padding(start=8.dp),style=MaterialTheme.typography.labelSmall,color=if(m.unread)MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(4.dp));Text(m.subject.ifBlank{"Без темы"},maxLines=1,overflow=TextOverflow.Ellipsis,fontWeight=if(m.unread)FontWeight.SemiBold else FontWeight.Normal,fontSize=14.sp)
            Text(m.body.replace('\n',' ').trim(),maxLines=1,overflow=TextOverflow.Ellipsis,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant,modifier=Modifier.padding(top=3.dp))
            Row(Modifier.padding(top=7.dp),verticalAlignment=Alignment.CenterVertically) {
                Box(Modifier.size(5.dp).background(MaterialTheme.colorScheme.primary,CircleShape));Spacer(Modifier.width(5.dp))
                Text(account?.name.orEmpty(),style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                if(m.attachments.isNotEmpty()||m.outgoing.isNotEmpty()){Spacer(Modifier.width(8.dp));Icon(Icons.Outlined.AttachFile,"Есть вложения",Modifier.size(13.dp))}
                if(m.delivery=="uncertain"){Spacer(Modifier.width(8.dp));Text("Результат отправки неизвестен",color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.labelSmall)}
            }
        }
        IconButton(onClick=onStar,enabled=!busy,modifier=Modifier.size(36.dp).align(Alignment.CenterVertically)) {
            Icon(if(m.starred)Icons.Outlined.Star else Icons.Outlined.StarOutline,if(m.starred)"Убрать из избранного" else "В избранное",Modifier.size(20.dp),tint=if(m.starred)MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline)
        }
    }
    HorizontalDivider(Modifier.padding(start=75.dp),color=MaterialTheme.colorScheme.outlineVariant.copy(alpha=0.45f))
}

@Composable private fun ReaderScreen(m: MailItem,account: MailAccount?,busy: Boolean,onBack: ()->Unit,onStar: ()->Unit,onDownload: (Attachment)->Unit,onOpenAttachment: (Attachment)->Unit,onOpenLocal: (OutgoingFile)->Unit,onReply: ()->Unit,onForward: ()->Unit) {
    val parts=remember(m.body){ReplyText.split(m.body)}
    var quotedExpanded by rememberSaveable(m.id){mutableStateOf(false)}
    Scaffold(topBar={TopAppBar(title={Text(account?.name?:"Письмо",style=MaterialTheme.typography.titleSmall)},navigationIcon={IconButton(onClick=onBack){Icon(Icons.AutoMirrored.Outlined.ArrowBack,"Назад")}},actions={IconButton(onClick=onStar,enabled=!busy){Icon(if(m.starred)Icons.Outlined.Star else Icons.Outlined.StarOutline,"Избранное")}})}) { padding->
        Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal=22.dp)) {
            if(busy)LinearProgressIndicator(Modifier.fillMaxWidth())
            Text(m.subject,style=MaterialTheme.typography.headlineSmall,fontWeight=FontWeight.SemiBold,modifier=Modifier.padding(top=12.dp,bottom=22.dp))
            Row(verticalAlignment=Alignment.CenterVertically) {
                if(account!=null)AccountMark(account)
                Column(Modifier.padding(start=12.dp).weight(1f)) {Text(m.sender,fontWeight=FontWeight.SemiBold);Text(m.senderEmail,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)}
                Text(displayDate(m.date),style=MaterialTheme.typography.labelSmall)
            }
            Text("Кому: ${m.recipients}",Modifier.padding(top=12.dp,bottom=18.dp),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            if(m.localFolder=="sent")InfoCard("Письмо принято SMTP-сервером отправителя. Эта копия хранится на устройстве.")
            HorizontalDivider(Modifier.padding(vertical=10.dp))
            SelectionContainer { Text((if(quotedExpanded || !parts.canCollapse)m.body else parts.current).ifBlank{"В письме нет доступного текстового содержимого."},style=MaterialTheme.typography.bodyLarge,lineHeight=27.sp,modifier=Modifier.fillMaxWidth().padding(vertical=14.dp)) }
            if(parts.canCollapse) {
                if(quotedExpanded)TextButton(onClick={quotedExpanded=false}){Text("Скрыть предыдущие сообщения")}
                else OutlinedIconButton(onClick={quotedExpanded=true},modifier=Modifier.padding(bottom=12.dp)) {Icon(Icons.Outlined.MoreHoriz,"Показать предыдущие сообщения")}
            }
            m.attachments.forEach { file->
                OutlinedCard(onClick={onOpenAttachment(file)},modifier=Modifier.fillMaxWidth().padding(vertical=5.dp),enabled=!busy) {
                    Row(Modifier.padding(start=15.dp,top=10.dp,bottom=10.dp,end=5.dp),verticalAlignment=Alignment.CenterVertically) {
                        Icon(Icons.Outlined.Description,null)
                        Column(Modifier.weight(1f).padding(horizontal=12.dp)){Text(file.name,maxLines=2,overflow=TextOverflow.Ellipsis);Text((if(file.size>0)"${(file.size/1024).coerceAtLeast(1)} КБ · " else "")+"Нажмите, чтобы открыть",style=MaterialTheme.typography.labelSmall)}
                        IconButton(onClick={onDownload(file)},enabled=!busy){Icon(Icons.Outlined.Download,"Сохранить ${file.name}")}
                    }
                }
            }
            m.outgoing.forEach { file->OutlinedCard(onClick={onOpenLocal(file)},modifier=Modifier.fillMaxWidth().padding(vertical=5.dp),enabled=!busy) {
                Row(Modifier.padding(15.dp),verticalAlignment=Alignment.CenterVertically){Icon(Icons.Outlined.AttachFile,null);Text(file.name,Modifier.weight(1f).padding(start=12.dp),maxLines=2,overflow=TextOverflow.Ellipsis);Icon(Icons.AutoMirrored.Outlined.OpenInNew,"Открыть вложение")}
            } }
            Row(Modifier.fillMaxWidth().padding(vertical=30.dp),horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                if(m.localFolder!="sent")Button(onClick=onReply,enabled=!busy,modifier=Modifier.weight(1f)){Icon(Icons.AutoMirrored.Outlined.Reply,null);Spacer(Modifier.width(8.dp));Text("Ответить")}
                OutlinedButton(onClick=onForward,enabled=!busy,modifier=Modifier.weight(1f)){Text("Переслать")}
            }
            Spacer(Modifier.height(32.dp))
        }
    }
}

@Composable private fun InfoCard(text: String) {
    Surface(color=MaterialTheme.colorScheme.primaryContainer.copy(alpha=0.6f),shape=RoundedCornerShape(12.dp),modifier=Modifier.fillMaxWidth().padding(vertical=8.dp)) {
        Text(text,Modifier.padding(14.dp),style=MaterialTheme.typography.bodySmall,lineHeight=19.sp)
    }
}

@Composable private fun AddAccount(busy: Boolean,onBack: ()->Unit,onAdd: (MailAccount,String)->Unit) {
    var provider by rememberSaveable { mutableStateOf(Provider.REG) }
    var name by rememberSaveable { mutableStateOf("") };var email by rememberSaveable { mutableStateOf("") };var user by rememberSaveable { mutableStateOf("") }
    var senderName by rememberSaveable { mutableStateOf("Дмитрий") }
    var password by remember { mutableStateOf("") };var showPassword by remember { mutableStateOf(false) }
    var imap by rememberSaveable { mutableStateOf(provider.imap) };var smtp by rememberSaveable { mutableStateOf(provider.smtp) }
    var imapPort by rememberSaveable { mutableStateOf("993") };var smtpPort by rememberSaveable { mutableStateOf("465") }
    var imapTls by rememberSaveable { mutableStateOf(false) };var smtpTls by rememberSaveable { mutableStateOf(false) }
    var advanced by rememberSaveable { mutableStateOf(false) };var error by remember { mutableStateOf<String?>(null) }
    Scaffold(topBar={TopAppBar(title={Text("Добавить ящик")},navigationIcon={IconButton(onClick=onBack,enabled=!busy){Icon(Icons.AutoMirrored.Outlined.ArrowBack,"Назад")}})}) { padding->
        Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).imePadding().padding(22.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            Text("Выберите почтовый сервис",style=MaterialTheme.typography.titleLarge,fontWeight=FontWeight.SemiBold)
            FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {Provider.entries.forEach { p->FilterChip(selected=p==provider,onClick={provider=p;imap=p.imap;smtp=p.smtp;imapPort="993";smtpPort="465";imapTls=false;smtpTls=false;advanced=p==Provider.CUSTOM},label={Text(p.label)},enabled=!busy)} }
            OutlinedTextField(name,{name=it},label={Text("Название ящика")},placeholder={Text("Например, Рабочая почта")},singleLine=true,modifier=Modifier.fillMaxWidth(),enabled=!busy)
            OutlinedTextField(senderName,{senderName=it},label={Text("Имя отправителя")},singleLine=true,modifier=Modifier.fillMaxWidth(),enabled=!busy)
            OutlinedTextField(email,{email=it.trim()},label={Text("Адрес почты")},singleLine=true,keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Email),modifier=Modifier.fillMaxWidth(),enabled=!busy)
            val appPassword=provider in listOf(Provider.MAIL,Provider.YANDEX,Provider.GMAIL)
            OutlinedTextField(password,{password=it},label={Text(if(appPassword)"Пароль приложения" else "Пароль почтового ящика")},singleLine=true,
                visualTransformation=if(showPassword)VisualTransformation.None else PasswordVisualTransformation(),keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Password),
                trailingIcon={IconButton(onClick={showPassword=!showPassword}){Icon(if(showPassword)Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,if(showPassword)"Скрыть пароль" else "Показать пароль")}},modifier=Modifier.fillMaxWidth(),enabled=!busy)
            if(appPassword)InfoCard(when(provider){Provider.GMAIL->"В этой сборке Gmail подключается с паролем приложения Google при включённой двухэтапной проверке. Обычный пароль не подойдёт. Доступность паролей приложений зависит от настроек аккаунта. Вход через Google OAuth — следующий этап.";else->"Создайте отдельный пароль для почтового приложения в настройках безопасности ${provider.label}. Также разрешите доступ по IMAP."})
            TextButton(onClick={advanced=!advanced},enabled=!busy){Text(if(advanced)"Скрыть настройки серверов" else "Настройки серверов")}
            if(advanced) {
                OutlinedTextField(user,{user=it},label={Text("Логин (по умолчанию — адрес почты)")},singleLine=true,modifier=Modifier.fillMaxWidth(),enabled=!busy)
                OutlinedTextField(imap,{imap=it.trim()},label={Text("Сервер IMAP")},singleLine=true,modifier=Modifier.fillMaxWidth(),enabled=!busy)
                OutlinedTextField(imapPort,{imapPort=it.filter(Char::isDigit)},label={Text("Порт IMAP")},singleLine=true,keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Number),modifier=Modifier.fillMaxWidth(),enabled=!busy)
                Row(verticalAlignment=Alignment.CenterVertically){Text("IMAP: STARTTLS",Modifier.weight(1f));Switch(checked=imapTls,onCheckedChange={imapTls=it;imapPort=if(it)"143" else "993"},enabled=!busy)}
                OutlinedTextField(smtp,{smtp=it.trim()},label={Text("Сервер SMTP")},singleLine=true,modifier=Modifier.fillMaxWidth(),enabled=!busy)
                OutlinedTextField(smtpPort,{smtpPort=it.filter(Char::isDigit)},label={Text("Порт SMTP")},singleLine=true,keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Number),modifier=Modifier.fillMaxWidth(),enabled=!busy)
                Row(verticalAlignment=Alignment.CenterVertically){Text("SMTP: STARTTLS",Modifier.weight(1f));Switch(checked=smtpTls,onCheckedChange={smtpTls=it;smtpPort=if(it)"587" else "465"},enabled=!busy)}
                Text("При выключенном STARTTLS используется TLS с начала соединения. Проверка сертификата обязательна.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            }
            error?.let{Text(it,color=MaterialTheme.colorScheme.error)}
            if(busy)LinearProgressIndicator(Modifier.fillMaxWidth())
            Button(onClick={val a=MailAccount(name=name.trim(),email=email.trim(),username=user.ifBlank{email}.trim(),provider=provider,imapHost=imap,imapPort=imapPort.toIntOrNull()?:0,imapStartTls=imapTls,smtpHost=smtp,smtpPort=smtpPort.toIntOrNull()?:0,smtpStartTls=smtpTls,senderName=senderName.trim().ifBlank{name})
                val pw=if(provider==Provider.GMAIL)password.replace(" ","") else password
                error=MailRules.validateAccount(a,pw);if(error==null)onAdd(a,pw)},enabled=!busy,modifier=Modifier.fillMaxWidth().height(52.dp)) {Text(if(busy)"Проверяем соединение…" else "Проверить и подключить")}
            Text("Проверим входящую и исходящую почту без отправки писем.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(18.dp))
        }
    }
}

@Composable private fun ComposeScreen(d: ComposeDraft,s: UiState,onChange: (ComposeDraft)->Unit,onBack: ()->Unit,onDelete: ()->Unit,onSend: ()->Unit) {
    val context=LocalContext.current
    var senderMenu by remember { mutableStateOf(false) }
    var attachmentError by remember { mutableStateOf<String?>(null) }
    var confirmResend by remember { mutableStateOf(false) }
    if(confirmResend)AlertDialog(onDismissRequest={confirmResend=false},title={Text("Повторить отправку?")},text={Text("Результат предыдущей попытки неизвестен. Если письмо уже доставлено, получатель получит ещё одну копию.")},
        confirmButton={TextButton(onClick={confirmResend=false;onSend()}){Text("Отправить ещё раз")}},dismissButton={TextButton(onClick={confirmResend=false}){Text("Отмена")}})
    val addFiles=rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()){uris->
        val files=uris.mapNotNull { uri->runCatching {
            context.contentResolver.takePersistableUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION)
            var name="Вложение";var size=-1L
            context.contentResolver.query(uri,arrayOf(OpenableColumns.DISPLAY_NAME,OpenableColumns.SIZE),null,null,null)?.use{c->if(c.moveToFirst()){name=c.getString(0)?:name;if(!c.isNull(1))size=c.getLong(1)}}
            OutgoingFile(uri.toString(),name,context.contentResolver.getType(uri)?:"application/octet-stream",size)
        }.getOrNull() }
        if(files.size!=uris.size)attachmentError="Некоторые файлы недоступны. Попробуйте выбрать их из папки на устройстве."
        else if(files.any{it.size>20L*1024*1024}||(d.attachments+files).sumOf{it.size.coerceAtLeast(0)}>25L*1024*1024)attachmentError="До 20 МБ на файл и 25 МБ суммарно."
        else onChange(d.copy(attachments=(d.attachments+files).distinctBy{it.uri}))
    }
    Scaffold(topBar={TopAppBar(title={Text("Новое письмо")},navigationIcon={IconButton(onClick=onBack,enabled=!s.busy){Icon(Icons.AutoMirrored.Outlined.ArrowBack,"Сохранить черновик и закрыть")}},actions={
        IconButton(onClick={addFiles.launch(arrayOf("*/*"))},enabled=!s.busy){Icon(Icons.Outlined.AttachFile,"Добавить вложения")}
        IconButton(onClick=onDelete,enabled=!s.busy){Icon(Icons.Outlined.DeleteOutline,"Удалить черновик")}
    })},bottomBar={Surface(tonalElevation=3.dp){Row(Modifier.fillMaxWidth().navigationBarsPadding().imePadding().padding(14.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
        OutlinedButton(onClick=onBack,enabled=!s.busy,modifier=Modifier.weight(1f)){Text("Сохранить")}
        Button(onClick={if(d.previousDeliveryUncertain)confirmResend=true else onSend()},enabled=!s.busy,modifier=Modifier.weight(1f)){Icon(Icons.AutoMirrored.Outlined.Send,null);Spacer(Modifier.width(8.dp));Text(if(s.busy)"Подождите…" else "Отправить")}
    }}}) { padding->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(10.dp)) {
            if(d.previousDeliveryUncertain)InfoCard("Результат прошлой отправки неизвестен. Проверьте доставку перед повтором.")
            Box(Modifier.padding(horizontal=8.dp)) {
                TextButton(onClick={senderMenu=true},enabled=!s.busy){Text("От: ${s.accounts.find{it.id==d.accountId}?.email.orEmpty()}");Icon(Icons.Outlined.ExpandMore,null)}
                DropdownMenu(expanded=senderMenu,onDismissRequest={senderMenu=false}){s.accounts.forEach{a->DropdownMenuItem(text={Text(a.email)},onClick={onChange(d.copy(accountId=a.id));senderMenu=false})}}
            }
            OutlinedTextField(d.to,{onChange(d.copy(to=it))},label={Text("Кому")},keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Email),modifier=Modifier.fillMaxWidth().padding(horizontal=20.dp),enabled=!s.busy)
            OutlinedTextField(d.subject,{onChange(d.copy(subject=it))},label={Text("Тема")},modifier=Modifier.fillMaxWidth().padding(horizontal=20.dp),enabled=!s.busy)
            HorizontalDivider(Modifier.padding(top=4.dp))
            BasicTextField(value=d.body,onValueChange={onChange(d.copy(body=it))},enabled=!s.busy,
                textStyle=MaterialTheme.typography.bodyLarge.copy(color=MaterialTheme.colorScheme.onSurface),cursorBrush=SolidColor(MaterialTheme.colorScheme.primary),
                keyboardOptions=KeyboardOptions(capitalization=KeyboardCapitalization.Sentences),
                modifier=Modifier.fillMaxWidth().heightIn(min=320.dp).padding(horizontal=14.dp,vertical=12.dp),
                decorationBox={inner->Box(Modifier.fillMaxWidth()){if(d.body.isEmpty())Text("Текст письма",color=MaterialTheme.colorScheme.onSurfaceVariant);inner()}})
            Column(Modifier.padding(horizontal=20.dp)) {
                d.attachments.forEach { file->InputChip(selected=false,onClick={runCatching{context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(file.uri),AttachmentRules.mime(file.name,file.mime)).apply{clipData=ClipData.newRawUri(file.name,Uri.parse(file.uri))}.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))}.onFailure{attachmentError="Не удалось открыть файл для просмотра."}},label={Text(file.name,maxLines=1,overflow=TextOverflow.Ellipsis)},trailingIcon={IconButton(onClick={onChange(d.copy(attachments=d.attachments.filter{it.uri!=file.uri}))},enabled=!s.busy,modifier=Modifier.size(22.dp)){Icon(Icons.Outlined.Close,"Удалить вложение",Modifier.size(16.dp))}}) }
                attachmentError?.let{Text(it,color=MaterialTheme.colorScheme.error)}
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable private fun SettingsScreen(s: UiState,onBack: ()->Unit,onAdd: ()->Unit,onRemove: (String)->Unit,onDefault: (String?)->Unit) {
    val context=LocalContext.current
    var removal by remember { mutableStateOf<MailAccount?>(null) }
    removal?.let{a->AlertDialog(onDismissRequest={removal=null},title={Text("Отключить ящик?")},text={Text("${a.email}\n\nЛокальные письма и черновики будут удалены с этого устройства. Письма на почтовом сервере сохранятся.")},
        confirmButton={TextButton(onClick={onRemove(a.id);removal=null}){Text("Отключить")}},dismissButton={TextButton(onClick={removal=null}){Text("Отмена")}})}
    Scaffold(topBar={TopAppBar(title={Text("Настройки")},navigationIcon={IconButton(onClick=onBack){Icon(Icons.AutoMirrored.Outlined.ArrowBack,"Назад")}})}) { padding->
        Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(22.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
            Text("Почтовые ящики",style=MaterialTheme.typography.titleLarge,fontWeight=FontWeight.SemiBold)
            s.accounts.forEach { a->OutlinedCard(Modifier.fillMaxWidth()) {Row(Modifier.padding(16.dp),verticalAlignment=Alignment.CenterVertically){AccountMark(a);Column(Modifier.weight(1f).padding(horizontal=12.dp)){Text(a.name,fontWeight=FontWeight.SemiBold);Text(a.email,style=MaterialTheme.typography.bodySmall);Text(a.provider.label,style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.primary)};IconButton(onClick={removal=a},enabled=!s.busy){Icon(Icons.Outlined.LinkOff,"Отключить ящик")}}} }
            Button(onClick=onAdd,modifier=Modifier.fillMaxWidth()){Icon(Icons.Outlined.Add,null);Spacer(Modifier.width(8.dp));Text("Добавить ящик")}
            HorizontalDivider(Modifier.padding(vertical=8.dp))
            Text("Отправитель по умолчанию",style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.SemiBold)
            Text("Используется для новых писем и пересылки. Ответ отправляется с того ящика, на который пришло письмо. Отправителя можно изменить в редакторе.",style=MaterialTheme.typography.bodyMedium)
            ListItem(headlineContent={Text("Ящик открытой папки")},supportingContent={Text("Во всех ящиках — первый добавленный")},
                leadingContent={RadioButton(selected=s.defaultAccountId==null,onClick=null)},modifier=Modifier.clickable(enabled=!s.busy){onDefault(null)})
            s.accounts.forEach { a->ListItem(headlineContent={Text(a.name)},supportingContent={Text(a.email)},
                leadingContent={RadioButton(selected=s.defaultAccountId==a.id,onClick=null)},modifier=Modifier.clickable(enabled=!s.busy){onDefault(a.id)}) }
            HorizontalDivider(Modifier.padding(vertical=8.dp))
            Text("Проверка почты",style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.SemiBold)
            Text("Фоновая проверка запрашивается каждые 15 минут. Android может запускать её позже для экономии батареи. Для немедленной проверки используйте кнопку обновления.",style=MaterialTheme.typography.bodyMedium)
            OutlinedButton(onClick={context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE,context.packageName))}){Text("Настроить уведомления Android")}
            Text("Хранение и синхронизация",style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.SemiBold)
            Text("Сначала загружаем 100 последних входящих. Внизу списка нажмите «Загрузить ещё», чтобы получить более старые письма. Загруженная история сохраняется при обновлении. Поиск работает по загруженным письмам. Черновики и отправленные из этого приложения хранятся на устройстве.\n\nВложения открываются нажатием на файл; значок загрузки сохраняет отдельную копию. PDF и изображения можно смотреть внутри приложения. Для других форматов используется установленная программа.\n\nПароли защищены Android Keystore. Резервное копирование почтовых данных отключено. Внешний сервер приложения не используется.",style=MaterialTheme.typography.bodyMedium)
            Text("Тестовая сборка · 0.2.0",style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(24.dp))
        }
    }
}
