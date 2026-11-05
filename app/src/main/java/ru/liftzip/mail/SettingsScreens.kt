@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package ru.liftzip.mail

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable private fun SettingsPage(title: String,onBack: ()->Unit,content: @Composable ColumnScope.()->Unit) {
    Scaffold(topBar={TopAppBar(title={Text(title)},navigationIcon={IconButton(onClick=onBack){Icon(Icons.AutoMirrored.Outlined.ArrowBack,"Назад")}})}){padding->
        Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),verticalArrangement=Arrangement.spacedBy(12.dp),content=content)
    }
}

@Composable private fun SettingsLink(title: String,detail: String,onClick: ()->Unit) {
    ListItem(headlineContent={Text(title)},supportingContent={Text(detail)},trailingContent={Icon(Icons.Outlined.ChevronRight,null)},modifier=Modifier.fillMaxWidth().clickable(onClick=onClick))
}

@Composable fun SettingsScreen(s: UiState,onBack: ()->Unit,onAdd: ()->Unit,onEdit: (String)->Unit,onSection: (String)->Unit,onHistory: ()->Unit,onRemove: (String)->Unit) {
    var removal by remember{mutableStateOf<MailAccount?>(null)}
    removal?.let{a->AlertDialog(onDismissRequest={removal=null},title={Text("Удалить ящик из приложения?")},
        text={Text("${a.email}\n\nЛокальные письма и черновики будут удалены с этого устройства. Письма на сервере сохранятся. Чтобы временно приостановить ящик, используйте переключатель в боковом меню.")},
        confirmButton={TextButton(onClick={onRemove(a.id);removal=null}){Text("Удалить")}},dismissButton={TextButton(onClick={removal=null}){Text("Отмена")}})}
    SettingsPage("Настройки",onBack) {
        Text("Почтовые ящики",style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.SemiBold)
        s.accounts.forEach{a->OutlinedCard(onClick={onEdit(a.id)},enabled=!s.busy,modifier=Modifier.fillMaxWidth()) {
            Row(Modifier.padding(start=12.dp,top=8.dp,bottom=8.dp,end=4.dp),verticalAlignment=androidx.compose.ui.Alignment.CenterVertically) {
                AccountMark(a)
                Column(Modifier.weight(1f).padding(horizontal=10.dp)) {
                    Text(a.name,fontWeight=FontWeight.SemiBold)
                    Text(a.email,maxLines=1,overflow=TextOverflow.Ellipsis,style=MaterialTheme.typography.bodySmall)
                    if(!a.enabled)Text("Синхронизация выключена",style=MaterialTheme.typography.labelSmall)
                }
                IconButton(onClick={onEdit(a.id)},enabled=!s.busy){Icon(Icons.Outlined.Edit,"Изменить настройки ящика")}
                IconButton(onClick={removal=a},enabled=!s.busy){Icon(Icons.Outlined.DeleteOutline,"Удалить ящик из приложения")}
            }
        }}
        OutlinedButton(onClick=onAdd,enabled=!s.busy,modifier=Modifier.fillMaxWidth()){Icon(Icons.Outlined.Add,null);Spacer(Modifier.width(8.dp));Text("Добавить ящик")}
        HorizontalDivider(Modifier.padding(vertical=4.dp))
        SettingsLink("Получение и уведомления","Интервалы проверки и уведомления Android"){onSection("delivery")}
        SettingsLink("Внешний вид","Стиль приложения и панели при прокрутке"){onSection("appearance")}
        val sender=s.accounts.find{it.id==s.defaultAccountId}
        SettingsLink("Отправитель по умолчанию",sender?.email?:"Ящик открытой папки"){onSection("sender")}
        SettingsLink("Загрузить всю историю","Все или выбранные ящики",onHistory)
        SettingsLink("Хранение","Кэш писем и вложений"){onSection("storage")}
        SettingsLink("Дополнительно","Диагностика и сведения о приложении"){onSection("advanced")}
    }
}

