package ru.liftzip.mail

object InboxPaging {
    const val PAGE_SIZE = 100

    fun latest(count: Int): IntRange? = if (count <= 0) null else (count - PAGE_SIZE + 1).coerceAtLeast(1)..count

    fun older(oldestLoadedSequence: Int): IntRange? = if (oldestLoadedSequence <= 1) null
        else (oldestLoadedSequence - PAGE_SIZE).coerceAtLeast(1)..(oldestLoadedSequence - 1)
}
