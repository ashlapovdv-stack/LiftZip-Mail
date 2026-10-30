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
import androidx.activity.result.IntentSenderRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.auth.api.identity.AuthorizationResult
import androidx.compose.foundation.*
import androidx.compose.animation.*
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.Velocity
import kotlinx.coroutines.flow.distinctUntilChanged
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
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
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
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
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.CancellationException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity: ComponentActivity() {
    private val requestedMessage=MutableStateFlow<NotificationOpenRequest?>(null)
    private fun handleNotification(intent: Intent?) {
        requestedMessage.value=NotificationNavigation.destination(intent?.dataString,intent?.getStringExtra(MailNotifications.MESSAGE_EXTRA),
            intent?.getBooleanExtra("mail-open-inbox",false)==true)?.let{NotificationOpenRequest(it)}
    }
    private fun consumeNotification(token: String) {
        if(requestedMessage.value?.token!=token)return
        requestedMessage.update{NotificationNavigation.consume(it,token)}
        intent.data=null
        intent.removeExtra(MailNotifications.MESSAGE_EXTRA)
        intent.removeExtra("mail-open-inbox")
    }
    override fun onNewIntent(intent: Intent){super.onNewIntent(intent);setIntent(intent);handleNotification(intent)}
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handleNotification(intent)
        setContent {
            val message by requestedMessage.collectAsStateWithLifecycle()
            MailTheme { MailApp(notificationRequest=message,onNotificationConsumed=::consumeNotification) }
        }
    }
}

private val Olive=Color(0xFF4C5D47)
private val Paper=Color(0xFFF8F9F6)

@Composable private fun MailTheme(content: @Composable ()->Unit) {
    val dark=isSystemInDarkTheme()
    val colors=if(dark)darkColorScheme(primary=Color(0xFFBDD0B2),secondary=Color(0xFFD5D8B8),secondaryContainer=Color(0xFF354332),onSecondaryContainer=Color(0xFFE2EADB),surface=Color(0xFF171B16),background=Color(0xFF111510))
    else lightColorScheme(primary=Olive,secondary=Color(0xFF69775F),secondaryContainer=Color(0xFFE3E8DC),onSecondaryContainer=Color(0xFF293525),primaryContainer=Color(0xFFE4EBD9),surface=Paper,background=Paper,surfaceContainer=Color(0xFFF0F2EB))
    MaterialTheme(colorScheme=colors,content=content)
}