@Composable fun AppearanceSettingsScreen(s: UiState,onBack: ()->Unit,onAutoHide: (Boolean)->Unit,onStyle: (MailStyle)->Unit) {
    SettingsPage("Внешний вид",onBack) {
        Text("Стиль приложения",style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.SemiBold)
        MailStyle.entries.forEach{style->StyleChoice(style,s.style==style){onStyle(style)}}
        HorizontalDivider(Modifier.padding(vertical=8.dp))
        ListItem(headlineContent={Text("Скрывать панели при прокрутке")},supportingContent={Text(if(s.autoHidePanels)"При просмотре старых писем список занимает экран. Прокрутите обратно, чтобы вернуть кнопки." else "Верхняя панель, нижняя навигация и кнопка «Написать» всегда видны.")},
            trailingContent={Switch(checked=s.autoHidePanels,onCheckedChange=onAutoHide)},modifier=Modifier.fillMaxWidth())
    }
}

@Composable fun DeliverySettingsScreen(s: UiState,onBack: ()->Unit,onPoll: (PollSettings)->Unit,onFastMail: (Boolean)->Unit,onPermission: ()->Unit,onDiagnostics: ()->Unit) {
    val context=LocalContext.current
    SettingsPage("Получение и уведомления",onBack) {
        Text("Проверка новой почты",style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.SemiBold)
        PollPicker("Когда приложение открыто",s.poll.foregroundMinutes,PollSettings.foregroundChoices,!s.busy){onPoll(s.poll.copy(foregroundMinutes=it))}
        PollPicker("В фоне",s.poll.backgroundMinutes,PollSettings.backgroundChoices,!s.busy){onPoll(s.poll.copy(backgroundMinutes=it))}
        Text("При возвращении проверяем почту сразу. Android разрешает периодическую проверку в фоне от 15 минут и может откладывать её при энергосбережении.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
        HorizontalDivider(Modifier.padding(vertical=6.dp))
        ListItem(headlineContent={Text("Быстрое получение")},supportingContent={Text("Ожидание новых писем через IMAP IDLE с тихим постоянным уведомлением")},trailingContent={Switch(checked=s.fastMail.enabled,onCheckedChange=onFastMail)})
        if(s.fastMail.enabled) {
            Text(if(s.fastMail.running)"Быстрый режим работает" else if(s.fastMail.starting)"Запускается…" else "Быстрый режим остановлен",style=MaterialTheme.typography.bodyMedium)
            s.accounts.filter{it.enabled}.forEach{a->Text(a.name+": "+(s.fastMail.accounts[a.id]?:"Ожидание подключения"),style=MaterialTheme.typography.bodySmall)}
            if(s.fastMail.error.isNotBlank())Text(s.fastMail.error,color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.bodySmall)
            if(!s.fastMail.running && !s.fastMail.starting)OutlinedButton(onClick={onFastMail(true)}){Text("Возобновить быстрый режим")}
        }
        Text("Режим использует больше энергии. Android 15 и новее ограничивает такую работу в фоне шестью часами; после остановки остаётся проверка по расписанию. При открытии приложения быстрый режим возобновляется. Сеть, энергосбережение и ограничения производителя могут задерживать почту.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)

        Text("Уведомления",style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.SemiBold)
        Text(if(s.notificationAccess.canPost)"Показ новых писем разрешён" else "Уведомления выключены в Android",color=if(s.notificationAccess.canPost)MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error)
        OutlinedButton(onClick=onPermission,modifier=Modifier.fillMaxWidth()){Text(if(s.notificationAccess.canPost)"Настроить уведомления Android" else "Разрешить уведомления")}
        if(s.notificationAccess.backgroundRestricted || s.notificationAccess.standbyRestricted || s.notificationAccess.dataRestricted) {
            Text("Android ограничивает работу приложения в фоне. Проверьте разрешения аккумулятора и фонового трафика.",style=MaterialTheme.typography.bodySmall)
            TextButton(onClick={runCatching{context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,Uri.parse("package:"+context.packageName)))}}){Text("Открыть настройки приложения")}
        }
        SettingsLink("Помощь с получением писем","Проверить работу фона и посмотреть результат",onDiagnostics)
    }
}

