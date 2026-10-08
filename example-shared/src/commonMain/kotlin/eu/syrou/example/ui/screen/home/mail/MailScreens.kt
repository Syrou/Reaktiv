package eu.syrou.example.ui.screen.home.mail

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import eu.syrou.example.reaktiv.mail.MailLogic
import eu.syrou.example.reaktiv.mail.MailMessage
import eu.syrou.example.reaktiv.mail.MailModule
import io.github.syrou.reaktiv.compose.composeState
import io.github.syrou.reaktiv.compose.rememberStore
import io.github.syrou.reaktiv.core.util.selectLogic
import io.github.syrou.reaktiv.navigation.NavigationState
import io.github.syrou.reaktiv.navigation.alias.TitleResource
import io.github.syrou.reaktiv.navigation.definition.Modal
import io.github.syrou.reaktiv.navigation.definition.Screen
import io.github.syrou.reaktiv.navigation.extension.navigation
import io.github.syrou.reaktiv.navigation.param.Params
import io.github.syrou.reaktiv.navigation.transition.NavTransition
import io.github.syrou.reaktiv.navigation.ui.currentPaneColumn
import kotlinx.coroutines.launch

object MailInboxScreen : Screen {
    override val route = "inbox"
    override val titleResource: TitleResource = { "Mail" }
    override val enterTransition = NavTransition.None
    override val exitTransition = NavTransition.None

    @Composable
    override fun Content(params: Params) {
        val store = rememberStore()
        val mail by composeState<MailModule.MailState>()
        val navigationState by composeState<NavigationState>()
        val openId = navigationState.paneColumns.getOrNull(1)?.params?.getString("messageId")

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
        ) {
            items(mail.messages, key = { it.id }) { message ->
                MailRow(
                    message = message,
                    selected = message.id == openId,
                    onClick = {
                        store.launch {
                            store.navigation { navigateTo(MailMessageScreen, "messageId" to message.id) }
                        }
                    }
                )
                HorizontalDivider()
            }
        }
    }
}

object MailMessageScreen : Screen {
    override val route = "message/{messageId}"
    override val titleResource: TitleResource = { "Message" }
    override val enterTransition = NavTransition.SlideInRight
    override val exitTransition = NavTransition.SlideOutLeft

    @Composable
    override fun Content(params: Params) {
        val store = rememberStore()
        val mail by composeState<MailModule.MailState>()
        val message = mail.message(params.getString("messageId"))
        if (message == null) {
            MailEmptyPane("This message is gone")
            return
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
        ) {
            MailPaneHeader(
                title = message.subject,
                showsBack = (currentPaneColumn() ?: 0) == 0
            )
            Column(
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text("From ${message.from}", style = MaterialTheme.typography.labelLarge)
                Text(message.body, style = MaterialTheme.typography.bodyLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = {
                        store.launch {
                            store.navigation { navigateTo(MailThreadScreen, "messageId" to message.id) }
                        }
                    }) {
                        Text("Show thread (${message.replies.size})")
                    }
                    Button(onClick = {
                        store.launch {
                            store.navigation { navigateTo(MailReplyModal, "messageId" to message.id) }
                        }
                    }) {
                        Text("Reply")
                    }
                }
            }
        }
    }
}

object MailThreadScreen : Screen {
    override val route = "message/{messageId}/thread"
    override val titleResource: TitleResource = { "Thread" }
    override val enterTransition = NavTransition.SlideInRight
    override val exitTransition = NavTransition.SlideOutLeft

    @Composable
    override fun Content(params: Params) {
        val store = rememberStore()
        val mail by composeState<MailModule.MailState>()
        val message = mail.message(params.getString("messageId"))
        if (message == null) {
            MailEmptyPane("This thread is gone")
            return
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
        ) {
            MailPaneHeader(title = "Thread: ${message.subject}", showsBack = true)
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(16.dp)
            ) {
                item { ThreadBubble(from = message.from, text = message.body, mine = false) }
                items(message.replies) { reply ->
                    ThreadBubble(from = reply.from, text = reply.text, mine = reply.from == "You")
                }
            }
            Button(
                modifier = Modifier.padding(16.dp),
                onClick = {
                    store.launch {
                        store.navigation { navigateTo(MailReplyModal, "messageId" to message.id) }
                    }
                }
            ) {
                Text("Reply")
            }
        }
    }
}

object MailReplyModal : Modal {
    override val route = "message/{messageId}/reply"
    override val titleResource: TitleResource = { "Reply" }
    override val enterTransition = NavTransition.SlideUpBottom
    override val exitTransition = NavTransition.SlideOutBottom

    @Composable
    override fun Content(params: Params) {
        val messageId = params.getString("messageId") ?: return
        if (currentPaneColumn() != null) {
            Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surfaceContainerLow) {
                ReplyEditor(messageId)
            }
        } else {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
                Card(
                    modifier = Modifier
                        .widthIn(max = 640.dp)
                        .fillMaxWidth(),
                    shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
                ) {
                    ReplyEditor(messageId)
                }
            }
        }
    }
}

@Composable
private fun ReplyEditor(messageId: String) {
    val store = rememberStore()
    val mail by composeState<MailModule.MailState>()
    val message = mail.message(messageId) ?: return
    val draft = mail.drafts[messageId].orEmpty()

    Column(
        modifier = Modifier.padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "Reply to ${message.from}",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f)
            )
            IconButton(onClick = {
                store.launch { store.navigation { navigateBack() } }
            }) {
                Icon(Icons.Default.Close, contentDescription = "Close reply")
            }
        }
        Text("Re: ${message.subject}", style = MaterialTheme.typography.labelMedium)
        OutlinedTextField(
            value = draft,
            onValueChange = { store.dispatch(MailModule.MailAction.UpdateDraft(messageId, it)) },
            modifier = Modifier
                .fillMaxWidth()
                .height(140.dp),
            placeholder = { Text("Write a reply") }
        )
        Button(
            enabled = draft.isNotBlank(),
            onClick = { store.launch { store.selectLogic<MailLogic>().send(messageId) } }
        ) {
            Text("Send")
        }
    }
}

@Composable
private fun MailRow(message: MailMessage, selected: Boolean, onClick: () -> Unit) {
    val background = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.background
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(background)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Text(message.from, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        Text(
            message.subject,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            message.body,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun MailPaneHeader(title: String, showsBack: Boolean) {
    val store = rememberStore()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 4.dp)
            .height(48.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (showsBack) {
            IconButton(onClick = {
                store.launch { store.navigation { navigateBack() } }
            }) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
        } else {
            Spacer(Modifier.padding(start = 12.dp))
        }
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
    HorizontalDivider()
}

@Composable
private fun ThreadBubble(from: String, text: String, mine: Boolean) {
    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = if (mine) Alignment.CenterEnd else Alignment.CenterStart) {
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = if (mine) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier.widthIn(max = 420.dp)
        ) {
            Column(modifier = Modifier.padding(10.dp)) {
                Text(from, style = MaterialTheme.typography.labelSmall)
                Text(text, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
fun MailEmptyPane(text: String) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surfaceContainerLowest),
        contentAlignment = Alignment.Center
    ) {
        Text(text, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
