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
                if (message.isShowing) {
                    VerticalDivider()
                    message(Modifier.weight(0.55f))
                }
            }
        }

        expanded(
            column(MailInboxScreen),
            column(MailMessageScreen, MailThreadScreen)
        ) { inbox, message ->
            Row(Modifier.fillMaxSize()) {
                inbox(Modifier.weight(0.4f))
                if (message.isShowing) {
                    VerticalDivider()
                    message(Modifier.weight(0.6f))
                }
            }
        }

        large(
            column(MailInboxScreen),
            column(MailMessageScreen, MailThreadScreen),
            column(MailReplyModal)
        ) { inbox, message, reply ->
            Row(Modifier.fillMaxSize()) {
                inbox(Modifier.weight(0.25f))
                if (message.isShowing) {
                    VerticalDivider()
                    message(Modifier.weight(0.45f))
                }
                if (reply.isShowing) {
                    VerticalDivider()
                    reply(Modifier.weight(0.3f))
                }
            }
        }
    }
}