@Composable fun SenderSettingsScreen(s: UiState,onBack: ()->Unit,onDefault: (String?)->Unit) {
    SettingsPage("Отправитель по умолчанию",onBack) {
        Text("Для новых писем и пересылки. Ответ отправляется с того ящика, на который пришло письмо. Отправителя можно изменить в редакторе.",style=MaterialTheme.typography.bodyMedium)
        ListItem(headlineContent={Text("Ящик открытой папки")},supportingContent={Text("Во всех ящиках — первый включённый")},leadingContent={RadioButton(selected=s.defaultAccountId==null,onClick=null)},modifier=Modifier.clickable(enabled=!s.busy){onDefault(null)})
        s.accounts.forEach{a->ListItem(headlineContent={Text(a.name)},supportingContent={Text(a.email+if(a.enabled)"" else " · выключен")},
            leadingContent={RadioButton(selected=s.defaultAccountId==a.id,onClick=null,enabled=a.enabled)},modifier=Modifier.clickable(enabled=!s.busy && a.enabled){onDefault(a.id)})}
    }
}

@Composable fun AdvancedSettingsScreen(onBack: ()->Unit,onSection: (String)->Unit) {
    SettingsPage("Дополнительно",onBack) {
        SettingsLink("Диагностика","Проверки, состояние фона и загрузки текстов"){onSection("diagnostics")}
        SettingsLink("О приложении","Версия, хранение писем и вложений"){onSection("about")}
    }
}

