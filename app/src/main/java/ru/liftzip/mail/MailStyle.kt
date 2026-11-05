package ru.liftzip.mail

enum class MailStyle(val key: String,val title: String,val detail: String) {
    STANDARD("standard","Стандартный","Привычный вид приложения"),
    COZY("cozy","Уютный","Тёплый фон, кот на ветке и мягкие карточки"),
    BUSINESS("business","Деловой","Голубой рабочий стол и синие акценты");
    companion object {fun fromKey(key: String?)=entries.firstOrNull{it.key==key}?:STANDARD}
}
