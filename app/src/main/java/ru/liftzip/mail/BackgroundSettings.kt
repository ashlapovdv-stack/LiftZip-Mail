package ru.liftzip.mail

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable fun BackgroundSettings(s: UiState,onPermission: ()->Unit,onTestNotification: ()->Unit,onCheck: ()->Unit,onTestBackground: ()->Unit,onRestore: ()->Unit,onRetryBodies: ()->Unit) {
    val context=LocalContext.current
    fun open(action: String,packageUri: Boolean=false){
        runCatching{context.startActivity(Intent(action).apply{if(packageUri)data=Uri.parse("package:"+context.packageName)})}
            .onFailure{runCatching{context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,Uri.parse("package:"+context.packageName)))}}
    }
    fun time(value: Long)=if(value<=0)"ещё не было" else SimpleDateFormat("d MMM, HH:mm:ss",Locale.forLanguageTag("ru")).format(Date(value))
    val access=s.notificationAccess;val bg=s.background;val runtime=s.backgroundRuntime
    Text("Уведомления и фон",style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.SemiBold)
    Text(when{!access.permission->"Уведомления не разрешены приложению.";!access.appEnabled->"Уведомления приложения выключены в Android.";!access.channelEnabled->"Канал «Новые письма» выключен в Android.";else->"Показ уведомлений разрешён."},color=if(access.canPost)MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error)
    Text("Проверка серверов и загрузка текстов работают независимо от разрешения на показ уведомлений.",style=MaterialTheme.typography.bodySmall)
    OutlinedButton(onClick=onPermission){Text(if(access.canPost)"Настройки уведомлений Android" else "Разрешить уведомления")}
    if(!access.canPost)TextButton(onClick={context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE,context.packageName))}){Text("Открыть системные настройки уведомлений")}
    OutlinedButton(onClick=onTestNotification,enabled=!s.busy){Text("Проверочное уведомление")}
    Text(BackgroundHealthRules.summary(s.poll,access,runtime,bg,System.currentTimeMillis()),style=MaterialTheme.typography.bodyMedium)
    Text(if(access.backgroundRestricted || access.standbyRestricted)"Фоновая работа ограничена Android." else "Явный запрет фоновой работы не обнаружен.",style=MaterialTheme.typography.bodySmall)
    Text(if(access.batteryExempt)"Аккумулятор: Почта исключена из оптимизации." else "Аккумулятор: Android может ограничивать работу Почты при выключенном экране.",style=MaterialTheme.typography.bodySmall)
    OutlinedButton(onClick={open(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,true)}){Text("Аккумулятор и фоновая работа Почты")}
    Text("В настройках Android выберите работу без ограничений, если нужна более своевременная почта. На некоторых телефонах также требуется разрешить автозапуск.",style=MaterialTheme.typography.bodySmall)
    TextButton(onClick={open(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)}){Text("Открыть оптимизацию аккумулятора")}
    if(access.dataRestricted){
        Text("Экономия трафика ограничивает фоновую сеть.",color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.bodySmall)
        TextButton(onClick={open(Settings.ACTION_IGNORE_BACKGROUND_DATA_RESTRICTIONS_SETTINGS,true)}){Text("Разрешить фоновый трафик")}
    }
    BackgroundHealthRules.stopReason(runtime.stopReason)?.let{Text(it,color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.bodySmall)}
    Text("Последний автоматический запуск: "+time(bg.automaticStarted),style=MaterialTheme.typography.bodySmall)
    Text("Завершение автоматической проверки: "+time(bg.automaticFinished),style=MaterialTheme.typography.bodySmall)
    Text("Последний запуск (${if(bg.kind=="test")"проверочный" else "автоматический"}): "+time(bg.started),style=MaterialTheme.typography.bodySmall)
    Text("Результат: "+when(bg.result){"running"->"проверяем почту и загружаем тексты";"success"->"успешно, новых непрочитанных: ${bg.newUnread}";"retry"->"ошибка, запланирован повтор";"stopped"->"задание прервано";else->"ещё не завершался"},style=MaterialTheme.typography.bodySmall)
    if(bg.finished>0)Text("Завершение: "+time(bg.finished),style=MaterialTheme.typography.bodySmall)
    if(bg.error.isNotBlank())Text(bg.error,color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.bodySmall)
    Text("Автоматическое задание: "+when(runtime.state){"RUNNING"->"выполнение";"ENQUEUED","BLOCKED"->"ожидание Android";"STOPPED"->"не запланировано";else->"проверяем"},style=MaterialTheme.typography.bodySmall)
    if(runtime.nextRunAt>0)Text("Расчётное время следующего запуска: "+time(runtime.nextRunAt)+". Android может выполнить позже.",style=MaterialTheme.typography.bodySmall)
    if(s.poll.backgroundMinutes>0)OutlinedButton(onClick=onRestore){Text("Восстановить проверку")}
    OutlinedButton(onClick=onTestBackground,enabled=runtime.testState !in listOf("ENQUEUED","RUNNING","BLOCKED")){Text("Проверить работу в фоне")}
    Text("После нажатия сверните приложение. Задание сможет запуститься через 10 секунд, когда Android разрешит. Вернувшись, проверьте время и результат проверочного запуска.",style=MaterialTheme.typography.bodySmall)
    OutlinedButton(onClick=onCheck,enabled=!s.refreshing && !s.unreadRefreshing){Text("Проверить почту сейчас")}
    Text("Готовность текстов писем",style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.SemiBold)
    Text(if(s.bodyCache.running)"Тексты загружаются в фоне · осталось ${s.bodyCache.pending}" else if(s.bodyCache.pending>0)"Ожидают загрузки: ${s.bodyCache.pending}" else "Тексты последних и загруженных непрочитанных писем сохранены.")
    if(s.bodyCache.finished>0)Text("Последняя загрузка: ${time(s.bodyCache.finished)} · сохранено ${s.bodyCache.saved}",style=MaterialTheme.typography.bodySmall)
    if(s.bodyCache.error.isNotBlank())Text(s.bodyCache.error,color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.bodySmall)
    Text("В первую очередь загружаем текст новых и непрочитанных писем, затем последних входящих. Вложения скачиваются по запросу.",style=MaterialTheme.typography.bodySmall)
    if(s.bodyCache.pending>0)OutlinedButton(onClick=onRetryBodies){Text("Повторить загрузку текстов")}
    TextButton(onClick={
        val report="Почта 0.4.3\nAndroid ${android.os.Build.VERSION.RELEASE}, ${android.os.Build.MANUFACTURER}\n"+
            "Фон: ${s.poll.backgroundMinutes} мин; ${runtime.state}; остановка ${runtime.stopReason}; попытка ${runtime.attempt}\n"+
            "Уведомления: ${access.canPost}; фон ограничен: ${access.backgroundRestricted}; экономия трафика: ${access.dataRestricted}; аккумулятор без ограничений: ${access.batteryExempt}; standby: ${access.standbyRestricted}; сеть: ${access.connected}\n"+
            "Автоматически: ${time(bg.automaticStarted)} — ${time(bg.automaticFinished)}, ${bg.automaticResult}\n"+
            "Последний запуск: ${bg.kind}, ${time(bg.started)} — ${time(bg.finished)}, ${bg.result}; ${bg.error}\n"+
            "Тексты: ${s.bodyCache.pending} ожидают; ${s.bodyCache.saved} сохранено; ${s.bodyCache.error}"
        context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Диагностика Почты",report))
    }){Text("Скопировать диагностику без паролей")}
}