@Composable fun DiagnosticsScreen(s: UiState,onBack: ()->Unit,onPermission: ()->Unit,onTestNotification: ()->Unit,onCheck: ()->Unit,onTestBackground: ()->Unit,onRestore: ()->Unit,onRetryBodies: ()->Unit) {
    SettingsPage("Диагностика",onBack) {
        BackgroundSettings(s,onPermission,onTestNotification,onCheck,onTestBackground,onRestore,onRetryBodies)
        HorizontalDivider(Modifier.padding(vertical=8.dp))
        Text("Проверки ящиков",style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.SemiBold)
        s.accounts.forEach{a->
            Text(a.name,fontWeight=FontWeight.SemiBold)
            val check=s.checks[a.id]
            Text(if(!a.enabled)"Синхронизация выключена" else if(check!=null && check.time>0)"Успешная проверка: "+SimpleDateFormat("d MMM, HH:mm",Locale.forLanguageTag("ru")).format(Date(check.time)) else "Успешная проверка ещё не выполнена",style=MaterialTheme.typography.bodySmall)
            if(check!=null && check.durationMs>0)Text("Последний запрос: "+String.format(Locale.forLanguageTag("ru"),"%.1f с",check.durationMs/1000.0),style=MaterialTheme.typography.bodySmall)
            if(check?.error?.isNotBlank()==true)Text(check.error,color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable fun AboutSettingsScreen(onBack: ()->Unit) {
    SettingsPage("О приложении",onBack) {
        Text("Почта · 0.5.6",style=MaterialTheme.typography.titleLarge,fontWeight=FontWeight.SemiBold)
        Text("Поиск работает по загруженным письмам. Черновики и отправленные сохраняются на устройстве и синхронизируются с папками сервера. Состояние видно в «Исходящих и синхронизации».")
        Text("Вложения открываются нажатием на файл; значок загрузки сохраняет копию. PDF и изображения можно смотреть внутри приложения. Для других форматов используется установленная программа.")
        Text("Пароли защищены Android Keystore. Резервное копирование почтовых данных отключено. Внешний сервер приложения не используется.")
        Text("Тестовая сборка",style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable fun MissingMessageScreen(onBack: ()->Unit) {
    SettingsPage("Письмо недоступно",onBack) {
        Text("Письмо из уведомления больше не найдено на устройстве. Возможно, оно удалено или ящик был переподключён.")
        Button(onClick=onBack){Text("К списку писем")}
    }
}

@Composable private fun PollPicker(label: String,value: Int,choices: List<Int>,enabled: Boolean,onChange: (Int)->Unit) {
    var expanded by remember{mutableStateOf(false)}
    fun title(minutes: Int)=when(minutes){0->"Отключена";60->"Каждый час";120->"Каждые 2 часа";1->"Каждую минуту";else->"Каждые $minutes минут"}
    Column {
        Text(label,style=MaterialTheme.typography.labelLarge)
        Box {
            OutlinedButton(onClick={expanded=true},enabled=enabled,modifier=Modifier.fillMaxWidth()){Text(title(value),Modifier.weight(1f));Icon(Icons.Outlined.ExpandMore,null)}
            DropdownMenu(expanded=expanded,onDismissRequest={expanded=false}){choices.forEach{minutes->DropdownMenuItem(text={Text(title(minutes))},onClick={expanded=false;onChange(minutes)})}}
        }
    }
}

@Composable fun StorageSettingsScreen(s: UiState,onBack: ()->Unit,onInspect: ()->Unit,onLimit: (Int)->Unit,onClear: ()->Unit) {
    var expanded by remember{mutableStateOf(false)}
    var clear by remember{mutableStateOf(false)}
    LaunchedEffect(Unit){onInspect()}
    fun mb(bytes: Long)=String.format(Locale.forLanguageTag("ru"),"%.1f МБ",bytes/1048576.0)
    if(clear)AlertDialog(onDismissRequest={clear=false},title={Text("Очистить кэш?")},text={Text("Тексты серверных писем и файлы для просмотра будут загружены заново при открытии. Черновики, Исходящие и сохранённые вами файлы останутся. Недавние и непрочитанные письма могут снова загрузиться в фоне.")},confirmButton={TextButton(onClick={clear=false;onClear()}){Text("Очистить")}},dismissButton={TextButton(onClick={clear=false}){Text("Отмена")}})
    SettingsPage("Хранение",onBack) {
        Text("Кэш: ${mb(s.storage.bytes)}",style=MaterialTheme.typography.titleLarge)
        Text("Тексты: ${mb(s.storage.bodyBytes)} · ${s.storage.cachedBodies} писем\nВложения для просмотра: ${mb(s.storage.attachmentBytes)}",style=MaterialTheme.typography.bodyMedium)
        Text("Предел кэша",style=MaterialTheme.typography.labelLarge)
        Box {
            OutlinedButton(onClick={expanded=true},enabled=!s.busy,modifier=Modifier.fillMaxWidth()){Text(if(s.storage.limitMb==0)"Без ограничения" else "${s.storage.limitMb} МБ",Modifier.weight(1f));Icon(Icons.Outlined.ExpandMore,null)}
            DropdownMenu(expanded=expanded,onDismissRequest={expanded=false}){CacheRules.choices.forEach{value->DropdownMenuItem(text={Text(if(value==0)"Без ограничения" else "$value МБ")},onClick={expanded=false;onLimit(value)})}}
        }
        Text("60% отведено текстам, 40% — вложениям для просмотра. Недавно открытые файлы временно сохраняются сверх предела. Локальные черновики и файлы очереди хранятся отдельно и не удаляются автоматически.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedButton(onClick={clear=true},enabled=!s.busy,modifier=Modifier.fillMaxWidth()){Text("Очистить кэш")}
        Text("Размер показывает содержимое кэша. База данных также хранит заголовки писем и служебные данные.",style=MaterialTheme.typography.bodySmall)
    }
}
