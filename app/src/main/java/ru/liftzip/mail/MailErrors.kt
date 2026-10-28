package ru.liftzip.mail

import jakarta.mail.AuthenticationFailedException
import jakarta.mail.MessagingException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

fun friendlyError(error: Throwable): String {
    val chain=mutableListOf<Throwable>()
    val pending=java.util.ArrayDeque<Throwable>().apply{add(error)}
    while(pending.isNotEmpty() && chain.size<32) {
        val item=pending.removeFirst()
        if(chain.any{it===item})continue
        chain+=item
        item.cause?.let{pending.add(it)}
        (item as? MessagingException)?.nextException?.let{pending.add(it)}
    }
    val text=when {
        chain.any{it is DeliveryUncertainException}->chain.first{it is DeliveryUncertainException}.message.orEmpty()
        chain.any{it is AuthenticationFailedException}->"Не удалось войти. Проверьте полный логин и пароль ящика или приложения."
        chain.any{it is SSLException}->"Не удалось проверить защищённое соединение. Проверьте дату на телефоне и повторите без VPN."
        chain.any{it is UnknownHostException}->"Не найден адрес сервера. Проверьте интернет, имя сервера и повторите без VPN."
        chain.any{it is SocketTimeoutException}->"Сервер не ответил вовремя. Попробуйте другую сеть или отключите VPN."
        chain.any{it is ConnectException}->"Не удалось соединиться с сервером. Проверьте порт, интернет и VPN."
        error is IllegalArgumentException || error is IllegalStateException->error.message?:"Проверьте настройки."
        else->"Операция не завершена. Повторите проверку; если ошибка сохраняется, попробуйте другую сеть."
    }
    val stage=chain.filterIsInstance<MailConnectionException>().firstOrNull()
    return if(stage==null)text else "${stage.stage} (${stage.host}:${stage.port}): $text"
}
