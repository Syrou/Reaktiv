package eu.syrou.example.ui.screen.home.mail

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.VerticalDivider
import androidx.compose.ui.Modifier
import io.github.syrou.reaktiv.navigation.definition.Graph
import io.github.syrou.reaktiv.navigation.definition.PaneLayout

object MailGraph : Graph {
    override val route = "mail"

    override val paneLayout = PaneLayout {
        medium(
            column(MailInboxScreen),
            column(MailMessageScreen, MailThreadScreen)
        ) { inbox, message ->
            Row(Modifier.fillMaxSize()) {
                inbox(Modifier.weight(0.45f))
                VerticalDivider()
                message(Modifier.weight(0.55f)) { MailEmptyPane("Select a message") }
            }
        }

        expanded(
            column(MailInboxScreen),
            column(MailMessageScreen, MailThreadScreen)
        ) { inbox, message ->
            Row(Modifier.fillMaxSize()) {
                inbox(Modifier.weight(0.4f))
                VerticalDivider()
                message(Modifier.weight(0.6f)) { MailEmptyPane("Select a message") }
            }
        }

        large(
            column(MailInboxScreen),
            column(MailMessageScreen, MailThreadScreen),
            column(MailReplyModal)
        ) { inbox, message, reply ->
            Row(Modifier.fillMaxSize()) {
                inbox(Modifier.weight(0.25f))
                VerticalDivider()
                message(Modifier.weight(0.45f)) { MailEmptyPane("Select a message") }
                if (reply.isOpen) {
                    VerticalDivider()
                    reply(Modifier.weight(0.3f))
                }
            }
        }
    }
}
