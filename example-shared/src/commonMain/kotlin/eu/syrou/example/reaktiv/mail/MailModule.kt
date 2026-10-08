package eu.syrou.example.reaktiv.mail

import io.github.syrou.reaktiv.core.Module
import io.github.syrou.reaktiv.core.ModuleAction
import io.github.syrou.reaktiv.core.ModuleLogic
import io.github.syrou.reaktiv.core.ModuleState
import io.github.syrou.reaktiv.core.StoreAccessor
import io.github.syrou.reaktiv.navigation.extension.navigateBack
import kotlinx.serialization.Serializable

@Serializable
data class MailMessage(
    val id: String,
    val from: String,
    val subject: String,
    val body: String,
    val replies: List<MailReply> = emptyList()
)

@Serializable
data class MailReply(val from: String, val text: String)

object MailModule : Module<MailModule.MailState, MailModule.MailAction> {

    @Serializable
    data class MailState(
        val messages: List<MailMessage> = sampleMessages,
        val drafts: Map<String, String> = emptyMap()
    ) : ModuleState {
        fun message(id: String?): MailMessage? = messages.firstOrNull { it.id == id }
    }

    sealed class MailAction : ModuleAction(MailModule::class) {
        @Serializable
        data class UpdateDraft(val messageId: String, val text: String) : MailAction()

        @Serializable
        data class Sent(val messageId: String) : MailAction()
    }

    override val initialState = MailState()

    override val reducer: (MailState, MailAction) -> MailState = { state, action ->
        when (action) {
            is MailAction.UpdateDraft -> state.copy(drafts = state.drafts + (action.messageId to action.text))
            is MailAction.Sent -> {
                val text = state.drafts[action.messageId].orEmpty().trim()
                state.copy(
                    drafts = state.drafts - action.messageId,
                    messages = state.messages.map { message ->
                        if (message.id == action.messageId && text.isNotEmpty()) {
                            message.copy(replies = message.replies + MailReply("You", text))
                        } else {
                            message
                        }
                    }
                )
            }
        }
    }

    override val createLogic: (storeAccessor: StoreAccessor) -> ModuleLogic =
        { storeAccessor -> MailLogic(storeAccessor) }
}

class MailLogic(private val storeAccessor: StoreAccessor) : ModuleLogic() {

    suspend fun send(messageId: String) {
        storeAccessor.dispatch(MailModule.MailAction.Sent(messageId))
        storeAccessor.navigateBack()
    }
}

private val sampleMessages = listOf(
    MailMessage(
        id = "41",
        from = "Grace Hopper",
        subject = "Compiler draft",
        body = "Draft attached. The linker section still needs a pass before Friday.",
        replies = listOf(MailReply("You", "I will take the linker section."))
    ),
    MailMessage(
        id = "42",
        from = "Ada Lovelace",
        subject = "Engine notes",
        body = "Revised notes on the engine. Note G covers the loop, and the table at the end walks through it.",
        replies = listOf(
            MailReply("You", "Reading it now. One question on the second part."),
            MailReply("Ada Lovelace", "Ask away, I am around all afternoon.")
        )
    ),
    MailMessage(
        id = "43",
        from = "Alan Turing",
        subject = "Tea on Friday?",
        body = "Free after four. Bring the paper on morphogenesis if you have it."
    ),
    MailMessage(
        id = "44",
        from = "Katherine Johnson",
        subject = "Trajectory check",
        body = "Numbers verified by hand. All within tolerance, sending the sheets over tomorrow."
    ),
    MailMessage(
        id = "45",
        from = "Margaret Hamilton",
        subject = "Priority display",
        body = "The alarm handling held up in the run. Let us go through the restart logs together."
    )
)
