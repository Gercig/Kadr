package ru.kadr.app.data

import kotlinx.coroutines.CancellationException

fun Throwable.userMessage(): String {
    if (this is CancellationException) throw this
    return message ?: "Не удалось выполнить операцию"
}
