package eu.syrou.androidexample.domain.network

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class ClaudeRequest(
    val model: String,
    val maxTokens: Int,
    val messages: List<Message>,
    val tools: List<Tool>,
    val toolChoice: ToolChoice,
    val system: String
)

@Serializable
data class Message(
    val role: String,
    val content: String
)

@Serializable
data class Tool(
    val name: String,
    val description: String,
    val inputSchema: InputSchema
)

@Serializable
data class InputSchema(
    val type: String,
    val properties: Properties,
    val required: List<String>
)

@Serializable
data class Properties(
    val query: Query
)

@Serializable
data class Query(
    val type: String,
    val description: String
)

@Serializable
data class ToolChoice(
    val type: String
)

@Serializable
data class ClaudeResponse(
    val content: List<ContentItem>? = emptyList()
)

@Serializable
data class ContentItem(
    val type: String,
    val text: String? = null,
    val input: ToolInput? = null
)

@Serializable
data class ToolInput(
    val query: String
)

@Serializable
data class WikiResponse(
    val parse: Parse? = null
)

@Serializable
data class Parse(
    val title: String,
    val pageid: Int,
    val text: Text?
)

@Serializable
data class Text(
    @SerialName("*")
    val content: String?
)