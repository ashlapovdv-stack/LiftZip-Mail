package ru.liftzip.mail

enum class MailStyle(val key: String,val title: String,val detail: String) {
    STANDARD("standard","Стандартный","Привычный вид приложения"),
    COZY("cozy","Уютный","Тёплый фон, кот на ветке и мягкие карточки"),
    BUSINESS("business","Деловой","Голубой рабочий стол и синие акценты"),
    FOREST("forest","Forest · Лес","Солнечный хвойный лес и мягкий зелёный фон"),
    FROST("frost","Frost · Горы","Снежные вершины и светло-голубые акценты"),
    EMERALD("emerald","Emerald · Листья","Свежие листья с каплями и зелёные акценты"),
    MEADOW("meadow","Meadow · Луг","Ромашка, божья коровка и светлый луг");
    val lightNature: Boolean get()=when(this){FOREST,FROST,EMERALD,MEADOW->true;else->false}
    companion object {fun fromKey(key: String?)=entries.firstOrNull{it.key==key}?:STANDARD}
}
