package pl.cyphr.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Lista rozmow: przelaczanie, zakladanie nowej, zmiana nazwy i usuwanie. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatListSheet(
    chats: List<Chat>,
    activeId: String,
    onPick: (String) -> Unit,
    onNew: () -> Unit,
    onRename: (String, String) -> Unit,
    onDelete: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var renaming by remember { mutableStateOf<Chat?>(null) }
    var confirmDelete by remember { mutableStateOf<Chat?>(null) }

    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Raise, contentColor = Paper) {
        ChatListContent(
            chats = chats,
            activeId = activeId,
            onPick = onPick,
            onNew = onNew,
            onRename = { renaming = it },
            onDelete = { confirmDelete = it },
        )
    }

    renaming?.let { chat ->
        var draft by remember(chat.id) { mutableStateOf(chat.title.ifBlank { chat.label }) }
        AlertDialog(
            onDismissRequest = { renaming = null },
            containerColor = Raise,
            title = { Text("Nazwa rozmowy", color = Paper, fontWeight = FontWeight.Bold) },
            text = { Field(draft, "Nazwa", { draft = it }) },
            confirmButton = {
                TextButton(onClick = { onRename(chat.id, draft.trim()); renaming = null }) {
                    Text("Zapisz", color = Paper, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { renaming = null }) { Text("Anuluj", color = Mist) }
            },
        )
    }

    confirmDelete?.let { chat ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            containerColor = Raise,
            title = { Text("Usunąć rozmowę?", color = Paper, fontWeight = FontWeight.Bold) },
            text = {
                Text(
                    "\"${chat.label}\" — ${chat.messages.size} wiadomości. " +
                        "Tego nie da się cofnąć.",
                    color = Mist,
                )
            },
            confirmButton = {
                TextButton(onClick = { onDelete(chat.id); confirmDelete = null }) {
                    Text("Usuń", color = Paper, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = null }) { Text("Zostaw", color = Mist) }
            },
        )
    }
}

/** Zawartosc arkusza — osobno, zeby dalo sie ja narysowac i sprawdzic bez okna arkusza. */
@Composable
internal fun ChatListContent(
    chats: List<Chat>,
    activeId: String,
    onPick: (String) -> Unit,
    onNew: () -> Unit,
    onRename: (Chat) -> Unit,
    onDelete: (Chat) -> Unit,
) {
    Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 24.dp)) {
        Row(
            Modifier.fillMaxWidth().padding(bottom = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text("Rozmowy", color = Paper, fontWeight = FontWeight.ExtraBold, fontSize = 22.sp)
            Text("${chats.size}", color = Mist, fontSize = 13.sp)
        }

        PrimaryButton("Nowa rozmowa", onClick = onNew)
        Spacer(Modifier.height(14.dp))

        if (chats.isEmpty()) {
            Lead("Nie ma jeszcze żadnej rozmowy.", center = true)
            Spacer(Modifier.height(20.dp))
        } else {
            LazyColumn(
                Modifier.heightIn(max = 380.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(chats, key = { it.id }) { chat ->
                    ChatRow(
                        chat = chat,
                        active = chat.id == activeId,
                        onPick = { onPick(chat.id) },
                        onRename = { onRename(chat) },
                        onDelete = { onDelete(chat) },
                    )
                }
            }
        }
    }
}

@Composable
private fun ChatRow(
    chat: Chat,
    active: Boolean,
    onPick: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .border(1.5.dp, if (active) Paper else Line, RoundedCornerShape(16.dp))
            .clickable { onPick() }
            .padding(start = 14.dp, top = 12.dp, bottom = 12.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                chat.label,
                color = Paper,
                fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(3.dp))
            Text(
                "${chat.messages.size} wiad." +
                    (if (chat.memory.folded > 0) " · ${chat.memory.folded} zwinięte" else "") +
                    (chat.updatedAt.takeIf { it > 0 }?.let { "  ·  ${stamp(it)}" } ?: ""),
                color = Mist,
                fontSize = 12.sp,
            )
        }
        IconButton(onClick = onRename) {
            Icon(painterResource(R.drawable.ic_edit), "Zmień nazwę", tint = Mist, modifier = Modifier.size(19.dp))
        }
        IconButton(onClick = onDelete) {
            Icon(painterResource(R.drawable.ic_delete), "Usuń", tint = Mist, modifier = Modifier.size(19.dp))
        }
    }
}

/** Cala aplikacja jest po polsku — daty tez, niezaleznie od jezyka telefonu. */
private val PL = Locale("pl", "PL")

private fun stamp(millis: Long): String {
    val now = System.currentTimeMillis()
    val day = 24 * 60 * 60 * 1000L
    return when {
        now - millis < day -> SimpleDateFormat("HH:mm", PL).format(Date(millis))
        now - millis < 7 * day -> SimpleDateFormat("EEEE", PL).format(Date(millis))
        else -> SimpleDateFormat("d MMM", PL).format(Date(millis))
    }
}