@Composable private fun MailApp(vm: MailViewModel=viewModel(),notificationRequest: NotificationOpenRequest?=null,onNotificationConsumed: (String)->Unit={}) {
    val s by vm.state.collectAsStateWithLifecycle()
    var route by rememberSaveable { mutableStateOf("inbox") }
    var currentAccount by rememberSaveable { mutableStateOf<String?>(null) }
    var folder by rememberSaveable { mutableStateOf(MailFolder.INBOX) }
    var serverPath by rememberSaveable { mutableStateOf<String?>(null) }
    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    var notificationFallback by remember { mutableStateOf<MailItem?>(null) }
    var draft by rememberSaveable { mutableStateOf<ComposeDraft?>(null) }
    var preview by rememberSaveable { mutableStateOf<OpenedAttachment?>(null) }
    var editing by rememberSaveable { mutableStateOf<String?>(null) }
    var historyReturn by rememberSaveable { mutableStateOf("inbox") }
    val inboxMemory=rememberSaveable(saver=InboxMemorySaver){mutableMapOf<String,InboxMemory>()}
    val savedScreens=rememberSaveableStateHolder()
    val snack=remember { SnackbarHostState() }
    val context=LocalContext.current
    val density=LocalDensity.current
    var rootHeight by remember{mutableIntStateOf(0)}
    var noticeAnchor by remember{mutableStateOf<NoticeAnchor?>(null)}
    val safeBottom=WindowInsets.navigationBars.union(WindowInsets.ime).getBottom(density)
    val noticeBottom=with(density){InboxInteractionRules.noticeBottom(rootHeight,route,noticeAnchor,safeBottom,12.dp.roundToPx()).toDp()}
    fun anchor(owner: String,top: Float?) {
        val next=top?.let{NoticeAnchor(owner,it)}
        if(next!=null || noticeAnchor?.owner==owner)noticeAnchor=next
    }
    LifecycleStartEffect(vm){vm.visible(true);onStopOrDispose{vm.visible(false)}}
    val notificationPermission=rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()){vm.notificationEnvironmentChanged()}
    var download by remember { mutableStateOf<Pair<MailItem,Attachment>?>(null) }
    val saveFile=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")){uri->
        val requested=download
        if(uri!=null && requested!=null)vm.download(requested.first,requested.second,uri)
        download=null
    }
    fun goInbox(){route="inbox";selected=null;notificationFallback=null}
    fun openExternal(file: OpenedAttachment) {
        runCatching {
            val uri=Uri.parse(file.uri)
            context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri,file.mime)
                .apply{clipData=ClipData.newRawUri(file.name,uri)}.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
        }.onFailure { vm.notice("Не удалось открыть файл. Установите приложение для просмотра этого формата или сохраните вложение.",error=true) }
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
    LaunchedEffect(notificationRequest?.token,s.loaded) {
        val request=notificationRequest
        if(s.loaded && request!=null) {
            if(route=="compose" && !s.busy)draft?.let{vm.preserveDraft(it)}
            val id=request.destination.messageId
            if(id==null){serverPath=null;currentAccount=null;folder=MailFolder.UNREAD;goInbox()}
            else {
                val message=try{vm.notificationMessage(id)}catch(e: CancellationException){throw e}catch(e: Exception){vm.notice(friendlyError(e),error=true);null}
                currentCoroutineContext().ensureActive()
                selected=id;notificationFallback=message
                if(message==null)route="message-missing"
                else {serverPath=null;currentAccount=message.accountId;folder=MailFolder.INBOX;route="reader";vm.notificationOpened(id)}
            }
            onNotificationConsumed(request.token)
        }
    }
    LaunchedEffect(s.notice?.id){s.notice?.let { snack.showSnackbar(NoticeVisuals(it));vm.dismissNotice(it.id) }}
    LaunchedEffect(route,selected){if(route=="reader")selected?.let(vm::openMessage)}
    LaunchedEffect(route,folder,currentAccount,serverPath){
        vm.showUnread(route=="inbox" && serverPath==null && folder==MailFolder.UNREAD,currentAccount)
        if(route=="inbox")vm.showFolders(folder,currentAccount,serverPath)
    }
    BackHandler(enabled=route!="inbox") {
        val d=draft
        if(route=="edit"){route="settings";editing=null}
        else if(route in setOf("delivery","sender","advanced","diagnostics","about","appearance","storage"))route="settings"
        else if(route=="history")route=historyReturn
        else if(route=="preview"){preview=null;route="reader"}
        else if(route=="compose" && d!=null && (d.body.isNotBlank()||d.subject.isNotBlank()||d.to.isNotBlank()||d.attachments.isNotEmpty()||d.forwarding!=null))vm.saveDraft(d){if(route=="compose" && draft?.id==d.id){draft=null;goInbox()}}
        else goInbox()
    }
    Scaffold(modifier=Modifier.onSizeChanged{rootHeight=it.height},snackbarHost={
        SnackbarHost(snack,Modifier.fillMaxWidth().padding(start=16.dp,end=16.dp,bottom=noticeBottom)){ServiceSnackbar(it)}
    },contentWindowInsets=WindowInsets(0,0,0,0)) { outer->
        Box(Modifier.fillMaxSize().padding(outer)) {
            when {
                !s.loaded -> Box(Modifier.fillMaxSize(),contentAlignment=Alignment.Center){CircularProgressIndicator()}
                route=="add" -> AddAccount(s.busy,onBack={goInbox()},onAdd={a,p->vm.addAccount(a,p){
                    if(route=="add"){currentAccount=a.id;goInbox()}
                    if(Build.VERSION.SDK_INT>=33)notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                }})
                route=="edit" -> {
                    val account=s.accounts.find{it.id==editing}
                    if(account==null)LaunchedEffect(Unit){route="settings"}
                    else key(account.id){AddAccount(s.busy,initial=account,onBack={route="settings"},onAdd={a,p->vm.editAccount(a,p){if(route=="edit" && editing==a.id){route="settings";editing=null}}})}
                }
                route=="actions" -> ActionsScreen(s,onBack={goInbox()},onRetry=vm::retryAction,onCancel=vm::cancelAction,onAccounts={route="settings"})
                route=="history" -> HistoryScreen(s,onBack={route=historyReturn},onStart=vm::startHistory,onPause=vm::pauseHistory,onResume=vm::resumeHistory)
                route=="settings" -> SettingsScreen(s,onBack={goInbox()},onAdd={route="add"},onEdit={editing=it;route="edit"},onSection={route=it},
                    onHistory={historyReturn="settings";route="history"},onRemove={id->if(currentAccount==id)currentAccount=null;vm.removeAccount(id)})
                route=="delivery" -> DeliverySettingsScreen(s,onBack={route="settings"},onPoll=vm::setPoll,onFastMail=vm::setFastMail,onPermission={
                    if(Build.VERSION.SDK_INT>=33 && !s.notificationAccess.permission)notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                    else context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE,context.packageName))
                },onDiagnostics={route="diagnostics"})
                route=="storage" -> StorageSettingsScreen(s,onBack={route="settings"},onInspect=vm::storageStatus,onLimit=vm::setCacheLimit,onClear=vm::clearCache)
                route=="appearance" -> AppearanceSettingsScreen(s,onBack={route="settings"},onAutoHide=vm::setAutoHidePanels)
                route=="sender" -> SenderSettingsScreen(s,onBack={route="settings"},onDefault=vm::setDefaultAccount)
                route=="advanced" -> AdvancedSettingsScreen(onBack={route="settings"},onSection={route=it})
                route=="about" -> AboutSettingsScreen(onBack={route="settings"})
                route=="diagnostics" -> DiagnosticsScreen(s,onBack={route="settings"},onPermission={
                    if(Build.VERSION.SDK_INT>=33 && !s.notificationAccess.permission)notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                    else context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE,context.packageName))
                },onTestNotification=vm::testNotification,onCheck=vm::refresh,onTestBackground=vm::testBackground,onRestore=vm::restoreBackground,onRetryBodies=vm::retryBodies)
                route=="message-missing" -> MissingMessageScreen(onBack={goInbox()})
                route=="preview" && preview!=null -> AttachmentPreviewScreen(preview!!,onBack={preview=null;route="reader"},onExternal={openExternal(preview!!)})
                route=="compose" && draft!=null -> {
                    val editor=draft!!
                    val stored=s.messages.find{it.id==editor.id}
                    val active=editor.copy(forwarding=s.forwarding[editor.id]?:stored?.forwarding?:editor.forwarding)
                    ComposeScreen(active,s,onChange={draft=it},onBack={vm.saveDraft(active){if(route=="compose" && draft?.id==active.id){draft=null;goInbox()}}},
                        onDelete={vm.deleteDraft(active.id){if(route=="compose" && draft?.id==active.id){draft=null;goInbox()}}},onSend={vm.send(active){if(route=="compose" && draft?.id==active.id){draft=null;goInbox()}}},
                        onNoticeAnchor={anchor("compose",it)},
                        onRetryForward={vm.retryForward(active.id)},onRemoveForward={vm.removeForwardFile(active.id,it)})
                }
                route=="reader" -> {
                    val summary=s.messages.find{it.id==selected}
                    val full=s.opened?.takeIf{it.id==selected}?:notificationFallback?.takeIf{it.id==selected}
                    val message=full?.let{if(summary==null)it else it.copy(unread=summary.unread,starred=summary.starred)}?:summary
                    if(message==null)MissingMessageScreen(onBack={goInbox()})
                    else ReaderScreen(message,s.accounts.find{it.id==message.accountId},s.busy || message.previewOnly,ownAddresses=s.accounts.map{it.email},attachmentBusy=s.attachmentBusy,onBack={goInbox()},onStar={vm.star(message)},
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
                    val memoryKey="${currentAccount?:"all"}:${folder.name}:${serverPath.orEmpty()}"
                    val memory=inboxMemory.getOrPut(memoryKey){InboxMemory()}
                    savedScreens.SaveableStateProvider(memoryKey){InboxScreen(s,currentAccount,folder,serverPath,memory,onAccount={currentAccount=it;serverPath=null},onEnable=vm::setAccountEnabled,onFolder={folder=it;serverPath=null},
                    onServerFolder={currentAccount=it.accountId;folder=MailFolder.INBOX;serverPath=it.path},onQueue={route="actions"},onLoadFolder=vm::loadFolderHistory,onPauseFolder=vm::pauseFolderHistory,onAdd={route="add"},onExit={(context as? ComponentActivity)?.finish()},
                    onNoticeAnchor={anchor("inbox",it)},onSettings={route="settings"},onDelivery={route="delivery"},onRefresh={if(serverPath!=null || folder in setOf(MailFolder.SENT,MailFolder.DRAFTS))vm.refreshFolders(folder,currentAccount,serverPath)else vm.refresh()},onCompose={compose()},onStar=vm::star,onMarkRead=vm::markRead,onDelete=vm::deleteMessages,onSearch=vm::search,onOpen={m->
                        selected=m.id
                        if(FolderRules.draft(m))vm.openDraft(m.id){full->if(route=="inbox" && selected==m.id)compose(full)}
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

@Composable private fun InboxScreen(s: UiState,accountId: String?,folder: MailFolder,serverPath: String?,memory: InboxMemory,onAccount: (String?)->Unit,onEnable: (String,Boolean)->Unit,onFolder: (MailFolder)->Unit,
    onServerFolder: (ServerFolder)->Unit,onQueue: ()->Unit,onLoadFolder: (String,String)->Unit,onPauseFolder: (String,String)->Unit,
    onAdd: ()->Unit,onExit: ()->Unit,onNoticeAnchor: (Float?)->Unit,onSettings: ()->Unit,onDelivery: ()->Unit,onRefresh: ()->Unit,onCompose: ()->Unit,onStar: (MailItem)->Unit,
    onMarkRead: (Set<String>,Boolean,(Set<String>)->Unit)->Unit,onDelete: (Set<String>,(Set<String>)->Unit)->Unit,onSearch: (String)->Unit,onOpen: (MailItem)->Unit) {
    val drawer=rememberDrawerState(DrawerValue.Closed)
    val scope=rememberCoroutineScope()
    var query by rememberSaveable { mutableStateOf("") }
    var checked by remember { mutableStateOf(arrayListOf<String>()) }
    var exitRequested by rememberSaveable { mutableStateOf(false) }
    var deleting by remember{mutableStateOf<Set<String>?>(null)}
    var selectionMenu by remember{mutableStateOf(false)}
    val controlsProgress=rememberSaveable{mutableFloatStateOf(1f)}
    val focus=LocalFocusManager.current
    val keyboard=LocalSoftwareKeyboardController.current
    var fabTop by remember{mutableStateOf<Float?>(null)}
    var navigationTop by remember{mutableStateOf<Float?>(null)}
    val anchorCallback by rememberUpdatedState(onNoticeAnchor)
    DisposableEffect(Unit){onDispose{anchorCallback(null)}}
    val viewedFolders=s.folders.filter{(accountId==null || it.accountId==accountId) && if(serverPath!=null)it.path==serverPath else when(folder){MailFolder.SENT->it.role==FolderRole.SENT;MailFolder.DRAFTS->it.role==FolderRole.DRAFTS;else->false}}
    val refreshing=s.refreshing || s.unreadRefreshing || viewedFolders.any{it.syncing}
    val pullState=rememberPullToRefreshState()
    var pullAccepted by remember{mutableStateOf(false)}
    var pullGestureArmed by remember{mutableStateOf(false)}
    val latestRefreshing by rememberUpdatedState(refreshing)
    LaunchedEffect(refreshing,pullState.distanceFraction){if(refreshing)pullGestureArmed=false; if(!refreshing && pullState.distanceFraction<0.02f){pullAccepted=false;pullGestureArmed=false}}
    val chrome=remember(memory){InboxChromeController(controlsProgress.floatValue)}

    LaunchedEffect(query){onSearch(query)}
    val visible=remember(s.messages,s.accounts,accountId,folder,query,s.searchIds,s.searchQuery,serverPath){
        val active=SyncRules.activeMessages(s.messages,s.accounts,accountId)
        val source=if(query.isBlank())active else active.filter{s.searchQuery==query && it.id in s.searchIds}
        if(serverPath==null)MailRules.filter(source,accountId,folder,"")else FolderRules.deduplicate(FolderRules.display(source).filter{it.remoteFolder==serverPath && (accountId==null || it.accountId==accountId)},preferDraft=true)
    }
    val visibleIds=remember(visible){visible.map{it.id}}
    val selected=checked.toSet().intersect(visibleIds.toSet())
    val selectionMode=selected.isNotEmpty()
    val chromeFraction=remember(controlsProgress){ {controlsProgress.floatValue} }
    val forceControls by rememberUpdatedState(selectionMode || !s.autoHidePanels)
    LaunchedEffect(selectionMode){if(!selectionMode)selectionMenu=false}
    LaunchedEffect(selectionMode){snapshotFlow{if(controlsProgress.floatValue>0.02f)listOfNotNull(navigationTop,fabTop.takeUnless{selectionMode}).minOrNull()else null}.distinctUntilChanged().collect{anchorCallback(it)}}
    val scrollControls=remember(memory,chrome,focus,keyboard){InboxScrollConnection(chrome,controlsProgress,
        forced={forceControls},canPull={!memory.list.canScrollBackward},refreshing={latestRefreshing},
        onHide={focus.clearFocus();keyboard?.hide()},onPull={pullGestureArmed=true})}
    LaunchedEffect(selectionMode,drawer.currentValue,s.autoHidePanels){if(selectionMode || !s.autoHidePanels || drawer.currentValue==DrawerValue.Open){chrome.show();controlsProgress.floatValue=1f}}

    val current=s.accounts.find{it.id==accountId}
    val selectedAccounts=s.accounts.filter{if(accountId==null)it.enabled else it.id==accountId}
    val hasOlder=selectedAccounts.any{SyncRules.needsHistory(it.id,s.messages,s.history[it.id])}
    val total=selectedAccounts.takeIf{accounts->accounts.all{s.history[it.id]?.total!=null}}?.sumOf{s.history[it.id]?.total?:0}
    val selectedIds=selectedAccounts.map{it.id}.toSet()
    val loaded=s.messages.count{it.localFolder=="inbox" && it.accountId in selectedIds}
    fun toggle(id: String){checked=ArrayList(if(id in selected)selected-id else selected+id)}
    fun mark(unread: Boolean){onMarkRead(selected,unread){updated->checked=ArrayList(checked.filter{it !in updated})}}
    deleting?.let{ids->
        val rows=s.messages.filter{it.id in ids}
        AlertDialog(onDismissRequest={deleting=null},title={Text("Удалить выбранные письма?")},text={Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
            Text("Выбрано писем: ${rows.size}")
            if(rows.any(TrashRules::remote))Text("Письма будут перемещены в Корзину на сервере после подключения к сети.")
            if(rows.any(TrashRules::local))Text("Черновики будут удалены; удаление серверной копии выполнится при подключении к сети.")
        }},confirmButton={TextButton(onClick={deleting=null;onDelete(ids){removed->checked=ArrayList(checked.filter{it !in removed})}},enabled=rows.isNotEmpty() && !s.busy){Text("Удалить")}},dismissButton={TextButton(onClick={deleting=null}){Text("Отмена")}})
    }
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
            LazyColumn {
            item { Column(Modifier.padding(24.dp)){Text("Почта",style=MaterialTheme.typography.headlineMedium,fontWeight=FontWeight.Bold);Text("Ваши почтовые ящики",style=MaterialTheme.typography.bodySmall)} }
            item { NavigationDrawerItem(label={Text("Все ящики")},selected=accountId==null,icon={Icon(Icons.Outlined.AllInbox,null)},modifier=Modifier.padding(horizontal=12.dp),onClick={onAccount(null);scope.launch{drawer.close()}}) }
            items(s.accounts,key={it.id}){a->Row(Modifier.fillMaxWidth().padding(end=12.dp),verticalAlignment=Alignment.CenterVertically) {
                NavigationDrawerItem(label={Column{Text(a.name,maxLines=1,overflow=TextOverflow.Ellipsis);Text(a.email,maxLines=1,overflow=TextOverflow.Ellipsis,style=MaterialTheme.typography.labelSmall);if(!a.enabled)Text("Синхронизация выключена",style=MaterialTheme.typography.labelSmall)}},selected=a.id==accountId,
                    icon={AccountMark(a)},modifier=Modifier.weight(1f).padding(start=12.dp,end=4.dp),onClick={onAccount(a.id);scope.launch{drawer.close()}})
                val unread=s.history[a.id]?.unread?:UnreadCounts.cached(s.messages,a.id)
                Surface(shape=RoundedCornerShape(10.dp),color=if(unread>0)MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer,
                    modifier=Modifier.padding(end=8.dp).semantics{contentDescription="${a.name}: непрочитанных $unread"}) {
                    Text(unread.toString(),Modifier.padding(horizontal=8.dp,vertical=4.dp),fontWeight=if(unread>0)FontWeight.Bold else FontWeight.Normal,
                        style=MaterialTheme.typography.labelMedium,color=if(unread>0)MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(checked=a.enabled,onCheckedChange={onEnable(a.id,it)},modifier=Modifier.semantics{contentDescription="Синхронизация ящика ${a.name}"})
            }}
            item { HorizontalDivider(Modifier.padding(horizontal=24.dp,vertical=12.dp)) }
            if(current!=null) {
                item { Text("Папки на сервере",Modifier.padding(horizontal=24.dp,vertical=8.dp),style=MaterialTheme.typography.labelLarge) }
                items(s.folders.filter{it.accountId==current.id},key={"folder:"+it.path}){f->
                    NavigationDrawerItem(label={Text(f.title,maxLines=1,overflow=TextOverflow.Ellipsis)},selected=serverPath==f.path,
                        icon={Icon(serverFolderIcon(f.role),null)},badge={f.unread?.takeIf{it>0}?.let{Text(it.toString())}},modifier=Modifier.padding(horizontal=12.dp),
                        onClick={if(f.role==FolderRole.INBOX){onAccount(f.accountId);onFolder(MailFolder.INBOX)}else onServerFolder(f);scope.launch{drawer.close()}})
                }
            }
            item { NavigationDrawerItem(label={Text("Исходящие и синхронизация")},selected=false,icon={Icon(Icons.Outlined.Outbox,null)},
                badge={s.actions.size.takeIf{it>0}?.let{Text(it.toString())}},modifier=Modifier.padding(horizontal=12.dp),onClick={scope.launch{drawer.close()};onQueue()}) }
            item {
            TextButton(onClick={scope.launch{drawer.close()};onAdd()},modifier=Modifier.padding(12.dp)){Icon(Icons.Outlined.Add,null);Spacer(Modifier.width(12.dp));Text("Добавить ящик")}

            TextButton(onClick={scope.launch{drawer.close()};onSettings()},modifier=Modifier.padding(12.dp)){Icon(Icons.Outlined.Settings,null);Spacer(Modifier.width(12.dp));Text("Настройки")}
            Text("Почта · 0.5.0",Modifier.padding(24.dp),style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            }
            }
        }
    }) {
        BackHandler {
            when {
                drawer.currentValue!=DrawerValue.Closed || drawer.targetValue!=DrawerValue.Closed -> scope.launch{drawer.close()}
                selectionMode -> checked=arrayListOf()
                else -> exitRequested=true
            }
        }
        if(exitRequested)AlertDialog(onDismissRequest=onExit,properties=DialogProperties(dismissOnClickOutside=false),
            title={Text("Выйти из Почты?")},text={Text("Нажмите «Выйти» или кнопку Android «Назад» ещё раз. Фоновая проверка почты продолжится по настройкам.")},
            confirmButton={TextButton(onClick=onExit){Text("Выйти")}},dismissButton={TextButton(onClick={exitRequested=false}){Text("Остаться")}})
        InboxViewport(fraction=chromeFraction,onHeaderMeasured=chrome::measureHeader,modifier=Modifier.fillMaxSize().safeDrawingPadding(),header={
            Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface)) {
                if(selectionMode)TopAppBar(windowInsets=WindowInsets(0,0,0,0),title={Text("Выбрано: ${selected.size}",maxLines=1,overflow=TextOverflow.Ellipsis)},navigationIcon={IconButton(onClick={checked=arrayListOf()},enabled=!s.busy){Icon(Icons.Outlined.Close,"Снять выделение")}},actions={
                    IconButton(onClick={deleting=selected.toSet()},enabled=!s.busy){Icon(Icons.Outlined.DeleteOutline,"Удалить выбранные письма")}
                    Box {
                        IconButton(onClick={selectionMenu=true},enabled=!s.busy){Icon(Icons.Outlined.MoreVert,"Действия с выбранными письмами")}
                        DropdownMenu(expanded=selectionMenu,onDismissRequest={selectionMenu=false}) {
                            DropdownMenuItem(text={Text("Выбрать все загруженные")},leadingIcon={Icon(Icons.Outlined.SelectAll,null)},onClick={selectionMenu=false;checked=ArrayList(visibleIds)})
                            DropdownMenuItem(text={Text("Отметить прочитанными")},leadingIcon={Icon(Icons.Outlined.MarkEmailRead,null)},onClick={selectionMenu=false;mark(false)})
                            DropdownMenuItem(text={Text("Отметить непрочитанными")},leadingIcon={Icon(Icons.Outlined.MarkEmailUnread,null)},onClick={selectionMenu=false;mark(true)})
                        }
                    }
                }) else TopAppBar(windowInsets=WindowInsets(0,0,0,0),title={Column{Text(viewedFolders.firstOrNull()?.takeIf{serverPath!=null}?.title?:folder.label,fontWeight=FontWeight.Bold);Text((current?.name?:"Все ящики")+if(current?.enabled==false)" · выключен" else "",style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)}},
                    navigationIcon={IconButton(onClick={scope.launch{drawer.open()}}){Icon(Icons.Outlined.Menu,"Выбрать ящик")}},actions={
                        IconButton(onClick=onRefresh,enabled=!s.refreshing && !s.unreadRefreshing){Icon(Icons.Outlined.Refresh,"Обновить почту")};IconButton(onClick=onSettings){Icon(Icons.Outlined.Settings,"Настройки")}
                    })
                Box(Modifier.fillMaxWidth().height(2.dp)) {
                    if(s.busy || refreshing || s.searching && query.isNotBlank())LinearProgressIndicator(Modifier.fillMaxWidth().height(2.dp).semantics{contentDescription="Обновление почты"})
                }
                if(!s.notificationAccess.canPost)TextButton(onClick=onDelivery,modifier=Modifier.fillMaxWidth()){Icon(Icons.Outlined.NotificationsOff,null);Spacer(Modifier.width(8.dp));Text("Уведомления выключены · настроить")}
                OutlinedTextField(value=query,onValueChange={query=it},placeholder={Text("Поиск писем")},leadingIcon={Icon(Icons.Outlined.Search,null)},trailingIcon={if(query.isNotEmpty())IconButton(onClick={query=""}){Icon(Icons.Outlined.Close,"Очистить поиск")}},
                    singleLine=true,shape=RoundedCornerShape(18.dp),modifier=Modifier.fillMaxWidth().padding(horizontal=18.dp,vertical=10.dp))
                if(s.notificationAccess.backgroundRestricted || s.notificationAccess.dataRestricted || s.notificationAccess.standbyRestricted || s.poll.backgroundMinutes==0)
                    TextButton(onClick=onDelivery,modifier=Modifier.fillMaxWidth()){Text("Фоновая проверка ограничена · настроить")}
                Row(Modifier.fillMaxWidth().padding(horizontal=22.dp,vertical=4.dp),verticalAlignment=Alignment.CenterVertically) {
                    Text(if(query.isNotBlank())"Поиск среди загруженных писем" else "${visible.size} писем · ${visible.count{it.unread}} непрочитанных",
                        style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        },bottom={
            Column(Modifier.fillMaxWidth()) {
                if(!selectionMode)Box(Modifier.fillMaxWidth().padding(end=16.dp,bottom=16.dp),contentAlignment=Alignment.CenterEnd) {
                    ExtendedFloatingActionButton(onClick=onCompose,modifier=Modifier.onGloballyPositioned{fabTop=it.boundsInRoot().top},
                        icon={Icon(Icons.Outlined.Edit,null)},text={Text("Написать")})
                }
                NavigationBar(windowInsets=WindowInsets(0,0,0,0),modifier=Modifier.onGloballyPositioned{navigationTop=it.boundsInRoot().top}) {
                    MailFolder.entries.forEach{f->NavigationBarItem(selected=serverPath==null && f==folder,onClick={onFolder(f)},modifier=Modifier.semantics{contentDescription=f.label},icon={Icon(folderIcon(f),null)},label={Text(f.navigationLabel,fontSize=10.sp,maxLines=1,softWrap=false,overflow=TextOverflow.Ellipsis)},alwaysShowLabel=true)}
                }
            }
        },list={bottomPadding->
                PullToRefreshBox(isRefreshing=refreshing,state=pullState,onRefresh={if(!refreshing){pullAccepted=true;pullGestureArmed=false;onRefresh()}},
                    indicator={PullRefreshHint(pullState.distanceFraction,refreshing,pullAccepted,pullGestureArmed,Modifier.align(Alignment.TopCenter))},modifier=Modifier.fillMaxSize()) {
                    LazyColumn(Modifier.fillMaxSize().nestedScroll(scrollControls),state=memory.list,contentPadding=bottomPadding) {
                        if(visible.isEmpty())item(key="empty") {Box(Modifier.fillMaxWidth().padding(vertical=30.dp),contentAlignment=Alignment.Center) {
                            Column(horizontalAlignment=Alignment.CenterHorizontally,modifier=Modifier.padding(24.dp)) {
                                Icon(folderIcon(folder),null,Modifier.size(48.dp),tint=MaterialTheme.colorScheme.outline)
                                Spacer(Modifier.height(16.dp));Text(if(query.isNotBlank())"Ничего не найдено" else if(folder==MailFolder.UNREAD)"Нет непрочитанных писем" else "Здесь пока нет писем",style=MaterialTheme.typography.titleMedium)
                                Text(if(query.isNotBlank())"Попробуйте другое слово" else "Потяните список вниз для проверки почты",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }}
                        if(query.isBlank())items(viewedFolders,key={"folder-status:"+it.accountId+":"+it.path}){f->
                            Column(Modifier.fillMaxWidth().padding(horizontal=22.dp,vertical=8.dp)) {
                                val count=s.messages.count{it.accountId==f.accountId && it.remoteFolder==f.path && FolderRules.remote(it) && !it.pendingDelete}
                                Text((if(accountId==null)(s.accounts.find{it.id==f.accountId}?.name.orEmpty()+" · ")else "")+"Загружено $count"+(f.total?.let{" из $it"}?:""),style=MaterialTheme.typography.labelMedium)
                                if(f.error.isNotBlank())Text(f.error,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.error)
                                if(f.hasOlder && !f.syncAll)TextButton(onClick={onLoadFolder(f.accountId,f.path)}){Text("Загрузить всю папку")}
                                if(f.syncAll)TextButton(onClick={onPauseFolder(f.accountId,f.path)}){Text("История загружается · приостановить")}
                            }
                        }
                        items(visible,key={it.id}){m->MailRow(m,s.accounts.find{it.id==m.accountId},onOpen={if(selectionMode)toggle(m.id)else onOpen(m)},
                            onSelect={toggle(m.id)},selectionMode=selectionMode,checked=m.id in selected,onStar={onStar(m)},busy=s.busy)}
                        if(folder==MailFolder.INBOX && serverPath==null)item(key="history-footer") {
                            Column(Modifier.fillMaxWidth().padding(horizontal=22.dp,vertical=20.dp),horizontalAlignment=Alignment.CenterHorizontally) {
                                Text(if(total==null)"Загружено $loaded писем" else "Загружено $loaded из $total",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                                if(!hasOlder)Text("Все входящие загружены",Modifier.padding(top=8.dp),style=MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }
        })
    }
}

private fun serverFolderIcon(role: FolderRole)=when(role){FolderRole.INBOX->Icons.Outlined.Inbox;FolderRole.SENT->Icons.AutoMirrored.Outlined.Send;FolderRole.DRAFTS->Icons.Outlined.Drafts;FolderRole.TRASH->Icons.Outlined.DeleteOutline;FolderRole.JUNK->Icons.Outlined.Report;FolderRole.ARCHIVE->Icons.Outlined.Archive;else->Icons.Outlined.Folder}

private fun folderIcon(folder: MailFolder)=when(folder){MailFolder.INBOX->Icons.Outlined.Inbox;MailFolder.UNREAD->Icons.Outlined.MarkEmailUnread;MailFolder.STARRED->Icons.Outlined.StarOutline;MailFolder.SENT->Icons.AutoMirrored.Outlined.Send;MailFolder.DRAFTS->Icons.Outlined.Drafts}
@Composable fun AccountMark(account: MailAccount) {
    Surface(color=MaterialTheme.colorScheme.primaryContainer,shape=CircleShape,modifier=Modifier.size(34.dp)) {
        Box(contentAlignment=Alignment.Center){Text(account.provider.mark,fontWeight=FontWeight.Bold,color=MaterialTheme.colorScheme.primary)}
    }
}
private fun displayDate(time: Long)=SimpleDateFormat(if(System.currentTimeMillis()-time<24*3600000L)"HH:mm" else "d MMM",Locale("ru")).format(Date(time))

@Composable private fun MailRow(m: MailItem,account: MailAccount?,onOpen: ()->Unit,onSelect: ()->Unit,selectionMode: Boolean,checked: Boolean,onStar: ()->Unit,busy: Boolean) {
    val background=when{checked->MaterialTheme.colorScheme.primaryContainer;m.unread->MaterialTheme.colorScheme.primaryContainer.copy(alpha=0.28f);else->MaterialTheme.colorScheme.surface}
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min).background(MaterialTheme.colorScheme.surface).background(background)
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

@Composable private fun ReaderScreen(m: MailItem,account: MailAccount?,busy: Boolean,ownAddresses: List<String>,attachmentBusy: Set<String>,onBack: ()->Unit,onStar: ()->Unit,onDownload: (Attachment)->Unit,onOpenAttachment: (Attachment)->Unit,onOpenLocal: (OutgoingFile)->Unit,onReply: ()->Unit,onForward: ()->Unit) {
    val conversation=remember(m.body){ConversationText.parse(m.body)}
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
            if(FolderRules.sent(m))InfoCard("Письмо принято SMTP-сервером отправителя. Эта копия хранится на устройстве.")
            HorizontalDivider(Modifier.padding(vertical=10.dp))
            if(m.previewOnly)Text("Открываем сохранённое письмо…",Modifier.padding(vertical=24.dp),color=MaterialTheme.colorScheme.onSurfaceVariant)
            else SelectionContainer { Text(conversation.current.ifBlank{"В письме нет доступного текстового содержимого."},style=MaterialTheme.typography.bodyLarge,lineHeight=27.sp,modifier=Modifier.fillMaxWidth().padding(vertical=14.dp)) }
            if(!m.previewOnly && conversation.messages.isNotEmpty())ConversationHistory(m.id,conversation,ownAddresses)
            m.attachments.forEach { file->
                val preparing="${m.id}|${file.path}" in attachmentBusy
                OutlinedCard(onClick={onOpenAttachment(file)},modifier=Modifier.fillMaxWidth().padding(vertical=5.dp),enabled=!busy && !preparing) {
                    Row(Modifier.padding(start=15.dp,top=10.dp,bottom=10.dp,end=5.dp),verticalAlignment=Alignment.CenterVertically) {
                        Icon(Icons.Outlined.Description,null)
                        Column(Modifier.weight(1f).padding(horizontal=12.dp)){Text(file.name,maxLines=2,overflow=TextOverflow.Ellipsis);Text((if(file.size>0)"${(file.size/1024).coerceAtLeast(1)} КБ · " else "")+(if(preparing)"Загружаем…" else "Нажмите, чтобы открыть"),style=MaterialTheme.typography.labelSmall)}
                        if(preparing)CircularProgressIndicator(Modifier.padding(12.dp).size(20.dp),strokeWidth=2.dp)
                        else IconButton(onClick={onDownload(file)},enabled=!busy){Icon(Icons.Outlined.Download,"Сохранить ${file.name}")}
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
    val context=LocalContext.current
    val newId=rememberSaveable{java.util.UUID.randomUUID().toString()}
    var authKind by rememberSaveable{mutableStateOf(initial?.authKind?:AuthKind.PASSWORD)}
    var googleBusy by rememberSaveable{mutableStateOf(false)}
    val formBusy=busy || googleBusy
    var provider by rememberSaveable { mutableStateOf(initial?.provider?:Provider.REG) }
    var name by rememberSaveable { mutableStateOf(initial?.name.orEmpty()) };var email by rememberSaveable { mutableStateOf(initial?.email.orEmpty()) };var user by rememberSaveable { mutableStateOf(initial?.username.orEmpty()) }
    var senderName by rememberSaveable { mutableStateOf(initial?.senderName?:"Дмитрий") }
    var password by remember { mutableStateOf("") };var showPassword by remember { mutableStateOf(false) }
    var imap by rememberSaveable { mutableStateOf(initial?.imapHost?:provider.imap) };var smtp by rememberSaveable { mutableStateOf(initial?.smtpHost?:provider.smtp) }
    var imapPort by rememberSaveable { mutableStateOf((initial?.imapPort?:993).toString()) };var smtpPort by rememberSaveable { mutableStateOf((initial?.smtpPort?:465).toString()) }
    var imapTls by rememberSaveable { mutableStateOf(initial?.imapStartTls?:false) };var smtpTls by rememberSaveable { mutableStateOf(initial?.smtpStartTls?:false) }
    var advanced by rememberSaveable { mutableStateOf(initial!=null) };var error by remember { mutableStateOf<String?>(null) }
    fun account()=MailAccount(id=initial?.id?:newId,name=name.trim(),email=email.trim(),username=user.ifBlank{email}.trim(),provider=provider,imapHost=imap,imapPort=imapPort.toIntOrNull()?:0,imapStartTls=imapTls,smtpHost=smtp,smtpPort=smtpPort.toIntOrNull()?:0,smtpStartTls=smtpTls,senderName=senderName.trim().ifBlank{name},authKind=authKind)
    fun googleReady(result: AuthorizationResult) {
        googleBusy=false
        if(result.accessToken.isNullOrBlank() || result.grantedScopes.none{it==GoogleMailAuth.SCOPE})error="Google не разрешил доступ к почте. Повторите подключение."
        else onAdd(account(),GoogleMailAuth.MARKER)
    }
    val googleResult=rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()){result->
        googleBusy=false
        if(result.resultCode!=android.app.Activity.RESULT_OK || result.data==null)error="Подключение Google отменено."
        else runCatching{Identity.getAuthorizationClient(context).getAuthorizationResultFromIntent(result.data!!)}
            .onSuccess{googleReady(it)}.onFailure{error=GoogleMailAuth.problem(it)}
    }
    Scaffold(topBar={TopAppBar(title={Text(if(initial==null)"Добавить ящик" else "Настройки ящика")},navigationIcon={IconButton(onClick=onBack,enabled=!formBusy){Icon(Icons.AutoMirrored.Outlined.ArrowBack,"Назад")}})}) { padding->
        Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).imePadding().padding(22.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            Text("Выберите почтовый сервис",style=MaterialTheme.typography.titleLarge,fontWeight=FontWeight.SemiBold)
            FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {Provider.entries.forEach { p->FilterChip(selected=p==provider,onClick={provider=p;authKind=if(p==Provider.GMAIL)AuthKind.GOOGLE else AuthKind.PASSWORD;imap=p.imap;smtp=p.smtp;imapPort="993";smtpPort="465";imapTls=false;smtpTls=false;advanced=p==Provider.CUSTOM},label={Text(p.label)},enabled=!formBusy)} }
            OutlinedTextField(name,{name=it},label={Text("Название ящика")},placeholder={Text("Например, Рабочая почта")},singleLine=true,modifier=Modifier.fillMaxWidth(),enabled=!formBusy)
            OutlinedTextField(senderName,{senderName=it},label={Text("Имя отправителя")},singleLine=true,modifier=Modifier.fillMaxWidth(),enabled=!formBusy)
            OutlinedTextField(email,{email=it.trim()},label={Text("Адрес почты")},singleLine=true,readOnly=initial!=null,keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Email),modifier=Modifier.fillMaxWidth(),enabled=!formBusy)
            if(initial!=null)Text("Для другого адреса добавьте отдельный ящик.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            if(provider==Provider.GMAIL)FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                FilterChip(selected=authKind==AuthKind.GOOGLE,onClick={authKind=AuthKind.GOOGLE;imap="imap.gmail.com";smtp="smtp.gmail.com";user=email;imapPort="993";smtpPort="465";imapTls=false;smtpTls=false},label={Text("Через Google")},enabled=!formBusy)
                FilterChip(selected=authKind==AuthKind.PASSWORD,onClick={authKind=AuthKind.PASSWORD},label={Text("Пароль приложения")},enabled=!formBusy)
            }
            val appPassword=provider in listOf(Provider.MAIL,Provider.YANDEX,Provider.GMAIL)
            if(authKind==AuthKind.PASSWORD) {
            OutlinedTextField(password,{password=it},label={Text(if(initial!=null)"Новый пароль (необязательно)" else if(appPassword)"Пароль приложения" else "Пароль почтового ящика")},singleLine=true,
                visualTransformation=if(showPassword)VisualTransformation.None else PasswordVisualTransformation(),keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Password),
                trailingIcon={IconButton(onClick={showPassword=!showPassword}){Icon(if(showPassword)Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,if(showPassword)"Скрыть пароль" else "Показать пароль")}},modifier=Modifier.fillMaxWidth(),enabled=!formBusy)
            if(initial!=null)Text("Оставьте поле пустым, чтобы сохранить действующий пароль.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            if(appPassword)InfoCard(when(provider){Provider.GMAIL->"Нужен отдельный пароль приложения Google. Обычный пароль аккаунта не подойдёт. Можно выбрать подключение через окно Google.";else->"Создайте отдельный пароль для почтового приложения в настройках безопасности ${provider.label}. Также разрешите доступ по IMAP."})
            } else InfoCard("Google попросит разрешить доступ к почте. Приложение не получает пароль аккаунта. Если разрешение будет отозвано, повторите подключение здесь.")
            TextButton(onClick={advanced=!advanced},enabled=!formBusy){Text(if(advanced)"Скрыть настройки серверов" else "Настройки серверов")}
            if(advanced) {
                OutlinedTextField(user,{user=it},label={Text("Логин (по умолчанию — адрес почты)")},singleLine=true,modifier=Modifier.fillMaxWidth(),enabled=!formBusy)
                OutlinedTextField(imap,{imap=it.trim()},label={Text("Сервер IMAP")},singleLine=true,modifier=Modifier.fillMaxWidth(),enabled=!formBusy)
                OutlinedTextField(imapPort,{imapPort=it.filter(Char::isDigit)},label={Text("Порт IMAP")},singleLine=true,keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Number),modifier=Modifier.fillMaxWidth(),enabled=!formBusy)
                Row(verticalAlignment=Alignment.CenterVertically){Text("IMAP: STARTTLS",Modifier.weight(1f));Switch(checked=imapTls,onCheckedChange={imapTls=it;imapPort=if(it)"143" else "993"},enabled=!formBusy)}
                OutlinedTextField(smtp,{smtp=it.trim()},label={Text("Сервер SMTP")},singleLine=true,modifier=Modifier.fillMaxWidth(),enabled=!formBusy)
                OutlinedTextField(smtpPort,{smtpPort=it.filter(Char::isDigit)},label={Text("Порт SMTP")},singleLine=true,keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Number),modifier=Modifier.fillMaxWidth(),enabled=!formBusy)
                Row(verticalAlignment=Alignment.CenterVertically){Text("SMTP: STARTTLS",Modifier.weight(1f));Switch(checked=smtpTls,onCheckedChange={smtpTls=it;smtpPort=if(it)"587" else "465"},enabled=!formBusy)}
                Text("При выключенном STARTTLS используется TLS с начала соединения. Проверка сертификата обязательна.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            }
            error?.let{Text(it,color=MaterialTheme.colorScheme.error)}
            if(busy)LinearProgressIndicator(Modifier.fillMaxWidth())
            if(initial!=null && (!initial.imapHost.equals(imap,true) || initial.username!=user.ifBlank{email}.trim() || initial.imapPort.toString()!=imapPort || initial.imapStartTls!=imapTls))InfoCard("При смене параметров IMAP или логина входящие будут загружены заново. Черновики и отправленные сохранятся.")
            Button(onClick={
                val a=account()
                val pw=if(provider==Provider.GMAIL)password.replace(" ","") else password
                error=MailRules.validateAccount(a,pw,passwordRequired=initial==null || initial.authKind!=authKind && authKind==AuthKind.PASSWORD)
                if(error==null) {
                    if(authKind==AuthKind.PASSWORD)onAdd(a,pw)
                    else {
                        googleBusy=true
                        Identity.getAuthorizationClient(context).authorize(GoogleMailAuth.request(a.email)).addOnSuccessListener{result->
                            if(result.hasResolution()) {
                                val sender=result.pendingIntent?.intentSender
                                if(sender==null){googleBusy=false;error="Не удалось открыть окно Google."}
                                else googleResult.launch(IntentSenderRequest.Builder(sender).build())
                            }else googleReady(result)
                        }.addOnFailureListener{googleBusy=false;error=GoogleMailAuth.problem(it)}
                    }
                }
            },enabled=!formBusy,modifier=Modifier.fillMaxWidth().height(52.dp)) {Text(if(formBusy)"Подождите…" else if(authKind==AuthKind.GOOGLE)"Подключить через Google" else if(initial==null)"Проверить и подключить" else "Проверить и сохранить")}
            Text("Проверим входящую и исходящую почту без отправки писем.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(18.dp))
        }
    }
}

@Composable private fun ComposeScreen(d: ComposeDraft,s: UiState,onChange: (ComposeDraft)->Unit,onBack: ()->Unit,onDelete: ()->Unit,onSend: ()->Unit,
    onRetryForward: ()->Unit,onRemoveForward: (String)->Unit,onNoticeAnchor: (Float?)->Unit) {
    val context=LocalContext.current
    var senderMenu by remember { mutableStateOf(false) }
    var attachmentError by remember { mutableStateOf<String?>(null) }
    var confirmResend by remember { mutableStateOf(false) }
    if(confirmResend)AlertDialog(onDismissRequest={confirmResend=false},title={Text("Повторить отправку?")},text={Text("Результат предыдущей попытки неизвестен. Если письмо уже доставлено, получатель получит ещё одну копию.")},
        confirmButton={TextButton(onClick={confirmResend=false;onSend()}){Text("Отправить ещё раз")}},dismissButton={TextButton(onClick={confirmResend=false}){Text("Отмена")}})
    DisposableEffect(Unit){onDispose{onNoticeAnchor(null)}}
    val addFiles=rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()){uris->
        val files=uris.mapNotNull { uri->runCatching {
            context.contentResolver.takePersistableUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION)
            var name="Вложение";var size=-1L
            context.contentResolver.query(uri,arrayOf(OpenableColumns.DISPLAY_NAME,OpenableColumns.SIZE),null,null,null)?.use{c->if(c.moveToFirst()){name=c.getString(0)?:name;if(!c.isNull(1))size=c.getLong(1)}}
            OutgoingFile(uri.toString(),name,context.contentResolver.getType(uri)?:"application/octet-stream",size)
        }.getOrNull() }
        if(files.size!=uris.size)attachmentError="Некоторые файлы недоступны. Попробуйте выбрать их из папки на устройстве."
        else if(files.any{it.size>20L*1024*1024}||(d.filesToSend()+files).sumOf{it.size.coerceAtLeast(0)}>25L*1024*1024)attachmentError="До 20 МБ на файл и 25 МБ суммарно."
        else onChange(d.copy(attachments=(d.attachments+files).distinctBy{it.uri}))
    }
    Scaffold(topBar={TopAppBar(title={Text("Новое письмо")},navigationIcon={IconButton(onClick=onBack,enabled=!s.busy){Icon(Icons.AutoMirrored.Outlined.ArrowBack,"Сохранить черновик и закрыть")}},actions={
        IconButton(onClick={addFiles.launch(arrayOf("*/*"))},enabled=!s.busy){Icon(Icons.Outlined.AttachFile,"Добавить вложения")}
        IconButton(onClick=onDelete,enabled=!s.busy){Icon(Icons.Outlined.DeleteOutline,"Удалить черновик")}
    })},bottomBar={Surface(tonalElevation=3.dp,modifier=Modifier.onGloballyPositioned{onNoticeAnchor(it.boundsInRoot().top)}){Row(Modifier.fillMaxWidth().navigationBarsPadding().imePadding().padding(14.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
        OutlinedButton(onClick=onBack,enabled=!s.busy,modifier=Modifier.weight(1f)){Text("Сохранить")}
        Button(onClick={if(d.previousDeliveryUncertain)confirmResend=true else onSend()},enabled=!s.busy && d.forwarding?.ready!=false,modifier=Modifier.weight(1f)){Icon(Icons.AutoMirrored.Outlined.Send,null);Spacer(Modifier.width(8.dp));Text(if(s.busy)"Подождите…" else "Отправить")}
    }}}) { padding->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(10.dp)) {
            if(d.previousDeliveryUncertain)InfoCard("Результат прошлой отправки неизвестен. Проверьте доставку перед повтором.")
            Box(Modifier.padding(horizontal=8.dp)) {
                TextButton(onClick={senderMenu=true},enabled=!s.busy){Text("От: ${s.accounts.find{it.id==d.accountId}?.email.orEmpty()}");Icon(Icons.Outlined.ExpandMore,null)}
                DropdownMenu(expanded=senderMenu,onDismissRequest={senderMenu=false}){s.accounts.forEach{a->DropdownMenuItem(text={Text(a.email+if(a.enabled)"" else " · выключен")},enabled=a.enabled,onClick={onChange(d.copy(accountId=a.id));senderMenu=false})}}
            }
            OutlinedTextField(d.to,{onChange(d.copy(to=it))},label={Text("Кому")},keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Email),modifier=Modifier.fillMaxWidth().padding(horizontal=20.dp),enabled=!s.busy)
            OutlinedTextField(d.subject,{onChange(d.copy(subject=it))},label={Text("Тема")},modifier=Modifier.fillMaxWidth().padding(horizontal=20.dp),enabled=!s.busy)
            d.forwarding?.takeIf{it.files.isNotEmpty()}?.let { plan->
                Column(Modifier.fillMaxWidth().padding(horizontal=20.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
                    Text(if(plan.ready)"Вложения готовы · ${plan.files.size}" else "Подготовка вложений · ${plan.files.count{it.status==ForwardStatus.READY}} из ${plan.files.size}",style=MaterialTheme.typography.labelLarge)
                    if(!plan.ready)Text("Можно вводить адрес и текст. Отправка станет доступна, когда файлы будут готовы.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                    plan.files.forEach{file->ForwardFileCard(file,!s.busy,onRemove={onRemoveForward(file.key)})}
                    if(plan.files.any{it.status==ForwardStatus.ERROR})TextButton(onClick=onRetryForward,enabled=!s.busy){Icon(Icons.Outlined.Refresh,null);Spacer(Modifier.width(6.dp));Text("Повторить подготовку")}
                }
            }
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

@Composable private fun ForwardFileCard(file: ForwardFile,enabled: Boolean,onRemove: ()->Unit) {
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
                IconButton(onClick=onRemove,enabled=enabled){Icon(Icons.Outlined.Close,"Удалить ${file.name} из пересылки")}
            }
            if(file.status==ForwardStatus.DOWNLOADING) {
                if(file.size>0)LinearProgressIndicator(progress={(file.bytes.toFloat()/file.size).coerceIn(0f,1f)},modifier=Modifier.fillMaxWidth().padding(end=8.dp))
                else LinearProgressIndicator(Modifier.fillMaxWidth().padding(end=8.dp))
            }
            if(openError)Text("Не удалось открыть файл. Установите приложение для этого формата.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.error)
        }
    }
}
