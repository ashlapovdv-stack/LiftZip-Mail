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
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LifecycleStartEffect
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
    var editing by rememberSaveable { mutableStateOf<String?>(null) }
    var historyReturn by rememberSaveable { mutableStateOf("inbox") }
    val inboxMemory=rememberSaveable(saver=InboxMemorySaver){mutableMapOf<String,InboxMemory>()}
    val savedScreens=rememberSaveableStateHolder()
    val snack=remember { SnackbarHostState() }
    val context=LocalContext.current
    LifecycleStartEffect(vm){vm.visible(true);onStopOrDispose{vm.visible(false)}}
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
        if(a==null){if(s.accounts.isEmpty())route="add" else vm.notice("Включите ящик переключателем в меню.");return}
        draft=value?:ComposeDraft(accountId=a.id);route="compose"
    }
    LaunchedEffect(s.notice){s.notice?.let { snack.showSnackbar(it,duration=SnackbarDuration.Long);vm.dismissNotice() }}
    LaunchedEffect(route,selected){if(route=="reader")selected?.let(vm::openMessage)}
    LaunchedEffect(route,folder,currentAccount){vm.showUnread(route=="inbox" && folder==MailFolder.UNREAD,currentAccount)}
    BackHandler(enabled=route!="inbox") {
        val d=draft
        if(route=="edit"){route="settings";editing=null}
        else if(route=="history")route=historyReturn
        else if(route=="preview"){preview=null;route="reader"}
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
                route=="edit" -> {
                    val account=s.accounts.find{it.id==editing}
                    if(account==null)LaunchedEffect(Unit){route="settings"}
                    else key(account.id){AddAccount(s.busy,initial=account,onBack={route="settings"},onAdd={a,p->vm.editAccount(a,p){route="settings";editing=null}})}
                }
                route=="history" -> HistoryScreen(s,onBack={route=historyReturn},onStart=vm::startHistory,onPause=vm::pauseHistory,onResume=vm::resumeHistory)
                route=="settings" -> SettingsScreen(s,onBack={goInbox()},onAdd={route="add"},onEdit={editing=it;route="edit"},
                    onHistory={historyReturn="settings";route="history"},onPoll=vm::setPoll,
                    onRemove={id->if(currentAccount==id)currentAccount=null;vm.removeAccount(id)},onDefault=vm::setDefaultAccount)
                route=="preview" && preview!=null -> AttachmentPreviewScreen(preview!!,onBack={preview=null;route="reader"},onExternal={openExternal(preview!!)})
                route=="compose" && draft!=null -> ComposeScreen(draft!!,s,onChange={draft=it},onBack={vm.saveDraft(draft!!){draft=null;goInbox()}},
                    onDelete={vm.deleteDraft(draft!!.id){draft=null;goInbox()}},onSend={vm.send(draft!!){draft=null;goInbox()}})
                route=="reader" -> {
                    val summary=s.messages.find{it.id==selected}
                    val message=s.opened?.takeIf{it.id==selected}?.let{full->summary?.let{full.copy(unread=it.unread,starred=it.starred)}}?:summary
                    if(message==null)LaunchedEffect(Unit){goInbox()}
                    else ReaderScreen(message,s.accounts.find{it.id==message.accountId},s.busy || message.previewOnly,onBack={goInbox()},onStar={vm.star(message)},
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
                else -> {
                    val memoryKey="${currentAccount?:"all"}:${folder.name}"
                    val memory=inboxMemory.getOrPut(memoryKey){InboxMemory()}
                    savedScreens.SaveableStateProvider(memoryKey){InboxScreen(s,currentAccount,folder,memory,onAccount={currentAccount=it},onEnable=vm::setAccountEnabled,onFolder={folder=it},onAdd={route="add"},
                    onSettings={route="settings"},onRefresh=vm::refresh,onCompose={compose()},onStar=vm::star,onMarkRead=vm::markRead,onSearch=vm::search,onOpen={m->
                        selected=m.id
                        if(m.localFolder=="drafts")vm.openDraft(m.id){full->if(route=="inbox" && selected==full.id)compose(ComposeDraft(full.id,full.accountId,full.recipients,full.subject,full.body,full.outgoing,previousDeliveryUncertain=full.delivery in listOf("sending","uncertain")))}
                        else route="reader"
                    })}
                }
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

@Composable private fun InboxScreen(s: UiState,accountId: String?,folder: MailFolder,memory: InboxMemory,onAccount: (String?)->Unit,onEnable: (String,Boolean)->Unit,onFolder: (MailFolder)->Unit,
    onAdd: ()->Unit,onSettings: ()->Unit,onRefresh: ()->Unit,onCompose: ()->Unit,onStar: (MailItem)->Unit,
    onMarkRead: (Set<String>,Boolean,(Set<String>)->Unit)->Unit,onSearch: (String)->Unit,onOpen: (MailItem)->Unit) {
    val drawer=rememberDrawerState(DrawerValue.Closed)
    val scope=rememberCoroutineScope()
    var query by rememberSaveable { mutableStateOf("") }
    var checked by remember { mutableStateOf(arrayListOf<String>()) }
    LaunchedEffect(query){onSearch(query)}
    val visible=remember(s.messages,s.accounts,accountId,folder,query,s.searchIds,s.searchQuery){
        val active=SyncRules.activeMessages(s.messages,s.accounts,accountId)
        val source=if(query.isBlank())active else active.filter{s.searchQuery==query && it.id in s.searchIds}
        MailRules.filter(source,accountId,folder,"")
    }
    val visibleIds=remember(visible){visible.map{it.id}}
    val selected=checked.toSet().intersect(visibleIds.toSet())
    val selectionMode=selected.isNotEmpty()
    val current=s.accounts.find{it.id==accountId}
    val selectedAccounts=s.accounts.filter{if(accountId==null)it.enabled else it.id==accountId}
    val hasOlder=selectedAccounts.any{SyncRules.needsHistory(it.id,s.messages,s.history[it.id])}
    val total=selectedAccounts.takeIf{accounts->accounts.all{s.history[it.id]?.total!=null}}?.sumOf{s.history[it.id]?.total?:0}
    val selectedIds=selectedAccounts.map{it.id}.toSet()
    val loaded=s.messages.count{it.localFolder=="inbox" && it.accountId in selectedIds}
    fun toggle(id: String){checked=ArrayList(if(id in selected)selected-id else selected+id)}
    fun mark(unread: Boolean){onMarkRead(selected,unread){updated->checked=ArrayList(checked.filter{it !in updated})}}
    BackHandler(enabled=selectionMode){checked=arrayListOf()}
    LaunchedEffect(visibleIds){checked=ArrayList(checked.filter{it in visibleIds})}
    LaunchedEffect(memory,visibleIds.firstOrNull()) {
        if(visibleIds.isNotEmpty()) {
            val restore=memory.restore
            if(restore!=null){memory.list.scrollToItem(restore.indexIn(visibleIds),if(restore.atTop)0 else restore.offset);memory.pinned=restore.atTop;memory.restore=null}
            else if(memory.pinned && memory.firstId!=visibleIds.firstOrNull())memory.list.requestScrollToItem(0)
            memory.firstId=visibleIds.firstOrNull()
        }
    }
    LaunchedEffect(memory) {
        snapshotFlow{Triple(memory.list.firstVisibleItemIndex,memory.list.firstVisibleItemScrollOffset,memory.list.isScrollInProgress)}.collect{(index,offset,moving)->
            if(moving)memory.pinned=index==0 && offset==0
        }
    }
    ModalNavigationDrawer(drawerState=drawer,drawerContent={
        ModalDrawerSheet {
            Column(Modifier.padding(24.dp)){Text("Почта",style=MaterialTheme.typography.headlineMedium,fontWeight=FontWeight.Bold);Text("Ваши почтовые ящики",style=MaterialTheme.typography.bodySmall)}
            NavigationDrawerItem(label={Text("Все ящики")},selected=accountId==null,icon={Icon(Icons.Outlined.AllInbox,null)},modifier=Modifier.padding(horizontal=12.dp),onClick={onAccount(null);scope.launch{drawer.close()}})
            s.accounts.forEach{a->Row(Modifier.fillMaxWidth().padding(end=12.dp),verticalAlignment=Alignment.CenterVertically) {
                NavigationDrawerItem(label={Column{Text(a.name,maxLines=1,overflow=TextOverflow.Ellipsis);Text(a.email,maxLines=1,overflow=TextOverflow.Ellipsis,style=MaterialTheme.typography.labelSmall);if(!a.enabled)Text("Синхронизация выключена",style=MaterialTheme.typography.labelSmall)}},selected=a.id==accountId,
                    icon={AccountMark(a)},modifier=Modifier.weight(1f).padding(start=12.dp,end=4.dp),onClick={onAccount(a.id);scope.launch{drawer.close()}})
                Switch(checked=a.enabled,onCheckedChange={onEnable(a.id,it)},modifier=Modifier.semantics{contentDescription="Синхронизация ящика ${a.name}"})
            }}
            Spacer(Modifier.height(16.dp));HorizontalDivider(Modifier.padding(horizontal=24.dp))
            TextButton(onClick={scope.launch{drawer.close()};onAdd()},modifier=Modifier.padding(12.dp)){Icon(Icons.Outlined.Add,null);Spacer(Modifier.width(12.dp));Text("Добавить ящик")}
            Spacer(Modifier.weight(1f))
            TextButton(onClick={scope.launch{drawer.close()};onSettings()},modifier=Modifier.padding(12.dp)){Icon(Icons.Outlined.Settings,null);Spacer(Modifier.width(12.dp));Text("Настройки")}
            Text("Lift-Zip Почта · 0.3.2",Modifier.padding(24.dp),style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }) {
        Scaffold(topBar={
            if(selectionMode)TopAppBar(title={Text("Выбрано: ${selected.size}")},navigationIcon={IconButton(onClick={checked=arrayListOf()},enabled=!s.busy){Icon(Icons.Outlined.Close,"Снять выделение")}},actions={
                IconButton(onClick={checked=ArrayList(visibleIds)},enabled=!s.busy){Icon(Icons.Outlined.SelectAll,"Выбрать все загруженные письма в списке")}
                IconButton(onClick={mark(false)},enabled=!s.busy){Icon(Icons.Outlined.MarkEmailRead,"Отметить прочитанными")}
                IconButton(onClick={mark(true)},enabled=!s.busy){Icon(Icons.Outlined.MarkEmailUnread,"Отметить непрочитанными")}
            }) else TopAppBar(title={Column{Text(folder.label,fontWeight=FontWeight.Bold);Text((current?.name?:"Все ящики")+if(current?.enabled==false)" · выключен" else "",style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)}},
                navigationIcon={IconButton(onClick={scope.launch{drawer.open()}}){Icon(Icons.Outlined.Menu,"Выбрать ящик")}},actions={
                    IconButton(onClick=onRefresh,enabled=!s.refreshing && !s.unreadRefreshing){Icon(Icons.Outlined.Refresh,"Обновить почту")};IconButton(onClick=onSettings){Icon(Icons.Outlined.Settings,"Настройки")}
                })
        },floatingActionButton={if(!selectionMode)ExtendedFloatingActionButton(onClick=onCompose,icon={Icon(Icons.Outlined.Edit,null)},text={Text("Написать")})},
            bottomBar={NavigationBar{MailFolder.entries.forEach{f->NavigationBarItem(selected=f==folder,onClick={onFolder(f)},modifier=Modifier.semantics{contentDescription=f.label},icon={Icon(folderIcon(f),null)},label={Text(f.navigationLabel,fontSize=10.sp,maxLines=1,softWrap=false,overflow=TextOverflow.Ellipsis)},alwaysShowLabel=true)}}}){padding->
            Column(Modifier.padding(padding).fillMaxSize()) {
                if(s.busy || s.refreshing || s.unreadRefreshing)LinearProgressIndicator(Modifier.fillMaxWidth().height(2.dp).semantics{contentDescription="Обновление почты"})
                OutlinedTextField(value=query,onValueChange={query=it},placeholder={Text("Поиск писем")},leadingIcon={Icon(Icons.Outlined.Search,null)},trailingIcon={if(query.isNotEmpty())IconButton(onClick={query=""}){Icon(Icons.Outlined.Close,"Очистить поиск")}},
                    singleLine=true,shape=RoundedCornerShape(18.dp),modifier=Modifier.fillMaxWidth().padding(horizontal=18.dp,vertical=10.dp))
                if(s.searching && query.isNotBlank())LinearProgressIndicator(Modifier.fillMaxWidth())
                Row(Modifier.fillMaxWidth().padding(horizontal=22.dp,vertical=4.dp),verticalAlignment=Alignment.CenterVertically) {
                    Text(if(query.isNotBlank())"Поиск среди загруженных писем" else if(folder==MailFolder.SENT)"Отправленные с этого устройства" else "${visible.size} писем · ${visible.count{it.unread}} непрочитанных",
                        style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
                }
                PullToRefreshBox(isRefreshing=s.refreshing || s.unreadRefreshing,onRefresh={if(!s.refreshing && !s.unreadRefreshing)onRefresh()},indicator={},modifier=Modifier.weight(1f)) {
                    LazyColumn(Modifier.fillMaxSize(),state=memory.list,contentPadding=PaddingValues(bottom=100.dp)) {
                        if(visible.isEmpty())item(key="empty") {Box(Modifier.fillMaxWidth().padding(vertical=30.dp),contentAlignment=Alignment.Center) {
                            Column(horizontalAlignment=Alignment.CenterHorizontally,modifier=Modifier.padding(24.dp)) {
                                Icon(folderIcon(folder),null,Modifier.size(48.dp),tint=MaterialTheme.colorScheme.outline)
                                Spacer(Modifier.height(16.dp));Text(if(query.isNotBlank())"Ничего не найдено" else if(folder==MailFolder.UNREAD)"Нет непрочитанных писем" else "Здесь пока нет писем",style=MaterialTheme.typography.titleMedium)
                                Text(if(query.isNotBlank())"Попробуйте другое слово" else "Потяните список вниз для проверки почты",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }}
                        items(visible,key={it.id}){m->MailRow(m,s.accounts.find{it.id==m.accountId},onOpen={if(selectionMode)toggle(m.id)else onOpen(m)},
                            onSelect={toggle(m.id)},selectionMode=selectionMode,checked=m.id in selected,onStar={onStar(m)},busy=s.busy)}
                        if(folder==MailFolder.INBOX)item(key="history-footer") {
                            Column(Modifier.fillMaxWidth().padding(horizontal=22.dp,vertical=20.dp),horizontalAlignment=Alignment.CenterHorizontally) {
                                Text(if(total==null)"Загружено $loaded писем" else "Загружено $loaded из $total",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                                if(!hasOlder)Text("Все входящие загружены",Modifier.padding(top=8.dp),style=MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun folderIcon(folder: MailFolder)=when(folder){MailFolder.INBOX->Icons.Outlined.Inbox;MailFolder.UNREAD->Icons.Outlined.MarkEmailUnread;MailFolder.STARRED->Icons.Outlined.StarOutline;MailFolder.SENT->Icons.AutoMirrored.Outlined.Send;MailFolder.DRAFTS->Icons.Outlined.Drafts}
@Composable private fun AccountMark(account: MailAccount) {
    Surface(color=MaterialTheme.colorScheme.primaryContainer,shape=CircleShape,modifier=Modifier.size(34.dp)) {
        Box(contentAlignment=Alignment.Center){Text(account.provider.mark,fontWeight=FontWeight.Bold,color=MaterialTheme.colorScheme.primary)}
    }
}
private fun displayDate(time: Long)=SimpleDateFormat(if(System.currentTimeMillis()-time<24*3600000L)"HH:mm" else "d MMM",Locale("ru")).format(Date(time))

@Composable private fun MailRow(m: MailItem,account: MailAccount?,onOpen: ()->Unit,onSelect: ()->Unit,selectionMode: Boolean,checked: Boolean,onStar: ()->Unit,busy: Boolean) {
    val background=when{checked->MaterialTheme.colorScheme.primaryContainer;m.unread->MaterialTheme.colorScheme.primaryContainer.copy(alpha=0.28f);else->MaterialTheme.colorScheme.surface}
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min).background(background)
        .combinedClickable(onClick=onOpen,onLongClick=onSelect,onLongClickLabel="Выделить письмо")
        .semantics{stateDescription=if(m.unread)"Не прочитано" else "Прочитано";selected=checked}) {
        Box(Modifier.width(4.dp).fillMaxHeight().background(if(m.unread)MaterialTheme.colorScheme.primary else Color.Transparent))
        Row(Modifier.weight(1f).padding(start=14.dp,top=15.dp,bottom=15.dp,end=8.dp),verticalAlignment=Alignment.CenterVertically) {
            if(selectionMode)Checkbox(checked=checked,onCheckedChange={onSelect()},modifier=Modifier.size(44.dp))else {
                val color=remember(m.sender){listOf(Color(0xFFE6EAD8),Color(0xFFE8E2D7),Color(0xFFDCE7E9),Color(0xFFEBDDDD))[(m.sender.hashCode() and Int.MAX_VALUE)%4]}
                Surface(shape=CircleShape,color=color,modifier=Modifier.size(44.dp)){Box(contentAlignment=Alignment.Center){Text(m.sender.take(1).uppercase(),fontWeight=FontWeight.Medium,color=Color(0xFF475042),fontSize=18.sp)}}
            }
            Column(Modifier.weight(1f).padding(start=13.dp)) {
                Row(verticalAlignment=Alignment.CenterVertically) {
                    Text(m.sender,Modifier.weight(1f),maxLines=1,overflow=TextOverflow.Ellipsis,fontWeight=if(m.unread)FontWeight.Bold else FontWeight.Normal,fontSize=14.sp)
                    Text(displayDate(m.date),Modifier.padding(start=8.dp),style=MaterialTheme.typography.labelSmall,color=if(m.unread)MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(Modifier.height(4.dp));Text(m.subject.ifBlank{"Без темы"},maxLines=1,overflow=TextOverflow.Ellipsis,fontWeight=if(m.unread)FontWeight.Bold else FontWeight.Normal,fontSize=14.sp)
                Text(if(!m.contentCached)"Текст загрузится при открытии" else m.body.replace('\n',' ').trim(),maxLines=1,overflow=TextOverflow.Ellipsis,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant,modifier=Modifier.padding(top=3.dp))
                Row(Modifier.padding(top=7.dp),verticalAlignment=Alignment.CenterVertically) {
                    if(m.unread){Box(Modifier.size(7.dp).background(MaterialTheme.colorScheme.primary,CircleShape));Spacer(Modifier.width(5.dp))}
                    Text((if(m.unread)"Не прочитано · " else "")+account?.name.orEmpty(),Modifier.weight(1f,fill=false),maxLines=1,overflow=TextOverflow.Ellipsis,
                        style=MaterialTheme.typography.labelSmall,fontWeight=if(m.unread)FontWeight.Bold else FontWeight.Normal,color=if(m.unread)MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                    if(m.attachments.isNotEmpty()||m.outgoing.isNotEmpty()){Spacer(Modifier.width(8.dp));Icon(Icons.Outlined.AttachFile,"Есть вложения",Modifier.size(13.dp))}
                }
                if(m.delivery=="uncertain")Text("Результат отправки неизвестен",color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.labelSmall)
            }
            if(!selectionMode)IconButton(onClick=onStar,enabled=!busy,modifier=Modifier.size(36.dp)) {
                Icon(if(m.starred)Icons.Outlined.Star else Icons.Outlined.StarOutline,if(m.starred)"Убрать из избранного" else "В избранное",Modifier.size(20.dp),tint=if(m.starred)MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline)
            }
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
            if(m.previewOnly)Text("Открываем сохранённое письмо…",Modifier.padding(vertical=24.dp),color=MaterialTheme.colorScheme.onSurfaceVariant)
            else SelectionContainer { Text((if(quotedExpanded || !parts.canCollapse)m.body else parts.current).ifBlank{"В письме нет доступного текстового содержимого."},style=MaterialTheme.typography.bodyLarge,lineHeight=27.sp,modifier=Modifier.fillMaxWidth().padding(vertical=14.dp)) }
            if(!m.previewOnly && parts.canCollapse) {
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

@Composable private fun AddAccount(busy: Boolean,initial: MailAccount?=null,onBack: ()->Unit,onAdd: (MailAccount,String)->Unit) {
    var provider by rememberSaveable { mutableStateOf(initial?.provider?:Provider.REG) }
    var name by rememberSaveable { mutableStateOf(initial?.name.orEmpty()) };var email by rememberSaveable { mutableStateOf(initial?.email.orEmpty()) };var user by rememberSaveable { mutableStateOf(initial?.username.orEmpty()) }
    var senderName by rememberSaveable { mutableStateOf(initial?.senderName?:"Дмитрий") }
    var password by remember { mutableStateOf("") };var showPassword by remember { mutableStateOf(false) }
    var imap by rememberSaveable { mutableStateOf(initial?.imapHost?:provider.imap) };var smtp by rememberSaveable { mutableStateOf(initial?.smtpHost?:provider.smtp) }
    var imapPort by rememberSaveable { mutableStateOf((initial?.imapPort?:993).toString()) };var smtpPort by rememberSaveable { mutableStateOf((initial?.smtpPort?:465).toString()) }
    var imapTls by rememberSaveable { mutableStateOf(initial?.imapStartTls?:false) };var smtpTls by rememberSaveable { mutableStateOf(initial?.smtpStartTls?:false) }
    var advanced by rememberSaveable { mutableStateOf(initial!=null) };var error by remember { mutableStateOf<String?>(null) }
    Scaffold(topBar={TopAppBar(title={Text(if(initial==null)"Добавить ящик" else "Настройки ящика")},navigationIcon={IconButton(onClick=onBack,enabled=!busy){Icon(Icons.AutoMirrored.Outlined.ArrowBack,"Назад")}})}) { padding->
        Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).imePadding().padding(22.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            Text("Выберите почтовый сервис",style=MaterialTheme.typography.titleLarge,fontWeight=FontWeight.SemiBold)
            FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {Provider.entries.forEach { p->FilterChip(selected=p==provider,onClick={provider=p;imap=p.imap;smtp=p.smtp;imapPort="993";smtpPort="465";imapTls=false;smtpTls=false;advanced=p==Provider.CUSTOM},label={Text(p.label)},enabled=!busy)} }
            OutlinedTextField(name,{name=it},label={Text("Название ящика")},placeholder={Text("Например, Рабочая почта")},singleLine=true,modifier=Modifier.fillMaxWidth(),enabled=!busy)
            OutlinedTextField(senderName,{senderName=it},label={Text("Имя отправителя")},singleLine=true,modifier=Modifier.fillMaxWidth(),enabled=!busy)
            OutlinedTextField(email,{email=it.trim()},label={Text("Адрес почты")},singleLine=true,readOnly=initial!=null,keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Email),modifier=Modifier.fillMaxWidth(),enabled=!busy)
            if(initial!=null)Text("Для другого адреса добавьте отдельный ящик.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            val appPassword=provider in listOf(Provider.MAIL,Provider.YANDEX,Provider.GMAIL)
            OutlinedTextField(password,{password=it},label={Text(if(initial!=null)"Новый пароль (необязательно)" else if(appPassword)"Пароль приложения" else "Пароль почтового ящика")},singleLine=true,
                visualTransformation=if(showPassword)VisualTransformation.None else PasswordVisualTransformation(),keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Password),
                trailingIcon={IconButton(onClick={showPassword=!showPassword}){Icon(if(showPassword)Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,if(showPassword)"Скрыть пароль" else "Показать пароль")}},modifier=Modifier.fillMaxWidth(),enabled=!busy)
            if(initial!=null)Text("Оставьте поле пустым, чтобы сохранить действующий пароль.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
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
            if(initial!=null && (!initial.imapHost.equals(imap,true) || initial.username!=user.ifBlank{email}.trim() || initial.imapPort.toString()!=imapPort || initial.imapStartTls!=imapTls))InfoCard("При смене параметров IMAP или логина входящие будут загружены заново. Черновики и отправленные сохранятся.")
            Button(onClick={val a=MailAccount(id=initial?.id?:java.util.UUID.randomUUID().toString(),name=name.trim(),email=email.trim(),username=user.ifBlank{email}.trim(),provider=provider,imapHost=imap,imapPort=imapPort.toIntOrNull()?:0,imapStartTls=imapTls,smtpHost=smtp,smtpPort=smtpPort.toIntOrNull()?:0,smtpStartTls=smtpTls,senderName=senderName.trim().ifBlank{name})
                val pw=if(provider==Provider.GMAIL)password.replace(" ","") else password
                error=MailRules.validateAccount(a,pw,passwordRequired=initial==null);if(error==null)onAdd(a,pw)},enabled=!busy,modifier=Modifier.fillMaxWidth().height(52.dp)) {Text(if(busy)"Проверяем соединение…" else if(initial==null)"Проверить и подключить" else "Проверить и сохранить")}
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
                DropdownMenu(expanded=senderMenu,onDismissRequest={senderMenu=false}){s.accounts.forEach{a->DropdownMenuItem(text={Text(a.email+if(a.enabled)"" else " · выключен")},enabled=a.enabled,onClick={onChange(d.copy(accountId=a.id));senderMenu=false})}}
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

@Composable private fun SettingsScreen(s: UiState,onBack: ()->Unit,onAdd: ()->Unit,onEdit: (String)->Unit,onHistory: ()->Unit,onPoll: (PollSettings)->Unit,onRemove: (String)->Unit,onDefault: (String?)->Unit) {
    val context=LocalContext.current
    var removal by remember { mutableStateOf<MailAccount?>(null) }
    removal?.let{a->AlertDialog(onDismissRequest={removal=null},title={Text("Отключить ящик?")},text={Text("${a.email}\n\nЛокальные письма и черновики будут удалены с этого устройства. Письма на почтовом сервере сохранятся.")},
        confirmButton={TextButton(onClick={onRemove(a.id);removal=null}){Text("Отключить")}},dismissButton={TextButton(onClick={removal=null}){Text("Отмена")}})}
    Scaffold(topBar={TopAppBar(title={Text("Настройки")},navigationIcon={IconButton(onClick=onBack){Icon(Icons.AutoMirrored.Outlined.ArrowBack,"Назад")}})}) { padding->
        Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),verticalArrangement=Arrangement.spacedBy(14.dp)) {
            Text("Почтовые ящики",style=MaterialTheme.typography.titleLarge,fontWeight=FontWeight.SemiBold)
            s.accounts.forEach{a->OutlinedCard(onClick={onEdit(a.id)},enabled=!s.busy,modifier=Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Row(verticalAlignment=Alignment.CenterVertically) {
                        AccountMark(a)
                        Column(Modifier.weight(1f).padding(horizontal=10.dp)){Text(a.name,fontWeight=FontWeight.SemiBold);Text(a.email,maxLines=1,overflow=TextOverflow.Ellipsis,style=MaterialTheme.typography.bodySmall)}
                        IconButton(onClick={onEdit(a.id)},enabled=!s.busy){Icon(Icons.Outlined.Edit,"Изменить настройки ящика")}
                        IconButton(onClick={removal=a},enabled=!s.busy){Icon(Icons.Outlined.LinkOff,"Отключить ящик")}
                    }
                    val check=s.checks[a.id]
                    Text(if(!a.enabled)"Синхронизация выключена" else if(check?.time!=null && check.time>0)"Успешная проверка: "+SimpleDateFormat("d MMM, HH:mm",Locale("ru")).format(Date(check.time)) else "Успешная проверка ещё не выполнена",style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant,modifier=Modifier.padding(top=8.dp))
                    if(a.enabled && check!=null && check.durationMs>0)Text("Последний запрос: "+String.format(Locale("ru"),"%.1f с",check.durationMs/1000.0),style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                    if(check?.error?.isNotEmpty()==true)Text(check.error,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.error,modifier=Modifier.padding(top=5.dp))
                }
            }}
            Button(onClick=onAdd,modifier=Modifier.fillMaxWidth(),enabled=!s.busy){Icon(Icons.Outlined.Add,null);Spacer(Modifier.width(8.dp));Text("Добавить ящик")}
            HorizontalDivider(Modifier.padding(vertical=8.dp))
            Text("Проверка новой почты",style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.SemiBold)
            PollPicker("Когда приложение открыто",s.poll.foregroundMinutes,PollSettings.foregroundChoices,!s.busy){onPoll(s.poll.copy(foregroundMinutes=it))}
            PollPicker("В фоне",s.poll.backgroundMinutes,PollSettings.backgroundChoices,!s.busy){onPoll(s.poll.copy(backgroundMinutes=it))}
            Text("При возвращении в приложение проверяем почту сразу. В фоне Android допускает периодическую проверку от 15 минут и может откладывать её при энергосбережении. Для немедленной проверки потяните список вниз.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedButton(onClick={context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE,context.packageName))}){Text("Уведомления Android")}
            Text("История писем",style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.SemiBold)
            OutlinedButton(onClick=onHistory,modifier=Modifier.fillMaxWidth()){Icon(Icons.Outlined.Download,null);Spacer(Modifier.width(8.dp));Text("Загрузить всю историю")}
            Text("Выберите все или отдельные ящики. Загрузка входящих идёт автоматически, прогресс сохраняется. Вложения скачиваются при открытии или сохранении.",style=MaterialTheme.typography.bodySmall)
            HorizontalDivider(Modifier.padding(vertical=8.dp))
            Text("Отправитель по умолчанию",style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.SemiBold)
            Text("Используется для новых писем и пересылки. Ответ отправляется с того ящика, на который пришло письмо. Отправителя можно изменить в редакторе.",style=MaterialTheme.typography.bodyMedium)
            ListItem(headlineContent={Text("Ящик открытой папки")},supportingContent={Text("Во всех ящиках — первый добавленный")},
                leadingContent={RadioButton(selected=s.defaultAccountId==null,onClick=null)},modifier=Modifier.clickable(enabled=!s.busy){onDefault(null)})
            s.accounts.forEach{a->ListItem(headlineContent={Text(a.name)},supportingContent={Text(a.email)},
                leadingContent={RadioButton(selected=s.defaultAccountId==a.id,onClick=null)},modifier=Modifier.clickable(enabled=!s.busy){onDefault(a.id)})}
            HorizontalDivider(Modifier.padding(vertical=8.dp))
            Text("Хранение и синхронизация",style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.SemiBold)
            Text("Поиск работает по загруженным письмам. Черновики и отправленные из этого приложения хранятся на устройстве.\n\nВложения открываются нажатием на файл; значок загрузки сохраняет копию. PDF и изображения можно смотреть внутри приложения. Для других форматов используется установленная программа.\n\nПароли защищены Android Keystore. Резервное копирование почтовых данных отключено. Внешний сервер приложения не используется.",style=MaterialTheme.typography.bodyMedium)
            Text("Тестовая сборка · 0.3.2",style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable private fun PollPicker(label: String,value: Int,choices: List<Int>,enabled: Boolean,onChange: (Int)->Unit) {
    var expanded by remember{mutableStateOf(false)}
    fun title(minutes: Int)=if(minutes==0)"Отключена" else if(minutes==60)"Каждый час" else if(minutes==120)"Каждые 2 часа" else if(minutes==1)"Каждую минуту" else "Каждые $minutes минут"
    Column {
        Text(label,style=MaterialTheme.typography.labelLarge)
        Box {
            OutlinedButton(onClick={expanded=true},enabled=enabled,modifier=Modifier.fillMaxWidth()){Text(title(value),Modifier.weight(1f));Icon(Icons.Outlined.ExpandMore,null)}
            DropdownMenu(expanded=expanded,onDismissRequest={expanded=false}){choices.forEach{minutes->DropdownMenuItem(text={Text(title(minutes))},onClick={expanded=false;onChange(minutes)})}}
        }
    }
}
