package ru.liftzip.mail

object InboxPaging {
    const val PAGE_SIZE = 100

    fun latest(count: Int, size: Int = PAGE_SIZE): IntRange? = if (count <= 0) null else (count - size.coerceAtLeast(1) + 1).coerceAtLeast(1)..count

    fun older(oldestLoadedSequence: Int, size: Int = PAGE_SIZE): IntRange? = if (oldestLoadedSequence <= 1) null
        else (oldestLoadedSequence - size.coerceAtLeast(1)).coerceAtLeast(1)..(oldestLoadedSequence - 1)
}
