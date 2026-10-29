package ru.liftzip.mail

object BackgroundHealthRules {
    fun stopReason(code: Int?): String?=when(code){
        null->null
        3->"Android остановил слишком долгое задание"
        10->"Android исчерпал выделенное приложению время фоновой работы"
        11->"Android запретил фоновую работу приложения"
        12->"Android ограничил редко используемое приложение"
        13->"Задание остановлено пользователем"
        else->"Последняя остановка Android: код $code"
    }
    fun summary(poll: PollSettings,access: NotificationAccess,runtime: BackgroundRuntime,background: BackgroundCheck,now: Long): String=when {
        poll.backgroundMinutes==0->"Автоматическая проверка в фоне выключена в настройках Почты."
        access.backgroundRestricted || access.standbyRestricted->"Android ограничивает фоновую работу Почты. Проверьте настройки аккумулятора и автозапуска."
        access.dataRestricted->"Экономия трафика ограничивает доступ Почты к сети в фоне."
        !access.connected->"Сейчас нет доступного подключения к сети."
        runtime.state=="STOPPED"->"Автоматическое задание не запланировано. Нажмите «Восстановить проверку»."
        background.automaticFinished>0 && background.automaticResult=="retry"->"Автоматическая проверка завершилась ошибкой. Ожидается повтор."
        background.automaticFinished>0 && now-background.automaticFinished>poll.backgroundMinutes*120_000L->"Давно не было завершённой автоматической проверки. Android мог отложить запуск."
        access.deviceIdle->"Телефон в режиме сна. Android может отложить проверку почты."
        access.powerSave->"Включено энергосбережение. Фоновая проверка может задерживаться."
        background.automaticFinished>0 && background.automaticResult=="success"->"Автоматическая проверка выполнялась успешно."
        else->"Проверка запланирована. Время запуска зависит от Android."
    }
}
