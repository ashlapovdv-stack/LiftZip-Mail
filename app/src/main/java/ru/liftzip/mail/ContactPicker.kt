package ru.liftzip.mail

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
internal fun ContactPicker(
    store: ContactStore,
    onChoose: (String) -> Unit,
    onClose: () -> Unit
) {
    var contacts by remember { mutableStateOf(store.all()) }
    var query by remember { mutableStateOf("") }
    var editing by remember { mutableStateOf<MailContact?>(null) }
    var creating by remember { mutableStateOf(false) }
    val shown = if (query.isBlank()) contacts else ContactAddresses.suggestions(contacts, query)

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(onClick = onClose) { Text("Закрыть") }
            Text("Адресная книга", style = MaterialTheme.typography.titleLarge)
            IconButton(onClick = { editing = null; creating = true }) {
                Icon(Icons.Outlined.Add, "Добавить контакт")
            }
        }
        OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            label = { Text("Поиск по имени или адресу") }, singleLine = true)
        if (shown.isEmpty()) {
            Text("Контактов нет", Modifier.padding(16.dp))
        }
        LazyColumn {
            items(shown, key = { it.id }) { contact ->
                ListItem(
                    headlineContent = { Text(contact.name) },
                    supportingContent = { Text(contact.emails.joinToString(", ")) },
                    modifier = Modifier.clickable { editing = contact; creating = false },
                    trailingContent = {
                        TextButton(onClick = { onChoose(contact.emails.first()) }) { Text("Выбрать") }
                    }
                )
                HorizontalDivider()
            }
        }
    }
    if (creating || editing != null) {
        val original = editing
        var name by remember(original?.id, creating) { mutableStateOf(original?.name.orEmpty()) }
        var addresses by remember(original?.id, creating) {
            mutableStateOf(original?.emails?.joinToString("\n").orEmpty())
        }
        var error by remember(original?.id, creating) { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { creating = false; editing = null },
            title = { Text(if (original == null) "Новый контакт" else "Изменить контакт") },
            text = {
                Column {
                    OutlinedTextField(name, { name = it }, label = { Text("Имя") }, singleLine = true)
                    OutlinedTextField(addresses, { addresses = it },
                        label = { Text("Адреса email, каждый с новой строки") })
                    if (error) Text("Укажите имя и корректные адреса электронной почты",
                        color = MaterialTheme.colorScheme.error)
                    if (original != null) TextButton(onClick = {
                        store.delete(original.id)
                        contacts = store.all()
                        editing = null
                    }) {
                        Icon(Icons.Outlined.DeleteOutline, null)
                        Text("Удалить контакт")
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val emails = ContactAddresses.clean(addresses.lines())
                    if (name.isBlank() || emails.isEmpty() || emails.any {
                            !android.util.Patterns.EMAIL_ADDRESS.matcher(it).matches()
                        }) {
                        error = true
                    } else {
                        store.save(MailContact(original?.id ?: java.util.UUID.randomUUID().toString(),
                            name.trim(), emails))
                        contacts = store.all()
                        editing = null
                        creating = false
                    }
                }) { Text("Сохранить") }
            },
            dismissButton = {
                TextButton(onClick = { editing = null; creating = false }) { Text("Отмена") }
            }
        )
    }
}
