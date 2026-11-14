package ru.liftzip.mail

import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
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
    var choosing by remember { mutableStateOf<MailContact?>(null) }
    val shown = if (query.isBlank()) contacts else ContactAddresses.suggestions(contacts, query)

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().height(64.dp).padding(horizontal=12.dp),
            verticalAlignment=Alignment.CenterVertically) {
            IconButton(onClick=onClose) {
                Icon(Icons.AutoMirrored.Outlined.ArrowBack,"Назад",tint=MaterialTheme.colorScheme.primary)
            }
            Text("Контакты",Modifier.weight(1f).padding(start=8.dp),
                style=MaterialTheme.typography.headlineSmall)
            IconButton(onClick={editing=null;creating=true}) {
                Icon(Icons.Outlined.Add,"Добавить контакт")
            }
        }
        OutlinedTextField(query,{query=it},Modifier.fillMaxWidth().padding(horizontal=20.dp),
            label={Text("Поиск контактов")},singleLine=true)
        Spacer(Modifier.height(12.dp))
        LazyColumn(Modifier.fillMaxSize()) {
            if(query.isBlank()) {
                item {
                    Text("Популярные",Modifier.padding(start=20.dp,top=16.dp,bottom=8.dp),
                        style=MaterialTheme.typography.titleMedium)
                }
            }
            items(shown,key={it.id}) { contact ->
                Row(
                    Modifier.fillMaxWidth().clickable {
                        if(contact.emails.size==1) onChoose(contact.emails.first())
                        else choosing=contact
                    }.heightIn(min=90.dp).padding(horizontal=20.dp,vertical=12.dp),
                    verticalAlignment=Alignment.CenterVertically
                ) {
                    Surface(Modifier.size(58.dp),shape=CircleShape,
                        color=Color(0xFFEDEDED)) {
                        Box(contentAlignment=Alignment.Center) {
                            Text(contact.name.take(1).uppercase(),
                                style=MaterialTheme.typography.headlineSmall,color=Color(0xFF777777))
                        }
                    }
                    Column(Modifier.padding(start=18.dp).weight(1f)) {
                        Text(contact.name,style=MaterialTheme.typography.titleMedium,
                            maxLines=1,overflow=TextOverflow.Ellipsis)
                        Text(contact.emails.firstOrNull().orEmpty(),
                            style=MaterialTheme.typography.bodyLarge,color=Color(0xFF929292),
                            maxLines=1,overflow=TextOverflow.Ellipsis)
                    }
                    TextButton(onClick={editing=contact}) { Text("Изменить") }
                }
            }
            if(shown.isEmpty()) item {
                Text("Контактов нет",Modifier.padding(24.dp))
            }
        }
    }
    choosing?.let { contact ->
        AlertDialog(onDismissRequest={choosing=null},title={Text(contact.name)},
            text={Column { contact.emails.forEach { email ->
                TextButton(onClick={choosing=null;onChoose(email)}) { Text(email) }
            } }},confirmButton={TextButton(onClick={choosing=null}){Text("Закрыть")}})
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
